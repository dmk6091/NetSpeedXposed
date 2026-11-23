package com.netspeed.xposed;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.Calendar;
import java.util.Random;

/**
 * Night lock screen: a boy lying on a hillside looking up, the town's lights in the valley below, and the real sky
 * for your place and time above (stars from the catalogue in SkyObjects, Moon with its phase, planets, a faint Milky Way).
 * Drawn on a 1080 x 2280 canvas and scaled to the screen.
 */
final class SkyScene {
    private SkyScene() {}

    private static final float VW = 1080f, VH = 2280f;
    private static final float HORIZON = 1800f;      // y of the horizon
    private static final float SPAN = 160f;          // degrees of sky across the screen (facing south, east on the left)
    private static final float TOP_ALT = 88f;        // altitude at the top of the screen
    private static final float AZ0 = 180f;

    private static float ky() { return (HORIZON - 60f) / TOP_ALT; }

    /** Draws and sets the night picture on the lock screen. Returns a short message. */
    static String applyLock(Context c, double lat, double lon) {
        try {
            int w = 1080, h = 2280;
            try {
                android.graphics.Rect r = c.getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
                w = r.width(); h = r.height();
            } catch (Throwable ignored) { }
            SharedPreferences sp = WallpaperMaker.sp(c);
            Object[] res = draw(w, h, lat, lon, System.currentTimeMillis(), AZ0);
            Bitmap b = (Bitmap) res[0];
            sp.edit().putLong("skyAt", System.currentTimeMillis()).apply();
            WallpaperManager.getInstance(c).setBitmap(b, null, true, WallpaperManager.FLAG_LOCK);
            b.recycle();
            return "live sky set on the lock screen";
        } catch (Throwable t) { return "sky failed: " + t; }
    }

