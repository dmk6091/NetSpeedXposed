package com.netspeed.xposed;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * A live sky behind the whole status bar.
 *
 *  - Sun and Moon at their real positions for your place and time (Astro). The camera hole is the
 *    plumb line: straight overhead is at the hole, the horizon is the bottom and the outer edges, east
 *    on the left, west on the right. Below the horizon = not drawn.
 *  - The Moon shows its real phase, lit side turned towards the Sun.
 *  - Behind clouds, rain, fog ... the Sun / Moon is drawn very faint, still in its true place.
 *  - The background follows the weather: sunny / clear, cloudy, overcast, rain, drizzle, windy, storm,
 *    snow, sleet, hail, fog, mist. Clear sky = no clouds at all.
 *
 * Weather: OmniJaws condition code (your weather app, same as the icon), refined by Open-Meteo's
 * WMO code, cloud cover, wind and visibility when they're fresh. Switch: Cfg.SKY.
 */
final class SkyView extends View {
    /** OmniJaws / Yahoo condition code, set by DualRow when the weather updates (-1 = unknown). */
    static volatile int wx = -1;
    /** Open-Meteo extras, set by DualRow every 15 min. */
    static volatile int wmo = -1, cloudPct = -1;
    static volatile float windKmh = Float.NaN, visM = Float.NaN;
    /** US AQI from Open-Meteo (-1 unknown): poor air makes the Sun hazy. */
    static volatile int aqi = -1;
    static volatile long wmoAt = 0;
    /** Solar radiation now, W/m2 (Open-Meteo shortwave_radiation), -1 unknown; and when it was read. */
    static volatile float rad = -1f;
    static volatile long radAt = 0;

    /** How strongly the sun shines now as a share of a clear sky at its height, 0..1; -1 when unknown, stale or the sun is too low to tell. */
    static float shine(double sunAlt) {
        if (rad < 0 || System.currentTimeMillis() - radAt > 60 * 60_000L || sunAlt < 5) return -1f;
        double clear = 1000.0 * Math.pow(Math.sin(Math.toRadians(sunAlt)), 1.15);
        return (float) Math.max(0.0, Math.min(1.0, rad / Math.max(80.0, clear)));
    }
    /** Real weather observed at the nearest airport (METAR): weather string ("-RA", "HZ", "TS"...), when, how far. */
    static volatile String obsWx = null, obsStation = "";
    static volatile long obsAt = 0;
    static volatile float obsKm = Float.NaN, obsTemp = Float.NaN;
    private static final int OBS_DRY = 100;
    /** WeatherAPI.com's current condition (station-corrected), when a key is set: kind and when. */
    static volatile int wapiKind = -1, wapiCode = -1;
    static volatile double wapiPrecip = 0;
    static volatile long wapiAt = 0;

    /**
     * Forecast models here keep calling "light rain / drizzle" under any thick cloud. Light wet calls from the
     * models are treated as cloud; only moderate or heavier rain, thunder with rain, or a real airport report shows wet.
     */
    private static boolean lightYahoo(int c) { return c == 8 || c == 9 || c == 11 || c == 12 || c == 39 || c == 40 || c == 45 || c == 47; }
    private static boolean lightWmo(int c) { return (c >= 51 && c <= 57) || c == 61 || c == 80; }
    private static boolean lightWapi(int c) {
        return c == 1063 || c == 1072 || c == 1150 || c == 1153 || c == 1168 || c == 1180 || c == 1183 || c == 1240;
    }

    /** WeatherAPI condition code -> sky kind. "Patchy rain / thunder nearby" without rain falling here is just cloud. */
    static int fromWapi(int code, double precipMm) {
        boolean wetHere = precipMm > 0.05;
        switch (code) {
            case 1000: return CLEAR;
            case 1003: return CLOUDY;
            case 1006: case 1009: return OVERCAST;
            case 1030: return MIST;
            case 1135: case 1147: return FOG;
            case 1063: return wetHere ? RAIN : CLOUDY;      // "patchy rain nearby"
            case 1087: return wetHere ? STORM : CLOUDY;
            case 1150: case 1153: case 1168: case 1171: return DRIZZLE;
            case 1072: case 1069: case 1204: case 1207: case 1249: case 1252: return SLEET;
            case 1237: case 1261: case 1264: return HAIL;
            case 1273: case 1276: case 1279: case 1282: return STORM;
        }
        if (code >= 1183 && code <= 1201) return RAIN;
        if (code >= 1240 && code <= 1246) return RAIN;
        if (code == 1066 || code == 1114 || code == 1117 || (code >= 1210 && code <= 1225) || code == 1255 || code == 1258) return SNOW;
        return -1;
    }

    /** What the airport actually reports: STORM / RAIN / DRIZZLE / SNOW / HAIL, OBS_DRY, or -1 (none, old or too far). */
    static int obsKind() {
        if (obsWx == null || System.currentTimeMillis() - obsAt > 150 * 60_000L || !(obsKm <= 60)) return -1;
        String w = obsWx.toUpperCase(java.util.Locale.US);
        if (w.contains("TS")) return STORM;
        if (w.contains("GR") || w.contains("GS")) return HAIL;
        if (w.contains("SN")) return SNOW;
        if (w.contains("RA") || w.contains("SH")) return RAIN;
        if (w.contains("DZ")) return DRIZZLE;
        return OBS_DRY;
    }

