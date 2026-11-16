package com.netspeed.xposed;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** A small cell-tower symbol (lattice mast with signal waves), drawn in one colour. */
final class TowerDrawable extends Drawable {
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();

    TowerDrawable(int color, int sizePx) {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(color);
        fill.setColor(color);
        setBounds(0, 0, sizePx, sizePx);
    }

    @Override
    public void draw(Canvas c) {
        float l = getBounds().left, t = getBounds().top;
        float w = getBounds().width(), h = getBounds().height();
        float sw = Math.max(1f, w * 0.09f);
        stroke.setStrokeWidth(sw);

        float cx = l + w / 2f, top = t + h * 0.30f, bottom = t + h * 0.96f;
        float half = w * 0.24f;
        // Legs.
        c.drawLine(cx, top, cx - half, bottom, stroke);
        c.drawLine(cx, top, cx + half, bottom, stroke);
        c.drawLine(cx, top, cx, bottom, stroke);
        // Cross bars and braces.
        float y1 = t + h * 0.58f, y2 = t + h * 0.78f;
        float x1 = half * (y1 - top) / (bottom - top), x2 = half * (y2 - top) / (bottom - top);
        c.drawLine(cx - x1, y1, cx + x1, y1, stroke);
        c.drawLine(cx - x2, y2, cx + x2, y2, stroke);
        c.drawLine(cx - x1, y1, cx + x2, y2, stroke);
        c.drawLine(cx + x1, y1, cx - x2, y2, stroke);
        // Antenna ball.
        c.drawCircle(cx, top, w * 0.09f, fill);
        // Signal waves on both sides.
        for (float r : new float[]{w * 0.20f, w * 0.34f}) {
            arc.set(cx - r, top - r, cx + r, top + r);
            c.drawArc(arc, -40, 80, false, stroke);
            c.drawArc(arc, 140, 80, false, stroke);
        }
    }

    @Override public void setAlpha(int a) { stroke.setAlpha(a); fill.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { stroke.setColorFilter(cf); fill.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
