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
    private static final float HORIZON = 1450f;      // y of the far ridge line
    private static final float SPAN = 150f;          // degrees of sky across the screen
    private static final float TOP_ALT = 78f;        // altitude at the top of the screen

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
            float az0 = sp.getFloat("skyAz", 180f);
            Object[] res = draw(w, h, lat, lon, System.currentTimeMillis(), az0);
            Bitmap b = (Bitmap) res[0];
            sp.edit().putFloat("skyAz", (Float) res[1]).putLong("skyAt", System.currentTimeMillis()).apply();
            WallpaperManager.getInstance(c).setBitmap(b, null, true, WallpaperManager.FLAG_LOCK);
            b.recycle();
            return "night sky set on the lock screen";
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
        float best = prevAz, bestScore = score(prevAz, st, pl, moon), here = bestScore;
        for (int a = 0; a < 360; a += 10) {
            float sc = score(a, st, pl, moon);
            if (sc > bestScore) { bestScore = sc; best = a; }
        }
        final float az0 = bestScore > here * 1.35f ? best : prevAz;

        // ---- sky colour
        float t = dark;
        int top = blend(0xFF223A63, 0xFF03050F, t), mid = blend(0xFF5A5C86, 0xFF0A1028, t), low = blend(0xFFE0905A, 0xFF1A2146, t);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, HORIZON + 40, new int[]{top, mid, low}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, VW, VH, p);
        p.setShader(null);

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
            if (aa[0] < 3) continue;
            double mag = (Double) SkyObjects.STARS[i][3];
            float[] xy = proj(aa[0], aa[1], az0);
            if (xy[0] < 6 || xy[0] > VW - 6 || xy[1] < 6) continue;
            float vis = dark * (float) Math.min(1, aa[0] / 12.0);       // thinner near the haze at the horizon
            if (vis <= 0.02f) continue;
            int col = SkyObjects.color((Character) SkyObjects.STARS[i][4]);
            float r = (float) Math.max(2.0, (4.0 - mag) * 1.5 + 1.0);
            sp.setShader(new RadialGradient(xy[0], xy[1], r * 4.2f, withAlpha(col, (int) (110 * vis)), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            cv.drawCircle(xy[0], xy[1], r * 4.2f, sp);
            sp.setShader(null);
            sp.setColor(withAlpha(col, (int) (255 * vis)));
            cv.drawCircle(xy[0], xy[1], r, sp);
            if (mag < 0.95 && vis > 0.5f) {
                lb.setColor(withAlpha(0xFFBCD0FF, (int) (190 * vis)));
                cv.drawText((String) SkyObjects.STARS[i][0], xy[0] + r + 12, xy[1] + 8, lb);
            }
        }

        // ---- planets
        for (int i = 0; i < 4; i++) {
            if (pl[i][0] < 3) continue;
            float[] xy = proj(pl[i][0], pl[i][1], az0);
            if (xy[0] < 20 || xy[0] > VW - 20 || xy[1] < 20) continue;
            float vis = Math.max(dark, 0f) * (float) Math.min(1, pl[i][0] / 10.0);
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

        // ---- Moon (drawn about 3x its true size so the phase can be seen)
        if (moon.alt > -1) {
            float[] xy = proj(Math.max(moon.alt, 0.5), moon.az, az0);
            if (xy[0] > 40 && xy[0] < VW - 40 && xy[1] > 40) drawMoon(cv, xy[0], xy[1], 34f, moon, dark);
        }

        // ---- ridge, valley with town lights, hillside, boy
        drawLand(cv, ms, lat, lon);

        Bitmap out = bm;
        return new Object[]{out, az0};
    }

    // ------------------------------------------------------------------ helpers

    private static float score(float az0, double[][] st, double[][] pl, Astro.Moon moon) {
        float s = 0;
        for (int i = 0; i < st.length; i++) {
            if (st[i][0] < 8) continue;
            if (Math.abs(angDiff(st[i][1], az0)) < SPAN / 2 - 6) s += Math.max(0.2f, 3.6f - (float) (double) (Double) SkyObjects.STARS[i][3]);
        }
        for (int i = 0; i < pl.length; i++)
            if (pl[i][0] > 8 && Math.abs(angDiff(pl[i][1], az0)) < SPAN / 2 - 8) s += 3f;
        if (moon.alt > 5 && Math.abs(angDiff(moon.az, az0)) < SPAN / 2 - 10) s += 6f;
        return s;
    }

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

    private static void drawMoon(Canvas cv, float x, float y, float r, Astro.Moon m, float dark) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(x, y, r * 3.6f, Color.argb((int) (90 * Math.max(0.35f, m.illum) ), 220, 225, 245), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        cv.drawCircle(x, y, r * 3.6f, p);
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
        p.setColor(Color.rgb(240, 238, 222));
        cv.drawPath(lit, p);
        cv.restore();
    }

    private static void drawLand(Canvas cv, long ms, double lat, double lon) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // far ridge
        Path ridge = new Path();
        ridge.moveTo(0, VH);
        for (int x = 0; x <= VW; x += 20) ridge.lineTo(x, HORIZON - (float) (70 * Math.sin(x / 260.0) + 30 * Math.sin(x / 70.0)) + 40);
        ridge.lineTo(VW, VH); ridge.close();
        p.setColor(0xFF0A0E1C);
        cv.drawPath(ridge, p);
        // valley floor
        float vy = 1700f;
        Path valley = new Path();
        valley.moveTo(0, vy - 40); valley.lineTo(VW, vy); valley.lineTo(VW, VH); valley.lineTo(0, VH); valley.close();
        p.setColor(0xFF080A14);
        cv.drawPath(valley, p);
        // glow of the town
        Calendar cal = Calendar.getInstance();
        int hr = cal.get(Calendar.HOUR_OF_DAY);
        float lit = hr >= 18 || hr < 23 ? 1f : hr < 5 ? 0.55f : 0.8f;     // fewer windows late at night
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setShader(new RadialGradient(VW / 2, vy + 120, 520, Color.argb((int) (95 * lit), 255, 160, 60), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        cv.drawCircle(VW / 2, vy + 120, 520, g);
        Random rnd = new Random(7);
        for (int i = 0; i < 300; i++) {
            float yy = vy + (float) Math.pow(rnd.nextFloat(), 1.4) * 230f;
            float xx = (float) (VW * 0.5 + (rnd.nextFloat() - 0.5) * (260 + (yy - vy) * 3.2));
            boolean on = rnd.nextFloat() < lit;
            int col = rnd.nextBoolean() ? 0xFFFFD27A : rnd.nextBoolean() ? 0xFFFFE6AA : 0xFFFFBE64;
            if (!on) continue;
            p.setColor(col);
            float r = 2.2f + (yy - vy) / 230f * 2.2f;
            cv.drawCircle(xx, yy, r, p);
        }
        // hillside in front
        Path fg = new Path();
        fg.moveTo(0, VH); fg.lineTo(0, 1960f);
        for (int x = 0; x <= VW; x += 20) fg.lineTo(x, 1960f - x * 0.22f + (float) (10 * Math.sin(x / 40.0)));
        fg.lineTo(VW, VH); fg.close();
        p.setColor(0xFF05070A);
        cv.drawPath(fg, p);
        // the boy, lying on the slope with his head uphill, looking up
        float bx = 300f, by = 1960f - 300f * 0.22f - 6f;
        p.setColor(0xFF020305);
        cv.drawCircle(bx + 80, by - 31, 21, p);                                   // head
        Path body = new Path();
        body.moveTo(bx - 90, by - 8); body.lineTo(bx + 62, by - 34); body.lineTo(bx + 62, by - 10); body.lineTo(bx - 90, by + 4); body.close();
        cv.drawPath(body, p);
        Path legs = new Path();
        legs.moveTo(bx - 90, by - 8); legs.lineTo(bx - 130, by - 20); legs.lineTo(bx - 132, by - 6); legs.lineTo(bx - 90, by + 4); legs.close();
        cv.drawPath(legs, p);
        Paint arm = new Paint(Paint.ANTI_ALIAS_FLAG);
        arm.setColor(0xFF020305); arm.setStrokeWidth(14); arm.setStrokeCap(Paint.Cap.ROUND);
        cv.drawLine(bx + 10, by - 30, bx + 50, by - 66, arm);                      // arm folded behind the head
        // grass blades
        Random gr = new Random(3);
        arm.setStrokeWidth(3);
        for (int i = 0; i < 200; i++) {
            float x = gr.nextFloat() * VW, y = 1995f - x * 0.22f + gr.nextFloat() * 90f;
            cv.drawLine(x, y, x + gr.nextInt(13) - 6, y - 14 - gr.nextInt(26), arm);
        }
    }
}
