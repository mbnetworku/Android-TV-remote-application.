package com.atvremote.receiver;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Runs on the TV. Listens on port 6470 for the TV Remote phone app and controls the TV through
 * Accessibility, so developer options / debugging are not needed. A new phone must enter the
 * 4-digit PIN shown on the TV before it is allowed in.
 *
 * Commands (one per line, same as the ADB helper): "k CODE", "l CODE", "t TEXT", "v", "s STEP",
 * "apps", "launch PKG/CLS", "name".
 */
public final class ReceiverService extends AccessibilityService {

    public static final int PORT = 6470;
    static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private ServerSocket server;
    private AudioManager audio;
    private SharedPreferences prefs;
    private View pinView;

    @Override
    protected void onServiceConnected() {
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        prefs = getSharedPreferences("receiver", MODE_PRIVATE);
        running = true;
        Thread t = new Thread(this::serve, "receiver-server");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    // ---------------------------------------------------------------- server

    private void serve() {
        try (ServerSocket ss = new ServerSocket(PORT)) {
            server = ss;
            while (running) {
                Socket s = ss.accept();
                Thread t = new Thread(() -> client(s), "receiver-client");
                t.setDaemon(true);
                t.start();
            }
        } catch (Exception ignored) {
        }
    }

    private void client(Socket s) {
        try (Socket sock = s) {
            sock.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = sock.getOutputStream();
            if (!authorize(in, out)) return;
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    handle(line, out);
                } catch (Exception e) {
                    write(out, "ERR " + e);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void write(OutputStream out, String line) throws java.io.IOException {
        synchronized (out) {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /** "HELLO token name" → OK, or ask for the PIN shown on the TV. */
    private boolean authorize(BufferedReader in, OutputStream out) throws Exception {
        String hello = in.readLine();
        if (hello == null || !hello.startsWith("HELLO")) return false;
        String[] p = hello.split(" ", 3);
        String token = p.length > 1 ? p[1] : "-";
        String phone = p.length > 2 ? p[2] : "a phone";
        Set<String> tokens = new HashSet<>(prefs.getStringSet("tokens", new HashSet<>()));
        if (tokens.contains(token)) {
            write(out, "OK " + token);
            return true;
        }
        String pin = String.format(Locale.ROOT, "%04d", random.nextInt(10000));
        showPin(pin, phone);
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                write(out, "PIN");
                String answer = in.readLine();
                if (answer == null) return false;
                if (answer.trim().equals("PIN " + pin)) {
                    byte[] b = new byte[16];
                    random.nextBytes(b);
                    StringBuilder sb = new StringBuilder();
                    for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
                    String newToken = sb.toString();
                    tokens.add(newToken);
                    prefs.edit().putStringSet("tokens", tokens).apply();
                    write(out, "OK " + newToken);
                    return true;
                }
            }
            write(out, "DENIED");
            return false;
        } finally {
            hidePin();
        }
    }

    private void showPin(String pin, String phone) {
        main.post(() -> {
            hidePin();
            TextView v = new TextView(this);
            v.setText("TV Remote\n\n" + phone + " wants to connect.\nEnter this PIN on the phone:\n\n" + pin);
            v.setTextColor(0xFFFFFFFF);
            v.setTextSize(22);
            v.setGravity(Gravity.CENTER);
            v.setPadding(60, 40, 60, 40);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xE6000000);
            bg.setCornerRadius(36);
            bg.setStroke(3, 0x66FFFFFF);
            v.setBackground(bg);
            if (Build.VERSION.SDK_INT >= 22) {
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.CENTER;
                try {
                    ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(v, lp);
                    pinView = v;
                    return;
                } catch (Exception ignored) {
                }
            }
            Toast.makeText(this, "TV Remote PIN: " + pin, Toast.LENGTH_LONG).show();
        });
        main.postDelayed(this::hidePin, 120000);
    }

    private void hidePin() {
        main.post(() -> {
            if (pinView != null) {
                try {
                    ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(pinView);
                } catch (Exception ignored) {
                }
                pinView = null;
            }
        });
    }

    // ---------------------------------------------------------------- commands

    private void handle(String line, OutputStream out) throws Exception {
        if (line.isEmpty()) return;
        String cmd = line.contains(" ") ? line.substring(0, line.indexOf(' ')) : line;
        String arg = line.contains(" ") ? line.substring(line.indexOf(' ') + 1) : "";
        switch (cmd) {
            case "k": key(Integer.parseInt(arg.trim()), false, out); break;
            case "l": key(Integer.parseInt(arg.trim()), true, out); break;
            case "t": type(arg); break;
            case "v": volume(out); break;
            case "s": setVolume(Integer.parseInt(arg.trim()), out); break;
            case "apps": apps(out); break;
            case "launch": launch(arg.trim()); break;
            case "name": write(out, "NAME " + Build.MODEL); break;
            case "p": write(out, "pong"); break;
            default: break;
        }
    }

    private void key(int code, boolean longPress, OutputStream out) throws Exception {
        switch (code) {
            case KeyEvent.KEYCODE_BACK: performGlobalAction(GLOBAL_ACTION_BACK); break;
            case KeyEvent.KEYCODE_HOME: performGlobalAction(GLOBAL_ACTION_HOME); break;
            case KeyEvent.KEYCODE_MENU: performGlobalAction(GLOBAL_ACTION_RECENTS); break;
            case KeyEvent.KEYCODE_SETTINGS:
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            case KeyEvent.KEYCODE_POWER:
                if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
                else performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
                break;
            case KeyEvent.KEYCODE_VOLUME_UP:
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                volume(out);
                break;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                volume(out);
                break;
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                if (Build.VERSION.SDK_INT >= 23) {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                } else {
                    audio.setStreamMute(AudioManager.STREAM_MUSIC, audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0);
                }
                volume(out);
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_STOP: {
                long now = SystemClock.uptimeMillis();
                audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
                audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
                break;
            }
            case KeyEvent.KEYCODE_DPAD_UP: move(View.FOCUS_UP); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(View.FOCUS_DOWN); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: move(View.FOCUS_LEFT); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(View.FOCUS_RIGHT); break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                click(longPress);
                break;
            case KeyEvent.KEYCODE_DEL: deleteChar(); break;
            default: break;
        }
    }

    // ---------------------------------------------------------------- focus navigation

    private AccessibilityNodeInfo focused(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null) f = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        return f;
    }

    private void move(int direction) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo cur = focused(root);
        List<AccessibilityNodeInfo> all = focusables(root);
        if (cur == null) {
            if (!all.isEmpty()) all.get(0).performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            return;
        }
        AccessibilityNodeInfo next = cur.focusSearch(direction);
        if (next != null && !next.equals(cur) && next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return;
        // Fallback: nearest focusable view in that direction on screen.
        Rect from = new Rect();
        cur.getBoundsInScreen(from);
        AccessibilityNodeInfo best = null;
        long bestScore = Long.MAX_VALUE;
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : all) {
            if (n.equals(cur)) continue;
            n.getBoundsInScreen(r);
            int dx = r.centerX() - from.centerX();
            int dy = r.centerY() - from.centerY();
            boolean ok;
            long main, cross;
            switch (direction) {
                case View.FOCUS_UP: ok = r.bottom <= from.top + 4; main = -dy; cross = Math.abs(dx); break;
                case View.FOCUS_DOWN: ok = r.top >= from.bottom - 4; main = dy; cross = Math.abs(dx); break;
                case View.FOCUS_LEFT: ok = r.right <= from.left + 4; main = -dx; cross = Math.abs(dy); break;
                default: ok = r.left >= from.right - 4; main = dx; cross = Math.abs(dy); break;
            }
            if (!ok) continue;
            long score = main * main + 4 * cross * cross;
            if (score < bestScore) {
                bestScore = score;
                best = n;
            }
        }
        if (best != null) {
            best.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            return;
        }
        // Nothing further: scroll the list so more items appear.
        AccessibilityNodeInfo p = cur;
        while (p != null && !p.isScrollable()) p = p.getParent();
        if (p != null) {
            boolean forward = direction == View.FOCUS_DOWN || direction == View.FOCUS_RIGHT;
            p.performAction(forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        }
    }

    private static List<AccessibilityNodeInfo> focusables(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty() && out.size() < 400) {
            AccessibilityNodeInfo n = q.poll();
            if (n.isFocusable() && n.isVisibleToUser() && n.isEnabled()) out.add(n);
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return out;
    }

    private void click(boolean longPress) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo n = focused(root);
        int action = longPress ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK;
        while (n != null) {
            if (n.performAction(action)) return;
            n = n.getParent();
        }
    }

