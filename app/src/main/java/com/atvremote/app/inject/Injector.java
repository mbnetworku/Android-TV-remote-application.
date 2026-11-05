package com.atvremote.app.inject;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Runs ON THE TV as the adb shell user:
 *   CLASSPATH=/data/local/tmp/atvremote.apk app_process / com.atvremote.app.inject.Injector
 * It reads one command per line from stdin and injects key events directly, which is far
 * faster than running "input keyevent" (a new VM per key) for every button press.
 *
 * Commands: "k CODE" press, "l CODE" long press, "t TEXT" type text, "p" ping.
 * Uses only framework classes; must never touch a Context.
 */
public final class Injector {

    private static Object manager;
    private static Method injectMethod;

    public static void main(String[] args) {
        if (args.length > 0 && "apps".equals(args[0])) {
            apps(args);
            System.exit(0);
            return;
        }
        try {
            init();
        } catch (Throwable t) {
            System.out.println("FAIL " + t);
            System.out.flush();
            return;
        }
        System.out.println("READY");
        System.out.flush();
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    handle(line);
                } catch (Throwable t) {
                    System.out.println("ERR " + t);
                    System.out.flush();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void init() throws Exception {
        try {
            // Android 14+
            Class<?> g = Class.forName("android.hardware.input.InputManagerGlobal");
            manager = g.getMethod("getInstance").invoke(null);
            injectMethod = g.getMethod("injectInputEvent", InputEvent.class, int.class);
            return;
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
        }
        Class<?> c = Class.forName("android.hardware.input.InputManager");
        Method getInstance = c.getDeclaredMethod("getInstance");
        getInstance.setAccessible(true);
        manager = getInstance.invoke(null);
        injectMethod = c.getMethod("injectInputEvent", InputEvent.class, int.class);
    }

    private static void handle(String line) throws Exception {
        if (line.isEmpty()) return;
        char op = line.charAt(0);
        String arg = line.length() > 2 ? line.substring(2) : "";
        switch (op) {
            case 'k':
                key(Integer.parseInt(arg.trim()), false);
                break;
            case 'l':
                key(Integer.parseInt(arg.trim()), true);
                break;
            case 't':
                text(arg);
                break;
            case 'p':
                System.out.println("pong");
                System.out.flush();
                break;
            case 'v':
                printVolume();
                break;
            case 's':
                setVolume(Integer.parseInt(arg.trim()));
                break;
            default:
                break;
        }
    }

    // ---------------------------------------------------------------- TV volume (music stream)

    private static final int STREAM_MUSIC = 3;
    private static Object audio;

    private static Object audio() throws Exception {
        if (audio == null) {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            android.os.IBinder b = (android.os.IBinder) sm.getMethod("getService", String.class).invoke(null, "audio");
            Class<?> stub = Class.forName("android.media.IAudioService$Stub");
            audio = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, b);
        }
        return audio;
    }

    private static int[] volume() throws Exception {
        Object a = audio();
        int cur = (Integer) a.getClass().getMethod("getStreamVolume", int.class).invoke(a, STREAM_MUSIC);
        int max = (Integer) a.getClass().getMethod("getStreamMaxVolume", int.class).invoke(a, STREAM_MUSIC);
        return new int[]{cur, max};
    }

    private static void printVolume() throws Exception {
        int[] v = volume();
        System.out.println("VOL " + v[0] + " " + v[1]);
        System.out.flush();
    }

