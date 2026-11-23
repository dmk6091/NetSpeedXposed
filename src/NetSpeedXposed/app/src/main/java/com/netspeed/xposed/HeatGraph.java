package com.netspeed.xposed;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Two charts: power and temperatures over time, and average watts by cause (screen, CPU, radio, GPS ...). */
final class HeatGraph extends View {
    private HeatLog.Data d;
    private final float dp;
    private static final int[] COL = {0xFFE0912A, 0xFF3C78D8, 0xFFC0392B, 0xFF2E9E5B, 0xFF8E5BC0, 0xFF9AA3AE};

    HeatGraph(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
    }

    void setData(HeatLog.Data data) { d = data; requestLayout(); invalidate(); }

    @Override
    protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), (int) (520 * dp)); }

    @Override
    protected void onDraw(Canvas c) {
        if (d == null) return;
        int W = getWidth();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float L = 38 * dp, R = 34 * dp;
        float x0 = L, x1 = W - R;
        long t0 = d.from, t1 = d.to;
        SimpleDateFormat tf = new SimpleDateFormat(t1 - t0 > 30 * 3600_000L ? "d MMM" : "HH:mm", Locale.US);
        // ---- chart 1: power and temperature
        float y0 = 28 * dp, y1 = 200 * dp;
        p.setTextSize(12 * dp); p.setColor(0xFF555555);
        c.drawText("Power (W) and temperature (°C)", x0, 16 * dp, p);
        double maxW = 4;
        for (double[] s : d.samples) if (!Double.isNaN(s[1])) maxW = Math.max(maxW, s[1]);
        maxW = Math.ceil(maxW / 2) * 2;
        p.setStrokeWidth(1); p.setTextSize(10 * dp);
        for (int i = 0; i <= 4; i++) {
            float y = y1 - (y1 - y0) * i / 4f;
            p.setColor(0xFFDDD6C8); c.drawLine(x0, y, x1, y, p);
            p.setColor(0xFFE0912A); c.drawText(String.format(Locale.US, "%.0f", maxW * i / 4), 4 * dp, y + 4 * dp, p);
            p.setColor(0xFFC0392B); c.drawText(String.format(Locale.US, "%.0f", 20 + 60.0 * i / 4), x1 + 4 * dp, y + 4 * dp, p);
        }
        line(c, p, x0, x1, y0, y1, t0, t1, 1, 0, maxW, 0xFFE0912A, 2.5f);
        line(c, p, x0, x1, y0, y1, t0, t1, 3, 20, 80, 0xFFC0392B, 1.6f);
        line(c, p, x0, x1, y0, y1, t0, t1, 2, 20, 80, 0xFF8E5BC0, 1.6f);
        if (d.samples.isEmpty()) { p.setColor(0xFF777777); p.setTextSize(12 * dp); c.drawText("No power readings yet. They are saved by the status bar every minute (restart System UI after updating).", x0, (y0 + y1) / 2, p); }
        axis(c, p, x0, x1, y1 + 14 * dp, t0, t1, tf);
        p.setTextSize(10 * dp);
        p.setColor(0xFFE0912A); c.drawText("— power", x0, y1 + 30 * dp, p);
        p.setColor(0xFFC0392B); c.drawText("— CPU °C", x0 + 70 * dp, y1 + 30 * dp, p);
        p.setColor(0xFF8E5BC0); c.drawText("— battery °C", x0 + 140 * dp, y1 + 30 * dp, p);
        // ---- chart 2: average watts by cause
        float by0 = 280 * dp, by1 = 440 * dp;
        p.setTextSize(12 * dp); p.setColor(0xFF555555);
        c.drawText("Average power by cause (W)", x0, 262 * dp, p);
        double maxB = 2;
        for (double[] b : d.bars) { double s = 0; for (int k = 0; k < 6; k++) s += b[2 + k]; maxB = Math.max(maxB, s); }
        maxB = Math.ceil(maxB);
        p.setTextSize(10 * dp);
        for (int i = 0; i <= 4; i++) {
            float y = by1 - (by1 - by0) * i / 4f;
            p.setColor(0xFFDDD6C8); c.drawLine(x0, y, x1, y, p);
            p.setColor(0xFF666666); c.drawText(String.format(Locale.US, "%.1f", maxB * i / 4), 4 * dp, y + 4 * dp, p);
        }
        for (double[] b : d.bars) {
            float xa = x0 + (float) ((b[0] - t0) / (double) (t1 - t0)) * (x1 - x0), xb = x0 + (float) ((b[1] - t0) / (double) (t1 - t0)) * (x1 - x0);
            xa = Math.max(x0, xa);
            if (xb <= xa) continue;
            float yb = by1;
            for (int k = 0; k < 6; k++) {
                float h = (float) (b[2 + k] / maxB) * (by1 - by0);
                p.setColor(COL[k]); c.drawRect(xa + 0.5f, yb - h, xb - 0.5f, yb, p);
                yb -= h;
            }
        }
        if (d.bars.isEmpty()) { p.setColor(0xFF777777); p.setTextSize(12 * dp); c.drawText(d.snaps < 2 ? "Collecting. A new reading is taken every 15 minutes." : "No readings in this time range.", x0, (by0 + by1) / 2, p); }
        axis(c, p, x0, x1, by1 + 14 * dp, t0, t1, tf);
        float lx = x0, ly = by1 + 34 * dp;
        p.setTextSize(10 * dp);
        for (int k = 0; k < 6; k++) {
            String s = HeatLog.CAT[k];
            float w = p.measureText(s) + 20 * dp;
            if (lx + w > x1 + R) { lx = x0; ly += 14 * dp; }
            p.setColor(COL[k]); c.drawRect(lx, ly - 8 * dp, lx + 8 * dp, ly, p);
            p.setColor(0xFF444444); c.drawText(s, lx + 11 * dp, ly, p);
            lx += w;
        }
    }

    private void line(Canvas c, Paint p, float x0, float x1, float y0, float y1, long t0, long t1, int idx, double lo, double hi, int col, float w) {
        Path path = new Path();
        boolean open = false;
        for (double[] s : d.samples) {
            double v = s[idx];
            if (Double.isNaN(v)) { open = false; continue; }
            float x = x0 + (float) ((s[0] - t0) / (double) (t1 - t0)) * (x1 - x0), y = y1 - (float) ((v - lo) / (hi - lo)) * (y1 - y0);
            y = Math.max(y0, Math.min(y1, y));
            if (!open) { path.moveTo(x, y); open = true; } else path.lineTo(x, y);
        }
        p.setStyle(Paint.Style.STROKE); p.setColor(col); p.setStrokeWidth(w * dp);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void axis(Canvas c, Paint p, float x0, float x1, float y, long t0, long t1, SimpleDateFormat tf) {
        p.setColor(0xFF666666); p.setTextSize(10 * dp);
        for (int i = 0; i <= 4; i++) {
            String s = tf.format(new Date(t0 + (t1 - t0) * i / 4));
            float x = x0 + (x1 - x0) * i / 4f - p.measureText(s) / 2;
            c.drawText(s, Math.max(0, x), y, p);
        }
    }
}
