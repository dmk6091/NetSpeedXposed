package com.netspeed.xposed;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/** One small line drawn under the clock digits (date, or sunrise -> sunset). */
final class ClockInfoDrawable extends Drawable {
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    String text = "";
    int color = 0xFFFFFFFF;
    float baseline;   // y of the text baseline inside the clock view

    ClockInfoDrawable() {
        p.setTypeface(Typeface.DEFAULT_BOLD);
    }

    @Override
    public void draw(Canvas c) {
        if (text.isEmpty()) return;
        p.setColor(color);
        c.drawText(text, getBounds().left, baseline, p);
    }

    @Override public void setAlpha(int a) { p.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