    // ---------------------------------------------------------------- text

    private AccessibilityNodeInfo editable() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        return f != null && f.isEditable() ? f : null;
    }

    private static String currentText(AccessibilityNodeInfo f) {
        if (Build.VERSION.SDK_INT >= 26 && f.isShowingHintText()) return "";
        CharSequence t = f.getText();
        return t == null ? "" : t.toString();
    }

    private void setText(AccessibilityNodeInfo f, String text) {
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    private void type(String text) {
        AccessibilityNodeInfo f = editable();
        if (f != null) setText(f, currentText(f) + text);
    }

    private void deleteChar() {
        AccessibilityNodeInfo f = editable();
        if (f == null) return;
        String t = currentText(f);
        if (!t.isEmpty()) setText(f, t.substring(0, t.length() - 1));
    }

    // ---------------------------------------------------------------- volume

    private void volume(OutputStream out) throws Exception {
        write(out, "VOL " + audio.getStreamVolume(AudioManager.STREAM_MUSIC) + " "
                + audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
    }

    private void setVolume(int step, OutputStream out) throws Exception {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, step, AudioManager.FLAG_SHOW_UI);
        volume(out);
    }

    // ---------------------------------------------------------------- apps

    private void apps(OutputStream out) throws Exception {
        PackageManager pm = getPackageManager();
        LinkedHashMap<String, ResolveInfo> found = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent it = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(it, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!found.containsKey(pkg) && !pkg.equals(getPackageName())) found.put(pkg, ri);
            }
        }
        for (ResolveInfo ri : found.values()) {
            write(out, "ACT " + ri.activityInfo.packageName + "/" + ri.activityInfo.name);
        }
        for (ResolveInfo ri : found.values()) {
            try {
                String label = String.valueOf(ri.loadLabel(pm)).replace('\t', ' ').replace('\n', ' ');
                Drawable d = ri.activityInfo.applicationInfo.loadIcon(pm);
                Bitmap bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
                d.setBounds(0, 0, 96, 96);
                d.draw(new Canvas(bmp));
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.PNG, 100, png);
                write(out, "APP " + ri.activityInfo.packageName + "\t" + label + "\t"
                        + Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP));
            } catch (Exception e) {
                write(out, "ERR " + e);
            }
        }
        write(out, "DONE");
    }

    private void launch(String component) {
        ComponentName cn = ComponentName.unflattenFromString(component);
        if (cn == null) return;
        Intent i = new Intent(Intent.ACTION_MAIN).setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        main.post(() -> {
            try {
                startActivity(i);
            } catch (Exception e) {
                Intent alt = getPackageManager().getLeanbackLaunchIntentForPackage(cn.getPackageName());
                if (alt == null) alt = getPackageManager().getLaunchIntentForPackage(cn.getPackageName());
                if (alt != null) startActivity(alt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        });
    }
}
