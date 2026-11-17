package com.netspeed.xposed;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** A small satellite-dish symbol (tilted dish on a stand, feed arm and waves), in one colour. */
final class DishDrawable extends Drawable {
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF arc = new RectF();

    DishDrawable(int color, int sizePx) {
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
        stroke.setStrokeWidth(Math.max(1f, w * 0.09f));

        // Dish: a bowl tilted up and to the right, filled.
        c.save();
        c.rotate(-35, l + w * 0.42f, t + h * 0.55f);
        path.reset();
        arc.set(l + w * 0.08f, t + h * 0.20f, l + w * 0.76f, t + h * 0.88f);
        path.arcTo(arc, 0, 180, true);
        path.close();
        c.drawPath(path, fill);
        // Feed arm from the dish centre to the focus point.
        float fx = l + w * 0.42f, fy = t + h * 0.54f;
        c.drawLine(fx, fy, fx, t + h * 0.22f, stroke);
        c.drawCircle(fx, t + h * 0.20f, w * 0.07f, fill);
        c.restore();

        // Stand.
        c.drawLine(l + w * 0.40f, t + h * 0.72f, l + w * 0.30f, t + h * 0.96f, stroke);
        c.drawLine(l + w * 0.18f, t + h * 0.96f, l + w * 0.52f, t + h * 0.96f, stroke);

        // Waves near the top right.
        for (float r : new float[]{w * 0.16f, w * 0.30f}) {
            arc.set(l + w * 0.70f - r, t + h * 0.22f - r, l + w * 0.70f + r, t + h * 0.22f + r);
            c.drawArc(arc, -80, 70, false, stroke);
        }
    }

    @Override public void setAlpha(int a) { stroke.setAlpha(a); fill.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { stroke.setColorFilter(cf); fill.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
