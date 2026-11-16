package com.netspeed.xposed;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Samsung-style data activity arrows: a thick ↓ and ↑ side by side, each with its own color. */
final class ArrowsDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int w, h, gap;
    private int inColor, outColor;
    private boolean inMove, outMove;
    private final Runnable tick = new Runnable() {
        @Override public void run() { invalidateSelf(); }
    };

    /** w = width of one arrow, h = height, gap = space between them (px). */
    ArrowsDrawable(int w, int h, int gap) {
        this.w = w;
        this.h = h;
        this.gap = gap;
        paint.setStyle(Paint.Style.FILL);
    }

    void setColors(int in, int out) {
        if (in == inColor && out == outColor) return;
        inColor = in;
        outColor = out;
        invalidateSelf();
    }

    /** Arrows that carry traffic slide in their direction and fade in and out. */
    void setMotion(boolean in, boolean out) {
        if (in == inMove && out == outMove) return;
        inMove = in;
        outMove = out;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float sx = b.width() / (float) (2 * w + gap), sy = b.height() / (float) h;
        c.save();
        c.translate(b.left, b.top);
        c.scale(sx, sy);
        float ph = (android.os.SystemClock.uptimeMillis() % 800) / 800f;
        float slide = (ph - 0.5f) * h * 0.4f, fade = 0.45f + 0.55f * (float) Math.sin(Math.PI * ph);
        c.save();
        if (inMove) c.translate(0, slide);
        arrow(c, 0, true, inMove ? fadeColor(inColor, fade) : inColor);
        c.restore();
        c.save();
        if (outMove) c.translate(0, -slide);
        arrow(c, w + gap, false, outMove ? fadeColor(outColor, fade) : outColor);
        c.restore();
        c.restore();
        if (inMove || outMove) scheduleSelf(tick, android.os.SystemClock.uptimeMillis() + 50);
    }

    private static int fadeColor(int color, float f) {
        int al = Math.round(((color >>> 24) & 0xFF) * f);
        return (color & 0x00FFFFFF) | (al << 24);
    }

    /** A thick arrow: shaft about 40% of the width, head as wide as the arrow. */
    private void arrow(Canvas c, float x, boolean down, int color) {
        float shaft = w * 0.42f, head = h * 0.5f, cx = x + w / 2f;
        path.reset();
        if (down) {
            path.moveTo(cx - shaft / 2, 0);
            path.lineTo(cx + shaft / 2, 0);
            path.lineTo(cx + shaft / 2, h - head);
            path.lineTo(x + w, h - head);
            path.lineTo(cx, h);
            path.lineTo(x, h - head);
            path.lineTo(cx - shaft / 2, h - head);
        } else {
            path.moveTo(cx, 0);
            path.lineTo(x + w, head);
            path.lineTo(cx + shaft / 2, head);
            path.lineTo(cx + shaft / 2, h);
            path.lineTo(cx - shaft / 2, h);
            path.lineTo(cx - shaft / 2, head);
            path.lineTo(x, head);
        }
        path.close();
        paint.setColor(color);
        c.drawPath(path, paint);
    }

    @Override public int getIntrinsicWidth() { return 2 * w + gap; }
    @Override public int getIntrinsicHeight() { return h; }
    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
