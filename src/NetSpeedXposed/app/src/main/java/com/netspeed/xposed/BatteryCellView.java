package com.netspeed.xposed;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Battery drawn as a Li-ion cell (18650 style): rounded cylinder with a + cap, filled like a
 * liquid with a wavy edge, % inside. Beside it, two small lines: voltage + charge left / total,
 * and time to empty (↓9h40) or, while charging, ϟ time to full. Colour by level.
 */
final class BatteryCellView extends View {
    int level = -1;          // 0..100
    boolean charging;
    String line1 = "", line2 = "";
    int color = 0xFFFFFFFF;
    float textPx = 22f;
    boolean showText = true;
    Typeface tf;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();

    BatteryCellView(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        text.setTypeface(Typeface.DEFAULT_BOLD);
    }

    private boolean dark() {
        return (0.299f * ((color >> 16) & 0xFF) + 0.587f * ((color >> 8) & 0xFF) + 0.114f * (color & 0xFF)) > 128;
    }

    int levelColor() {
        boolean dk = dark();
        if (charging) return dk ? 0xFF34C759 : 0xFF1E8E3E;
        if (level < 20) return dk ? 0xFFFF453A : 0xFFC4281C;
        if (level < 40) return dk ? 0xFFFF9F0A : 0xFFC46A00;
        if (level < 60) return dk ? 0xFFFFCC00 : 0xFFB38600;
        return dk ? 0xFF34C759 : 0xFF1E8E3E;
    }

    private float cellW() { return textPx * 2.55f; }
    private float cellH() { return textPx * 1.2f; }
    private float capW() { return textPx * 0.18f; }
    private float smallPx() { return textPx * 0.62f; }

    /** Width the two detail lines take (whether or not they're shown now). */
    float detailsWidth() {
        text.setTextSize(smallPx());
        return Math.max(text.measureText(line1), text.measureText(line2)) + textPx * 0.25f;
    }

    private float sideWidth() {
        if (!showText) return 0;
        text.setTextSize(smallPx());
        return Math.max(text.measureText(line1), text.measureText(line2)) + textPx * 0.25f;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = (int) Math.ceil(cellW() + capW() + sideWidth()) + 2;
        int h = (int) Math.ceil(Math.max(cellH(), smallPx() * 2.2f)) + 2;
        setMeasuredDimension(w, resolveSize(h, hSpec));
    }

    @Override
    protected void onDraw(Canvas c) {
        float cw = cellW(), ch = cellH(), cap = capW();
        float top = (getHeight() - ch) / 2f, left = 1;
        float sw = Math.max(1.5f, textPx * 0.085f);
        float rad = ch * 0.38f;
        int lc = levelColor();

        // body outline
        stroke.setColor(color);
        stroke.setStrokeWidth(sw);
        r.set(left + sw / 2, top + sw / 2, left + cw - sw / 2, top + ch - sw / 2);
        c.drawRoundRect(r, rad, rad, stroke);
        // + cap
        fill.setColor(color);
        r.set(left + cw + sw * 0.2f, top + ch * 0.28f, left + cw + cap, top + ch * 0.72f);
        c.drawRoundRect(r, cap * 0.4f, cap * 0.4f, fill);

        // liquid fill with a wavy edge (gently moving while charging)
        if (level >= 0) {
            float in = sw * 1.6f;
            float x0 = left + in, x1 = left + cw - in, y0 = top + in, y1 = top + ch - in;
            float fx = x0 + (x1 - x0) * Math.min(100, level) / 100f;
            c.save();
            r.set(x0, y0, x1, y1);
            path.reset();
            path.addRoundRect(r, rad * 0.7f, rad * 0.7f, Path.Direction.CW);
            c.clipPath(path);
            path.reset();
            path.moveTo(x0, y0);
            float amp = ch * 0.07f;
            double phase = charging ? (android.os.SystemClock.uptimeMillis() % 1200) / 1200.0 * 2 * Math.PI : 0;
            int steps = 12;
            for (int i = 0; i <= steps; i++) {
                float y = y0 + (y1 - y0) * i / steps;
                float x = fx + amp * (float) Math.sin(phase + i * Math.PI / 3);
                if (i == 0) path.lineTo(x, y); else path.lineTo(x, y);
            }
            path.lineTo(x0, y1);
            path.close();
            fill.setColor(lc);
            c.drawPath(path, fill);
            if ((charging || SysState.testing()) && Cfg.b(Cfg.ANIM_CHARGE, Cfg.D_ANIM_CHARGE)) {
                // a light running along the fill, left to right
                float fw = fx - x0, band = Math.max(ch * 0.5f, fw * 0.3f);
                float tx = x0 + (fw + band) * ((android.os.SystemClock.uptimeMillis() % 1400) / 1400f) - band;
                c.save();
                c.clipRect(x0, y0, Math.max(x0, fx), y1);
                fill.setColor(0x66FFFFFF);
                c.drawRect(tx, y0, tx + band, y1, fill);
                c.restore();
            }
            c.restore();

            // % inside, with a soft shadow so it reads over any fill colour
            String pct = level + "%";
            text.setTextSize(ch * 0.62f);
            if (tf != null) text.setTypeface(Typeface.create(tf, Typeface.BOLD));
            text.setColor(0xFFFFFFFF);
            text.setShadowLayer(sw * 1.2f, 0, 0, 0xCC000000);
            float tw = text.measureText(pct);
            c.drawText(pct, left + (cw - tw) / 2f, top + ch / 2f + ch * 0.22f, text);
            text.setShadowLayer(0, 0, 0, 0);
        }

        // side text
        if (showText) {
            float sx = left + cw + cap + textPx * 0.25f;
            text.setTextSize(smallPx());
            text.setColor(color);
            float mid = getHeight() / 2f;
            c.drawText(line1, sx, mid - smallPx() * 0.15f, text);
            text.setColor(charging ? lc : (color & 0x00FFFFFF) | 0xAA000000);
            c.drawText(line2, sx, mid + smallPx() * 0.95f, text);
        }
        if (((charging && Cfg.b(Cfg.ANIM_CHARGE, Cfg.D_ANIM_CHARGE)) || SysState.testing()) && isShown()) postInvalidateDelayed(80);
    }
}
