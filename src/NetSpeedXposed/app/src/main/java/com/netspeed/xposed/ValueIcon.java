package com.netspeed.xposed;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/** A tile icon that is a short bold text ("240", "30s", "5G"). Follows the tint the panel gives icons, so it matches the theme. */
final class ValueIcon extends Drawable {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String text;
    private int color = 0xFFFFFFFF;
    private boolean tinted;

    ValueIcon(String text, int defaultColor) {
        this.text = text;
        color = defaultColor;
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float size = b.height() * 0.62f;
        p.setTextSize(size);
        float w = p.measureText(text);
        float max = b.width() * 1.0f;
        if (w > max) p.setTextSize(size * max / w);
        p.setColor(tinted ? color : color);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(text, b.centerX(), b.centerY() - (fm.ascent + fm.descent) / 2f, p);
    }

    @Override public void setTint(int t) { color = t; tinted = true; invalidateSelf(); }
    @Override public void setTintList(android.content.res.ColorStateList l) {
        if (l != null) { color = l.getDefaultColor(); tinted = true; invalidateSelf(); }
    }
    @Override public void setAlpha(int a) { p.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return 96; }
    @Override public int getIntrinsicHeight() { return 96; }

    /** For TileService icons (a bitmap the panel tints like any icon). */
    static android.graphics.drawable.Icon icon(String text) {
        Bitmap bm = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        ValueIcon v = new ValueIcon(text, 0xFFFFFFFF);
        v.setBounds(0, 0, 96, 96);
        v.draw(new Canvas(bm));
        return android.graphics.drawable.Icon.createWithBitmap(bm);
    }
}
