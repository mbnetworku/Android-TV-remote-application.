package com.atvremote.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** Calm graphite background with a soft light glow at the top; the glass controls sit on it. */
public final class LiquidBackground extends View {

    private final Paint base = new Paint();
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);

    public LiquidBackground(Context c, AttributeSet a) {
        super(c, a);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        base.setShader(new LinearGradient(0, 0, 0, h, 0xFF23262D, 0xFF0B0C0F, Shader.TileMode.CLAMP));
        glow.setShader(new RadialGradient(w / 2f, h * 0.08f, w * 0.9f,
                new int[]{0x2EFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawRect(0, 0, getWidth(), getHeight(), base);
        c.drawRect(0, 0, getWidth(), getHeight(), glow);
    }
}
