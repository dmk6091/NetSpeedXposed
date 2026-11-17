package com.netspeed.xposed;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/**
 * Small animated thermometer: outlined tube in the text colour, bulb + mercury in the heat
 * colour. The mercury glides to each new reading and gently "breathes"; when warm or hot the
 * bulb pulses (faster when hot). Animated by its host invalidating it (~12 fps).
 */
final class ThermoDrawable extends Drawable {
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint heat = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int w, h;
    private final float phase;      // so two thermometers don't move in lockstep
    private float target = 0.5f, shown = Float.NaN;
    private int stage;              // 0 cool, 1 normal, 2 warm, 3 hot
    private long lastDraw = 0;

    ThermoDrawable(int widthPx, int heightPx, float phase) {
        outline.setStyle(Paint.Style.STROKE);
        heat.setStrokeCap(Paint.Cap.ROUND);
        w = widthPx;
        h = heightPx;
        this.phase = phase;
        setBounds(0, 0, w, h);
    }

    /** level 0..1 (how full), stage 0..3 (cool .. hot). */
    void set(int lineColor, int heatColor, float level, int stage) {
        outline.setColor(lineColor);
        heat.setColor(heatColor);
        glow.setColor((heatColor & 0x00FFFFFF) | 0x55000000);
        target = Math.max(0.15f, Math.min(1f, level));
        this.stage = stage;
    }

    int width() { return w; }
    int height() { return h; }

    @Override
    public void draw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastDraw == 0 ? 1f : Math.min(1f, (now - lastDraw) / 400f);
        lastDraw = now;
        // glide towards the new reading
        shown = Float.isNaN(shown) ? target : shown + (target - shown) * Math.max(0.12f, dt);
        double t = now / 1000.0;
        float breathe = (float) Math.sin(t * 2 * Math.PI / 2.4 + phase) * 0.035f;
        float level = Math.max(0.12f, Math.min(1f, shown + breathe));

        float l = getBounds().left, top = getBounds().top, bw = getBounds().width(), bh = getBounds().height();
        float cx = l + bw / 2f;
        float bulbR = bw * 0.36f, bulbY = top + bh - bulbR - 0.5f;
        float tubeW = bw * 0.34f, tubeTop = top + 0.5f;
        outline.setStrokeWidth(Math.max(1f, bw * 0.12f));
        r.set(cx - tubeW / 2f, tubeTop, cx + tubeW / 2f, bulbY);
        c.drawRoundRect(r, tubeW / 2f, tubeW / 2f, outline);
        // warm / hot: pulsing glow around the bulb
        if (stage >= 2) {
            double period = stage == 3 ? 0.7 : 1.4;
            float p = (float) (0.5 + 0.5 * Math.sin(t * 2 * Math.PI / period + phase));
            c.drawCircle(cx, bulbY, bulbR * (1.15f + 0.35f * p), glow);
        }
        c.drawCircle(cx, bulbY, bulbR, heat);
        heat.setStrokeWidth(tubeW * 0.55f);
        float fillTop = bulbY - (bulbY - tubeTop - tubeW * 0.3f) * level;
        c.drawLine(cx, bulbY, cx, fillTop, heat);
    }

    @Override public int getIntrinsicWidth() { return w; }
    @Override public int getIntrinsicHeight() { return h; }
    @Override public void setAlpha(int a) { outline.setAlpha(a); heat.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { outline.setColorFilter(cf); heat.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