    static final int CLEAR = 0, CLOUDY = 1, OVERCAST = 2, RAIN = 3, DRIZZLE = 4, WINDY = 5, STORM = 6, SNOW = 7,
            SLEET = 8, HAIL = 9, FOG = 10, MIST = 11;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), cut = new Path();
    private final RectF rf = new RectF();
    private final long t0 = SystemClock.uptimeMillis();
    private final float[] sx = new float[48], sy = new float[48], sph = new float[48];
    private final float[] cx = new float[8], cy = new float[8], cs = new float[8], cv = new float[8];
    private final float[] rx = new float[90], ry = new float[90];
    private final float[] wlx = new float[14], wly = new float[14], wll = new float[14];
    private double lat = 18.62, lon = 73.80;
    private long locAt;
    private final Paint cp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Bitmap[] clouds = new Bitmap[4];
    private int cloudTint;
    private android.graphics.ColorFilter cloudFilter;
    private LinearGradient grad;
    private int gTop, gBot, gH;
    private final float dens;

    // sun / moon, recomputed every 20 s
    private long astroAt;
    private Astro.Pos sunPos;
    private Astro.Moon moonPos;
    // camera hole centre in this view's pixels, rechecked every 10 s
    private long camAt;
    private float camX = Float.NaN, camY = Float.NaN;

    /** Outside opacity requests seen (the ROM fades the first status bar layer, where the sky sits). */
    static volatile int alphaAsks = 0;
    static volatile float lastAsk = 1f;

    /** The sky stays opaque: whoever fades the bar's first layer must not fade the sky with it. */
    @Override
    protected boolean onSetAlpha(int alpha) {
        if (alpha >= 255) return false;   // full opacity goes through normally
        alphaAsks++;
        lastAsk = alpha / 255f;
        return true;      // "handled": the framework then leaves the sky at full opacity
    }

    SkyView(Context ctx) {
        super(ctx);
        dens = ctx.getResources().getDisplayMetrics().density;
        Random r = new Random(11);
        for (int i = 0; i < sx.length; i++) { sx[i] = r.nextFloat(); sy[i] = r.nextFloat(); sph[i] = r.nextFloat() * 6.28f; }
        for (int i = 0; i < cx.length; i++) { cx[i] = r.nextFloat(); cy[i] = 0.15f + r.nextFloat() * 0.6f; cs[i] = 0.8f + r.nextFloat() * 0.6f; cv[i] = 5 + r.nextFloat() * 9; }
        for (int i = 0; i < rx.length; i++) { rx[i] = r.nextFloat(); ry[i] = r.nextFloat(); }
        for (int i = 0; i < wlx.length; i++) { wlx[i] = r.nextFloat(); wly[i] = 0.1f + r.nextFloat() * 0.8f; wll[i] = 0.06f + r.nextFloat() * 0.1f; }
        for (int i = 0; i < clouds.length; i++) clouds[i] = makeCloud(100 + i * 17);
        setWillNotDraw(false);
    }

    /** One cloud as a soft white-and-grey puff pile (grey underside), later multiplied by the tint colour. */
    private static Bitmap makeCloud(long seed) {
        final int W = 280, H = 110;
        Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random r = new Random(seed);
        int n = 34;
        float[] px = new float[n], py = new float[n], pr = new float[n];
        for (int i = 0; i < n; i++) {
            float f = r.nextFloat();
            px[i] = W * (0.16f + 0.68f * f);
            float dome = (float) Math.sin(Math.PI * f);
            pr[i] = 14 + dome * 24 * (0.6f + 0.6f * r.nextFloat());
            py[i] = H * 0.74f - dome * H * 0.30f * (0.4f + 0.8f * r.nextFloat()) - pr[i] * 0.2f;
        }
        for (int i = 0; i < n; i++) {
            float rr = pr[i] * 1.15f;
            q.setShader(new RadialGradient(px[i], py[i] + rr * 0.30f, rr,
                    new int[]{0xB09AA3B2, 0x709AA3B2, 0x00,}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(px[i], py[i] + rr * 0.30f, rr, q);
        }
        for (int i = 0; i < n; i++) {
            float rr = pr[i] * 0.95f;
            q.setShader(new RadialGradient(px[i], py[i] - rr * 0.22f, rr,
                    new int[]{0xE0FFFFFF, 0x90FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(px[i], py[i] - rr * 0.22f, rr, q);
        }
        q.setShader(new LinearGradient(0, H * 0.66f, 0, H * 0.95f, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        q.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        c.drawRect(0, 0, W, H, q);
        return bm;
    }

    /** The bar has side padding (corners / cutout); stretch the sky over it so it reaches both screen edges. */
    private void fitEdges() {
        try {
            android.view.ViewParent vp = getParent();
            if (!(vp instanceof android.view.ViewGroup)) return;
            android.view.ViewGroup g = (android.view.ViewGroup) vp;
            g.setClipToPadding(false);
            g.setClipChildren(false);
            android.view.ViewGroup.MarginLayoutParams lp = (android.view.ViewGroup.MarginLayoutParams) getLayoutParams();
            int l = -g.getPaddingLeft(), r = -g.getPaddingRight();
            if (lp.leftMargin != l || lp.rightMargin != r) {
                lp.leftMargin = l; lp.rightMargin = r;
                setLayoutParams(lp);
            }
        } catch (Throwable ignored) { }
    }

    private long txtAt;
    private boolean darkText;

    /** Status bar text turns dark over light apps; then wash the sky lighter so it stays readable. */
    private void contrast(Canvas c, int w, int h, int base, boolean clearDay) {
        long now = SystemClock.uptimeMillis();
        if (now - txtAt > 2000) {
            txtAt = now;
            try {
                int id = getResources().getIdentifier("clock", "id", "com.android.systemui");
                android.view.ViewParent vp = getParent();
                View ck = id != 0 && vp instanceof View ? ((View) vp).findViewById(id) : null;
                if (ck instanceof android.widget.TextView) {
                    int col = ((android.widget.TextView) ck).getCurrentTextColor();
                    double lum = (0.299 * ((col >> 16) & 255) + 0.587 * ((col >> 8) & 255) + 0.114 * (col & 255)) / 255.0;
                    darkText = lum < 0.5;
                }
            } catch (Throwable ignored) { }
        }
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        // dark text: lighten (a clear day is already pale blue, so only a touch); light text: darken a little
        int wash = darkText ? (clearDay ? 0 : 0x78FFFFFF) : base;
        p.setColor(wash);
        if ((wash >>> 24) != 0) c.drawRect(0, 0, w, h, p);
    }

    private long battAt;
    private boolean low;

    /** True when the battery is at or below the chosen level and not charging: the sky then redraws only every 30 s (looks still). */
    private boolean lowBatt() {
        long now = SystemClock.uptimeMillis();
        if (now - battAt > 30_000) {
            battAt = now;
            low = false;
            int lim = Cfg.i(Cfg.SKY_LOW, Cfg.D_SKY_LOW);
            if (lim > 0) {
                try {
                    android.content.Intent b = getContext().registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
                    if (b != null) {
                        int lvl = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 100) * 100 / Math.max(1, b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100));
                        boolean chg = b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
                        low = !chg && lvl <= lim;
                    }
                } catch (Throwable ignored) { }
            }
        }
        return low;
    }

    private static int lerp(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static float sm(double x) {
        x = Math.max(0, Math.min(1, x));
        return (float) (x * x * (3 - 2 * x));
    }

    private static int alpha(int c, float a) {
        return (c & 0x00FFFFFF) | (Math.round(Math.max(0f, Math.min(1f, a)) * 255) << 24);
    }

    // ------------------------------------------------------------------ weather condition

    private static int fromYahoo(int c) {
        switch (c) {
            case 0: case 1: case 2: case 3: case 4: case 37: case 38: case 39: case 45: case 47: return STORM;
            case 5: case 6: case 7: case 10: case 18: return SLEET;
            case 8: case 9: return DRIZZLE;
            case 11: case 12: case 40: return RAIN;
            case 13: case 14: case 15: case 16: case 41: case 42: case 43: case 46: return SNOW;
            case 17: case 35: return HAIL;
            case 19: case 21: case 22: return MIST;
            case 20: return FOG;
            case 23: case 24: return WINDY;
            case 26: return OVERCAST;
            case 27: case 28: case 29: case 30: case 44: return CLOUDY;
            case 25: case 31: case 32: case 33: case 34: case 36: return CLEAR;
            default: return -1;
        }
    }

    private static int fromWmo(int c) {
        if (c == 0 || c == 1) return CLEAR;
        if (c == 2) return CLOUDY;
        if (c == 3) return OVERCAST;
        if (c == 45 || c == 48) return FOG;
        if (c >= 51 && c <= 57) return DRIZZLE;
        if ((c >= 61 && c <= 65) || (c >= 80 && c <= 82)) return RAIN;
        if (c == 66 || c == 67) return SLEET;
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return SNOW;
        if (c == 95) return STORM;
        if (c == 96 || c == 99) return HAIL;
        return -1;
    }

    static boolean wet(int k) {
        return k == RAIN || k == DRIZZLE || k == STORM || k == SNOW || k == SLEET || k == HAIL;
    }

    /** Icon code for the status bar: the weather app's, unless the sky we paint disagrees with it about rain. */
    static int iconCode(boolean day) {
        int k = condition(), a = fromYahoo(wx);
        if (a >= 0 && wet(a) == wet(k)) return wx;
        switch (k) {
            case STORM: return 4;
            case HAIL: return 17;
            case RAIN: return wmo >= 80 ? 11 : 12;
            case DRIZZLE: return 9;
            case SLEET: return 5;
            case SNOW: return 16;
            case FOG: return 20;
            case MIST: return 21;
            case OVERCAST: return 26;
            case WINDY: return 24;
        }
        int cl = cloudPct;
        if (k == CLOUDY) return cl >= 0 && cl < 70 ? (day ? 30 : 29) : (day ? 28 : 27);
        return cl >= 10 ? (day ? 34 : 33) : (day ? 32 : 31);
    }

    /** The condition to paint, from your weather app first, sharpened by Open-Meteo when that is fresh. */
    static int condition() {
        boolean fresh = System.currentTimeMillis() - wmoAt < 60 * 60_000L;
        int a = fromYahoo(wx), b = fresh ? fromWmo(wmo) : -1;
        int k = a >= 0 ? a : b >= 0 ? b : CLEAR;
        boolean wFresh = wapiKind >= 0 && System.currentTimeMillis() - wapiAt < 60 * 60_000L;
        if (a >= 0 && b >= 0) {
            // the weather app and Open-Meteo disagree about rain: trust Open-Meteo (15-minute data, same as the rain %)
            if (wet(a) != wet(b)) k = b;
            if (a == RAIN && (b == DRIZZLE || b == SLEET)) k = b;      // finer kind of the same wet weather
            if (a == STORM && b == HAIL) k = HAIL;
        }
        // WeatherAPI (station-corrected current conditions) beats both forecasts when it's there
        if (wFresh) k = wapiKind;
        if (wet(k)) {
            boolean strong = false;
            if (a >= 0 && wet(a) && !lightYahoo(wx)) strong = true;
            if (b >= 0 && wet(b) && !lightWmo(wmo)) strong = true;
            if (wFresh && wet(wapiKind) && !lightWapi(wapiCode) && wapiPrecip >= 0.3) strong = true;
            if (!strong) k = CLOUDY;
        }
        // what the nearest airport really sees beats both forecasts on "is it raining"
        int o = obsKind();
        if (o == OBS_DRY && wet(k)) k = CLOUDY;
        else if (o >= 0 && o != OBS_DRY && !wet(k)) k = o;
        if (fresh) {
            int cl = cloudPct;
            float vis = visM, wind = windKmh;
            // measured cloud cover decides between clear / cloudy / overcast (codes alone are often off)
            if ((k == CLEAR || k == CLOUDY || k == OVERCAST) && cl >= 0) k = cl >= 85 ? OVERCAST : cl >= 40 ? CLOUDY : CLEAR;   // up to ~40 % is 'mostly clear'
            if ((k == CLEAR || k == CLOUDY || k == OVERCAST) && !Float.isNaN(vis)) {
                if (vis < 1000) k = FOG;
                else if (vis < 4000) k = MIST;
            }
            // "mist" / "haze" from a weather service while you can see 5 km+: a clear sky (the haze only softens the Sun)
            if ((k == MIST || k == FOG) && !Float.isNaN(vis) && vis >= 5000) k = cl >= 85 ? OVERCAST : cl >= 40 ? CLOUDY : CLEAR;
            if ((k == CLEAR || k == CLOUDY) && !Float.isNaN(wind) && wind >= 35) k = WINDY;
        }
        return k;
    }

    /** How clearly you'd see the Sun / Moon: 1 clear, ~0.16 hidden behind weather (drawn very faint). */
    private static float clarity(int k) {
        switch (k) {
            case CLEAR: return 1f;
            case WINDY: return cloudPct >= 0 ? Math.max(0.4f, 1f - cloudPct / 100f * 0.7f) : 1f;
            case CLOUDY: return cloudPct >= 0 ? Math.max(0.4f, 1f - cloudPct / 100f * 0.7f) : 0.6f;
            case MIST: return 0.5f;
            default: return 0.28f;
        }
    }

    private static volatile int diagK = -1;
    private static volatile float diagVis = Float.NaN;
    private static final String[] COND = {"clear", "cloudy", "overcast", "rain", "drizzle", "windy", "storm", "snow",
            "sleet", "hail", "fog", "mist"};

    /** For the NetSpeed app: what the sky is drawing and why. */
    static volatile String diagView = "view: not drawn yet";
    static volatile long diagViewAt;
    static volatile float diagD = Float.NaN;

    static String diag(double lat, double lon) {
        long ms = System.currentTimeMillis();
        StringBuilder b = new StringBuilder("SKY\n");
        b.append("  ").append(diagView).append(" (").append((ms - diagViewAt) / 1000).append(" s ago)\n");
        int k = diagK;
        b.append("  showing: ").append(k >= 0 && k < COND.length ? COND[k] : "not drawn yet")
                .append(k == CLEAR && cloudPct >= 10 ? " (a few light clouds)" : "")
                .append(Float.isNaN(diagVis) ? "" : String.format(java.util.Locale.US, ", sun/moon clarity %.0f%%", diagVis * 100))
                .append('\n');
        boolean fresh = ms - wmoAt < 60 * 60_000L;
        b.append("  weather app code ").append(wx).append(" -> ").append(name(fromYahoo(wx))).append('\n');
        b.append("  Open-Meteo ").append(fresh ? "" : "(stale) ").append("WMO ").append(wmo).append(" -> ").append(name(fromWmo(wmo)))
                .append(", cloud ").append(cloudPct).append("%, vis ")
                .append(Float.isNaN(visM) ? "?" : String.format(java.util.Locale.US, "%.1f km", visM / 1000f))
                .append(", wind ").append(Float.isNaN(windKmh) ? "?" : Math.round(windKmh) + " km/h").append('\n');
        if (obsWx != null) {
            int o = obsKind();
            b.append(String.format(java.util.Locale.US, "  airport %s %.0f km, %d min ago: %s%s -> %s%n", obsStation, obsKm,
                    (ms - obsAt) / 60_000L, obsWx.isEmpty() ? "no weather" : obsWx,
                    Float.isNaN(obsTemp) ? "" : String.format(java.util.Locale.US, ", %.0f°", obsTemp),
                    o < 0 ? "too old / far, not used" : o == OBS_DRY ? "dry" : name(o)));
        } else b.append("  airport report: none yet\n");
        if (wapiKind >= 0) b.append("  WeatherAPI: ").append(name(wapiKind)).append(" (code ").append(wapiCode)
                .append(String.format(java.util.Locale.US, ", %.1f mm", wapiPrecip)).append("), ")
                .append((ms - wapiAt) / 60_000L).append(" min ago (used first)\n");
        try {
            double lst = SkyObjects.lst(ms, lon);
            String[] dir = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
            StringBuilder pl = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                double[] rd = SkyObjects.planetRaDec(i, ms);
                double[] aa = SkyObjects.altAz(rd[0], rd[1], lst, lat);
                if (aa[0] > 0) pl.append(pl.length() > 0 ? ", " : "").append(SkyObjects.PLANETS[i])
                        .append(String.format(java.util.Locale.US, " %.0f° %s", aa[0], dir[(int) Math.round(aa[1] / 45.0) % 8]));
            }
            int ns = 0;
            for (Object[] st : SkyObjects.STARS) if (SkyObjects.altAz((Double) st[1] * 15, (Double) st[2], lst, lat)[0] > 0.5) ns++;
            b.append("  planets up: ").append(pl.length() > 0 ? pl : "none").append(" · bright stars up: ").append(ns).append('\n');
        } catch (Throwable ignored) { }
        try {
            Astro.Pos s = Astro.sun(ms, lat, lon);
            Astro.Moon m = Astro.moon(ms, lat, lon);
            b.append(String.format(java.util.Locale.US, "  sun %.1f° high, az %.0f°%s%n", s.alt, s.az, s.alt < -0.6 ? " (set, not drawn)" : ""));
            b.append(String.format(java.util.Locale.US, "  moon %.1f° high, az %.0f°, %.0f%% lit%s%n", m.alt, m.az, m.illum * 100,
                    m.alt < -0.3 ? " (set, not drawn)" : ""));
        } catch (Throwable ignored) { }
        b.append("  outside fade requests ignored: ").append(alphaAsks)
                .append(alphaAsks > 0 ? String.format(java.util.Locale.US, " (last %.0f%%)", lastAsk * 100) : "").append('\n');
        return b.toString();
    }

    private static String name(int k) {
        return k >= 0 && k < COND.length ? COND[k] : "unknown";
    }

    // ------------------------------------------------------------------ geometry

    /** Camera hole centre in this view's pixels (top centre if the phone reports none). */
    private void cam(int w, int h) {
        long now = SystemClock.uptimeMillis();
        if (now - camAt < 10_000 && !Float.isNaN(camX)) return;
        camAt = now;
        camX = w / 2f;
        camY = h * 0.38f;
        try {
            android.view.WindowInsets wi = getRootWindowInsets();
            android.view.DisplayCutout dc = wi == null ? null : wi.getDisplayCutout();
            if (dc == null) return;
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            for (android.graphics.Rect r : dc.getBoundingRects()) {
                if (r.top > loc[1] + h || r.width() <= 0) continue;
                camX = r.exactCenterX() - loc[0];
                camY = Math.max(0, Math.min(h * 0.6f, r.exactCenterY() - loc[1]));
                break;
            }
        } catch (Throwable ignored) { }
    }

    /**
     * Sky position to bar pixels: the camera hole is straight overhead, the bottom edge and the far
     * left / right are the horizon; east to the left, west to the right. Height above the horizon decides
     * how far down the bar it sits, the east-west part of its direction how far to the side.
     */
    private long setMs, riseMs;

    /** Sun's horizon crossings around now (last set, next rise), found by stepping the real sun position. */
    private void nightSpan(long ms) {
        if (setMs > 0 && ms >= setMs && ms < riseMs) return;
        long st = 0, rs = 0;
        try {
            for (long t = ms; t > ms - 20 * 3600_000L; t -= 5 * 60_000L)
                if (Astro.sun(t, lat, lon).alt > -0.833) { st = t; break; }
            for (long t = ms; t < ms + 20 * 3600_000L; t += 5 * 60_000L)
                if (Astro.sun(t, lat, lon).alt > -0.833) { rs = t; break; }
        } catch (Throwable ignored) { }
        setMs = st;
        riseMs = rs;
    }

    /**
     * Night: a small dim dot along the bottom edge for the Sun under the horizon, sliding from the right
     * (where it set, west) to the left (where it rises, east) with the time of night.
     */
    private void drawSunBelow(Canvas c, int w, int h) {
        long ms = System.currentTimeMillis();
        nightSpan(ms);
        if (setMs <= 0 || riseMs <= setMs) return;
        float f = Math.max(0f, Math.min(1f, (ms - setMs) / (float) (riseMs - setMs)));
        belowSunF = f;
        float m = 6 * dens;
        float x = (w - m) - f * (w - 2 * m);
        float y = h - 3f * dens;                 // right on the bottom edge, under the last text line
        float r = 3.8f * dens;
        p.setColor(0xFFFFFFFF);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(x, y, r * 4.5f, new int[]{0xAAFF9A40, 0}, null, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r * 4.5f, p);
        p.setShader(null);
        p.setColor(0xFFFFB060);
        c.drawCircle(x, y, r, p);
    }

    private float[] place(double alt, double az, int w, int h, float r) {
        double rho = Math.max(0, Math.min(1, (90 - alt) / 90));
        double side = Math.sin(Math.toRadians(az));           // +1 due east, -1 due west
        float m = r * 1.1f;
        float reachL = Math.max(1, camX - m), reachR = Math.max(1, w - m - camX);
        float x = (float) (camX - side * rho * (side > 0 ? reachL : reachR));
        float y = (float) (camY + rho * Math.max(1, h - m - camY));
        return new float[]{x, y};
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        if (!Cfg.b(Cfg.SKY, Cfg.D_SKY)) {
            postInvalidateDelayed(3000);
            return;
        }
        fitEdges();
        if (getAlpha() < 0.99f) {          // faded by an animation (skips onSetAlpha): put it back
            alphaAsks++;
            lastAsk = getAlpha();
            setAlpha(1f);
        }
        int w = getWidth(), h = getHeight();
        try {
            StringBuilder a = new StringBuilder();
            for (android.view.View v = this; v != null; v = v.getParent() instanceof android.view.View ? (android.view.View) v.getParent() : null) {
                if (a.length() > 160) break;
                a.append(String.format(java.util.Locale.US, "%s %.2f%s / ", v.getClass().getSimpleName(), v.getAlpha(),
                        v.getVisibility() == VISIBLE ? "" : " hid"));
            }
            diagView = String.format(java.util.Locale.US, "view %dx%d, top %08X bottom %08X, day %.2f, dark text %s, opacity %d, fade %s%n  alphas: %s",
                    w, h, gTop, gBot, diagD, darkText, Cfg.i(Cfg.SKY_OPACITY, Cfg.D_SKY_OPACITY), Cfg.b(Cfg.SKY_FADE, Cfg.D_SKY_FADE), a);
            diagViewAt = System.currentTimeMillis();
        } catch (Throwable ignored) { }
        if (w <= 0 || h <= 0) return;
        // see-through: the whole sky in one layer at the chosen opacity, its lower part fading into what's below
        int op = Math.max(30, Math.min(100, Cfg.i(Cfg.SKY_OPACITY, Cfg.D_SKY_OPACITY)));
        boolean fade = Cfg.b(Cfg.SKY_FADE, Cfg.D_SKY_FADE);
        int layer = op < 100 || fade ? c.saveLayerAlpha(0, 0, w, h, Math.round(op * 2.55f)) : -1;
        try {
            drawSky(c, w, h);
        } finally {
            if (layer >= 0) {
                if (fade) {
                    if (fadeShader == null || fadeH != h) {
                        fadeShader = new LinearGradient(0, h * 0.45f, 0, h, 0xFF000000, 0x80000000, Shader.TileMode.CLAMP);
                        fadeH = h;
                    }
                    p.setShader(fadeShader);
                    p.setStyle(Paint.Style.FILL);
                    p.setXfermode(DST_IN);
                    c.drawRect(0, 0, w, h, p);
                    p.setXfermode(null);
                    p.setShader(null);
                }
                c.restoreToCount(layer);
            }
        }
        // the paint still carries the last wash's alpha (a shader's colours are multiplied by it): opaque for the Sun / Moon and their glows
        p.setShader(null);
        p.setXfermode(null);
        p.setColor(0xFFFFFFFF);
        try {
            if (bodyMoonV >= 0) drawMoon(c, w, h, bodyMoonV, bodyVis < 0.35f);
            if (bodySun) drawSun(c, w, h, bodyVis, bodyVis < 0.35f, bodyK);
        } catch (Throwable ignored) { }
        if (!bodySun) glowOn = 0f;
        if (!sunBelowDue) belowSunF = -1f;
        if (!moonBelowDue) belowMoonF = -1f;
        if (sunBelowDue) try { drawSunBelow(c, w, h); } catch (Throwable ignored) { }
        if (moonBelowDue) try { drawMoonBelow(c, w, h); } catch (Throwable ignored) { }
    }

    /** Where the Sun / Moon dot under the horizon sits along the bottom edge (0 right .. 1 left), -1 when not shown; the status bar draws them over its skyline. */
    static volatile float belowSunF = -1f, belowMoonF = -1f, belowMoonPhase = .5f;

    private boolean sunBelowDue, moonBelowDue, bodySun;
    private float bodyMoonV = -1, bodyVis = 1;
    private int bodyK;
    private long mSetMs, mRiseMs;

    /** Moonset .. next moonrise around now (5-min steps), cached until it no longer brackets now. */
    private void moonSpan(long ms) {
        if (mSetMs > 0 && ms >= mSetMs && ms < mRiseMs) return;
        long st = 0, rs = 0;
        try {
            for (long t = ms; t > ms - 26 * 3600_000L; t -= 5 * 60_000L)
                if (Astro.moon(t, lat, lon).alt > -0.3) { st = t; break; }
            for (long t = ms; t < ms + 26 * 3600_000L; t += 5 * 60_000L)
                if (Astro.moon(t, lat, lon).alt > -0.3) { rs = t; break; }
        } catch (Throwable ignored) { }
        mSetMs = st;
        mRiseMs = rs;
    }

    /** Moon under the horizon: a small pale dot on the bottom edge, from where it set (west, right) to where it rises (east, left), in its phase. */
    private void drawMoonBelow(Canvas c, int w, int h) {
        long ms = System.currentTimeMillis();
        moonSpan(ms);
        if (mSetMs <= 0 || mRiseMs <= mSetMs) return;
        float f = Math.max(0f, Math.min(1f, (ms - mSetMs) / (float) (mRiseMs - mSetMs)));
        belowMoonF = f;
        belowMoonPhase = (float) (moonPos != null ? ((Astro.Moon) moonPos).phase : 0.5);
        float m = 6 * dens;
        float x = (w - m) - f * (w - 2 * m);
        float y = h - 3f * dens;
        float r = 4.2f * dens;
        p.setColor(0xFFFFFFFF);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(x, y, r * 4.5f, new int[]{0x99C9D8FF, 0}, null, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r * 4.5f, p);
        p.setShader(null);
        double ph = moonPos != null ? ((Astro.Moon) moonPos).phase : 0.5;
        p.setColor(0x99A0A8B8);                                 // dark side
        c.drawCircle(x, y, r, p);
        // lit part: simple terminator, waxing lit on the right, waning on the left
        float k = (float) Math.cos(2 * Math.PI * ph);           // 1 new .. -1 full
        android.graphics.Path mp = new android.graphics.Path();
        boolean wax = ph < 0.5;
        android.graphics.RectF o = new android.graphics.RectF(x - r, y - r, x + r, y + r);
        mp.addArc(o, wax ? -90 : 90, 180);
        android.graphics.RectF in = new android.graphics.RectF(x - r * Math.abs(k), y - r, x + r * Math.abs(k), y + r);
        mp.arcTo(in, wax ? 90 : -90, k > 0 ? -180 : 180);
        mp.close();
        p.setColor(0xFFEAF0FF);
        c.drawPath(mp, p);
    }

    private Shader fadeShader;
    private int fadeH;
    private static final android.graphics.PorterDuffXfermode DST_IN =
            new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN);

    private void drawSky(Canvas c, int w, int h) {
        long now = SystemClock.uptimeMillis();
        float t = (now - t0) / 1000f;
        if (now - locAt > 600_000) {
            locAt = now;
            try {
                android.location.Location l = TowerDb.myLocation(getContext());
                if (l != null) { lat = l.getLatitude(); lon = l.getLongitude(); }
            } catch (Throwable ignored) { }
            astroAt = 0;
        }
        if (sunPos == null || now - astroAt > 20_000) {
            astroAt = now;
            long ms = System.currentTimeMillis();
            try {
                sunPos = Astro.sun(ms, lat, lon);
                moonPos = Astro.moon(ms, lat, lon);
            } catch (Throwable ignored) { }
        }
        cam(w, h);
        double sunAlt = sunPos == null ? 0 : sunPos.alt;
        float d = sm((sunAlt + 8) / 14.0);                     // 0 night (sun 8° down) .. 1 day (6° up)
        int k = condition();
        float vis = clarity(k);
        boolean night = d < 0.4f;
        diagK = k; diagVis = vis; diagD = d;
        boolean moving;

        if (night && Cfg.b(Cfg.NIGHT, Cfg.D_NIGHT) && (k == CLEAR || k == CLOUDY || k == WINDY)) {
            drawNightClear(c, w, h, t, k);
            moving = true;
        } else {
            background(c, w, h, k, d);
            if (night && (k == CLOUDY || k == WINDY || k == CLEAR)) realSky(c, w, h, t, k == CLOUDY ? 0.45f : 1f);
            moving = night;
        }
        // readability wash under the Sun / Moon / weather, so it never bleaches them
        // white text: a firmer dark veil by day (stronger under clouds); dark text: the light wash in contrast()
        contrast(c, w, h, d > 0.4f ? (k == CLEAR ? 0x22000000 : 0x58000000) : 0x18000000, k == CLEAR && d > 0.4f);
        // bodies: real places, faint when the weather hides them; the Moon is paler by day
        // drawn after the fade layer (onDraw), so a low Sun / Moon near the bottom edge isn't faded away
        bodyMoonV = moonPos != null && moonPos.alt > -0.3 ? vis * (d > 0.5f ? 0.55f + 0.45f * (1 - d) : 1f) : -1;
        bodySun = sunPos != null && sunPos.alt > -0.6;
        bodyVis = vis; bodyK = k;
        sunBelowDue = sunPos != null && sunPos.alt <= -0.6;   // drawn after the fade layer, so it stays visible
        moonBelowDue = moonPos != null && moonPos.alt <= -0.3;
        // weather on top
        boolean wxMoving = weather(c, w, h, t, k, d);
        moving |= wxMoving;
        if (!darkText && d > 0.4f && k != CLEAR) {             // white text over clouds / rain: a light veil on top too
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x26000000);
            c.drawRect(0, 0, w, h, p);
        }

        // light on power: rain / snow / cloud motion at 10 frames a second; a still night sky (stars, planets,
        // satellites) once a second; nothing moving -> every 5 s
        long next = lowBatt() ? 30000 : !ScreenState.on() ? 3000 : wxMoving ? 100 : moving ? 1000 : 5000;
        postInvalidateDelayed(next);
    }

    /** {top, bottom} colours for a condition, day and night, blended by daylight d. */
    private void background(Canvas c, int w, int h, int k, float d) {
        int dayT, dayB, nT, nB;
        float warmK = 0;
        switch (k) {
            case OVERCAST: dayT = 0xFF8A929C; dayB = 0xFFB4BAC2; nT = 0xFF15171C; nB = 0xFF23262E; break;
            case RAIN:     dayT = 0xFF5B6573; dayB = 0xFF7D8794; nT = 0xFF0E1116; nB = 0xFF1A1E26; break;
            case DRIZZLE:  dayT = 0xFF7A8591; dayB = 0xFFA3ACB6; nT = 0xFF12151B; nB = 0xFF20242C; break;
            case STORM:    dayT = 0xFF2E3240; dayB = 0xFF4A4458; nT = 0xFF07080C; nB = 0xFF15121E; break;
            case SNOW:     dayT = 0xFFB9C4D0; dayB = 0xFFDDE4EC; nT = 0xFF1A1F2A; nB = 0xFF2B3240; break;
            case SLEET:    dayT = 0xFF8E99A6; dayB = 0xFFB7C0CA; nT = 0xFF141820; nB = 0xFF252A34; break;
            case HAIL:     dayT = 0xFF4E5663; dayB = 0xFF7A8290; nT = 0xFF0D1015; nB = 0xFF1E222B; break;
            case FOG:      dayT = 0xFFA9AEB4; dayB = 0xFFC9CDD1; nT = 0xFF1C1E22; nB = 0xFF2C2F34; break;
            case MIST:     dayT = 0xFF8FAAC6; dayB = 0xFFC3D2E0; nT = 0xFF141A26; nB = 0xFF262D3A; warmK = 0.5f; break;
            case CLOUDY:   dayT = 0xFF6F98C8; dayB = 0xFFAFCBE6; nT = 0xFF0B0E22; nB = 0xFF1C2244; warmK = 0.7f; break;
            default:
                // clear: a pale blue tint over the whole bar (deeper when the bar's text is white)
                dayT = darkText ? 0xFF64AEF0 : 0xFF2F86E6;
                dayB = darkText ? 0xFFA6D2F8 : 0xFF62AEF2;
                nT = 0xFF080A20; nB = 0xFF1C2250; warmK = 1f; break;
        }
        int top = lerp(nT, dayT, d), bot = lerp(nB, dayB, d);
        float warm = 4 * d * (1 - d) * warmK;                 // sunrise / sunset glow
        top = lerp(top, 0xFF463C78, warm);
        bot = lerp(bot, 0xFFFA965A, warm);
        if (grad == null || gTop != top || gBot != bot || gH != h) {
            grad = new LinearGradient(0, 0, 0, h, top, bot, Shader.TileMode.CLAMP);
            gTop = top; gBot = bot; gH = h;
        }
        p.setShader(grad);
        p.setStyle(Paint.Style.FILL);
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
    }

    private void stars(Canvas c, int w, int h, float t, float amount) {
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < sx.length; i++) {
            if (amount < 1f && (i % 2 == 1)) continue;
            float b = 0.5f + 0.5f * (float) Math.sin(t * 0.9f + sph[i]);
            p.setColor(alpha(0xFFFFFFFF, amount * (0.3f + 0.6f * b)));
            c.drawCircle(sx[i] * w, 2 + sy[i] * (h - 6), dens * (0.45f + 0.55f * b), p);
        }
    }

    /**
     * Realistic sun: limb-darkened disc and a glow that follows the sky.
     *  clear      - crisp warm-yellow disc, tight bright glow
     *  clouds     - the glow spreads wide and turns whiter (light scattered by the cloud), the disc softens
     *  haze/dust  - (low visibility, mist, poor air) the disc turns pale orange, the glow goes wide and dull
     *  overcast.. - only a broad, soft bright patch with a faint disc outline where the Sun is
     * Low in the sky everything shifts to orange-red.
     */
    private void drawSun(Canvas c, int w, int h, float vis, boolean faint, int k) {
        // clearer sky = bigger, brighter Sun
        float r = h * (k == CLEAR ? 0.16f : 0.13f);
        glowOn = 0f;
        // the paint still carries the readability wash's alpha (a shader's colours are multiplied by it): back to opaque
        p.setColor(0xFFFFFFFF);
        p.setAlpha(255);
        p.setXfermode(null);
        float[] xy = place(sunPos.alt, sunPos.az, w, h, r);
        float x = xy[0], y = xy[1];
        float hi = sm((sunPos.alt - 1) / 20.0);               // 0 at the horizon .. 1 high
        boolean fresh = System.currentTimeMillis() - wmoAt < 60 * 60_000L;
        // cloud cover 0..1
        float shn = shine(sunPos.alt);                         // real sunshine strength when the weather feed gave it
        float cover = shn >= 0 ? 1f - shn
                : fresh && cloudPct >= 0 ? cloudPct / 100f
                : k == CLEAR ? 0f : k == CLOUDY || k == WINDY ? 0.55f : k == MIST || k == FOG ? 0.3f : 0.95f;
        // haze 0..1: visibility under ~15 km, mist / fog, or poor air
        float haze = 0f;
        if (fresh && !Float.isNaN(visM)) haze = Math.max(haze, Math.min(1f, (15000f - visM) / 11000f));
        if (k == MIST) haze = Math.max(haze, 0.6f);
        if (k == FOG) haze = 1f;
        if (aqi > 100) haze = Math.max(haze, Math.min(0.8f, (aqi - 100) / 150f));
        haze = Math.max(0f, haze) * (k == CLEAR ? 0.4f : 1f);   // light haze on a clear day barely dims it

        int core = lerp(0xFFFFA030, 0xFFFFD21A, hi);          // deep yellow-orange, stands out on a pale sky
        int limb = lerp(0xFFFF5A10, 0xFFFFA41C, hi);
        int glow = lerp(0xFFFF6A1C, 0xFFFFB81A, hi);
        // haze: paler, more orange disc and a duller glow; clouds: whiter glow
        core = lerp(core, 0xFFFFE2C0, haze * 0.7f);
        limb = lerp(limb, 0xFFFF9A5A, haze * 0.6f);
        glow = lerp(glow, 0xFFFFC8A0, haze * 0.6f);
        glow = lerp(glow, 0xFFFFF6E6, cover * 0.85f);

        p.setStyle(Paint.Style.FILL);
        // wide soft bloom: grows with cloud and haze (light spread across the sky)
        float bloom = cover * 0.9f + haze * 0.6f;
        if (bloom > 0.08f) {
            float br = r * (5f + 4f * Math.min(1f, bloom));
            float ba = Math.min(0.32f, 0.12f + 0.2f * bloom) * Math.max(0.5f, vis + 0.3f);
            p.setShader(new RadialGradient(x, y, br, new int[]{alpha(glow, ba), alpha(glow, ba * 0.4f), 0},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, br, p);
        }
        // inner glow: tight and bright when clear, weaker and wider with cloud / haze
        float gr = r * (3.6f + 1.6f * cover + 1.2f * haze) * (faint ? 0.8f : 1f);
        float ga = (1f - 0.35f * cover - 0.25f * haze) * (faint ? 0.6f : 1f) * (shn >= 0 ? 0.55f + 0.45f * shn : 1f);
        p.setShader(new RadialGradient(x, y, gr, new int[]{alpha(glow, Math.max(0.12f, ga) * Math.max(vis, 0.4f)),
                alpha(glow, 0.18f * Math.max(vis, 0.4f)), 0}, new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, gr, p);
        if (faint) {                                           // behind rain / storm cloud: only a soft bright patch, no disc
            p.setShader(new RadialGradient(x, y, r * 1.6f, new int[]{alpha(core, 0.5f), alpha(glow, 0.22f), 0},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, r * 1.6f, p);
            p.setShader(null);
            return;
        }
        // the disc stays clearly visible through ordinary haze / thin cloud (only heavy weather hides it, above)
        float dv = Math.max(vis, 0.85f);
        // white-hot corona hugging the disc, so it glows on a pale sky too
        float hot = Math.max(0f, 1f - cover * 1.4f) * (1f - haze * 0.6f);   // how white the core is
        {
            float cr = r * 2.6f, ca = (0.55f + 0.45f * (1f - cover)) * dv;
            p.setShader(new RadialGradient(x, y, cr, new int[]{alpha(0xFFFFE9A0, 0.55f * ca), alpha(0xFFFFC85A, 0.38f * ca), alpha(0xFFFFB000, 0)},
                    new float[]{0.35f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, cr, p);
        }
        // disc: white-hot centre, yellow body, orange limb; edge softer through haze / thin cloud
        float edge = Math.min(0.6f, 0.15f * cover + 0.35f * haze);
        int hotC = lerp(core, 0xFFFFFCE6, hot * 0.92f);
        p.setShader(new RadialGradient(x, y, r * (1f + edge * 0.25f),
                new int[]{alpha(hotC, dv), alpha(lerp(core, hotC, 0.35f), dv), alpha(core, dv), alpha(limb, dv * (0.95f - edge * 0.5f)), alpha(limb, 0)},
                new float[]{0f, 0.3f, 0.62f, 0.9f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r * (1f + edge * 0.25f), p);
        p.setShader(null);
        glowX = x; glowY = y; glowR = r * 3.2f; glowOn = 1f;
    }

    /** Where the Sun's glow is on the bar (view px): the status bar's haze veil leaves a hole there and its text gets a soft dark edge; glowOn 0 when it isn't drawn. */
    static volatile float glowX, glowY, glowR, glowOn;

    /** The Moon with its real phase, lit side turned to the Sun, faint grey seas; very faint behind weather. */
    private void drawMoon(Canvas c, int w, int h, float vis, boolean faint) {
        float r = h * 0.12f;
        float[] xy = place(moonPos.alt, moonPos.az, w, h, r);
        float x = xy[0], y = xy[1];
        float k = (float) moonPos.illum;
        // which way is "up" (towards overhead = the camera) from the Moon, then turn by the bright limb angle
        float ux = camX - x, uy = camY - y;
        float ul = (float) Math.hypot(ux, uy);
        if (ul < r) { ux = 0; uy = -1; } else { ux /= ul; uy /= ul; }
        double th = Math.toRadians(-moonPos.limbFromZenith);  // east is to the left on this map
        float lx = (float) (ux * Math.cos(th) - uy * Math.sin(th)), ly = (float) (ux * Math.sin(th) + uy * Math.cos(th));
        float rot = (float) Math.toDegrees(Math.atan2(ly, lx));

        // glow, stronger near full
        p.setStyle(Paint.Style.FILL);
        float gr = r * 3f;
        p.setShader(new RadialGradient(x, y, gr, new int[]{alpha(0xFFDCE6FF, (faint ? 0.10f : 0.30f) * vis * (0.2f + k)), 0},
                null, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, gr, p);
        p.setShader(null);
        // dark side (earthshine)
        p.setColor(alpha(0xFF2C3244, (faint ? 0.25f : 0.6f) * Math.min(1f, vis * 1.6f)));
        c.drawCircle(x, y, r, p);
        // lit part, drawn with the lit side on +x, then rotated into place
        c.save();
        c.rotate(rot, x, y);
        path.reset();
        rf.set(x - r, y - r, x + r, y + r);
        path.addArc(rf, -90, 180);
        path.close();
        float ew = Math.abs(1 - 2 * k) * r;
        cut.reset();
        cut.addOval(new RectF(x - ew, y - r, x + ew, y + r), Path.Direction.CW);
        path.op(cut, k < 0.5f ? Path.Op.DIFFERENCE : Path.Op.UNION);
        p.setColor(alpha(0xFFF4F1E4, vis));
        c.drawPath(path, p);
        // a few grey seas inside the lit part, so it reads as the Moon
        if (!faint && r > 6) {
            c.save();
            c.clipPath(path);
            c.rotate(-rot, x, y);                              // seas stay fixed to the Moon's face, not the light
            p.setColor(alpha(0xFF9A9A92, 0.35f * vis));
            c.drawCircle(x - r * 0.25f, y - r * 0.30f, r * 0.30f, p);
            c.drawCircle(x + r * 0.20f, y - r * 0.05f, r * 0.24f, p);
            c.drawCircle(x - r * 0.05f, y + r * 0.35f, r * 0.18f, p);
            c.restore();
        }
        c.restore();
        if (faint) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, dens * 0.7f));
            p.setColor(alpha(0xFFE6EBF5, 0.32f));
            c.drawCircle(x, y, r, p);
            p.setStyle(Paint.Style.FILL);
        }
    }

    /** Clouds, rain, snow, fog ... Returns true when something on screen moves. */
    private boolean weather(Canvas c, int w, int h, float t, int k, float d) {
        boolean moving = false;
        int n;
        switch (k) {
            case CLOUDY: n = 4; break;
            case WINDY: n = cloudPct >= 25 ? 3 : 0; break;
            case OVERCAST: case RAIN: case STORM: case SLEET: case HAIL: n = 7; break;
            case DRIZZLE: case SNOW: n = 6; break;
            case CLEAR:                                        // a few light clouds from 10 % cover; none below
                n = cloudPct >= 10 && System.currentTimeMillis() - wmoAt < 60 * 60_000L ? Math.min(3, 1 + (cloudPct - 10) / 10) : 0;
                break;
            default: n = 0;                                    // fog, mist: no clouds drifting
        }
        if (n > 0) {
            moving = true;
            boolean dark = k == STORM || k == RAIN || k == HAIL;
            int cc = lerp(0xFF565E80, 0xFFFFFFFF, d);
            if (k != CLOUDY && k != WINDY && k != CLEAR) cc = lerp(cc, dark ? lerp(0xFF2A2E38, 0xFF6E7684, d) : lerp(0xFF4A5060, 0xFFA2AAB6, d), 0.75f);
            if (cloudTint != cc) { cloudTint = cc; cloudFilter = new android.graphics.PorterDuffColorFilter(cc, android.graphics.PorterDuff.Mode.MULTIPLY); }
            cp.setColorFilter(cloudFilter);
            int ca = k == CLEAR ? 150 : n > 4 ? 235 : 200;      // light, see-through puffs on a clear day
            if (!darkText && d > 0.4f) ca = ca * 2 / 3;            // white text: softer clouds so it stays readable
            cp.setAlpha(ca);
            float u = h / 96f, speed = k == WINDY || k == STORM ? 4f : 1f;
            for (int i = 0; i < n; i++) {
                Bitmap bm = clouds[i % clouds.length];
                float sc = cs[i] * u * (k == CLEAR ? 0.8f : n > 4 ? 1.5f : 1.1f);
                float bw = bm.getWidth() * sc * 0.9f, bh = bm.getHeight() * sc * 0.9f;
                float x = ((cx[i] * (w + bw) + t * cv[i] * speed * dens * 0.6f) % (w + bw)) - bw, y = cy[i] * h - bh * 0.55f;
                rf.set(x, y, x + bw, y + bh);
                c.drawBitmap(bm, null, rf, cp);
            }
        }
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        switch (k) {
            case RAIN: rain(c, w, h, t, 90, 260, 9, 0.55f, 0.8f); moving = true; break;
            case DRIZZLE: rain(c, w, h, t, 50, 150, 4, 0.40f, 0.6f); moving = true; break;
            case STORM:
                rain(c, w, h, t, 90, 320, 11, 0.6f, 0.9f);
                lightning(c, w, h, t);
                moving = true;
                break;
            case SNOW: flakes(c, w, h, t, rx.length, 40, 1.2f); moving = true; break;
            case SLEET:
                rain(c, w, h, t, 45, 220, 6, 0.5f, 0.7f);
                flakes(c, w, h, t, 45, 90, 0.9f);
                moving = true;
                break;
            case HAIL: hail(c, w, h, t); moving = true; break;
            case FOG: fog(c, w, h, t, 0.42f, 3); moving = true; break;
            case MIST: fog(c, w, h, t, 0.18f, 1); moving = true; break;
            case WINDY: wind(c, w, h, t); moving = true; break;
            default: break;
        }
        return moving;
    }

    private void rain(Canvas c, int w, int h, float t, int n, float speed, float len, float a, float width) {
        p.setColor(alpha(0xFFBED2EB, a));
        p.setStrokeWidth(Math.max(1f, dens * width));
        for (int i = 0; i < Math.min(n, rx.length); i++) {
            float y = (ry[i] * h + t * speed * dens) % h, x = rx[i] * w;
            c.drawLine(x, y, x - len * 0.33f * dens, y + len * dens, p);
        }
    }

    private void flakes(Canvas c, int w, int h, float t, int n, float speed, float size) {
        p.setColor(alpha(0xFFFFFFFF, 0.8f));
        for (int i = 0; i < Math.min(n, rx.length); i++) {
            int j = rx.length - 1 - i;
            float y = (ry[j] * h + t * speed * dens) % h, x = rx[j] * w + (float) Math.sin(t + j) * 4 * dens;
            c.drawCircle(x, y, dens * size, p);
        }
    }

    /** White pellets falling fast, bouncing off the bottom edge. */
    private void hail(Canvas c, int w, int h, float t) {
        p.setColor(alpha(0xFFF4F8FF, 0.9f));
        for (int i = 0; i < 60; i++) {
            float cyc = (ry[i] + t * 1.6f) % 1.2f, x = rx[i] * w;
            float y;
            if (cyc < 1f) y = cyc * h;
            else { float b = (cyc - 1f) / 0.2f; y = h - (float) Math.sin(Math.PI * b) * h * 0.18f; x += b * 6 * dens; }
            c.drawCircle(x, y, dens * 1.6f, p);
        }
    }

    /** Soft drifting grey bands. */
    private void fog(Canvas c, int w, int h, float t, float a, int bands) {
        p.setColor(alpha(0xFFC8CDD4, a * 0.6f));
        c.drawRect(0, 0, w, h, p);
        for (int i = 0; i < bands; i++) {
            float yc = h * (0.3f + 0.25f * i), bh = h * 0.35f;
            float shift = (float) Math.sin(t * 0.08f + i * 1.7f) * w * 0.15f;
            p.setShader(new LinearGradient(0, yc - bh, 0, yc + bh, new int[]{0, alpha(0xFFD5D9DE, a), 0},
                    null, Shader.TileMode.CLAMP));
            c.drawRect(-w * 0.2f + shift, yc - bh, w * 1.2f + shift, yc + bh, p);
        }
        p.setShader(null);
    }

    /** Fast thin streaks blowing across. */
    private void wind(Canvas c, int w, int h, float t) {
        p.setStrokeWidth(Math.max(1f, dens * 0.8f));
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < wlx.length; i++) {
            float len = wll[i] * w;
            float x = ((wlx[i] * (w + len) + t * (220 + i * 9) * dens) % (w + len)) - len;
            float y = wly[i] * h + (float) Math.sin(t * 2 + i) * 2 * dens;
            p.setColor(alpha(0xFFFFFFFF, 0.28f));
            c.drawLine(x, y, x + len, y, p);
        }
        p.setStrokeCap(Paint.Cap.BUTT);
    }

    /** A flash and a jagged bolt every 6-9 seconds. */
    private void lightning(Canvas c, int w, int h, float t) {
        float period = 7.5f;
        int n = (int) (t / period);
        float ph = t - n * period - (n * 37 % 3);
        if (ph < 0 || ph > 0.35f) return;
        float a = ph < 0.08f ? 1f : ph < 0.14f ? 0.3f : ph < 0.2f ? 0.8f : 1f - (ph - 0.2f) / 0.15f;
        p.setColor(alpha(0xFFE6E9FF, 0.35f * a));
        c.drawRect(0, 0, w, h, p);
        Random r = new Random(n * 7919L);
        float x = w * (0.1f + 0.8f * r.nextFloat()), y = 0;
        path.reset();
        path.moveTo(x, y);
        while (y < h) {
            y += h * (0.12f + 0.12f * r.nextFloat());
            x += (r.nextFloat() - 0.5f) * 14 * dens;
            path.lineTo(x, Math.min(y, h));
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.2f, dens * 1.3f));
        p.setColor(alpha(0xFFFFFFFF, a));
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
    }

    // ---- Clear (or partly cloudy) night: dark sky, stars, shooting stars. The Moon is drawn by onDraw.
    private void drawNightClear(Canvas c, int w, int h, float t, int k) {
        p.setStyle(Paint.Style.FILL);
        if (grad == null || gTop != 0x02030A || gH != h) {
            grad = new LinearGradient(0, 0, 0, h, 0xFF02030A, 0xFF0C1030, Shader.TileMode.CLAMP);
            gTop = 0x02030A; gBot = 0; gH = h;
        }
        p.setShader(grad);
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        realSky(c, w, h, t, k == CLOUDY ? 0.45f : 1f);
    }

    // ------------------------------------------------------------------ the real night sky

    private long skyAt, satAt;
    private int skyW, skyH;
    private float[] stX = new float[0], stY = new float[0], stM = new float[0], stA = new float[0];
    private int[] stC = new int[0];
    private final float[] plX = new float[4], plY = new float[4];
    private final boolean[] plUp = new boolean[4];
    private float[] saX = new float[0], saY = new float[0];
    private boolean[] saIss = new boolean[0];

    /** Brightest stars and the planets where they really are now (cached 20 s: they turn 0.25°/min). */
    private void skyPositions(int w, int h) {
        long ms = System.currentTimeMillis();
        if (ms - skyAt < 20_000L && w == skyW && h == skyH) return;
        skyAt = ms; skyW = w; skyH = h;
        double lst = SkyObjects.lst(ms, lon);
        // Milky Way: the galactic equator (b = 0), sampled every 3° of galactic longitude, where it really is now
        {
            int k = 120, mm = 0;
            float[] mx = new float[k], my = new float[k], mb = new float[k];
            double aN = Math.toRadians(192.85948), dN = Math.toRadians(27.12825), lN = Math.toRadians(122.93192);
            for (int i = 0; i < k; i++) {
                double l = Math.toRadians(i * 3.0);
                double sd = Math.cos(dN) * Math.cos(lN - l);                       // b = 0
                double dec = Math.asin(sd);
                double ra = aN + Math.atan2(Math.sin(lN - l), -Math.sin(dN) * Math.cos(lN - l));
                double[] aa = SkyObjects.altAz(Math.toDegrees(ra), Math.toDegrees(dec), lst, lat);
                if (aa[0] <= 1) continue;
                float[] xy = place(aa[0], aa[1], w, h, 2 * dens);
                mx[mm] = xy[0]; my[mm] = xy[1];
                // brighter towards the galactic centre (Sagittarius, l = 0) and the Cygnus arm, fainter towards l = 180
                double lc = Math.cos(l);
                mb[mm] = (float) (0.45 + 0.55 * Math.max(0, lc) + 0.15 * Math.max(0, Math.cos(l - Math.toRadians(80))))
                        * (float) Math.min(1, aa[0] / 15.0 + 0.3);
                mm++;
            }
            mwX = java.util.Arrays.copyOf(mx, mm); mwY = java.util.Arrays.copyOf(my, mm); mwB = java.util.Arrays.copyOf(mb, mm);
        }
        int n = SkyObjects.STARS.length, m = 0;
        float[] x = new float[n], y = new float[n], mg = new float[n], al = new float[n];
        int[] cl = new int[n];
        float r = 2 * dens;
        for (Object[] st : SkyObjects.STARS) {
            double[] aa = SkyObjects.altAz((Double) st[1] * 15, (Double) st[2], lst, lat);
            if (aa[0] <= 0.5) continue;
            float[] xy = place(aa[0], aa[1], w, h, r);
            x[m] = xy[0]; y[m] = xy[1]; mg[m] = ((Double) st[3]).floatValue(); al[m] = (float) aa[0];
            cl[m] = SkyObjects.color((Character) st[4]);
            m++;
        }
        stX = java.util.Arrays.copyOf(x, m); stY = java.util.Arrays.copyOf(y, m);
        stM = java.util.Arrays.copyOf(mg, m); stA = java.util.Arrays.copyOf(al, m); stC = java.util.Arrays.copyOf(cl, m);
        for (int i = 0; i < 4; i++) {
            double[] rd = SkyObjects.planetRaDec(i, ms);
            double[] aa = SkyObjects.altAz(rd[0], rd[1], lst, lat);
            plUp[i] = aa[0] > 0.5;
            if (plUp[i]) { float[] xy = place(aa[0], aa[1], w, h, r); plX[i] = xy[0]; plY[i] = xy[1]; }
        }
    }

    private final Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Tiny faint name beside an object: to its right, or to its left near the right edge. */
    private void label(Canvas c, String s, float x, float y, float gap, int w, int col) {
        lp.setTextSize(8.5f * dens);
        lp.setColor(alpha(col, 0.7f));
        lp.setShadowLayer(dens * 1.2f, 0, 0, 0xCC000000);
        float tw = lp.measureText(s);
        float tx = x + gap + 2 * dens;
        if (tx + tw > w - 2 * dens) tx = x - gap - 2 * dens - tw;
        c.drawText(s, tx, y + lp.getTextSize() * 0.35f, lp);
    }

    /** Sunlit satellites above the horizon, moved every second from their orbits. */
    private void satPositions(int w, int h) {
        long ms = System.currentTimeMillis();
        if (ms - satAt < 1_000L) return;
        satAt = ms;
        java.util.List<Sat.Tle> list = Sat.overhead;
        float[] x = new float[list.size()], y = new float[list.size()];
        boolean[] iss = new boolean[list.size()];
        int m = 0;
        double[] obs = Sat.ecef(lat, lon, 0.55);
        // the Sun's direction in the same Earth-fixed frame, for "is it in sunlight"
        double[] sun = null;
        if (sunPos != null) {
            double ra = Math.toRadians(sunPos.ra), de = Math.toRadians(sunPos.dec);
            double xi = Math.cos(de) * Math.cos(ra), yi = Math.cos(de) * Math.sin(ra), zi = Math.sin(de);
            double th = Math.toRadians(Astro.gmst(Astro.jd(ms)));
            sun = new double[]{Math.cos(th) * xi + Math.sin(th) * yi, -Math.sin(th) * xi + Math.cos(th) * yi, zi};
        }
        for (Sat.Tle t : list) {
            try {
                double[] g = Sat.propagate(t, ms);
                if (g == null) continue;
                double el = Sat.elevation(obs, lat, lon, g);
                if (el <= 1) continue;
                if (sun != null) {
                    double d = g[0] * sun[0] + g[1] * sun[1] + g[2] * sun[2];
                    if (d < 0) {
                        double px = g[0] - d * sun[0], py = g[1] - d * sun[1], pz = g[2] - d * sun[2];
                        if (Math.sqrt(px * px + py * py + pz * pz) < 6371) continue;   // in Earth's shadow: dark
                    }
                }
                float[] xy = place(el, Sat.azimuth(obs, lat, lon, g), w, h, 2 * dens);
                x[m] = xy[0]; y[m] = xy[1]; iss[m] = t.id == 25544; m++;
            } catch (Throwable ignored) { }
        }
        saX = java.util.Arrays.copyOf(x, m); saY = java.util.Arrays.copyOf(y, m); saIss = java.util.Arrays.copyOf(iss, m);
    }

    /**
     * Real stars (true colours, twinkling more near the horizon; the faint ones drop out under a bright Moon,
     * clouds or haze), the planets (steady, no twinkle) and sunlit satellites drifting across.
     */
    private float[] mwX = new float[0], mwY = new float[0], mwB = new float[0];

    private void realSky(Canvas c, int w, int h, float t, float amount) {
        skyPositions(w, h);
        p.setStyle(Paint.Style.FILL);
        // Milky Way first, under the stars: soft overlapping glows along the band (washed out by moonlight / haze)
        float mwA = amount;
        if (moonPos != null && moonPos.alt > 0) mwA *= 1f - 0.7f * (float) moonPos.illum;
        if (aqi > 150) mwA *= 0.4f; else if (aqi > 100) mwA *= 0.7f;
        if (mwA > 0.05f) {
            float rad = Math.max(h * 0.55f, 14 * dens);
            for (int i = 0; i < mwX.length; i++) {
                int a = Math.round(70 * mwA * mwB[i]);
                if (a < 3) continue;
                p.setShader(new RadialGradient(mwX[i], mwY[i], rad, new int[]{(a << 24) | 0xC9D4FF, ((a / 3) << 24) | 0xB8C4F0, 0},
                        new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                c.drawCircle(mwX[i], mwY[i], rad, p);
            }
            p.setShader(null);
            // a sprinkle of faint "unresolved stars" along the band, fixed per spot so they don't flicker
            for (int i = 0; i < mwX.length; i++) {
                int a = Math.round(170 * mwA * mwB[i]);
                if (a < 10) continue;
                for (int j = 0; j < 3; j++) {
                    int hsh = (i * 73 + j * 151) % 97;
                    float ox = ((hsh % 13) - 6) * dens * 1.6f, oy = (((hsh / 13) % 7) - 3) * dens * 1.4f;
                    p.setColor((a << 24) | 0xE8EEFF);
                    c.drawCircle(mwX[i] + ox, mwY[i] + oy, dens * 0.5f, p);
                }
            }
        }
        // a display, not a light-pollution simulator: moon / cloud / haze thin the field only gently
        float lim = 3.0f;
        if (moonPos != null && moonPos.alt > 0) lim -= 0.8f * (float) moonPos.illum;
        if (amount < 1f) lim -= 0.6f;
        if (aqi > 150) lim -= 0.5f; else if (aqi > 100) lim -= 0.25f;
        float amt = Math.max(amount, 0.75f);
        for (int i = 0; i < stX.length; i++) {
            float mg = stM[i];
            if (mg > lim) continue;
            float low = Math.max(0.55f, Math.min(1f, stA[i] / 12f));           // dimmer near the horizon
            // gentle twinkle: at one frame a second a big swing would look like blinking
            float tw = 1f - (0.05f + 0.10f * (1f - low)) * (0.5f + 0.5f * (float) Math.sin(t * (0.7f + (i % 5) * 0.23f) + i * 1.7f));
            float a = Math.max(0.6f, Math.min(1f, (lim - mg) / 1.5f + 0.5f)) * amt * low * tw;
            float r = dens * 1.3f * Math.max(0.9f, Math.min(2.4f, 1.6f - 0.3f * mg));
            if (mg < 1.6f) {
                p.setShader(new RadialGradient(stX[i], stY[i], r * 3.2f, new int[]{alpha(stC[i], 0.4f * a), 0}, null, Shader.TileMode.CLAMP));
                c.drawCircle(stX[i], stY[i], r * 3.2f, p);
                p.setShader(null);
            }
            p.setColor(alpha(stC[i], a));
            c.drawCircle(stX[i], stY[i], r, p);
            if (mg < 0.6f) {   // the brightest stars get a small 4-point sparkle
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f, dens * 0.7f));
                p.setColor(alpha(stC[i], 0.55f * a));
                float sl = r * 3.6f;
                c.drawLine(stX[i] - sl, stY[i], stX[i] + sl, stY[i], p);
                c.drawLine(stX[i], stY[i] - sl, stX[i], stY[i] + sl, p);
                p.setStyle(Paint.Style.FILL);
            }
        }
        // planets: only once the Sun is well down
        if (sunPos == null || sunPos.alt < -4) {
            for (int i = 0; i < 4; i++) {
                if (!plUp[i]) continue;
                float mg = SkyObjects.PLANET_MAG[i];
                float r = dens * 1.25f * Math.max(1.8f, Math.min(2.8f, 1.95f - 0.2f * mg));
                float a = Math.max(0.85f, amount);
                p.setShader(new RadialGradient(plX[i], plY[i], r * 3.5f,
                        new int[]{alpha(SkyObjects.PLANET_COLOR[i], 0.65f * a), 0}, null, Shader.TileMode.CLAMP));
                c.drawCircle(plX[i], plY[i], r * 3.5f, p);
                p.setShader(null);
                p.setColor(alpha(SkyObjects.PLANET_COLOR[i], a));
                c.drawCircle(plX[i], plY[i], r, p);
                // a thin ring and a tiny name, so a planet can't be mistaken for a star
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f, dens * 0.6f));
                p.setColor(alpha(SkyObjects.PLANET_COLOR[i], 0.4f));
                c.drawCircle(plX[i], plY[i], r * 2.3f, p);
                p.setStyle(Paint.Style.FILL);
                label(c, SkyObjects.PLANETS[i], plX[i], plY[i], r * 2.3f, w, SkyObjects.PLANET_COLOR[i]);
            }
        }
        // satellites: only sunlit ones against a dark sky, like the real thing
        if (sunPos == null || sunPos.alt < -6) {
            satPositions(w, h);
            for (int i = 0; i < saX.length; i++) {
                float r = dens * (saIss[i] ? 2.0f : 1.2f);
                p.setColor(alpha(saIss[i] ? 0xFFFFFFFF : 0xFFE8EEFF, saIss[i] ? 1f : 0.85f));
                c.drawCircle(saX[i], saY[i], r, p);
                if (saIss[i]) label(c, "ISS", saX[i], saY[i], r * 1.5f, w, 0xFFFFFFFF);
            }
        }
    }
}
