package com.atvremote.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.view.HapticFeedbackConstants;
import android.view.View;

/** Vibration and tick sound for every button, each switchable in Settings. */
public final class Feedback {

    private static boolean haptics = true;
    private static boolean sound = true;
    private static AudioManager audio;

    private Feedback() {
    }

    public static void init(Context ctx, SharedPreferences prefs) {
        haptics = prefs.getBoolean("haptics", true);
        sound = prefs.getBoolean("sound", true);
        audio = (AudioManager) ctx.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }

    public static boolean haptics() {
        return haptics;
    }

    public static boolean sound() {
        return sound;
    }

    public static void set(SharedPreferences prefs, boolean haptics, boolean sound) {
        Feedback.haptics = haptics;
        Feedback.sound = sound;
        prefs.edit().putBoolean("haptics", haptics).putBoolean("sound", sound).apply();
    }

    public static void tap(View v) {
        play(v, HapticFeedbackConstants.KEYBOARD_TAP);
    }

    public static void tick(View v) {
        play(v, HapticFeedbackConstants.CLOCK_TICK);
    }

    public static void longPress(View v) {
        play(v, HapticFeedbackConstants.LONG_PRESS);
    }

    private static void play(View v, int haptic) {
        if (haptics && v != null) {
            v.performHapticFeedback(haptic, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
        if (sound && audio != null) {
            // The volume argument makes it play even when system touch sounds are off.
            audio.playSoundEffect(AudioManager.FX_KEY_CLICK, 0.6f);
        }
    }
}
