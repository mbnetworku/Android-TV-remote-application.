package com.atvremote.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

/** Pure black background; the transparent glass controls sit on top of it. */
public final class LiquidBackground extends View {

    public LiquidBackground(Context c, AttributeSet a) {
        super(c, a);
        setBackgroundColor(0xFF000000);
    }
}
