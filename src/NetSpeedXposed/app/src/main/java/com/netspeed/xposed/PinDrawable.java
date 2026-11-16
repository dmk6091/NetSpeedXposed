package com.netspeed.xposed;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Distance: a dotted line ending in a map pin. */
final class PinDrawable extends Drawable {
    private final Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pin = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int w, h;

    PinDrawable(int dotColor, int pinColor, int widthPx, int heightPx) {
        dots.setColor(dotColor);
        dots.setStrokeCap(Paint.Cap.ROUND);
        pin.setColor(pinColor);
        w = widthPx;
        h = heightPx;
        setBounds(0, 0, w, h);
    }

    @Override
    public void draw(Canvas c) {
        float l = getBounds().left, t = getBounds().top, bw = getBounds().width(), bh = getBounds().height();
        float y = t + bh * 0.80f;
        dots.setStrokeWidth(Math.max(1f, bh * 0.12f));
        float pinW = bh * 0.55f;
        float lineEnd = l + bw - pinW * 0.6f;
        for (float x = l + bh * 0.08f; x < lineEnd; x += bh * 0.30f) c.drawPoint(x, y, dots);
        // pin: circle head + point at the line's end
        float px = l + bw - pinW / 2f, r = pinW / 2f, cy = t + bh * 0.32f;
        c.drawCircle(px, cy, r, pin);
        path.reset();
        path.moveTo(px - r * 0.85f, cy + r * 0.45f);
        path.lineTo(px + r * 0.85f, cy + r * 0.45f);
        path.lineTo(px, y);
        path.close();
        c.drawPath(path, pin);
        Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
        hole.setColor(0x66000000);
        c.drawCircle(px, cy, r * 0.38f, hole);
    }

    @Override public int getIntrinsicWidth() { return w; }
    @Override public int getIntrinsicHeight() { return h; }
    @Override public void setAlpha(int a) { dots.setAlpha(a); pin.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { dots.setColorFilter(cf); pin.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
