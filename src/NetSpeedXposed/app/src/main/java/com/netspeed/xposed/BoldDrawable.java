package com.netspeed.xposed;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Draws another drawable a few times, shifted sideways by a fraction of a pixel step, so thin
 * vector icons (like the system's 5G icon) look bold, matching the bold status bar text.
 */
final class BoldDrawable extends Drawable {
    private final Drawable d;
    private final float off;
    private final int extra;

    BoldDrawable(Drawable d, float offPx) {
        this.d = d;
        this.off = offPx;
        this.extra = (int) Math.ceil(offPx);
    }

    @Override
    public void draw(Canvas c) {
        int steps = 4;
        for (int i = 0; i <= steps; i++) {
            int s = c.save();
            c.translate(off * i / steps, 0);
            d.draw(c);
            c.restoreToCount(s);
        }
    }

    @Override
    protected void onBoundsChange(Rect b) {
        // Keep the aspect ratio of the inner icon; the extra width is for the shift.
        d.setBounds(b.left, b.top, Math.max(b.left, b.right - extra), b.bottom);
    }

    @Override public int getIntrinsicWidth() {
        int w = d.getIntrinsicWidth();
        return w <= 0 ? w : w + extra;
    }
    @Override public int getIntrinsicHeight() { return d.getIntrinsicHeight(); }
    @Override public void setAlpha(int a) { d.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { d.setColorFilter(cf); }
    @Override public void setTint(int t) { d.setTint(t); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
