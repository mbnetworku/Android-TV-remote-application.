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
            default:
                break;
        }
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