    /** Sets the volume step; uses the audio service, or volume key presses if that is not allowed. */
    private static void setVolume(int target) throws Exception {
        int[] v = volume();
        target = Math.max(0, Math.min(v[1], target));
        boolean done = false;
        Object a = audio();
        for (Method m : a.getClass().getMethods()) {
            if (!m.getName().equals("setStreamVolume")) continue;
            Class<?>[] p = m.getParameterTypes();
            Object[] args = new Object[p.length];
            int ints = 0;
            for (int i = 0; i < p.length; i++) {
                if (p[i] == int.class) {
                    args[i] = ints == 0 ? STREAM_MUSIC : ints == 1 ? target : 1; // flags: show TV volume UI
                    ints++;
                } else if (p[i] == String.class) {
                    args[i] = "com.android.shell";
                } else if (p[i] == boolean.class) {
                    args[i] = false;
                }
            }
            if (ints < 3) continue;
            try {
                m.invoke(a, args);
                done = true;
                break;
            } catch (Throwable ignored) {
            }
        }
        if (!done || volume()[0] != target) {
            int diff = target - volume()[0];
            int code = diff > 0 ? KeyEvent.KEYCODE_VOLUME_UP : KeyEvent.KEYCODE_VOLUME_DOWN;
            for (int i = 0; i < Math.abs(diff); i++) {
                key(code, false);
                Thread.sleep(40);
            }
        }
        printVolume();
    }

    // ---------------------------------------------------------------- app names + icons

    /** "apps pkg1 pkg2 …": prints "APP pkg\tlabel\tbase64-png" for each, then "DONE". */
    private static void apps(String[] pkgs) {
        android.content.pm.PackageManager pm = null;
        try {
            if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper();
            Class<?> atc = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Constructor<?> ctor = atc.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object at = ctor.newInstance();
            java.lang.reflect.Field f = atc.getDeclaredField("sCurrentActivityThread");
            f.setAccessible(true);
            f.set(null, at);
            Method gsc = atc.getDeclaredMethod("getSystemContext");
            gsc.setAccessible(true);
            pm = ((android.content.Context) gsc.invoke(at)).getPackageManager();
        } catch (Throwable t) {
            System.out.println("ERR " + t);
        }
        if (pkgs.length == 1 && pm != null) {
            // No packages given: list launchable apps ourselves ("cmd package" is missing before Android 7).
            java.util.LinkedHashMap<String, String> found = new java.util.LinkedHashMap<>();
            for (String category : new String[]{"android.intent.category.LEANBACK_LAUNCHER",
                    "android.intent.category.LAUNCHER"}) {
                android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_MAIN);
                it.addCategory(category);
                try {
                    for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(it, 0)) {
                        String p = ri.activityInfo.packageName;
                        if (!found.containsKey(p)) found.put(p, p + "/" + ri.activityInfo.name);
                    }
                } catch (Throwable t) {
                    System.out.println("ERR " + t);
                }
            }
            for (String component : found.values()) System.out.println("ACT " + component);
            String[] all = new String[found.size() + 1];
            all[0] = "apps";
            int k = 1;
            for (String p : found.keySet()) all[k++] = p;
            pkgs = all;
        }
        for (int i = 1; i < pkgs.length && pm != null; i++) {
            try {
                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkgs[i], 0);
                String label = String.valueOf(pm.getApplicationLabel(ai)).replace('\t', ' ').replace('\n', ' ');
                android.graphics.drawable.Drawable d = ai.loadIcon(pm);
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                        96, 96, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas c = new android.graphics.Canvas(bmp);
                d.setBounds(0, 0, 96, 96);
                d.draw(c);
                java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
                System.out.println("APP " + pkgs[i] + "\t" + label + "\t"
                        + android.util.Base64.encodeToString(png.toByteArray(), android.util.Base64.NO_WRAP));
            } catch (Throwable t) {
                System.out.println("ERR " + pkgs[i] + " " + t);
            }
            System.out.flush();
        }
        System.out.println("DONE");
        System.out.flush();
    }

    private static void key(int code, boolean longPress) throws Exception {
        long down = SystemClock.uptimeMillis();
        inject(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
        if (longPress) {
            Thread.sleep(600);
            inject(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, code, 1, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_LONG_PRESS, InputDevice.SOURCE_KEYBOARD));
        }
        inject(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }

    private static void text(String s) throws Exception {
        KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        for (int i = 0; i < s.length(); i++) {
            KeyEvent[] events = kcm.getEvents(new char[]{s.charAt(i)});
            if (events == null) continue; // character not on the virtual keyboard (e.g. emoji)
            for (KeyEvent e : events) inject(e);
        }
    }

    private static void inject(KeyEvent e) throws Exception {
        injectMethod.invoke(manager, e, 0); // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
    }
}
