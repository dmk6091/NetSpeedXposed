package com.netspeed.xposed;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Thin line under the clock that fills up over each minute. */
final class SecondsLineDrawable extends Drawable {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    float frac = 0f;

    SecondsLineDrawable() {
        track.setStrokeCap(Paint.Cap.ROUND);
        fill.setStrokeCap(Paint.Cap.ROUND);
    }

    void set(float frac, int fillColor, int trackColor) {
        this.frac = frac;
        fill.setColor(fillColor);
        track.setColor(trackColor);
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        float l = getBounds().left, r = getBounds().right, y = getBounds().exactCenterY();
        float sw = Math.max(1.5f, getBounds().height());
        track.setStrokeWidth(sw);
        fill.setStrokeWidth(sw);
        c.drawLine(l + sw / 2, y, r - sw / 2, y, track);
        if (frac > 0.005f) c.drawLine(l + sw / 2, y, l + sw / 2 + (r - l - sw) * frac, y, fill);
    }

    @Override public void setAlpha(int a) { track.setAlpha(a); fill.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { track.setColorFilter(cf); fill.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
