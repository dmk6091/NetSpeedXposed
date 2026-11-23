package com.netspeed.xposed;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Today's UV by hour as coloured bars (6 am to 7 pm), with "now" marked. */
final class UvBars extends View {
    private float[] uv = new float[24];
    private int nowH = -1;
    private final float dp;

    UvBars(Context c) { super(c); dp = c.getResources().getDisplayMetrics().density; }

    void set(float[] v, int hourNow) { if (v != null && v.length >= 24) uv = v; nowH = hourNow; invalidate(); }

    static int colour(float v) { return v < 3 ? 0xFF78C878 : v < 6 ? 0xFFF0D246 : v < 8 ? 0xFFFF9632 : v < 11 ? 0xFFEB3C32 : 0xFFAA3CC8; }

    @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), (int) (170 * dp)); }

    @Override protected void onDraw(Canvas c) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int W = getWidth(), n = 14;
        float bw = (W - 16 * dp) / n, base = 140 * dp, maxV = 12f;
        p.setTextSize(11 * dp);
        for (int i = 0; i < n; i++) {
            int h = 6 + i;
            float v = uv[h], bh = Math.min(v, maxV) / maxV * 110 * dp;
            float x = 8 * dp + i * bw;
            p.setColor(colour(v)); p.setAlpha(h == nowH ? 255 : 190);
            c.drawRect(x + 2 * dp, base - bh, x + bw - 2 * dp, base, p);
            p.setColor(h == nowH ? 0xFFFFFFFF : 0xFF9AA3AE); p.setAlpha(255);
            String s = String.valueOf(h > 12 ? h - 12 : h);
            c.drawText(s, x + bw / 2 - p.measureText(s) / 2, base + 16 * dp, p);
            if (v >= 0.5f) { p.setColor(0xFFDDDDDD); String t = String.valueOf(Math.round(v)); c.drawText(t, x + bw / 2 - p.measureText(t) / 2, base - bh - 4 * dp, p); }
        }
        p.setColor(0xFF9AA3AE); c.drawText("hour of day", 8 * dp, base + 32 * dp, p);
    }
}
