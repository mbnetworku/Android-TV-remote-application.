package com.atvremote.app;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.atvremote.app.adb.AdbConnection;
import com.atvremote.app.adb.AdbCrypto;
import com.atvremote.app.adb.AdbStream;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.atvremote.app.adb.IoUtil;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns the ADB connection to one TV and turns button presses into key events on it. */
public final class TvController {

    private static final String TAG = "TvController";
    public static final int DEFAULT_PORT = 5555;

    private static final String REMOTE_APK = "/data/local/tmp/atvremote-helper.apk";
    private static final String INJECTOR_CLASS = "com.atvremote.app.inject.Injector";

    public enum State { DISCONNECTED, CONNECTING, AWAITING_APPROVAL, AWAITING_CODE, CONNECTED }

    public interface Listener {
        void onStateChanged(State state, String title, String detail);
    }

    public static final class TvApp {
        public final String component;
        public final String packageName;
        public String label;
        public android.graphics.Bitmap icon;

        public TvApp(String component, String packageName, String label) {
            this.component = component;
            this.packageName = packageName;
            this.label = label;
        }
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Connects and sends input, strictly in order. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Slow one-off commands (app list, launching) so they never delay key presses. */
    private final ExecutorService bg = Executors.newCachedThreadPool();

    private volatile int generation;
    private volatile AdbConnection conn;
    private volatile AdbStream input;
    private volatile boolean fallbackInput;
    private volatile State state = State.DISCONNECTED;
    private volatile String host;
    private volatile String model;
    private Listener listener;