    /** {Bitmap, centre azimuth used}. */
    static Object[] draw(int W, int H, double lat, double lon, long ms, float prevAz) {
        Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        cv.scale(W / VW, H / VH);
        Astro.Pos sun = Astro.sun(ms, lat, lon);
        Astro.Moon moon = Astro.moon(ms, lat, lon);
        double lst = SkyObjects.lst(ms, lon);
        float dark = (float) Math.max(0, Math.min(1, (-sun.alt - 2) / 14.0));   // 0 at dusk, 1 once fully dark

        // ---- what is in the sky, to choose which way to look
        int ns = SkyObjects.STARS.length;
        double[][] st = new double[ns][];
        for (int i = 0; i < ns; i++) {
            Object[] s = SkyObjects.STARS[i];
            st[i] = SkyObjects.altAz((Double) s[1] * 15.0, (Double) s[2], lst, lat);
        }
        double[][] pl = new double[4][];
        for (int i = 0; i < 4; i++) {
            double[] rd = SkyObjects.planetRaDec(i, ms);
            pl[i] = SkyObjects.altAz(rd[0], rd[1], lst, lat);
        }
        final float az0 = AZ0;

        // ---- sky colour from the Sun's height
        float[] sc = skyColours(sun.alt);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, HORIZON, new int[]{(int) sc[0], (int) sc[1], (int) sc[2]}, new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, VW, VH, p);
        p.setShader(null);
        // glow of the twilight / Sun along the horizon, centred under the Sun
        if (sun.alt > -14 && sun.alt < 25) {
            float[] sxy = proj(Math.max(sun.alt, -3), sun.az, az0);
            float gs = (float) Math.max(0, 1 - Math.abs(sun.alt - 2) / 20.0);
            Paint hg = new Paint(Paint.ANTI_ALIAS_FLAG);
            hg.setShader(new RadialGradient(sxy[0], HORIZON, 900, Color.argb((int) (150 * gs), 255, 150, 70), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            cv.drawRect(0, HORIZON - 900, VW, HORIZON, hg);
        }

        // ---- Milky Way: a very faint band (this is a town sky, so it is only a hint)
        if (dark > 0.3f) {
            Paint mw = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (int l = 0; l < 360; l += 3) {
                double wgt = 0.55 + 0.45 * Math.cos(Math.toRadians(l));
                for (int bo = -1; bo <= 1; bo++) {
                    double[] eq = galToEq(l, bo * 5.0);
                    double[] aa = SkyObjects.altAz(eq[0], eq[1], lst, lat);
                    if (aa[0] < 4) continue;
                    float[] xy = proj(aa[0], aa[1], az0);
                    if (xy[0] < -80 || xy[0] > VW + 80) continue;
                    int a = (int) (11 * wgt * dark * (bo == 0 ? 1 : 0.6) * Math.min(1, aa[0] / 25));
                    if (a < 1) continue;
                    mw.setShader(new RadialGradient(xy[0], xy[1], 95, Color.argb(a, 170, 180, 215), Color.TRANSPARENT, Shader.TileMode.CLAMP));
                    cv.drawCircle(xy[0], xy[1], 95, mw);
                }
            }
        }

        // ---- stars
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint lb = new Paint(Paint.ANTI_ALIAS_FLAG);
        lb.setTextSize(26); lb.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        for (int i = 0; i < ns; i++) {
            double[] aa = st[i];
            if (aa[0] < 0.5) continue;
            double mag = (Double) SkyObjects.STARS[i][3];
            float[] xy = proj(aa[0], aa[1], az0);
            if (xy[0] < 6 || xy[0] > VW - 6 || xy[1] < 6) continue;
            float vis = dark * (float) Math.min(1, aa[0] / 6.0);        // a little fainter right at the horizon
            if (vis <= 0.02f) continue;
            int col = SkyObjects.color((Character) SkyObjects.STARS[i][4]);
            float r = (float) Math.max(2.0, (4.0 - mag) * 1.5 + 1.0);
            sp.setShader(new RadialGradient(xy[0], xy[1], r * 4.2f, withAlpha(col, (int) (110 * vis)), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            cv.drawCircle(xy[0], xy[1], r * 4.2f, sp);
            sp.setShader(null);
            sp.setColor(withAlpha(col, (int) (255 * vis)));
            cv.drawCircle(xy[0], xy[1], r, sp);
            if (mag < 1.7 && vis > 0.4f) {
                lb.setColor(withAlpha(0xFFBCD0FF, (int) (190 * vis)));
                cv.drawText((String) SkyObjects.STARS[i][0], xy[0] + r + 12, xy[1] + 8, lb);
            }
        }

        // ---- planets
        for (int i = 0; i < 4; i++) {
            if (pl[i][0] < 0.5) continue;
            float[] xy = proj(pl[i][0], pl[i][1], az0);
            if (xy[0] < 20 || xy[0] > VW - 20 || xy[1] < 20) continue;
            float vis = Math.max(dark, 0f) * (float) Math.min(1, pl[i][0] / 5.0);
            if (vis <= 0.05f) continue;
            float r = Math.max(3.5f, (float) (2.2 - SkyObjects.PLANET_MAG[i]) * 2.4f + 2f);
            sp.setShader(new RadialGradient(xy[0], xy[1], r * 4f, withAlpha(SkyObjects.PLANET_COLOR[i], (int) (120 * vis)), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            cv.drawCircle(xy[0], xy[1], r * 4f, sp);
            sp.setShader(null);
            sp.setColor(withAlpha(SkyObjects.PLANET_COLOR[i], (int) (255 * vis)));
            cv.drawCircle(xy[0], xy[1], r, sp);
            lb.setColor(withAlpha(0xFFFFFFFF, (int) (220 * vis)));
            cv.drawText(SkyObjects.PLANETS[i], xy[0] + r + 12, xy[1] + 8, lb);
        }

        // ---- Sun
        if (sun.alt > -2) {
            float[] xy = proj(Math.max(sun.alt, -1.5), sun.az, az0);
            drawSun(cv, xy[0], xy[1], (float) sun.alt);
        }
        // ---- Moon (drawn about 3x its true size so the phase can be seen), with its glow
        if (moon.alt > -1.5) {
            float[] xy = proj(Math.max(moon.alt, -1.0), moon.az, az0);
            float dim = sun.alt > -6 ? (float) Math.max(0.25, 0.9 + sun.alt / 30.0) * 0.0f + (sun.alt > 3 ? 0.55f : 0.8f) : 1f;
            if (xy[0] > 30 && xy[0] < VW - 30 && xy[1] > 30) drawMoon(cv, xy[0], xy[1], 34f, moon, dark, dim, (float) moon.alt);
        }

        drawLand(cv, sun.alt);

        Bitmap out = bm;
        return new Object[]{out, az0};
    }

    // ------------------------------------------------------------------ helpers

    private static double angDiff(double a, double b) { double d = (a - b) % 360; if (d > 180) d -= 360; if (d < -180) d += 360; return d; }

    /** Altitude / azimuth to canvas x,y: facing az0, west to the right. */
    private static float[] proj(double alt, double az, float az0) {
        float x = (float) (VW / 2 + angDiff(az, az0) * (VW / SPAN));
        float y = (float) (HORIZON - alt * ky());
        return new float[]{x, y};
    }

    private static double[] galToEq(double l, double b) {
        double aG = Math.toRadians(192.85948), dG = Math.toRadians(27.12825), lN = Math.toRadians(122.93192);
        double lr = Math.toRadians(l), br = Math.toRadians(b);
        double sd = Math.sin(dG) * Math.sin(br) + Math.cos(dG) * Math.cos(br) * Math.cos(lN - lr);
        double dec = Math.asin(sd);
        double y = Math.cos(br) * Math.sin(lN - lr);
        double x = Math.sin(br) * Math.cos(dG) - Math.cos(br) * Math.sin(dG) * Math.cos(lN - lr);
        double ra = Math.toDegrees(aG + Math.atan2(y, x));
        ra %= 360; if (ra < 0) ra += 360;
        return new double[]{ra, Math.toDegrees(dec)};
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb((int) (Color.red(a) * (1 - t) + Color.red(b) * t), (int) (Color.green(a) * (1 - t) + Color.green(b) * t), (int) (Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    private static int withAlpha(int c, int a) { return Color.argb(Math.max(0, Math.min(255, a)), Color.red(c), Color.green(c), Color.blue(c)); }

    private static void drawMoon(Canvas cv, float x, float y, float r, Astro.Moon m, float dark, float dim, float alt) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float lum = (float) Math.pow(m.illum, 1.4) * dark * dim;
        float warm = alt < 12 ? (12 - Math.max(alt, 0)) / 12f : 0f;          // low Moon looks yellower
        int gr = Color.rgb(225 + (int) (30 * warm), 228 - (int) (30 * warm), 245 - (int) (75 * warm));
        p.setShader(new RadialGradient(x, y, r * 9f, withAlpha(gr, (int) (46 * lum)), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        cv.drawCircle(x, y, r * 9f, p);
        p.setShader(new RadialGradient(x, y, r * 4f, withAlpha(gr, (int) (110 * lum)), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        cv.drawCircle(x, y, r * 4f, p);
        p.setShader(new RadialGradient(x, y, r * 1.7f, withAlpha(gr, (int) (190 * lum)), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        cv.drawCircle(x, y, r * 1.7f, p);
        p.setShader(null);
        cv.save();
        cv.translate(x, y);
        double phi = Math.toRadians(m.limbFromZenith);
        cv.rotate((float) Math.toDegrees(Math.atan2(-Math.cos(phi), -Math.sin(phi))));
        p.setColor(Color.argb(60, 190, 200, 225));                 // earthshine on the dark part
        cv.drawCircle(0, 0, r, p);
        float k = (float) (1 - 2 * m.illum);
        Path lit = new Path();
        lit.moveTo(0, -r);
        lit.arcTo(new RectF(-r, -r, r, r), -90, 180);
        float rx = Math.max(0.5f, Math.abs(k) * r);
        if (k > 0) lit.arcTo(new RectF(-rx, -r, rx, r), 90, -180); else lit.arcTo(new RectF(-rx, -r, rx, r), 90, 180);
        lit.close();
        p.setColor(Color.argb((int) (255 * Math.max(0.5f, dim)), 244 , 240 - (int) (22 * (alt < 12 ? (12 - Math.max(alt, 0)) / 12f : 0f)), 222 - (int) (60 * (alt < 12 ? (12 - Math.max(alt, 0)) / 12f : 0f))));
        cv.drawPath(lit, p);
        cv.restore();
    }

    /** Sky colours {top, middle, horizon} for the Sun's altitude: night, twilight, sunrise, morning, day. */
    private static float[] skyColours(double alt) {
        double[] k = {-18, -12, -6, 0, 6, 20, 50};
        int[][] c = {{0xFF03050F, 0xFF0A1028, 0xFF1A2146}, {0xFF0A1230, 0xFF1B2450, 0xFF3A3A6A}, {0xFF1B2B5A, 0xFF4A4F86, 0xFFD0805A},
                {0xFF3B5A94, 0xFFB88AA0, 0xFFFFB070}, {0xFF4C80C8, 0xFF8FB4E0, 0xFFFFD2A0}, {0xFF3A78D0, 0xFF7FB0E8, 0xFFC8E0F8}, {0xFF2C6AC8, 0xFF6FA6E6, 0xFFBCDCF8}};
        float[] out = new float[3];
        if (alt <= k[0]) { for (int i = 0; i < 3; i++) out[i] = c[0][i]; return out; }
        if (alt >= k[k.length - 1]) { for (int i = 0; i < 3; i++) out[i] = c[k.length - 1][i]; return out; }
        for (int j = 0; j < k.length - 1; j++) {
            if (alt <= k[j + 1]) {
                float t = (float) ((alt - k[j]) / (k[j + 1] - k[j]));
                for (int i = 0; i < 3; i++) out[i] = blend(c[j][i], c[j + 1][i], t);
                return out;
            }
        }
        return out;
    }

    private static void drawSun(Canvas cv, float x, float y, float alt) {
        float low = alt < 15 ? (15 - Math.max(alt, 0)) / 15f : 0f;              // redder and bigger near the horizon
        int core = Color.rgb(255, 250 - (int) (45 * low), 225 - (int) (110 * low));
        int glow = Color.rgb(255, 215 - (int) (65 * low), 140 - (int) (80 * low));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float big = 520 + 260 * low;
        p.setShader(new RadialGradient(x, y, big, withAlpha(glow, 80), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        cv.drawCircle(x, y, big, p);
        p.setShader(new RadialGradient(x, y, 190, withAlpha(glow, 150), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        cv.drawCircle(x, y, 190, p);
        p.setShader(null);
        p.setColor(core);
        cv.drawCircle(x, y, 40 + 8 * low, p);
    }

    /** One mountain with a fainter ridge behind it. */
    private static void drawLand(Canvas cv, double sunAlt) {
        float day = (float) Math.max(0, Math.min(1, (sunAlt + 8) / 14.0));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // far ridge, hazy
        Path far = new Path();
        far.moveTo(0, VH);
        for (int x = 0; x <= VW; x += 20) far.lineTo(x, HORIZON - 38 - (float) (22 * Math.sin(x / 150.0) + 12 * Math.sin(x / 47.0)));
        far.lineTo(VW, VH); far.close();
        p.setColor(blend(0xFF0B1022, 0xFF6F7C9E, day));
        cv.drawPath(far, p);
        // the mountain: a broad peak to the right of centre with a rugged edge
        Path m = new Path();
        m.moveTo(-20, VH);
        m.lineTo(-20, HORIZON + 10);
        float[][] pts = {{0, 1790}, {120, 1760}, {230, 1730}, {330, 1700}, {420, 1650}, {500, 1600}, {580, 1545}, {640, 1500}, {690, 1470}, {720, 1480}, {760, 1500},
                {800, 1540}, {850, 1575}, {910, 1620}, {960, 1650}, {1020, 1700}, {1080, 1730}, {1100, 1760}};
        for (float[] q : pts) m.lineTo(q[0], q[1] + (float) (8 * Math.sin(q[0] / 13.0)));
        m.lineTo(VW + 20, HORIZON + 10); m.lineTo(VW + 20, VH); m.close();
        p.setColor(blend(0xFF05070C, 0xFF2B3550, day));
        cv.drawPath(m, p);
        // ground
        p.setColor(blend(0xFF030407, 0xFF1A2236, day));
        cv.drawRect(0, HORIZON + 8, VW, VH, p);
    }
}
