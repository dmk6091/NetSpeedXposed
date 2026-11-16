package com.netspeed.xposed;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Small speedometer: grey half-ring track, coloured progress, white needle (frac 0..1). */
final class GaugeDrawable extends Drawable {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint prog = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int w, h;
    private final float frac;

    GaugeDrawable(float frac, int progColor, int needleColor, int trackColor, int widthPx, int heightPx) {
        for (Paint p : new Paint[]{track, prog, needle}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        track.setColor(trackColor);
        prog.setColor(progColor);
        needle.setColor(needleColor);
        this.frac = Math.max(0f, Math.min(1f, frac));
        w = widthPx;
        h = heightPx;
        setBounds(0, 0, w, h);
    }

    @Override
    public void draw(Canvas c) {
        float l = getBounds().left, t = getBounds().top, bw = getBounds().width(), bh = getBounds().height();
        float sw = Math.max(1.5f, bw * 0.12f);
        float rad = Math.min(bw / 2f - sw, bh * 0.62f - sw);
        float cx = l + bw / 2f, cy = t + bh * 0.68f;
        r.set(cx - rad, cy - rad, cx + rad, cy + rad);
        track.setStrokeWidth(sw);
        prog.setStrokeWidth(sw);
        c.drawArc(r, 180, 180, false, track);
        if (frac > 0.01f) c.drawArc(r, 180, 180 * frac, false, prog);
        double a = Math.toRadians(180 + 180 * frac);
        needle.setStrokeWidth(Math.max(1f, sw * 0.7f));
        c.drawLine(cx, cy, cx + (float) Math.cos(a) * rad * 0.85f, cy + (float) Math.sin(a) * rad * 0.85f, needle);
        needle.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, sw * 0.8f, needle);
        needle.setStyle(Paint.Style.STROKE);
    }

    @Override public int getIntrinsicWidth() { return w; }
    @Override public int getIntrinsicHeight() { return h; }
    @Override public void setAlpha(int a) { track.setAlpha(a); prog.setAlpha(a); needle.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { track.setColorFilter(cf); prog.setColorFilter(cf); needle.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
