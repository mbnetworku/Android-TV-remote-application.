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

    public enum State { DISCONNECTED, CONNECTING, AWAITING_APPROVAL, CONNECTED }

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

    public void connect(String host, int port) {
        disconnect();
        final int gen = generation;
        this.host = host;
        this.model = null;
        setState(gen, State.CONNECTING, host, "Connecting…");
        io.execute(() -> {
            if (gen != generation) return;
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

    public void disconnect() {
        generation++;
        final AdbConnection c = conn;
        conn = null;
        input = null;
        if (c != null) bg.execute(c::close);
        if (state != State.DISCONNECTED) {
            state = State.DISCONNECTED;
            notifyListener(State.DISCONNECTED, host != null ? host : "", "Disconnected");
        }
    }

    private void onConnectionLost(int gen) {
        if (gen != generation) return;
        conn = null;
        input = null;
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
                    if (buf.length() > 8192) buf.setLength(0);
                }
            } catch (IOException ignored) {
            }
        });
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
        return !fallbackInput;
    }

    public void requestVolume() {
        if (!fallbackInput) sendLine("v");
    }

    public void setVolumeStep(int step) {
        if (!fallbackInput) sendLine("s " + step);
    }

    // ---------------------------------------------------------------- input

    public boolean pressKey(int keyCode) {
        return sendLine(fallbackInput ? "input keyevent " + keyCode : "k " + keyCode);
    }

    public boolean longPressKey(int keyCode) {
        return sendLine(fallbackInput ? "input keyevent --longpress " + keyCode : "l " + keyCode);
    }

    public boolean sendText(String text) {
        String clean = text.replace("\n", "").replace("\r", "");
        if (clean.isEmpty()) return true;
        if (!fallbackInput) return sendLine("t " + clean);
        String escaped = clean.replace("'", "'\\''").replace(" ", "%s");
        return sendLine("input text '" + escaped + "'");
    }

    private boolean sendLine(String line) {
        if (state != State.CONNECTED) return false;
        final byte[] data = (line + "\n").getBytes(StandardCharsets.UTF_8);
        io.execute(() -> {
            AdbStream s = input;
            if (s == null) return;
            try {
                s.write(data);
            } catch (IOException e) {
                Log.w(TAG, "send failed", e);
            }
        });
        return true;
    }

    // ---------------------------------------------------------------- apps

    private static final Pattern COMPONENT = Pattern.compile("^\\s*([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)\\s*$");

    public interface AppsCallback {
        void onApps(List<TvApp> apps);
    }

    public void loadApps(AppsCallback callback) {
        final AdbConnection c = conn;
        bg.execute(() -> {
            List<TvApp> apps = new ArrayList<>();
            if (c != null) {
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
