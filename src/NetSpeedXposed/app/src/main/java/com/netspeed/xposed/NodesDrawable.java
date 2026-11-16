package com.netspeed.xposed;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Cells heard: a dot (your phone) with two pairs of broadcast waves, ((•)). */
final class NodesDrawable extends Drawable {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF r = new android.graphics.RectF();
    private final int size;

    NodesDrawable(int color, int sizePx) {
        p.setColor(color);
        p.setStrokeCap(Paint.Cap.ROUND);
        size = sizePx;
        setBounds(0, 0, size, size);
    }

    @Override
    public void draw(Canvas c) {
        float l = getBounds().left, t = getBounds().top, s = getBounds().width();
        float cx = l + s / 2f, cy = t + s / 2f;
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, s * 0.12f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, s * 0.1f));
        for (float rad : new float[]{s * 0.27f, s * 0.42f}) {
            r.set(cx - rad, cy - rad, cx + rad, cy + rad);
            c.drawArc(r, -50, 100, false, p);
            c.drawArc(r, 130, 100, false, p);
        }
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setAlpha(int a) { p.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
