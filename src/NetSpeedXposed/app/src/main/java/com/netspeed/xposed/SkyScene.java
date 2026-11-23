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
    private static final float HORIZON = 1230f;      // y of the horizon
    private static final float SPAN = 160f;          // degrees of sky across the screen (facing south, east on the left)
    private static final float TOP_ALT = 88f;        // altitude at the top of the screen
    private static final float AZ0 = 180f;

    /** "cloud%|aqi|uv|temp" written by the status bar every weather update; empty if unknown. */
    static volatile String wxNow = "";
    /** The nearest named peak for the current picture (from OpenStreetMap, any height); null = use the built-in list. */
    static volatile Object[] peakNow = null;
    /** More weather for the chips: feels|wind|windDir|gust|rain|uv by hour|tide next|tide place|uv, written by the status bar. */
    static volatile String wxMore = "";
    static volatile PlaceInfo.Info placeNow = null;
    static volatile java.util.List<double[]> stationsNow = new java.util.ArrayList<>();
    static volatile int skinType = 5;
    private static double curSunAlt = -90, curSunAz = 180;

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
            try { String v = android.provider.Settings.Global.getString(c.getContentResolver(), "netspeed_wx_now"); wxNow = v == null ? "" : v; } catch (Throwable ignored) { wxNow = ""; }
            try { peakNow = nearbyPeak(c, lat, lon); } catch (Throwable t) { peakNow = null; }
            try { String v = android.provider.Settings.Global.getString(c.getContentResolver(), "netspeed_wx_more"); wxMore = v == null ? "" : v; } catch (Throwable ignored) { wxMore = ""; }
            try { placeNow = PlaceInfo.lookup(c, sp, lat, lon); } catch (Throwable t) { placeNow = null; }
            try { stationsNow = Sat.stationsNow(c, lat, lon, System.currentTimeMillis()); } catch (Throwable t) { stationsNow = new java.util.ArrayList<>(); }
            try { skinType = Math.max(1, Math.min(6, SettingsProvider.prefs(c).getInt("uv_skin", 5))); } catch (Throwable ignored) { }
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
        curSunAlt = sun.alt; curSunAz = sun.az;

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
        int[] wx = parseWx();
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

        // ---- constellation lines (faint), with the name where both ends are up
        if (dark > 0.2f) drawConstellations(cv, st, az0, dark);

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
            float vis = dark * (1f - 0.65f * cloudFrac(wx)) * (float) Math.min(1, aa[0] / 6.0);        // a little fainter right at the horizon
            if (vis <= 0.02f) continue;
            int col = SkyObjects.color((Character) SkyObjects.STARS[i][4]);
            float r = (float) Math.max(2.0, (4.0 - mag) * 1.5 + 1.0);
            sp.setShader(new RadialGradient(xy[0], xy[1], r * 4.2f, withAlpha(col, (int) (110 * vis)), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            cv.drawCircle(xy[0], xy[1], r * 4.2f, sp);
            sp.setShader(null);
            sp.setColor(withAlpha(col, (int) (255 * vis)));
            cv.drawCircle(xy[0], xy[1], r, sp);
            if (mag < 1.7 && vis > 0.4f && aa[0] >= 28) {
                lb.setColor(withAlpha(0xFFBCD0FF, (int) (190 * vis)));
                cv.drawText((String) SkyObjects.STARS[i][0], xy[0] + r + 12, xy[1] + 8, lb);
            }
        }

        // ---- planets
        for (int i = 0; i < 4; i++) {
            if (pl[i][0] < 0.5) continue;
            float[] xy = proj(pl[i][0], pl[i][1], az0);
            if (xy[0] < 20 || xy[0] > VW - 20 || xy[1] < 20) continue;
            float vis = planetVis(i, pl[i][0], dark, sun.alt, wx);
            if (vis <= 0.05f) continue;
            float r = Math.max(3.5f, (float) (2.2 - SkyObjects.PLANET_MAG[i]) * 2.4f + 2f);
            sp.setShader(new RadialGradient(xy[0], xy[1], r * 4f, withAlpha(SkyObjects.PLANET_COLOR[i], (int) (120 * vis)), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            cv.drawCircle(xy[0], xy[1], r * 4f, sp);
            sp.setShader(null);
            sp.setColor(withAlpha(SkyObjects.PLANET_COLOR[i], (int) (255 * vis)));
            cv.drawCircle(xy[0], xy[1], r, sp);
            if (pl[i][0] >= 28) { lb.setColor(withAlpha(0xFFFFFFFF, (int) (220 * vis))); cv.drawText(SkyObjects.PLANETS[i], xy[0] + r + 12, xy[1] + 8, lb); }
        }

        if (sun.alt > -0.8) drawSunPath(cv, lat, lon, ms, az0);
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

        drawHighlights(cv, st, pl, dark, sun.alt, wx, az0, peakLabelBox(lat, lon));
        drawClouds(cv, wx, sun.alt, ms);
        drawLand(cv, sun.alt, lat, lon, ms, sun, moon, lst, wx);

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
    private static void drawLand(Canvas cv, double sunAlt, double lat, double lon, long ms, Astro.Pos sun, Astro.Moon moon, double lst, int[] wx) {
        float day = (float) Math.max(0, Math.min(1, (sunAlt + 8) / 14.0));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // far ridge, hazy
        Path far = new Path();
        far.moveTo(0, VH);
        for (int x = 0; x <= VW; x += 20) far.lineTo(x, HORIZON - 38 - (float) (22 * Math.sin(x / 150.0) + 12 * Math.sin(x / 47.0)));
        far.lineTo(VW, VH); far.close();
        p.setColor(blend(0xFF0B1022, 0xFF6F7C9E, day));
        cv.drawPath(far, p);
        // ground
        p.setColor(blend(0xFF030407, 0xFF1A2236, day));
        cv.drawRect(0, HORIZON + 8, VW, VH, p);
        drawPeak(cv, day, lat, lon);
        drawInfo(cv, day, lat, lon, ms, sun, moon, lst, wx);
    }

    // ------------------------------------------------------------------ weather

    /** {cloud %, aqi, uv x10, temp} with -1 = unknown. */
    private static int[] parseWx() {
        int[] r = {-1, -1, -1, -99};
        try {
            String[] a = wxNow.split("\\|");
            if (a.length > 0) r[0] = Integer.parseInt(a[0].trim());
            if (a.length > 1) r[1] = Integer.parseInt(a[1].trim());
            if (a.length > 2 && !a[2].trim().equalsIgnoreCase("NaN")) r[2] = Math.round(Float.parseFloat(a[2].trim()) * 10);
            if (a.length > 3 && !a[3].trim().equals("null")) r[3] = Math.round(Float.parseFloat(a[3].trim()));
        } catch (Throwable ignored) { }
        return r;
    }

    private static float cloudFrac(int[] wx) { return wx[0] < 0 ? 0f : Math.min(1f, wx[0] / 100f); }

    /** Soft clouds only when there really are some (cloud cover over 10 %), and a haze when the air is poor. */
    private static void drawClouds(Canvas cv, int[] wx, double sunAlt, long ms) {
        float cf = cloudFrac(wx);
        float day = (float) Math.max(0, Math.min(1, (sunAlt + 6) / 12.0));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (cf > 0.1f) {
            Random r = new Random(ms / (6 * 3600_000L));                      // the pattern holds still for a few hours
            int n = Math.round(3 + cf * 9);
            int col = blend(0xFF8C97B8, 0xFFFFFFFF, day);
            for (int i = 0; i < n; i++) {
                float cx = r.nextFloat() * VW, cy = 220 + r.nextFloat() * (HORIZON - 520), sz = 90 + r.nextFloat() * 140;
                int a = (int) (60 + 90 * cf);
                if (sunAlt < -6) a = (int) (a * 0.5f);
                for (int k = 0; k < 6; k++) {
                    float x = cx + (k - 2.5f) * sz * 0.55f, y = cy + (float) Math.sin(k * 1.7 + i) * sz * 0.18f, rr = sz * (0.7f + 0.25f * (float) Math.sin(k * 2.3 + i));
                    p.setShader(new RadialGradient(x, y, rr, withAlpha(col, a), Color.TRANSPARENT, Shader.TileMode.CLAMP));
                    cv.drawCircle(x, y, rr, p);
                }
            }
            p.setShader(null);
        }
        if (wx[1] > 120) {                                                      // hazy air: a warm grey veil low in the sky
            float a = Math.min(1f, (wx[1] - 100) / 200f);
            p.setShader(new LinearGradient(0, HORIZON - 700, 0, HORIZON, Color.TRANSPARENT, Color.argb((int) (120 * a), 200, 180, 150), Shader.TileMode.CLAMP));
            cv.drawRect(0, HORIZON - 700, VW, HORIZON, p);
            p.setShader(null);
        }
    }

    // ------------------------------------------------------------------ constellations

    private static final String[][] CON = {
            {"Orion", "Betelgeuse-Alnitak", "Bellatrix-Mintaka", "Alnitak-Alnilam", "Alnilam-Mintaka", "Alnitak-Saiph", "Mintaka-Rigel", "Betelgeuse-Bellatrix"},
            {"Big Dipper", "Dubhe-Merak", "Merak-Phecda", "Phecda-Alioth", "Alioth-Mizar", "Mizar-Alkaid"},
            {"Scorpius", "Dschubba-Antares", "Antares-Sargas", "Sargas-Shaula"},
            {"Cassiopeia", "Caph-Schedar", "Schedar-Navi"},
    };

    private static int starIndex(String n) {
        for (int i = 0; i < SkyObjects.STARS.length; i++) if (SkyObjects.STARS[i][0].equals(n)) return i;
        return -1;
    }

    private static void drawConstellations(Canvas cv, double[][] st, float az0, float dark) {
        Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
        ln.setStrokeWidth(2.2f); ln.setColor(Color.argb((int) (95 * dark), 130, 160, 225));
        Paint tx = new Paint(Paint.ANTI_ALIAS_FLAG);
        tx.setTextSize(25); tx.setTypeface(Typeface.create("sans-serif", Typeface.ITALIC)); tx.setColor(Color.argb((int) (150 * dark), 150, 180, 240));
        for (String[] con : CON) {
            float sx = 0, sy = 0; int cnt = 0;
            for (int k = 1; k < con.length; k++) {
                String[] ab = con[k].split("-");
                int i = starIndex(ab[0]), j = starIndex(ab[1]);
                if (i < 0 || j < 0 || st[i][0] < 1 || st[j][0] < 1) continue;
                float[] a = proj(st[i][0], st[i][1], az0), b = proj(st[j][0], st[j][1], az0);
                if (a[0] < 0 || b[0] < 0 || a[0] > VW || b[0] > VW) continue;
                cv.drawLine(a[0], a[1], b[0], b[1], ln);
                sx += a[0] + b[0]; sy += a[1] + b[1]; cnt += 2;
            }
            if (cnt >= 4) cv.drawText(con[0], sx / cnt - tx.measureText(con[0]) / 2, sy / cnt + 70, tx);
        }
    }

    // ------------------------------------------------------------------ the nearest peak

    private static final String[][] NOTES = {
            {"Tikona", "pyramid", "Pyramid-shaped hill fort (Vitandgad)"}, {"Sinhagad", "flat", "Flat-topped hill fort"},
            {"Kalsubai", "dome", "Highest peak in Maharashtra"}, {"Raigad", "flat", "Shivaji's capital fort"},
            {"Torna", "flat", "Torna fort (Prachandagad)"}, {"Matheran", "flat", "Flat-topped hill station plateau"},
            {"Bhimashankar", "dome", "Source of the Bhima river"}, {"Brahmagiri", "dome", "Source of the Godavari"},
            {"Harishchandragad", "flat", "Fort with the Konkankada cliff"}, {"Rajgad", "flat", ""}, {"Lohgad", "flat", ""},
            {"Rajmachi", "flat", ""}, {"Purandar", "flat", ""}, {"Pratapgad", "flat", ""}, {"Salher", "dome", ""},
            {"Mahabaleshwar", "dome", ""},
    };

    /**
     * The nearest named peak of any height within 20 km, from OpenStreetMap (kept for two weeks, or until you have moved 4 km);
     * the built-in list of big peaks is the fallback and also competes if it is nearer.
     */
    static Object[] nearbyPeak(Context c, double lat, double lon) {
        SharedPreferences sp = WallpaperMaker.sp(c);
        Object[] built = nearestPeak(lat, lon);
        Object[] osm = null;
        float[] mv = new float[1];
        boolean have = sp.contains("npLat");
        if (have) android.location.Location.distanceBetween(sp.getFloat("npLat", 0), sp.getFloat("npLon", 0), lat, lon, mv);
        boolean fresh = have && mv[0] < 4000f && System.currentTimeMillis() - sp.getLong("npAt", 0) < 14L * 86400_000L;
        if (fresh) {
            String nm = sp.getString("npName", "");
            if (!nm.isEmpty()) osm = peakInfo(nm, sp.getFloat("npLa", 0), sp.getFloat("npLo", 0), sp.getInt("npEle", 0), lat, lon);
        } else {
            String js = TerrainMaker.overpass(String.format(java.util.Locale.US, "[out:json][timeout:25];node[\"natural\"=\"peak\"][\"name\"](around:20000,%.5f,%.5f);out 80;", lat, lon));
            if (js != null) {
                try {
                    org.json.JSONArray els = new org.json.JSONObject(js).getJSONArray("elements");
                    double bd = 1e9; String bn = ""; double bla = 0, blo = 0; int be = 0;
                    for (int i = 0; i < els.length(); i++) {
                        org.json.JSONObject e = els.getJSONObject(i), tg = e.optJSONObject("tags");
                        if (tg == null) continue;
                        String nm = tg.optString("name:en", ""); if (nm.isEmpty()) nm = tg.optString("name", "");
                        if (nm.isEmpty()) continue;
                        double la = e.getDouble("lat"), lo = e.getDouble("lon");
                        double d = Math.hypot((la - lat) * 110.57, (lo - lon) * 111.32 * Math.cos(Math.toRadians(lat)));
                        if (d < bd) {
                            bd = d; bn = nm; bla = la; blo = lo; be = 0;
                            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(\\d+)").matcher(tg.optString("ele", ""));
                            if (m.find()) be = Integer.parseInt(m.group(1));
                        }
                    }
                    sp.edit().putFloat("npLat", (float) lat).putFloat("npLon", (float) lon).putLong("npAt", System.currentTimeMillis())
                            .putString("npName", bn).putFloat("npLa", (float) bla).putFloat("npLo", (float) blo).putInt("npEle", be).apply();
                    if (!bn.isEmpty()) osm = peakInfo(bn, bla, blo, be, lat, lon);
                } catch (Throwable ignored) { }
            }
        }
        if (osm == null) return built;
        return (Double) osm[2] <= (Double) built[2] ? osm : built;
    }

    private static Object[] peakInfo(String name, double la, double lo, int ele, double lat, double lon) {
        double n = (la - lat) * 110.57, e = (lo - lon) * 111.32 * Math.cos(Math.toRadians(lat));
        double d = Math.hypot(n, e), b = (Math.toDegrees(Math.atan2(e, n)) + 360) % 360;
        String shape = "dome", note = "";
        for (String[] nn : NOTES) if (nn[0].equalsIgnoreCase(name)) { shape = nn[1]; note = nn[2]; }
        String lc = name.toLowerCase(java.util.Locale.ROOT);
        if (note.isEmpty() && (lc.contains("gad") || lc.contains("garh") || lc.contains("fort"))) shape = "flat";
        return new Object[]{name, ele, d, b, shape, note};
    }

    /** {name, height, distance km, bearing deg, shape, note} of the nearest peak in the built-in list. */
    static Object[] nearestPeak(double lat, double lon) {
        Object[] best = null; double bd = 1e9, bb = 0;
        for (Object[] pk : RegionNames.PEAKS) {
            double la = (Double) pk[1], lo = (Double) pk[2];
            double n = (la - lat) * 110.57, e = (lo - lon) * 111.32 * Math.cos(Math.toRadians(lat));
            double d = Math.hypot(n, e);
            if (d < bd) { bd = d; best = pk; bb = (Math.toDegrees(Math.atan2(e, n)) + 360) % 360; }
        }
        String shape = "dome", note = "";
        for (String[] nn : NOTES) if (nn[0].equals(best[0])) { shape = nn[1]; note = nn[2]; }
        return new Object[]{best[0], best[3], bd, bb, shape, note};
    }

    private static final float[][] PYRAMID = {{-330, 330}, {-250, 265}, {-170, 185}, {-100, 100}, {-40, 38}, {0, 0}, {14, 6}, {40, 45}, {95, 115}, {160, 185}, {240, 255}, {330, 330}};
    private static final float[][] FLAT = {{-380, 330}, {-300, 300}, {-230, 230}, {-170, 150}, {-120, 70}, {-90, 22}, {-70, 0}, {60, 0}, {85, 22}, {120, 60}, {180, 140}, {260, 230}, {340, 300}, {400, 330}};
    private static final float[][] DOME = {{-420, 330}, {-330, 280}, {-240, 200}, {-150, 110}, {-70, 40}, {-20, 8}, {20, 0}, {80, 18}, {160, 70}, {250, 150}, {340, 230}, {430, 330}};

    /** Where the nearest peak's text sits, so stars highlighted on the horizon keep clear of it. */
    private static RectF peakLabelBox(double lat, double lon) {
        Object[] pk = peakNow != null ? peakNow : nearestPeak(lat, lon);
        double az = (Double) pk[3];
        float x0 = (float) (VW / 2 + angDiff(az, AZ0) * (VW / SPAN));
        float scale = 0.8f + 0.3f * Math.min(1f, ((Integer) pk[1]) / 1400f);
        float half = 430 * scale;
        x0 = Math.max(half * 0.7f + 20, Math.min(VW - half * 0.7f - 20, x0));
        float top = HORIZON + 10 - 290 * scale;
        boolean left = x0 > VW / 2f;
        float tx = left ? x0 - 70 : x0 + 70;
        return new RectF(left ? tx - 440 : tx - 20, top - 70, left ? tx + 20 : tx + 440, top + 200);
    }

    private static void drawPeak(Canvas cv, float day, double lat, double lon) {
        Object[] pk = peakNow != null ? peakNow : nearestPeak(lat, lon);
        String shape = (String) pk[4];
        float[][] sh = shape.equals("pyramid") ? PYRAMID : shape.equals("flat") ? FLAT : DOME;
        double az = (Double) pk[3];
        float x0 = (float) (VW / 2 + angDiff(az, AZ0) * (VW / SPAN));
        float scale = 0.8f + 0.3f * Math.min(1f, ((Integer) pk[1]) / 1400f);
        float half = 430 * scale;
        x0 = Math.max(half * 0.7f + 20, Math.min(VW - half * 0.7f - 20, x0));
        float base = HORIZON + 10;
        float top = base - 290 * scale;
        Path m = new Path();
        m.moveTo(-40, VH); m.lineTo(-40, base);
        m.lineTo(x0 + (sh[0][0] - 400) * scale, base);
        for (float[] q : sh) m.lineTo(x0 + q[0] * scale, top + q[1] * scale + (float) (3 * Math.sin(q[0] / 9.0)));
        m.lineTo(x0 + (sh[sh.length - 1][0] + 400) * scale, base);
        m.lineTo(VW + 40, base); m.lineTo(VW + 40, VH); m.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(blend(0xFF05070C, 0xFF2B3550, day));
        cv.drawPath(m, p);
        if (curSunAlt > 0) {          // the side facing the Sun is lit
            int side = angDiff(curSunAz, az) > 0 ? 1 : -1;
            float k = (float) Math.min(1, curSunAlt / 20.0);
            cv.save(); cv.clipPath(m);
            Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
            lp.setShader(new LinearGradient(x0, 0, x0 + side * 420 * scale, 0, Color.argb((int) (95 * k), 255, 225, 170), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            cv.drawRect(side > 0 ? x0 : x0 - 460 * scale, top - 40, side > 0 ? x0 + 460 * scale : x0, base, lp);
            cv.restore();
        }
        // pencil edge and a few hatch strokes, like a sketch
        Paint ed = new Paint(Paint.ANTI_ALIAS_FLAG);
        ed.setStyle(Paint.Style.STROKE); ed.setStrokeWidth(2.6f); ed.setColor(blend(0xFFB8C6E8, 0xFFE4EAF8, day));
        Path e = new Path();
        for (int i = 0; i < sh.length; i++) { float x = x0 + sh[i][0] * scale, y = top + sh[i][1] * scale; if (i == 0) e.moveTo(x, y); else e.lineTo(x, y); }
        cv.drawPath(e, ed);
        ed.setStrokeWidth(1.4f); ed.setAlpha(110);
        for (int k = 0; k < 9; k++) {
            float hx = x0 + (-10 + k * 16) * scale, hy = top + (30 + k * 14) * scale;
            cv.drawLine(hx, hy, hx - 85 * scale, hy + 120 * scale, ed);
        }
        // flag on the summit and the label beside it
        float sx = x0, sy = top;
        if (shape.equals("flat")) sx = x0 - 5 * scale;
        Paint fl = new Paint(Paint.ANTI_ALIAS_FLAG);
        fl.setColor(0xFFFFBE5A); fl.setStrokeWidth(3);
        cv.drawLine(sx, sy, sx, sy - 44, fl);
        Path flag = new Path(); flag.moveTo(sx, sy - 44); flag.lineTo(sx + 30, sy - 35); flag.lineTo(sx, sy - 26); flag.close();
        cv.drawPath(flag, fl);
        String dir = RegionNames.dir8(Math.cos(Math.toRadians(az)), Math.sin(Math.toRadians(az)));
        String l1 = (String) pk[0];
        String l2 = ((Integer) pk[1]) > 0 ? pk[1] + " m above sea level" : "height not listed";
        String l3 = String.format(java.util.Locale.US, "%.0f km away · %s (%.0f°)", (Double) pk[2], dir, az);
        String l4 = (String) pk[5];
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG), sh2 = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean left = x0 > VW / 2f;
        tp.setTextAlign(left ? Paint.Align.RIGHT : Paint.Align.LEFT); sh2.setTextAlign(tp.getTextAlign());
        float tx = left ? sx - 70 : sx + 70, ty = sy - 20;
        int c1 = 0xFFFFFFFF, c2 = 0xFFD8E2F8;
        String[] ls = {l1, l2, l3, l4};
        float[] sz = {46, 30, 30, 28};
        for (int i = 0; i < 4; i++) {
            if (ls[i] == null || ls[i].isEmpty()) continue;
            tp.setTextSize(sz[i]); tp.setTypeface(i == 0 ? Typeface.create("sans-serif", Typeface.BOLD) : Typeface.create("sans-serif", Typeface.NORMAL));
            sh2.set(tp); sh2.setColor(0xAA000000); sh2.setStyle(Paint.Style.STROKE); sh2.setStrokeWidth(5);
            tp.setColor(i == 0 ? c1 : c2);
            cv.drawText(ls[i], tx, ty, sh2); cv.drawText(ls[i], tx, ty, tp);
            ty += sz[i] + 10;
        }
        ed.setAlpha(150); ed.setStrokeWidth(1.6f);
        cv.drawLine(sx + (left ? -8 : 8), sy - 8, tx + (left ? 14 : -14), sy - 30, ed);
    }

    // ------------------------------------------------------------------ times and the info lines

    private interface Alt { double at(long ms); }

    /** First crossing of thr after t0 going up (rising = true) or down, scanning n steps; 0 if none. */
    private static long cross(Alt f, long t0, long step, int n, double thr, boolean rising) {
        double prev = f.at(t0);
        for (int i = 1; i <= n; i++) {
            long t = t0 + i * step;
            double a = f.at(t);
            if (rising ? (prev < thr && a >= thr) : (prev >= thr && a < thr)) return t - step / 2;
            prev = a;
        }
        return 0;
    }

    private static String hm(long t) { return t == 0 ? "--:--" : new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(t)); }

    private static String dayLen(long rise, long set) { long m = (set - rise) / 60000L; return (m / 60) + "h " + (m % 60) + "m"; }

    private static String phaseName(double ph, boolean waxing) {
        if (ph < 0.03 || ph > 0.97) return "new moon";
        if (ph < 0.22) return "waxing crescent";
        if (ph < 0.28) return "first quarter";
        if (ph < 0.47) return "waxing gibbous";
        if (ph < 0.53) return "full moon";
        if (ph < 0.72) return "waning gibbous";
        if (ph < 0.78) return "last quarter";
        return "waning crescent";
    }

    private static String airWord(int aqi) { return aqi <= 50 ? "good" : aqi <= 100 ? "fair" : aqi <= 150 ? "poor" : aqi <= 200 ? "hazy" : "very poor"; }

    private static String shower(long ms) {
        Object[][] sh = {{"Quadrantids", 1, 3}, {"Lyrids", 4, 22}, {"Eta Aquariids", 5, 6}, {"Perseids", 8, 12}, {"Orionids", 10, 21}, {"Leonids", 11, 17}, {"Geminids", 12, 13}, {"Ursids", 12, 22}};
        Calendar now = Calendar.getInstance(); now.setTimeInMillis(ms);
        int bestDays = 999; String name = "";
        for (Object[] s : sh) {
            Calendar c = Calendar.getInstance(); c.setTimeInMillis(ms);
            c.set(Calendar.MONTH, (Integer) s[1] - 1); c.set(Calendar.DAY_OF_MONTH, (Integer) s[2]);
            long d = Math.round((c.getTimeInMillis() - ms) / 86400000.0);
            if (d < -1) { c.add(Calendar.YEAR, 1); d = Math.round((c.getTimeInMillis() - ms) / 86400000.0); }
            if (d < bestDays) { bestDays = (int) d; name = (String) s[0]; }
        }
        if (bestDays <= 1) return name + " meteor shower peaks tonight";
        return "Next meteor shower: " + name + " in " + bestDays + " days";
    }

    // ------------------------------------------------------------------ chips, highlights, sun path

    private static float planetVis(int i, double alt, float dark, double sunAlt, int[] wx) {
        if (alt < 0.5) return 0f;
        float night = dark * (float) Math.min(1, alt / 5.0);
        float day = (sunAlt > -8 && SkyObjects.PLANET_MAG[i] < -1.5f && alt > 8) ? 0.85f * (1f - 0.8f * cloudFrac(wx)) : 0f;
        return Math.max(night, day);
    }

    private static void shadowText(Canvas cv, String t, float x, float y, Paint tp, Paint sd) {
        sd.setTextAlign(tp.getTextAlign()); sd.setTextSize(tp.getTextSize()); sd.setTypeface(tp.getTypeface());
        cv.drawText(t, x, y, sd); cv.drawText(t, x, y, tp);
    }

    private static void drawSunPath(Canvas cv, double lat, double lon, long ms, float az0) {
        final double la = lat, lo = lon;
        Calendar mid = Calendar.getInstance(); mid.setTimeInMillis(ms);
        mid.set(Calendar.HOUR_OF_DAY, 0); mid.set(Calendar.MINUTE, 0); mid.set(Calendar.SECOND, 0); mid.set(Calendar.MILLISECOND, 0);
        long t0 = mid.getTimeInMillis();
        Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG); dp.setColor(Color.argb(165, 255, 255, 255));
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG), sd = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextSize(24); tp.setColor(Color.WHITE); sd.setStyle(Paint.Style.STROKE); sd.setStrokeWidth(5); sd.setColor(0x88000000);
        float[] first = null, last = null, noonXY = null; long noonT = 0; double maxAlt = -90;
        for (long t = t0; t < t0 + 86400000L; t += 600_000L) {
            Astro.Pos q = Astro.sun(t, la, lo);
            if (q.alt < -0.5) continue;
            float[] xy = proj(q.alt, q.az, az0);
            if (xy[0] < 120 || xy[0] > 960 || xy[1] < 280) continue;
            cv.drawCircle(xy[0], xy[1], 3.2f, dp);
            if (first == null) first = xy;
            last = xy;
            if (q.alt > maxAlt) { maxAlt = q.alt; noonT = t; noonXY = xy; }
        }
        Alt sunF = t -> Astro.sun(t, la, lo).alt;
        long rise = cross(sunF, t0, 5 * 60_000L, 288, -0.83, true), set = cross(sunF, t0, 5 * 60_000L, 288, -0.83, false);
        tp.setTextAlign(Paint.Align.CENTER);
        if (noonXY != null) shadowText(cv, "Noon " + hm(noonT), noonXY[0], noonXY[1] - 62, tp, sd);
        tp.setTextAlign(Paint.Align.LEFT);
        if (first != null && rise != 0) shadowText(cv, "Rise " + hm(rise), Math.max(140, first[0] - 6), first[1] + 40, tp, sd);
        tp.setTextAlign(Paint.Align.RIGHT);
        if (last != null && set != 0) shadowText(cv, "Set " + hm(set), Math.min(940, last[0] + 6), last[1] + 40, tp, sd);
    }

    private static final class Hl { float x, y; String name, sub; int col; double order; java.util.ArrayList<float[]> trail; }

    private static void drawHighlights(Canvas cv, double[][] st, double[][] pl, float dark, double sunAlt, int[] wx, float az0, RectF avoid) {
        java.util.ArrayList<Hl> cs = new java.util.ArrayList<>();
        if (dark > 0.5f) {
            for (int i = 0; i < st.length; i++) {
                double mag = (Double) SkyObjects.STARS[i][3];
                if (mag > 2.3 || st[i][0] < 1 || st[i][0] > 30) continue;
                float[] xy = proj(st[i][0], st[i][1], az0);
                if (xy[0] < 150 || xy[0] > 930 || xy[1] < 300) continue;
                Hl h = new Hl(); h.x = xy[0]; h.y = xy[1]; h.name = (String) SkyObjects.STARS[i][0]; h.col = 0xFFFFFFFF; h.order = mag;
                h.sub = "star · " + Math.round(st[i][0]) + "° " + compass(st[i][1]);
                cs.add(h);
            }
        }
        for (int i = 0; i < 4; i++) {
            if (pl[i][0] > 30 || planetVis(i, pl[i][0], dark, sunAlt, wx) < 0.3f) continue;
            float[] xy = proj(pl[i][0], pl[i][1], az0);
            if (xy[0] < 150 || xy[0] > 930 || xy[1] < 300) continue;
            Hl h = new Hl(); h.x = xy[0]; h.y = xy[1]; h.name = SkyObjects.PLANETS[i]; h.col = 0xFFFFC85A; h.order = SkyObjects.PLANET_MAG[i] - 1;
            h.sub = "planet · " + Math.round(pl[i][0]) + "° " + compass(pl[i][1]);
            cs.add(h);
        }
        for (double[] r : stationsNow) {
            if (r[1] < 0.5) continue;
            float[] xy = proj(r[1], r[2], az0);
            if (xy[0] < 150 || xy[0] > 930 || xy[1] < 300) continue;
            Hl h = new Hl(); h.x = xy[0]; h.y = xy[1]; h.name = r[0] == 25544 ? "ISS" : "Tiangong"; h.col = 0xFF5ADCF0; h.order = -20;
            h.sub = Math.round(r[1]) + "° " + compass(r[2]) + " · up now";
            h.trail = new java.util.ArrayList<>();
            for (int k = 0; k < 3; k++) if (r[3 + k] > -2) h.trail.add(proj(Math.max(r[3 + k], 0), r[6 + k], az0));
            cs.add(h);
        }
        cs.sort((a, b) -> Double.compare(a.order, b.order));
        java.util.ArrayList<RectF> used = new java.util.ArrayList<>();
        if (avoid != null) used.add(avoid);
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), tp = new Paint(Paint.ANTI_ALIAS_FLAG), sd = new Paint(Paint.ANTI_ALIAS_FLAG), tl = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(3f);
        sd.setStyle(Paint.Style.STROKE); sd.setStrokeWidth(5); sd.setColor(0x99000000);
        tl.setStyle(Paint.Style.STROKE); tl.setStrokeWidth(2.4f);
        int n = 0;
        for (Hl h : cs) {
            if (n >= 6) break;
            boolean left = h.x > 620;
            RectF box = new RectF(left ? h.x - 320 : h.x + 30, h.y - 40, left ? h.x - 30 : h.x + 320, h.y + 40);
            boolean clash = false;
            for (RectF u : used) if (RectF.intersects(u, box)) { clash = true; break; }
            if (clash) continue;
            used.add(box); n++;
            g.setShader(new RadialGradient(h.x, h.y, 52, withAlpha(h.col, 110), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            cv.drawCircle(h.x, h.y, 52, g); g.setShader(null);
            ring.setColor(h.col); cv.drawCircle(h.x, h.y, 27, ring);
            if (h.trail != null && !h.trail.isEmpty()) {
                tl.setColor(withAlpha(h.col, 190));
                float px = h.trail.get(0)[0], py = h.trail.get(0)[1];
                for (int k = 1; k < h.trail.size(); k++) { cv.drawLine(px, py, h.trail.get(k)[0], h.trail.get(k)[1], tl); px = h.trail.get(k)[0]; py = h.trail.get(k)[1]; }
                cv.drawLine(px, py, h.x, h.y, tl);
            }
            tp.setTextAlign(left ? Paint.Align.RIGHT : Paint.Align.LEFT);
            float tx = left ? h.x - 38 : h.x + 38;
            tp.setTextSize(30); tp.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); tp.setColor(Color.WHITE);
            shadowText(cv, h.name, tx, h.y - 4, tp, sd);
            tp.setTextSize(22); tp.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); tp.setColor(h.col);
            shadowText(cv, h.sub, tx, h.y + 26, tp, sd);
        }
    }

    private static String compass(double az) {
        return RegionNames.dir8(Math.cos(Math.toRadians(az)), Math.sin(Math.toRadians(az)));
    }

    private static final class Chip { final String a, b; final int col; Chip(String a, String b, int col) { this.a = a; this.b = b; this.col = col; } }

    private static float f(String[] a, int i) {
        try { return i < a.length ? Float.parseFloat(a[i].trim()) : Float.NaN; } catch (Throwable t) { return Float.NaN; }
    }

    private static String inDur(long now, long t) { long m = Math.max(0, (t - now) / 60000); return m >= 60 ? (m / 60) + "h " + (m % 60) + "m" : m + " min"; }

    private static String windArrow(float from) {
        String[] a = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        return a[(int) Math.round((((from + 180) % 360 + 360) % 360) / 45.0) % 8];
    }

    private static void fit(Paint p, String s, float maxW, float min) {
        while (p.measureText(s) > maxW && p.getTextSize() > min) p.setTextSize(p.getTextSize() - 1);
    }

    private static void drawChip(Canvas cv, float x, float y, float w, float h, String a, String b, int col) {
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.argb(125, 8, 14, 30)); cv.drawRoundRect(new RectF(x, y, x + w, y + h), 24, 24, bg);
        bg.setStyle(Paint.Style.STROKE); bg.setStrokeWidth(1.6f); bg.setColor(Color.argb(80, 255, 255, 255)); cv.drawRoundRect(new RectF(x, y, x + w, y + h), 24, 24, bg);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG); dot.setColor(col);
        boolean two = b != null && !b.isEmpty();
        cv.drawCircle(x + 26, y + h / 2f, 9, dot);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(Color.WHITE); t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); t.setTextSize(22);
        fit(t, a, w - 62, 11);
        cv.drawText(a, x + 46, two ? y + h * 0.42f : y + h / 2f + 8, t);
        if (two) {
            t.setColor(Color.rgb(212, 222, 244)); t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); t.setTextSize(18);
            fit(t, b, w - 62, 11);
            cv.drawText(b, x + 46, y + h * 0.80f, t);
        }
    }

    private static void drawInfo(Canvas cv, float day, double lat, double lon, long ms, Astro.Pos sun, Astro.Moon moon, double lst, int[] wx) {
        final double la = lat, lo = lon;
        Alt sunF = t -> Astro.sun(t, la, lo).alt;
        Alt moonF = t -> Astro.moon(t, la, lo).alt;
        Calendar mid = Calendar.getInstance(); mid.setTimeInMillis(ms);
        mid.set(Calendar.HOUR_OF_DAY, 0); mid.set(Calendar.MINUTE, 0); mid.set(Calendar.SECOND, 0); mid.set(Calendar.MILLISECOND, 0);
        long t0 = mid.getTimeInMillis(), step5 = 5 * 60_000L;
        boolean isDay = sun.alt > -0.83;
        String[] mo = wxMore.split("\\|", -1);
        float feels = f(mo, 0), wind = f(mo, 1), wdir = f(mo, 2), gust = f(mo, 3), rain = f(mo, 4);
        String tideA = mo.length > 6 ? mo[6] : "", tideB = mo.length > 7 ? mo[7] : "";
        float uv = wx[2] >= 0 ? wx[2] / 10f : Float.NaN;

        java.util.ArrayList<Chip> ch = new java.util.ArrayList<>();
        // sun
        if (isDay) {
            long set = cross(sunF, ms, step5, 288, -0.83, false);
            long noon = 0; double mx = -90;
            for (long t = t0; t < t0 + 86400000L; t += step5) { double a = sunF.at(t); if (a > mx) { mx = a; noon = t; } }
            long gold = cross(sunF, noon, step5, 150, 6.0, false);
            ch.add(new Chip(set == 0 ? "Sun is up" : "Sunset in " + inDur(ms, set), gold == 0 ? "" : "golden hour " + hm(gold), 0xFFFFAA46));
        } else {
            long rise = cross(sunF, ms, step5, 288, -0.83, true);
            ch.add(new Chip(rise == 0 ? "Sun is down" : "Sunrise in " + inDur(ms, rise), rise == 0 ? "" : "rise " + hm(rise), 0xFFFFAA46));
        }
        // moon, with its distance in AU
        long from = ms - 12 * 3600_000L;
        long mr = cross(moonF, from, 10 * 60_000L, 144, -0.3, true), mset = cross(moonF, from, 10 * 60_000L, 144, -0.3, false);
        boolean moonUp = moon.alt > -0.3;
        ch.add(new Chip(String.format(java.util.Locale.US, "Moon %.4f AU", moon.distKm / 149597870.7),
                String.format(java.util.Locale.US, "%.0f%% %s · %s", moon.illum * 100, moon.waxing ? "waxing" : "waning", moonUp ? "sets " + hm(mset) : "rises " + hm(mr)), 0xFFEBE6C8));
        // rain
        if (!Float.isNaN(rain) && rain >= 0)
            ch.add(rain < 20 ? new Chip("No rain for 3 h", wx[0] >= 0 ? "clouds " + wx[0] + "%" : "", 0xFF6EAAFF)
                    : new Chip("Rain chance " + Math.round(rain) + "%", "next 3 h" + (wx[0] >= 0 ? " · clouds " + wx[0] + "%" : ""), 0xFF6EAAFF));
        boolean windChip = !Float.isNaN(feels) && !Float.isNaN(wind);
        Chip windC = windChip ? new Chip(String.format(java.util.Locale.US, "Feels %.0f° · wind %.0f km/h %s", feels, wind, Float.isNaN(wdir) ? "" : windArrow(wdir)),
                Float.isNaN(gust) ? "" : String.format(java.util.Locale.US, "gust %.0f km/h", gust), 0xFFFF825A) : null;
        Chip airC = wx[1] >= 0 ? new Chip("AQI " + wx[1], airWord(wx[1]), wx[1] <= 50 ? 0xFF78D282 : wx[1] <= 100 ? 0xFF96D25A : wx[1] <= 150 ? 0xFFFFA03C : 0xFFFF5A46) : null;
        if (isDay) {
            if (sun.alt > 3) ch.add(new Chip(String.format(java.util.Locale.US, "Shadow %s your height", 1 / Math.tan(Math.toRadians(sun.alt)) > 9 ? "very long vs" : String.format(java.util.Locale.US, "%.1f×", 1 / Math.tan(Math.toRadians(sun.alt)))),
                    String.format(java.util.Locale.US, "sun %.0f° up", sun.alt), 0xFFBEAA82));
            if (windC != null) ch.add(windC);
            if (airC != null) ch.add(airC);
        } else {
            String sky = wx[0] < 0 ? null : wx[0] < 25 ? "Stars clear" : wx[0] < 70 ? "Stars partly hidden" : "Stars hidden";
            String dk = sun.alt <= -18 ? "dark until " + hm(cross(sunF, ms, step5, 200, -18, true)) : "fully dark from " + hm(cross(sunF, ms, step5, 288, -18, false));
            if (sky != null) ch.add(new Chip(sky, dk, 0xFFAA96FF));
            if (airC != null) ch.add(airC);
            if (!tideA.isEmpty()) ch.add(new Chip("Tide " + tideA, tideB, 0xFF50C8DC)); else if (windC != null) ch.add(windC);
        }

        // layout, built upwards from the bottom so nothing runs under the system's charging line
        float placeY = 1604f, chipH = 76f, row = 84f;
        PlaceInfo.Info pi = placeNow;
        boolean hasPlace = pi != null && !pi.where().isEmpty();
        float bottom = hasPlace ? placeY : placeY + chipH;
        boolean hasUv = isDay && !Float.isNaN(uv) && uv >= 1f;
        float uvY = (hasPlace ? placeY : placeY + chipH) - row - 8;
        float endY = (hasUv ? uvY : bottom) - 8;
        int rows = (ch.size() + 1) / 2;
        float y0 = endY - rows * row;
        for (int i = 0; i < ch.size(); i++) {
            Chip c = ch.get(i);
            drawChip(cv, 150 + (i % 2) * 396, y0 + (i / 2) * row, 384, chipH, c.a, c.b, c.col);
        }
        if (hasUv) {
            int lv = uv < 3 ? 0 : uv < 6 ? 1 : uv < 8 ? 2 : uv < 11 ? 3 : 4;
            String[] nm = {"LOW", "MODERATE", "HIGH", "VERY HIGH", "EXTREME"};
            int[] cl = {0xFF78D282, 0xFFF0D246, 0xFFFF9632, 0xFFEB3C32, 0xFFAA3CC8};
            String[] adv = {"No protection needed for a short time outside", "SPF 30 if out over 30 min · a cap helps",
                    "SPF 30+, cap, sunglasses · shade 11 to 3", "SPF 50, cap + neck cover, redo every 2 h · shade 11 to 3", "Avoid midday sun · SPF 50+, cover up"};
            float[] med = {200, 250, 300, 450, 600, 900};
            int burn = (int) Math.round(med[skinType - 1] / (1.5 * Math.max(0.5, uv)) / 5.0) * 5, tan = Math.max(5, (int) Math.round(burn * 0.4 / 5.0) * 5);
            String a = lv == 0 ? String.format(java.util.Locale.US, "UV %.0f · LOW", uv) : String.format(java.util.Locale.US, "UV %.0f · %s · tans in ~%d min", uv, nm[lv], tan);
            drawChip(cv, 150, uvY, 780, chipH + 8, a, adv[lv], cl[lv]);
        }
        if (hasPlace) drawChip(cv, 150, placeY, 780, chipH, pi.where(), pi.ground(), 0xFF78B45A);
    }
}