    public TvController(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public State getState() {
        return state;
    }

    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    public String getHost() {
        return host;
    }

    // ---------------------------------------------------------------- connection

    /** How this TV is controlled. Picked automatically: receiver app, then Google TV, then ADB. */
    public enum Mode { ADB, RECEIVER, GOOGLE }

    private volatile Mode mode = Mode.ADB;
    private volatile java.net.Socket rxSocket;
    private volatile java.io.OutputStream rxOut;
    private volatile com.atvremote.app.atv.AtvRemote atv;
    private final java.util.concurrent.LinkedBlockingQueue<String> codeAnswers = new java.util.concurrent.LinkedBlockingQueue<>();
    private volatile String codeKind = "CODE";

    public Mode getMode() {
        return mode;
    }

    /** "PIN" (receiver app, 4 digits) or "CODE" (Google TV, 6 characters). */
    public String getCodeKind() {
        return codeKind;
    }

    /** Answer for AWAITING_CODE; null cancels. */
    public void submitCode(String code) {
        codeAnswers.offer(code == null ? "\u0000" : code);
    }

    private String waitForCode(int gen, String kind, String message) throws InterruptedException {
        codeKind = kind;
        codeAnswers.clear();
        setState(gen, State.AWAITING_CODE, host, message);
        String c = codeAnswers.poll(5, java.util.concurrent.TimeUnit.MINUTES);
        if (c == null || c.equals("\u0000") || gen != generation) return null;
        setState(gen, State.CONNECTING, host, "Checking…");
        return c;
    }

    private static boolean portOpen(String host, int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void connect(String host, int port) {
        disconnect();
        final int gen = generation;
        this.host = host;
        this.model = null;
        setState(gen, State.CONNECTING, host, "Connecting…");
        io.execute(() -> {
            if (gen != generation) return;
            // No debugging needed for the first two.
            if (port == DEFAULT_PORT) {
                if (portOpen(host, RECEIVER_PORT)) {
                    connectReceiver(gen);
                    return;
                }
                if (portOpen(host, com.atvremote.app.atv.AtvRemote.REMOTE_PORT)) {
                    connectGoogle(gen);
                    return;
                }
            }
            mode = Mode.ADB;
            AdbConnection c = null;
            try {
                AdbCrypto crypto = AdbCrypto.loadOrCreate(ctx.getFilesDir(), "TVRemote@" + Build.MODEL.replace(' ', '_'));
                c = AdbConnection.connect(host, port, crypto,
                        () -> setState(gen, State.AWAITING_APPROVAL, host, "Choose Allow on your TV"),
                        4000, 90000);
                if (gen != generation) {
                    c.close();
                    return;
                }
                conn = c;
                c.setOnClosed(() -> onConnectionLost(gen));
                model = readModel(c);
                startInput(c, gen);
                if (gen != generation) return;
                setState(gen, State.CONNECTED, model != null ? model : host, "Connected");
                requestVolume();
            } catch (Exception e) {
                Log.w(TAG, "connect failed", e);
                if (c != null) c.close();
                if (gen == generation) {
                    conn = null;
                    input = null;
                    setState(gen, State.DISCONNECTED, host, describe(e));
                }
            }
        });
    }

    // ---------------------------------------------------------------- B: receiver app on the TV

    public static final int RECEIVER_PORT = 6470;

    private String rxToken() {
        return ctx.getSharedPreferences("remote", Context.MODE_PRIVATE).getString("rx_token_" + host, "-");
    }

    /** Opens a receiver connection and logs in (asking for the TV's PIN if needed). */
    private java.net.Socket openReceiver(int gen, java.io.BufferedReader[] readerOut) throws Exception {
        java.net.Socket s = new java.net.Socket();
        s.connect(new java.net.InetSocketAddress(host, RECEIVER_PORT), 4000);
        s.setTcpNoDelay(true);
        java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        java.io.OutputStream out = s.getOutputStream();
        out.write(("HELLO " + rxToken() + " " + Build.MODEL + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        String msg = "Enter the 4-digit PIN shown on your TV";
        while (true) {
            String line = in.readLine();
            if (line == null) throw new IOException("The TV closed the connection");
            if (line.startsWith("OK ")) {
                ctx.getSharedPreferences("remote", Context.MODE_PRIVATE).edit()
                        .putString("rx_token_" + host, line.substring(3).trim()).apply();
                readerOut[0] = in;
                return s;
            }
            if (line.equals("DENIED")) throw new IOException("Wrong PIN · tap to try again");
            if (line.equals("PIN")) {
                if (gen < 0) throw new IOException("not paired");
                String pin = waitForCode(gen, "PIN", msg);
                if (pin == null) throw new IOException("Pairing cancelled");
                msg = "Wrong PIN. Check the TV and try again";
                out.write(("PIN " + pin.trim() + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    private void connectReceiver(int gen) {
        try {
            java.io.BufferedReader[] r = new java.io.BufferedReader[1];
            java.net.Socket s = openReceiver(gen, r);
            if (gen != generation) {
                s.close();
                return;
            }
            java.io.OutputStream out = s.getOutputStream();
            out.write("name\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            String line;
            while ((line = r[0].readLine()) != null && !line.startsWith("NAME ")) {
                handleLine(line);
            }
            model = line == null ? null : line.substring(5).trim();
            mode = Mode.RECEIVER;
            fallbackInput = false;
            rxSocket = s;
            rxOut = out;
            final java.io.BufferedReader in = r[0];
            bg.execute(() -> {
                try {
                    String l;
                    while ((l = in.readLine()) != null) handleLine(l);
                } catch (IOException ignored) {
                }
                onConnectionLost(gen);
            });
            setState(gen, State.CONNECTED, model != null ? model : host, "Connected · no debugging needed");
            requestVolume();
        } catch (Exception e) {
            Log.w(TAG, "receiver connect failed", e);
            if (gen == generation) setState(gen, State.DISCONNECTED, host, describe(e));
        }
    }

    // ---------------------------------------------------------------- A: Google TV remote protocol

    private void connectGoogle(int gen) {
        try {
            com.atvremote.app.atv.AtvCert cert = com.atvremote.app.atv.AtvCert.loadOrCreate(ctx.getFilesDir(), "TVRemote");
            com.atvremote.app.atv.AtvRemote.Listener l = new com.atvremote.app.atv.AtvRemote.Listener() {
                @Override
                public void onVolume(int level, int max, boolean muted) {
                    volume = level;
                    volumeMax = max;
                    notifyListener(state, lastTitle, lastDetail);
                }

                @Override
                public void onClosed() {
                    onConnectionLost(gen);
                }
            };
            String version = "1";
            try {
                version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            } catch (Exception ignored) {
            }
            com.atvremote.app.atv.AtvRemote r;
            try {
                r = com.atvremote.app.atv.AtvRemote.connect(host, cert, Build.MODEL, version, l);
            } catch (com.atvremote.app.atv.AtvRemote.PairingFailed notPaired) {
                com.atvremote.app.atv.AtvRemote.pair(host, cert, "TV Remote (" + Build.MODEL + ")",
                        message -> waitForCode(gen, "CODE", message));
                setState(gen, State.CONNECTING, host, "Paired · connecting…");
                r = com.atvremote.app.atv.AtvRemote.connect(host, cert, Build.MODEL, version, l);
            }
            if (gen != generation) {
                r.close();
                return;
            }
            atv = r;
            mode = Mode.GOOGLE;
            fallbackInput = false;
            model = "Android TV";
            setState(gen, State.CONNECTED, model, "Connected · Google TV pairing");
        } catch (Exception e) {
            Log.w(TAG, "google tv connect failed", e);
            if (gen == generation) setState(gen, State.DISCONNECTED, host, describe(e));
        }
    }

    /** Google TV mode has no text field access: type with key presses (letters, digits, basic symbols). */
    private static int charToKey(char c) {
        c = Character.toLowerCase(c);
        if (c >= 'a' && c <= 'z') return KeyEventCodes.A + (c - 'a');
        if (c >= '0' && c <= '9') return KeyEventCodes.ZERO + (c - '0');
        switch (c) {
            case ' ': return 62;
            case '.': return 56;
            case ',': return 55;
            case '@': return 77;
            case '-': return 69;
            case '/': return 76;
            case '+': return 81;
            case '=': return 70;
            default: return -1;
        }
    }

    private static final class KeyEventCodes {
        static final int A = 29;
        static final int ZERO = 7;
    }

    public void disconnect() {
        generation++;
        codeAnswers.offer("\u0000"); // release a pending code prompt
        final AdbConnection c = conn;
        conn = null;
        input = null;
        if (c != null) bg.execute(c::close);
        final java.net.Socket rs = rxSocket;
        rxSocket = null;
        rxOut = null;
        if (rs != null) bg.execute(() -> {
            try {
                rs.close();
            } catch (IOException ignored) {
            }
        });
        final com.atvremote.app.atv.AtvRemote a = atv;
        atv = null;
        if (a != null) bg.execute(a::close);
        if (state != State.DISCONNECTED) {
            state = State.DISCONNECTED;
            notifyListener(State.DISCONNECTED, host != null ? host : "", "Disconnected");
        }
    }

    private void onConnectionLost(int gen) {
        if (gen != generation) return;
        conn = null;
        input = null;
        rxOut = null;
        rxSocket = null;
        atv = null;
        setState(gen, State.DISCONNECTED, model != null ? model : host, "Connection lost · press any key");
    }

    private static String readModel(AdbConnection c) {
        try {
            String m = c.shell("getprop ro.product.model", 5000).trim();
            return m.isEmpty() ? null : m;
        } catch (IOException e) {
            return null;
        }
    }

    /** Copies this APK to the TV and starts the Injector from it; falls back to "input" if that fails. */
    private void startInput(AdbConnection c, int gen) throws IOException {
        try {
            byte[] apk = IoUtil.read(new File(ctx.getApplicationInfo().sourceDir));
            String want = md5(apk);
            String have = c.shell("md5sum " + REMOTE_APK + " 2>/dev/null", 10000).trim();
            if (!have.startsWith(want)) {
                setState(gen, State.CONNECTING, host, "Setting up the TV (first time only)…");
                c.push(apk, REMOTE_APK, 0644, System.currentTimeMillis() / 1000);
            }
            AdbStream s = c.open("shell:CLASSPATH=" + REMOTE_APK + " app_process / " + INJECTOR_CLASS);
            if (waitForReady(s)) {
                fallbackInput = false;
                input = s;
                drain(s);
                return;
            }
            s.close();
        } catch (IOException e) {
            if (c.isClosed()) throw e;
            Log.w(TAG, "injector setup failed, using input command", e);
        } catch (Exception e) {
            Log.w(TAG, "injector setup failed, using input command", e);
        }
        AdbStream sh = c.open("shell:");
        fallbackInput = true;
        input = sh;
        drain(sh);
    }

    private static boolean waitForReady(AdbStream s) throws IOException {
        long deadline = System.currentTimeMillis() + 8000;
        StringBuilder out = new StringBuilder();
        while (System.currentTimeMillis() < deadline) {
            byte[] d;
            try {
                d = s.read(deadline - System.currentTimeMillis());
            } catch (EOFException e) {
                break;
            }
            if (d == null) break;
            out.append(new String(d, StandardCharsets.UTF_8));
            if (out.indexOf("READY") >= 0) return true;
            if (out.indexOf("FAIL") >= 0) break;
        }
        Log.w(TAG, "injector did not start: " + out);
        return false;
    }

    /** Keeps reading a stream's output so the TV never blocks on a full pipe. */
    private void drain(AdbStream s) {
        bg.execute(() -> {
            StringBuilder buf = new StringBuilder();
            try {
                byte[] d;
                while ((d = s.read()) != null) {
                    buf.append(new String(d, StandardCharsets.UTF_8));
                    int nl;
                    while ((nl = buf.indexOf("\n")) >= 0) {
                        String line = buf.substring(0, nl).trim();
                        buf.delete(0, nl + 1);
                        handleLine(line);
                    }
                    if (buf.length() > 8192) buf.setLength(0);
                }
            } catch (IOException ignored) {
            }
        });
    }

    /** Output from the ADB helper or the receiver app. */
    private void handleLine(String line) {
        line = line.trim();
        if (line.startsWith("VOL ")) {
            String[] p = line.split(" ");
            try {
                volume = Integer.parseInt(p[1]);
                volumeMax = Integer.parseInt(p[2]);
                notifyListener(state, lastTitle, lastDetail);
            } catch (RuntimeException ignored) {
            }
        } else if (line.startsWith("ERR")) {
            Log.w(TAG, "TV: " + line);
        }
    }

    // ---------------------------------------------------------------- volume

    private volatile int volume = -1;
    private volatile int volumeMax = -1;

    /** Current TV volume in percent, or -1 if unknown (e.g. helper not running). */
    public int getVolumePercent() {
        return volumeMax > 0 && volume >= 0 ? Math.round(volume * 100f / volumeMax) : -1;
    }

    public int getVolumeStep() {
        return volume;
    }

    public int getVolumeMax() {
        return volumeMax;
    }

    public boolean supportsVolume() {
        return mode == Mode.GOOGLE ? volumeMax > 0 : !fallbackInput;
    }

    public void requestVolume() {
        if (mode != Mode.GOOGLE && !fallbackInput) sendLine("v"); // Google TVs report volume by themselves
    }

    public void setVolumeStep(int step) {
        if (mode == Mode.GOOGLE) {
            int diff = step - volume;
            int code = diff > 0 ? 24 : 25;
            for (int i = 0; i < Math.abs(diff); i++) pressKey(code);
        } else if (!fallbackInput) {
            sendLine("s " + step);
        }
    }

    // ---------------------------------------------------------------- input

    public boolean pressKey(int keyCode) {
        if (mode == Mode.GOOGLE) return atvSend(r -> r.key(keyCode));
        return sendLine(fallbackInput ? "input keyevent " + keyCode : "k " + keyCode);
    }

    public boolean longPressKey(int keyCode) {
        if (mode == Mode.GOOGLE) return atvSend(r -> r.longKey(keyCode));
        return sendLine(fallbackInput ? "input keyevent --longpress " + keyCode : "l " + keyCode);
    }

    public boolean sendText(String text) {
        String clean = text.replace("\n", "").replace("\r", "");
        if (clean.isEmpty()) return true;
        if (mode == Mode.GOOGLE) {
            for (char ch : clean.toCharArray()) {
                int k = charToKey(ch);
                if (k > 0) pressKey(k);
            }
            return true;
        }
        if (!fallbackInput) return sendLine("t " + clean);
        String escaped = clean.replace("'", "'\\''").replace(" ", "%s");
        return sendLine("input text '" + escaped + "'");
    }

    private interface AtvAction {
        void run(com.atvremote.app.atv.AtvRemote r) throws Exception;
    }

    private boolean atvSend(AtvAction a) {
        if (state != State.CONNECTED) return false;
        io.execute(() -> {
            com.atvremote.app.atv.AtvRemote r = atv;
            if (r == null) return;
            try {
                a.run(r);
            } catch (Exception e) {
                Log.w(TAG, "send failed", e);
            }
        });
        return true;
    }

    private boolean sendLine(String line) {
        if (state != State.CONNECTED) return false;
        final byte[] data = (line + "\n").getBytes(StandardCharsets.UTF_8);
        io.execute(() -> {
            try {
                if (mode == Mode.RECEIVER) {
                    java.io.OutputStream o = rxOut;
                    if (o != null) {
                        o.write(data);
                        o.flush();
                    }
                    return;
                }
                AdbStream s = input;
                if (s != null) s.write(data);
            } catch (IOException e) {
                Log.w(TAG, "send failed", e);
            }
        });
        return true;
    }

    /** Popular apps for Google TV mode, which cannot list what is installed. */
    private static final String[][] GOOGLE_APPS = {
            {"com.google.android.youtube.tv", "YouTube"},
            {"com.netflix.ninja", "Netflix"},
            {"com.amazon.amazonvideo.livingroom", "Prime Video"},
            {"com.disney.disneyplus", "Disney+"},
            {"com.spotify.tv.android", "Spotify"},
            {"com.google.android.youtube.tvmusic", "YouTube Music"},
            {"com.plexapp.android", "Plex"},
            {"org.xbmc.kodi", "Kodi"},
            {"tv.twitch.android.app", "Twitch"},
            {"com.apple.atve.androidtv.appletv", "Apple TV"},
            {"com.wbd.stream", "Max"},
            {"com.android.vending", "Play Store"},
    };

    // ---------------------------------------------------------------- apps

    private static final Pattern COMPONENT = Pattern.compile("^\\s*([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)\\s*$");

    public interface AppsCallback {
        void onApps(List<TvApp> apps);
    }

    public void loadApps(AppsCallback callback) {
        final AdbConnection c = conn;
        final Mode how = mode;
        bg.execute(() -> {
            List<TvApp> apps = new ArrayList<>();
            if (how == Mode.GOOGLE) {
                for (String[] a : GOOGLE_APPS) {
                    TvApp app = new TvApp(a[0] + "/", a[0], a[1]);
                    app.icon = cachedIcon(ctx, a[0]);
                    apps.add(app);
                }
            } else if (how == Mode.RECEIVER) {
                try {
                    java.io.BufferedReader[] r = new java.io.BufferedReader[1];
                    try (java.net.Socket s = openReceiver(-1, r)) {
                        s.setSoTimeout(45000);
                        s.getOutputStream().write("apps\n".getBytes(StandardCharsets.UTF_8));
                        s.getOutputStream().flush();
                        StringBuilder out = new StringBuilder();
                        String line;
                        while ((line = r[0].readLine()) != null && !line.equals("DONE")) out.append(line).append('\n');
                        parseAppsOutput(out.toString(), apps);
                    }
                    Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
                } catch (Exception e) {
                    Log.w(TAG, "receiver app list failed", e);
                }
            } else if (c != null) {
                try {
                    String out = c.shell("cmd package query-activities -a android.intent.action.MAIN"
                            + " -c android.intent.category.LEANBACK_LAUNCHER", 20000);
                    Set<String> seen = new HashSet<>();
                    for (String line : out.split("\n")) {
                        Matcher m = COMPONENT.matcher(line);
                        if (!m.matches()) continue;
                        String pkg = m.group(1);
                        if (!seen.add(pkg)) continue;
                        String cls = m.group(2);
                        TvApp app = new TvApp(pkg + "/" + cls, pkg, prettyName(pkg));
                        String cachedLabel = cachedLabel(ctx, pkg);
                        if (cachedLabel != null) app.label = cachedLabel;
                        app.icon = cachedIcon(ctx, pkg);
                        apps.add(app);
                    }
                    fetchNamesAndIcons(c, apps);
                    Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
                } catch (IOException e) {
                    Log.w(TAG, "app list failed", e);
                }
            }
            main.post(() -> callback.onApps(apps));
        });
    }

    /** Asks the helper on the TV for real app names and icons; caches them on the phone. */
    private void fetchNamesAndIcons(AdbConnection c, List<TvApp> apps) {
        if (fallbackInput) return;
        // With no package list (Android 5/6 have no "cmd package"), the helper finds the apps itself.
        StringBuilder cmd = new StringBuilder("CLASSPATH=" + REMOTE_APK + " app_process / " + INJECTOR_CLASS + " apps");
        for (TvApp a : apps) cmd.append(' ').append(a.packageName);
        String out;
        try {
            out = c.shell(cmd.toString(), 45000);
        } catch (IOException e) {
            Log.w(TAG, "icon fetch failed", e);
            return;
        }
        parseAppsOutput(out, apps);
    }

    /** Parses "ACT pkg/cls" and "APP pkg\tlabel\tbase64-png" lines into apps, caching icons. */
    private void parseAppsOutput(String out, List<TvApp> apps) {
        Map<String, TvApp> byPkg = new HashMap<>();
        for (TvApp a : apps) byPkg.put(a.packageName, a);
        for (String line : out.split("\n")) {
            if (!line.startsWith("ACT ")) continue;
            String component = line.substring(4).trim();
            int slash = component.indexOf('/');
            if (slash <= 0) continue;
            String pkg = component.substring(0, slash);
            if (byPkg.containsKey(pkg)) continue;
            TvApp app = new TvApp(component, pkg, prettyName(pkg));
            String cached = cachedLabel(ctx, pkg);
            if (cached != null) app.label = cached;
            app.icon = cachedIcon(ctx, pkg);
            apps.add(app);
            byPkg.put(pkg, app);
        }
        for (String line : out.split("\n")) {
            if (!line.startsWith("APP ")) continue;
            String[] p = line.substring(4).trim().split("\t");
            if (p.length < 3) continue;
            TvApp a = byPkg.get(p[0]);
            if (a == null) continue;
            try {
                byte[] png = android.util.Base64.decode(p[2], android.util.Base64.DEFAULT);
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
                if (bmp != null) a.icon = bmp;
                if (!p[1].isEmpty()) a.label = p[1];
                File dir = new File(ctx.getFilesDir(), "icons");
                dir.mkdirs();
                IoUtil.write(new File(dir, a.packageName + ".png"), png);
                IoUtil.write(new File(dir, a.packageName + ".txt"), a.label.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w(TAG, "bad icon for " + p[0], e);
            }
        }
    }

    public static android.graphics.Bitmap cachedIcon(Context ctx, String pkg) {
        File f = new File(new File(ctx.getFilesDir(), "icons"), pkg + ".png");
        return f.isFile() ? android.graphics.BitmapFactory.decodeFile(f.getPath()) : null;
    }

    static String cachedLabel(Context ctx, String pkg) {
        try {
            File f = new File(new File(ctx.getFilesDir(), "icons"), pkg + ".txt");
            return f.isFile() ? new String(IoUtil.read(f), StandardCharsets.UTF_8).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    public void launchApp(TvApp app) {
        if (mode == Mode.GOOGLE) {
            atvSend(r -> r.openLink("market://launch?id=" + app.packageName));
            return;
        }
        if (mode == Mode.RECEIVER) {
            sendLine("launch " + app.component);
            return;
        }
        final AdbConnection c = conn;
        if (c == null) return;
        bg.execute(() -> {
            try {
                c.shell("am start -n '" + app.component + "'", 15000);
            } catch (IOException e) {
                Log.w(TAG, "launch failed", e);
            }
        });
    }

    private static final Map<String, String> KNOWN_APPS = new HashMap<>();
    static {
        KNOWN_APPS.put("com.google.android.youtube.tv", "YouTube");
        KNOWN_APPS.put("com.google.android.youtube.tvkids", "YouTube Kids");
        KNOWN_APPS.put("com.google.android.youtube.tvmusic", "YouTube Music");
        KNOWN_APPS.put("com.netflix.ninja", "Netflix");
        KNOWN_APPS.put("com.android.vending", "Play Store");
        KNOWN_APPS.put("com.amazon.amazonvideo.livingroom", "Prime Video");
        KNOWN_APPS.put("com.disney.disneyplus", "Disney+");
        KNOWN_APPS.put("com.spotify.tv.android", "Spotify");
        KNOWN_APPS.put("com.android.tv.settings", "Settings");
        KNOWN_APPS.put("com.google.android.tvlauncher", "Home");
        KNOWN_APPS.put("com.google.android.apps.tv.launcherx", "Home");
        KNOWN_APPS.put("com.google.android.videos", "Google TV");
        KNOWN_APPS.put("org.videolan.vlc", "VLC");
        KNOWN_APPS.put("com.plexapp.android", "Plex");
        KNOWN_APPS.put("org.xbmc.kodi", "Kodi");
        KNOWN_APPS.put("com.apple.atve.androidtv.appletv", "Apple TV");
        KNOWN_APPS.put("com.hbo.hbonow", "Max");
        KNOWN_APPS.put("com.wbd.stream", "Max");
    }

    private static final Set<String> NAME_NOISE = new HashSet<>(java.util.Arrays.asList(
            "com", "org", "net", "io", "tv", "android", "androidtv", "atv", "app", "apps", "google",
            "mobile", "client", "player", "main", "activity", "launcher", "ui"));

    static String prettyName(String pkg) {
        String known = KNOWN_APPS.get(pkg);
        if (known != null) return known;
        String[] parts = pkg.split("\\.");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i];
            if (p.length() > 1 && !NAME_NOISE.contains(p.toLowerCase(Locale.ROOT))) {
                return Character.toUpperCase(p.charAt(0)) + p.substring(1);
            }
        }
        return pkg;
    }

    // ---------------------------------------------------------------- helpers

    private void setState(int gen, State s, String title, String detail) {
        if (gen != generation) return;
        state = s;
        notifyListener(s, title, detail);
    }

    private volatile String lastTitle;
    private volatile String lastDetail;

    public String getTitle() {
        return lastTitle;
    }

    public String getDetail() {
        return lastDetail;
    }

    private void notifyListener(State s, String title, String detail) {
        lastTitle = title;
        lastDetail = detail;
        main.post(() -> {
            if (listener != null) listener.onStateChanged(s, title, detail);
        });
    }

    private static String describe(Exception e) {
        if (e instanceof AdbConnection.NotApprovedException) return e.getMessage() + " · tap to retry";
        if (e instanceof ConnectException || e instanceof NoRouteToHostException) {
            return "Can't reach the TV · is network debugging on?";
        }
        if (e instanceof SocketTimeoutException) return "TV not responding · check the IP address";
        if (e instanceof UnknownHostException) return "Unknown address";
        if (e instanceof EOFException) return "The TV closed the connection · tap to retry";
        String m = e.getMessage();
        return m != null ? m : e.getClass().getSimpleName();
    }

    private static String md5(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
