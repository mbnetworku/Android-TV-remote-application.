package com.atvremote.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Deep background with slowly drifting colour blobs; the glass controls sit on top of it. */
public final class LiquidBackground extends View {

    private static final int[][] BLOBS = {
            // color, x%, y%, radius% (of width), phase
            {0xFF3D6BFF, 15, 18, 75, 0},
            {0xFF9B4DFF, 90, 45, 70, 2},
            {0xFF00C2B8, 20, 85, 65, 4},
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float t;
    private ValueAnimator anim;

    public LiquidBackground(Context c, AttributeSet a) {
        super(c, a);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        anim = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        anim.setDuration(24000);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(v -> {
            t = (float) v.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(0xFF070A14);
        for (int[] b : BLOBS) {
            float drift = w * 0.08f;
            float x = w * b[1] / 100f + (float) Math.cos(t + b[4]) * drift;
            float y = h * b[2] / 100f + (float) Math.sin(t * 0.8 + b[4]) * drift;
            float r = w * b[3] / 100f;
            int core = (b[0] & 0x00FFFFFF) | 0x8C000000;
            paint.setShader(new RadialGradient(x, y, r, new int[]{core, b[0] & 0x00FFFFFF},
                    null, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, r, paint);
        }
        paint.setShader(null);
        paint.setColor(0x59070A14); // soften so glass and text stay readable
        c.drawRect(0, 0, w, h, paint);
    }
}
