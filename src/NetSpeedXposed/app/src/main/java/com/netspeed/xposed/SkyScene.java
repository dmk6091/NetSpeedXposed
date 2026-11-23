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

    /** "cloud%|aqi|uv|temp" written by the status bar every weather update; empty if unknown. */
    static volatile String wxNow = "";

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

    private static void drawPeak(Canvas cv, float day, double lat, double lon) {
        Object[] pk = nearestPeak(lat, lon);
        String shape = (String) pk[4];
        float[][] sh = shape.equals("pyramid") ? PYRAMID : shape.equals("flat") ? FLAT : DOME;
        double az = (Double) pk[3];
        float x0 = (float) (VW / 2 + angDiff(az, AZ0) * (VW / SPAN));
        float scale = 0.8f + 0.3f * Math.min(1f, ((Integer) pk[1]) / 1400f);
        float half = 430 * scale;
        x0 = Math.max(half * 0.7f + 20, Math.min(VW - half * 0.7f - 20, x0));
        float base = HORIZON + 10;
        float top = base - 330 * scale;
        Path m = new Path();
        m.moveTo(-40, VH); m.lineTo(-40, base);
        m.lineTo(x0 + (sh[0][0] - 400) * scale, base);
        for (float[] q : sh) m.lineTo(x0 + q[0] * scale, top + q[1] * scale + (float) (3 * Math.sin(q[0] / 9.0)));
        m.lineTo(x0 + (sh[sh.length - 1][0] + 400) * scale, base);
        m.lineTo(VW + 40, base); m.lineTo(VW + 40, VH); m.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(blend(0xFF05070C, 0xFF2B3550, day));
        cv.drawPath(m, p);
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
        String l2 = pk[1] + " m above sea level";
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

    private static void drawInfo(Canvas cv, float day, double lat, double lon, long ms, Astro.Pos sun, Astro.Moon moon, double lst, int[] wx) {
        java.util.ArrayList<String> rows = new java.util.ArrayList<>();
        final double la = lat, lo = lon;
        Alt sunF = t -> Astro.sun(t, la, lo).alt;
        Alt moonF = t -> Astro.moon(t, la, lo).alt;
        Calendar mid = Calendar.getInstance(); mid.setTimeInMillis(ms);
        mid.set(Calendar.HOUR_OF_DAY, 0); mid.set(Calendar.MINUTE, 0); mid.set(Calendar.SECOND, 0); mid.set(Calendar.MILLISECOND, 0);
        long t0 = mid.getTimeInMillis(), step5 = 5 * 60_000L;
        String dirS = RegionNames.dir8(Math.cos(Math.toRadians(sun.az)), Math.sin(Math.toRadians(sun.az)));
        boolean isDay = sun.alt > -0.83;
        long rise = cross(sunF, t0, step5, 288, -0.83, true), set = cross(sunF, t0, step5, 288, -0.83, false);
        String w3 = "";
        if (wx[0] >= 0) w3 += "Cloud " + wx[0] + "%";
        if (wx[2] >= 0 && isDay) w3 += (w3.isEmpty() ? "" : " · ") + "UV " + String.format(java.util.Locale.US, "%.0f", wx[2] / 10f);
        if (wx[1] >= 0) w3 += (w3.isEmpty() ? "" : " · ") + "AQI " + wx[1] + " (" + airWord(wx[1]) + ")";
        if (wx[3] > -90) w3 += (w3.isEmpty() ? "" : " · ") + wx[3] + "°";
        if (isDay) {
            long noon = 0; double mx = -90;
            for (long t = t0; t < t0 + 86400000L; t += step5) { double a = sunF.at(t); if (a > mx) { mx = a; noon = t; } }
            long gold = cross(sunF, noon, step5, 150, 6.0, false);
            long yRise = cross(sunF, t0 - 86400000L, step5, 288, -0.83, true), ySet = cross(sunF, t0 - 86400000L, step5, 288, -0.83, false);
            int diff = (int) Math.round(((set - rise) - (ySet - yRise)) / 60000.0);
            rows.add(String.format(java.util.Locale.US, "Sun %.0f° up · %s (%.0f°) · noon %s", sun.alt, dirS, sun.az, hm(noon)));
            rows.add("Rise " + hm(rise) + " · set " + hm(set) + " · golden hour " + hm(gold));
            rows.add("Day " + dayLen(rise, set) + (rise == 0 || set == 0 ? "" : " (" + (diff >= 0 ? "+" : "−") + Math.abs(diff) + " min)"));
            if (!w3.isEmpty()) rows.add(w3);
            long mr = cross(moonF, ms - 12 * 3600_000L, 10 * 60_000L, 144, -0.3, true), ms2 = cross(moonF, ms - 12 * 3600_000L, 10 * 60_000L, 144, -0.3, false);
            rows.add(String.format(java.util.Locale.US, "Moon %.0f%% · rises %s · sets %s", moon.illum * 100, hm(mr), hm(ms2)));
        } else {
            long from = ms - 12 * 3600_000L;
            long mr = cross(moonF, from, 10 * 60_000L, 144, -0.3, true), mset = cross(moonF, from, 10 * 60_000L, 144, -0.3, false);
            rows.add(String.format(java.util.Locale.US, "Moon %s %.0f%% · rises %s · sets %s", phaseName(moon.phase, moon.waxing), moon.illum * 100, hm(mr), hm(mset)));
            StringBuilder pl = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                final int pi = i;
                Alt pf = t -> { double[] rd = SkyObjects.planetRaDec(pi, t); return SkyObjects.altAz(rd[0], rd[1], SkyObjects.lst(t, lo), la)[0]; };
                double nowAlt = pf.at(ms);
                long pr = cross(pf, from, 10 * 60_000L, 144, 0, true), ps = cross(pf, from, 10 * 60_000L, 144, 0, false);
                if (nowAlt > 0 || (pr != 0 && pr > ms - 3600_000L)) {
                    if (pl.length() > 0) pl.append(" · ");
                    pl.append(SkyObjects.PLANETS[i]).append(nowAlt > 0 ? " sets " + hm(ps) : " rises " + hm(pr));
                }
            }
            if (pl.length() > 0) rows.add(pl.toString());
            long fromN = ms - 8 * 3600_000L;
            String dk;
            if (sun.alt <= -18) dk = "Fully dark until " + hm(cross(sunF, ms, step5, 200, -18, true));
            else dk = "Fully dark from " + hm(cross(sunF, ms, step5, 288, -18, false));
            String sky = wx[0] < 0 ? "" : wx[0] < 25 ? " · stars clear" : wx[0] < 70 ? " · stars partly hidden" : " · stars hidden";
            rows.add(dk + (wx[0] >= 0 ? " · clouds " + wx[0] + "%" : "") + sky);
            rows.add(shower(ms));
            if (!w3.isEmpty() && wx[1] >= 0) rows.add(w3);
        }
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG), sd = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextSize(31); tp.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        tp.setColor(0xFFE8EEFF);
        sd.set(tp); sd.setStyle(Paint.Style.STROKE); sd.setStrokeWidth(5); sd.setColor(0x99000000);
        float y = 1900f;
        for (String r : rows) { cv.drawText(r, 48, y, sd); cv.drawText(r, 48, y, tp); y += 50; }
    }
}
