package com.atvremote.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/** Round D-pad: four direction segments (auto-repeat while held) and an OK button (hold = long press). */
public final class DpadView extends View {

    public interface Listener {
        void onKey(int keyCode);

        void onLongKey(int keyCode);
    }

    private static final int NONE = -1;
    private static final int CENTER = KeyEvent.KEYCODE_DPAD_CENTER;

    private final Paint ring = paint(0x26FFFFFF);
    private final Paint ringPressed = paint(0x40FFFFFF);
    private final Paint center = paint(0x47FFFFFF);
    private final Paint centerPressed = paint(0x80FFFFFF);
    private final Paint gap = paint(0x33FFFFFF);
    private final Paint chevron = paint(0xCCFFFFFF);
    private final Paint label = paint(0xFFECEFF4);
    private final Paint edge = paint(0x55FFFFFF);
    private final RectF outerRect = new RectF();
    private final RectF innerRect = new RectF();
    private final Path path = new Path();
    private final float dp;

    private float cx, cy, outerR, innerR;
    private int pressed = NONE;
    private boolean longFired;
    private Listener listener;

    private final Runnable repeat = new Runnable() {
        @Override
        public void run() {
            if (pressed != NONE && pressed != CENTER) {
                fire(pressed);
                postDelayed(this, 110);
            }
        }
    };

    private final Runnable longPress = () -> {
        if (pressed == CENTER) {
            longFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (listener != null) listener.onLongKey(CENTER);
        }
    };

    public DpadView(Context c, AttributeSet a) {
        super(c, a);
        dp = getResources().getDisplayMetrics().density;
        gap.setStyle(Paint.Style.STROKE);
        gap.setStrokeWidth(1 * dp);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1 * dp);
        chevron.setStyle(Paint.Style.STROKE);
        chevron.setStrokeWidth(3 * dp);
        chevron.setStrokeCap(Paint.Cap.ROUND);
        chevron.setStrokeJoin(Paint.Join.ROUND);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(18 * dp);
        label.setFakeBoldText(true);
    }

    private static Paint paint(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        return p;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        cx = w / 2f;
        cy = h / 2f;
        outerR = Math.min(w, h) / 2f - 4 * dp;
        innerR = outerR * 0.40f;
        outerRect.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR);
        innerRect.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR);
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawCircle(cx, cy, outerR, ring);
        c.drawCircle(cx, cy, outerR, edge);
        if (pressed != NONE && pressed != CENTER) {
            float start = startAngle(pressed);
            path.reset();
            path.arcTo(outerRect, start, 90);
            path.arcTo(innerRect, start + 90, -90);
            path.close();
            c.drawPath(path, ringPressed);
        }
        for (int a = 45; a < 360; a += 90) {
            double r = Math.toRadians(a);
            c.drawLine(cx + (float) Math.cos(r) * innerR, cy + (float) Math.sin(r) * innerR,
                    cx + (float) Math.cos(r) * (outerR + 2), cy + (float) Math.sin(r) * (outerR + 2), gap);
        }
        float mid = (outerR + innerR) / 2f;
        float s = Math.max(6 * dp, outerR * 0.07f);
        for (int a = 0; a < 360; a += 90) {
            c.save();
            c.rotate(a, cx, cy);
            float x = cx + mid;
            path.reset();
            path.moveTo(x - s / 2, cy - s);
            path.lineTo(x + s / 2, cy);
            path.lineTo(x - s / 2, cy + s);
            c.drawPath(path, chevron);
            c.restore();
        }
        c.drawCircle(cx, cy, innerR - 6 * dp, pressed == CENTER ? centerPressed : center);
        c.drawCircle(cx, cy, innerR - 6 * dp, edge);
        c.drawText("OK", cx, cy - (label.descent() + label.ascent()) / 2, label);
    }

    private static float startAngle(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_RIGHT: return -45;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 45;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 135;
            default: return 225;
        }
    }

    private int hit(float x, float y) {
        float dx = x - cx, dy = y - cy;
        double d = Math.hypot(dx, dy);
        if (d <= innerR) return CENTER;
        if (d > outerR * 1.1) return NONE;
        double a = Math.toDegrees(Math.atan2(dy, dx));
        if (a >= -45 && a < 45) return KeyEvent.KEYCODE_DPAD_RIGHT;
        if (a >= 45 && a < 135) return KeyEvent.KEYCODE_DPAD_DOWN;
        if (a >= -135 && a < -45) return KeyEvent.KEYCODE_DPAD_UP;
        return KeyEvent.KEYCODE_DPAD_LEFT;
    }

    private void fire(int key) {
        if (listener != null) listener.onKey(key);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                int zone = hit(e.getX(), e.getY());
                if (zone == NONE) return false;
                pressed = zone;
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (zone == CENTER) {
                    longFired = false;
                    postDelayed(longPress, 550);
                } else {
                    fire(zone);
                    postDelayed(repeat, 400);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (pressed == CENTER && !longFired) {
                    fire(CENTER);
                    performClick();
                }
                reset();
                return true;
            case MotionEvent.ACTION_CANCEL:
                reset();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void reset() {
        removeCallbacks(repeat);
        removeCallbacks(longPress);
        pressed = NONE;
        invalidate();
    }
}
