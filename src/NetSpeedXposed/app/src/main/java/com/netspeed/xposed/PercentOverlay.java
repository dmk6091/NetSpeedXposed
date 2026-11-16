package com.netspeed.xposed;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/**
 * Battery percentage drawn over the stock (horizontal) battery icon. The digits are dark where
 * the icon is filled and light where it's empty, so they read at any level.
 */
final class PercentOverlay extends Drawable {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int pct = -1, color = 0xFFFFFFFF;

    PercentOverlay() {
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
    }

    void set(int pct, int color) {
        if (pct == this.pct && color == this.color) return;
        this.pct = pct;
        this.color = color;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        if (pct < 0) return;
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        boolean horizontal = b.width() >= b.height();
        // inner area of the body (leave out the cap and the outline)
        float capFrac = 0.10f, inset = Math.min(b.width(), b.height()) * 0.14f;
        float l, t, r, bot;
        if (horizontal) {
            l = b.left + inset; r = b.left + b.width() * (1 - capFrac) - inset; t = b.top + inset; bot = b.bottom - inset;
        } else {
            l = b.left + inset; r = b.right - inset; t = b.top + b.height() * capFrac + inset; bot = b.bottom - inset;
        }
        String s = String.valueOf(pct);
        float size = (bot - t) * (horizontal ? 0.95f : 0.42f);
        p.setTextSize(size);
        float maxW = (r - l) * 0.95f;
        if (p.measureText(s) > maxW) p.setTextSize(size * maxW / p.measureText(s));
        float cx = (l + r) / 2f;
        Paint.FontMetrics fm = p.getFontMetrics();
        float cy = (t + bot) / 2f - (fm.ascent + fm.descent) / 2f;
        // filled part: from the left (horizontal) or from the bottom (vertical)
        float fillEdge = horizontal ? l + (r - l) * pct / 100f : bot - (bot - t) * pct / 100f;
        boolean darkBar = (0.299f * ((color >> 16) & 0xFF) + 0.587f * ((color >> 8) & 0xFF) + 0.114f * (color & 0xFF)) > 128;
        int onFill = darkBar ? 0xFF000000 : 0xFFFFFFFF;
        c.save();
        if (horizontal) c.clipRect(b.left, b.top, fillEdge, b.bottom); else c.clipRect(b.left, fillEdge, b.right, b.bottom);
        p.setColor(onFill);
        c.drawText(s, cx, cy, p);
        c.restore();
        c.save();
        if (horizontal) c.clipRect(fillEdge, b.top, b.right, b.bottom); else c.clipRect(b.left, b.top, b.right, fillEdge);
        p.setColor(color);
        c.drawText(s, cx, cy, p);
        c.restore();
    }

    @Override public void setAlpha(int a) { p.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
