package com.netspeed.xposed;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/**
 * Speed sparkline: the last N seconds of ↓ (green, soft fill) and ↑ (blue line), log scale.
 * Each new sample slides in over ~350 ms and the newest point has a dot.
 */
final class SparkDrawable extends Drawable {
    static final int N = 30;
    private final long[] down = new long[N], up = new long[N];
    private int count;
    private long pushedAt;
    private int w, h, downColor, upColor, trackColor;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), area = new Paint(Paint.ANTI_ALIAS_FLAG),
            dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path p = new Path();
    private static final long SLIDE_MS = 350;
    private final Runnable tick = this::invalidateSelf;

    SparkDrawable() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        area.setStyle(Paint.Style.FILL);
    }

    void size(int w, int h) {
        if (w == this.w && h == this.h) return;
        this.w = w;
        this.h = h;
        setBounds(0, 0, w, h);
    }

    void colors(int downColor, int upColor, int trackColor) {
        this.downColor = downColor;
        this.upColor = upColor;
        this.trackColor = trackColor;
    }

    void push(long d, long u) {
        System.arraycopy(down, 1, down, 0, N - 1);
        System.arraycopy(up, 1, up, 0, N - 1);
        down[N - 1] = d;
        up[N - 1] = u;
        count = Math.min(N, count + 1);
        pushedAt = SystemClock.uptimeMillis();
        invalidateSelf();
    }

    /** log scale: 1 K/s at the bottom, top = the biggest value shown (at least 64 K/s) */
    private float y(long v, double top, float t, float b) {
        double l = v <= 1024 ? 0 : Math.log10(v / 1024.0);
        return (float) (b - (b - t) * Math.min(1.0, l / top));
    }

    @Override
    public void draw(Canvas c) {
        float l = getBounds().left, t = getBounds().top + h * 0.12f, b = getBounds().top + h * 0.88f;
        float sw = Math.max(1.2f, h * 0.075f);
        long max = 64 * 1024;
        for (int i = 0; i < N; i++) max = Math.max(max, Math.max(down[i], up[i]));
        double top = Math.log10(max / 1024.0);
        long el = SystemClock.uptimeMillis() - pushedAt;
        float slide = el < SLIDE_MS ? 1f - el / (float) SLIDE_MS : 0f;
        float step = (w - sw * 2) / (N - 2f);
        float x0 = l + sw + slide * step;
        // baseline
        line.setStrokeWidth(Math.max(1f, sw * 0.5f));
        line.setColor(trackColor);
        c.drawLine(l + sw, b, l + w - sw, b, line);
        int first = N - Math.max(2, count);
        for (int pass = 0; pass < 2; pass++) {
            long[] a = pass == 0 ? down : up;
            int col = pass == 0 ? downColor : upColor;
            p.reset();
            float lx = 0, ly = 0;
            for (int i = first; i < N; i++) {
                float x = x0 + (i - 1) * step, yy = y(a[i], top, t, b);
                if (x < l + sw) x = l + sw;
                if (i == first) p.moveTo(x, yy);
                else p.lineTo(x, yy);
                lx = x;
                ly = yy;
            }
            if (pass == 0) {
                Path f = new Path(p);
                f.lineTo(lx, b);
                f.lineTo(Math.max(l + sw, x0 + (first - 1) * step), b);
                f.close();
                area.setColor((col & 0x00FFFFFF) | 0x40000000);
                c.drawPath(f, area);
            }
            line.setStrokeWidth(pass == 0 ? sw : sw * 0.8f);
            line.setColor(col);
            c.drawPath(p, line);
            dot.setColor(col);
            c.drawCircle(lx, ly, sw * (pass == 0 ? 1.2f : 1f), dot);
        }
        if (slide > 0f) scheduleSelf(tick, SystemClock.uptimeMillis() + 33);
    }

    @Override public int getIntrinsicWidth() { return w; }
    @Override public int getIntrinsicHeight() { return h; }
    @Override public void setAlpha(int a) { line.setAlpha(a); area.setAlpha(a); dot.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { line.setColorFilter(cf); area.setColorFilter(cf); dot.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
