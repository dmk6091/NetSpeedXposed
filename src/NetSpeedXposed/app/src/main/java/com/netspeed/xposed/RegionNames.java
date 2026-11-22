package com.netspeed.xposed;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;

/** Big towns and cities written on the wide (Region / Far) wallpaper maps: a dot and the name, only where there is room. */
final class RegionNames {
    private RegionNames() { }

    private static final Object[][] CITIES = {
            {"Mumbai", 19.076, 72.878}, {"Thane", 19.218, 72.978}, {"Panvel", 18.989, 73.112}, {"Pune", 18.520, 73.857},
            {"Nashik", 19.998, 73.790}, {"Satara", 17.680, 74.018}, {"Kolhapur", 16.705, 74.243}, {"Sangli", 16.855, 74.564},
            {"Solapur", 17.660, 75.906}, {"Ahmednagar", 19.095, 74.748}, {"Sambhajinagar", 19.876, 75.343}, {"Alibag", 18.641, 72.872},
            {"Ratnagiri", 16.990, 73.312}, {"Lonavala", 18.750, 73.409}, {"Mahabaleshwar", 17.923, 73.658}, {"Karad", 17.289, 74.185},
            {"Chiplun", 17.530, 73.516}, {"Junnar", 19.209, 73.877}, {"Baramati", 18.151, 74.577}, {"Dhule", 20.902, 74.775},
            {"Jalgaon", 21.007, 75.563}, {"Surat", 21.170, 72.831}, {"Belgaum", 15.849, 74.498}, {"Panaji", 15.499, 73.828},
            {"Vasai", 19.391, 72.839}, {"Shirdi", 19.766, 74.477}, {"Latur", 18.408, 76.560}, {"Nanded", 19.138, 77.321},
            {"Mangaon", 18.234, 73.275}, {"Khopoli", 18.785, 73.346}, {"Bhor", 18.150, 73.844}, {"Wai", 17.951, 73.890},
            {"Daman", 20.414, 72.832}, {"Nagpur", 21.146, 79.088}, {"Hyderabad", 17.385, 78.487}, {"Bengaluru", 12.972, 77.594},
            {"Goa (Margao)", 15.270, 73.958}, {"Ahmedabad", 23.023, 72.572}, {"Indore", 22.720, 75.858}, {"Bhopal", 23.260, 77.413},
    };

    static String dir8(double northKm, double eastKm) {
        int di = (int) Math.round(Math.toDegrees(Math.atan2(eastKm, northKm)) / 45.0);
        di = ((di % 8) + 8) % 8;
        return new String[]{"N", "NE", "E", "SE", "S", "SW", "W", "NW"}[di];
    }

    static void draw(Canvas cv, int W, int H, double lat, double lon, int z, double ox, double oy, float S, boolean day) {
        final float u = W / 1080f;
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTextSize(20 * u);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        Paint halo = new Paint(t);
        halo.setStyle(Paint.Style.STROKE); halo.setStrokeWidth(3.4f * u); halo.setStrokeJoin(Paint.Join.ROUND);
        halo.setColor(day ? 0xD2FFFFFF : 0xDC000000);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        List<RectF> used = new ArrayList<>();
        used.add(new RectF(0, 0, W, H * 0.22f));
        used.add(new RectF(0, H * 0.68f, W, H));
        used.add(new RectF(W * 0.02f, H * 0.165f, W * 0.66f, H * 0.265f));
        used.add(new RectF(W / 2f - 120 * u, H * 0.5f - 120 * u, W / 2f + 120 * u, H * 0.5f + 120 * u));
        int shown = 0;
        // bigger places first: the list is roughly in order of size for the Maharashtra region
        for (Object[] c : CITIES) {
            double la = (Double) c[1], lo = (Double) c[2];
            if (la == 0) continue;
            float x = (float) ((WallpaperMaker.worldX(lo, z) - ox) * S), y = (float) ((WallpaperMaker.worldY(la, z) - oy) * S);
            float tw = t.measureText((String) c[0]);
            RectF r = new RectF(x - 8 * u, y - 14 * u, x + 14 * u + tw, y + 8 * u);
            if (r.left < 8 * u || r.right > W - 8 * u || r.top < 0 || r.bottom > H) continue;
            boolean ok = true;
            for (RectF o : used) if (RectF.intersects(o, r)) { ok = false; break; }
            if (!ok) continue;
            used.add(r);
            dot.setStyle(Paint.Style.FILL); dot.setColor(day ? 0xFF222222 : 0xFFFFFFFF);
            cv.drawCircle(x, y, 5.5f * u, dot);
            dot.setStyle(Paint.Style.STROKE); dot.setStrokeWidth(1.6f * u); dot.setColor(day ? 0xFFFFFFFF : 0xFF000000);
            cv.drawCircle(x, y, 5.5f * u, dot);
            t.setColor(day ? 0xFF222222 : 0xFFFFFFFF);
            cv.drawText((String) c[0], x + 12 * u, y + 6 * u, halo);
            cv.drawText((String) c[0], x + 12 * u, y + 6 * u, t);
            if (++shown >= 12) break;
        }
    }
}
