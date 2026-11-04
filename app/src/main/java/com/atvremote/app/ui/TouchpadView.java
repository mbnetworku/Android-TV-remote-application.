package com.atvremote.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** Swipe area: swipes become D-pad moves, tap = OK, hold = long-press OK. */
public final class TouchpadView extends View {

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint touch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float dp;
    private final float step;
    private final int slop;

    private DpadView.Listener listener;
    private float downX, downY, lastX, lastY, accX, accY;
    private boolean touching, moved, longFired;

    private final Runnable longPress = () -> {
        if (touching && !moved) {
            longFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (listener != null) listener.onLongKey(KeyEvent.KEYCODE_DPAD_CENTER);
        }
    };

    public TouchpadView(Context c, AttributeSet a) {
        super(c, a);
        dp = getResources().getDisplayMetrics().density;
        step = 44 * dp;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        bg.setColor(0x26FFFFFF);
        hint.setColor(0xB3FFFFFF);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(14 * dp);
        touch.setColor(0x555B8CFF);
    }

    public void setListener(DpadView.Listener l) {
        listener = l;
    }

    @Override
    protected void onDraw(Canvas c) {
        rect.set(0, 0, getWidth(), getHeight());
        c.drawRoundRect(rect, 28 * dp, 28 * dp, bg);
        if (touching) {
            c.drawCircle(lastX, lastY, 36 * dp, touch);
        } else {
            float y = getHeight() / 2f;
            c.drawText("Swipe to move", getWidth() / 2f, y - 4 * dp, hint);
            c.drawText("Tap to select · Hold for options", getWidth() / 2f, y + 18 * dp, hint);
        }
    }

    private void emit(int key) {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (listener != null) listener.onKey(key);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = x;
                downY = lastY = y;
                accX = accY = 0;
                touching = true;
                moved = longFired = false;
                postDelayed(longPress, 600);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                accX += x - lastX;
                accY += y - lastY;
                lastX = x;
                lastY = y;
                if (!moved && Math.hypot(x - downX, y - downY) > slop) {
                    moved = true;
                    removeCallbacks(longPress);
                }
                while (Math.abs(accX) >= step || Math.abs(accY) >= step) {
                    if (Math.abs(accX) >= Math.abs(accY)) {
                        emit(accX > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
                        accX -= Math.signum(accX) * step;
                        accY = 0;
                    } else {
                        emit(accY > 0 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP);
                        accY -= Math.signum(accY) * step;
                        accX = 0;
                    }
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (!moved && !longFired) {
                    emit(KeyEvent.KEYCODE_DPAD_CENTER);
                    performClick();
                }
                touching = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                touching = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
