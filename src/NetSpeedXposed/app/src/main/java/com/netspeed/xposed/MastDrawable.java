package com.netspeed.xposed;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** A radio mast sending out waves (pole on an A-frame, beacon on top, two arcs each side). */
final class MastDrawable extends Drawable {
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final int w, h;

    private final Paint red = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waves = new Paint(Paint.ANTI_ALIAS_FLAG);

    MastDrawable(int color, int widthPx, int heightPx) {
        this(color, widthPx, heightPx, true);
    }

    /** Colourful: red upper legs and beacon, orange waves, lower legs in the text colour. */
    MastDrawable(int color, int widthPx, int heightPx, boolean darkBar) {
        for (Paint p : new Paint[]{stroke, red, waves}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        stroke.setColor(color);
        fill.setColor(darkBar ? 0xFFFF3B30 : 0xFFC62828);
        red.setColor(darkBar ? 0xFFFF3B30 : 0xFFC62828);
        waves.setColor(darkBar ? 0xFFFF9F0A : 0xFFC46A00);
        w = widthPx;
        h = heightPx;
        setBounds(0, 0, w, h);
    }

    @Override
    public void draw(Canvas c) {
        float l = getBounds().left, t = getBounds().top;
        float bw = getBounds().width(), bh = getBounds().height();
        float cx = l + bw / 2f, top = t + bh * 0.30f, bottom = t + bh * 0.97f;
        float sw = Math.max(1f, bw * 0.085f);
        for (Paint p : new Paint[]{stroke, red, waves}) p.setStrokeWidth(sw);
        float foot = bw * 0.20f;
        float mid = top + (bottom - top) * 0.5f, kMid = foot * 0.5f;
        // upper legs red, lower legs white (like a painted mast)
        c.drawLine(cx, top, cx - kMid, mid, red);
        c.drawLine(cx, top, cx + kMid, mid, red);
        c.drawLine(cx - kMid, mid, cx - foot, bottom, stroke);
        c.drawLine(cx + kMid, mid, cx + foot, bottom, stroke);
        float y1 = top + (bottom - top) * 0.45f, y2 = top + (bottom - top) * 0.75f;
        c.drawLine(cx - foot * 0.45f, y1, cx + foot * 0.45f, y1, red);
        c.drawLine(cx - foot * 0.75f, y2, cx + foot * 0.75f, y2, stroke);
        c.drawCircle(cx, top, bw * 0.085f, fill);
        for (float r : new float[]{bw * 0.20f, bw * 0.36f}) {
            arc.set(cx - r, top - r, cx + r, top + r);
            c.drawArc(arc, 140, 80, false, waves);
            c.drawArc(arc, -40, 80, false, waves);
        }
    }

    @Override public int getIntrinsicWidth() { return w; }
    @Override public int getIntrinsicHeight() { return h; }
    @Override public void setAlpha(int a) { stroke.setAlpha(a); fill.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { stroke.setColorFilter(cf); fill.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
