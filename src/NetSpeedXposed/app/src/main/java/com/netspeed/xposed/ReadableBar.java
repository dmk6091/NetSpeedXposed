package com.netspeed.xposed;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.TrafficStats;
import android.os.SystemClock;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The "readable" status bar: one view that draws four evenly spaced lines of large text.
 *
 *   left   : clock, seconds line, date, sunrise - sunset
 *   next   : CPU / battery temperature, watts, amps
 *   next   : download / upload speed, notification count, Bluetooth device
 *   centre : left empty (camera)
 *   right  : one block per SIM (network, operator, signal, band, distance, voice dot; the SIM that
 *            carries mobile data sits in a highlighted pill with live ↓↑), weather under them,
 *            then the portrait battery
 *
 * Everything is drawn in a 1280 x 124 design space that is scaled to the bar. When a line is too
 * long the least important item drops out instead of anything being cut or squeezed.
 * DualRow.Updater fills in the model fields once a second; the old layout keeps running hidden.
 */
final class ReadableBar extends View {

    /** One SIM's block. */
    static final class SimInfo {
        int slot;
        String rat;        // "5G", "4G+" ... null = unknown
        boolean inService;
        int dbm = Bands.NO_DBM;
        String opLetter;   // operator badge
        boolean roaming;
        int opColor;
        String band;       // "n78"
        String dist;       // "1.0km" or null
        String voice;      // "NR", "LTE", "WiFi" when calls use IMS, else null
        boolean data;      // carries mobile data
        boolean dataOn;    // its own mobile-data switch is on (network type shows for every such SIM)
        boolean inCall;
        int cells = -1;
        boolean eco;       // NetSwitch eco mode (5G off to save battery)
        float snr = Float.NaN;
        String area;       // locality of the tower ("Ravet"), null = unknown
        String netName;    // network's own broadcast name when it differs from the SIM's ("Dolphin"), else null
        String circle;     // circle code when on another circle's network ("KA"), else null
        String nrMode;     // "SA" / "NSA·B3" / null
        int cqi = -1;      // channel quality 0..15, -1 unknown
        int rsrq = Integer.MIN_VALUE;
        int pci = -1, arfcn = -1, tac = -1;   // serving cell details, -1 unknown
        int bw = -1;                          // channel width in MHz, -1 unknown
        String nbr;                           // strongest neighbour: "B3 \u221296", null none
        int nbrDbm = Integer.MIN_VALUE;
        long dataToday = -1;                  // bytes on this SIM since midnight
        String bearing;                       // "↗NE" from you to the tower, null unknown
        int hoToday;                          // tower switches today for this SIM
        String onCell;                        // time on this tower: "45s", "3m", "1h05"
        String trend;                         // signal change over the last minute: "+4dB" / "−5dB", null steady
        String site;                          // site-sector id, null unknown
        String towerH;                        // tower ground height vs you: "+14 m", null unknown
        boolean newCell;                      // moved to a different tower in the last 2 minutes: small blue "N" box on the signal line
        boolean towerEst;                     // tower position only a rough guess: tower line drawn grey with a ~
        int los = -1;                         // ground sticking up above the line to the tower, metres; 0 clear; -1 unknown
    }

    // ------------------------------------------------------------------ model (main thread)
    int color = 0xFFFFFFFF;
    Typeface base;
    float cpu = Float.NaN, bat = Float.NaN, amps = Float.NaN, watts = Float.NaN;
    int powerColor = 0xFFFFD60A;
    boolean charging;
    int notifAll = -1, notifSilent;
    boolean btOn;
    List<SysState.Dev> devs = new ArrayList<>();
    int ringer;                    // 0 none, 1 vibrate, 2 silent, 3 DND
    long alarmAt = -1;
    boolean wifiOn;
    int wifiLevel, wifiRssi, wifiFreq;
    List<Bitmap> tray = new ArrayList<>();
    List<SimInfo> sims = new ArrayList<>();
    boolean dataIn, dataOut;
    String wxTemp;
    Drawable wxIcon;
    int wxCode = -1;   // OmniJaws / Yahoo condition code behind the icon
    boolean wxTint = true;
    float wxMax = Float.NaN, wxMin = Float.NaN, wxFeels = Float.NaN, wxWind = Float.NaN, wxUv = Float.NaN;
    int wxHum = -1, wxRain = -1, wxAqi = -1;
    String wxArrow = "";
    int battPct = -1;
    private boolean battPctMoved;   // the phone battery level is drawn after the BAT temperature: not again in the right column
    double[] latLon = {18.62, 73.80};
    float gpu = Float.NaN;
    float ghz = Float.NaN;
    long ramFree = -1, ramTotal = -1;
    float wxVis = Float.NaN;
    int wxCloud = -1;
    int hz = -1;
    double[] fix;
    float groundElev = Float.NaN;
    float quakeMag = Float.NaN;
    int quakeKm = -1;
    long quakeAt = 0;
    String quakeCc;
    /** Strip above the clock: "ISS over Nagpur", "No ISS pass", plus a satellite in your sky; satLive = something is up. */
    long m4g = 0, m5g = 0;                        // this month's 4G / 5G data
    long used4g = 0, limit4g = 1, used5g = 0;   // today's 4G data and the fallback limit
    int nrShare = -1;               // % of today on 5G, -1 unknown
    String satText = "";
    /** Where the satellite strip may carry on after the camera hole (x range), 0 when it can't. */
    private float stripC0 = 0, stripC1 = 0;
    boolean satLive;
    String satIssS = "", satNameS = "", satCityS = "";
    /** Where the left row 4 ended and the right (weather) row 4 starts, for the centre item. */
    private float l4End = 0, w4Start = 1e9f, healthEnd = 0;
    /** How far right row 4 is free for the quake line: under SIM boxes whose area text stays on one line. */
    private float q4End = 1e9f, q3End = 0;
    /** What the moon / quake line did last frame (for the NetSpeed app's diagnostics box). */
    static volatile String skyDbg = "";

    // ------------------------------------------------------------------ internals
    private static final float DESIGN_W = 1280f, DESIGN_H = 124f;
    private static final float CY1 = 16, CY2 = 46, CY3 = 76, CY4 = 106;   // line centres, design px

    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final RectF rf = new RectF();
    private Typeface bold = Typeface.DEFAULT_BOLD, semi = Typeface.DEFAULT_BOLD, reg = Typeface.DEFAULT;
    private Typeface builtFrom;
    private boolean dk = true;

    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEE d MMM", Locale.getDefault());
    private final SimpleDateFormat t24 = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat t12 = new SimpleDateFormat("h:mm", Locale.getDefault());
    private final SimpleDateFormat ampm = new SimpleDateFormat("a", Locale.getDefault());
    private boolean h24 = true;
    private long h24CheckedAt;

    private long lastRx = -1, lastTx = -1, lastAt;
    private long down, up;
    private final long[] hDown = new long[90], hUp = new long[90];   // last 90 s of speed, for the graph behind the meter
    private int hPos, hN;
    private final int[] cH = new int[60];   // CPU load, last 60 s
    private int cPos, cN;
    private long cSec;
    private String sunTxt = "";
    private long sunAt;

    private float s = 1f, oy = 0f, vs = 1f, W = DESIGN_W;

    ReadableBar(Context c) {
        super(c);
        setWillNotDraw(false);
    }

    // ------------------------------------------------------------------ tick (seconds hand, clock)

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            if (isAttachedToWindow()) {
                long now = System.currentTimeMillis();
                postDelayed(this, 1000 - now % 1000 + 5);
            }
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(tick);
        post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    /** Called once a second by the updater: network speed from the traffic counters. */
    void sampleSpeed() {
        long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes();
        long now = SystemClock.elapsedRealtime();
        if (lastAt > 0 && rx >= 0 && tx >= 0 && lastRx >= 0) {
            long dt = Math.max(1, now - lastAt);
            down = Math.max(0, (rx - lastRx) * 1000 / dt);
            up = Math.max(0, (tx - lastTx) * 1000 / dt);
            hDown[hPos] = down;
            hUp[hPos] = up;
            hPos = (hPos + 1) % hDown.length;
            if (hN < hDown.length) hN++;
        }
        lastRx = rx;
        lastTx = tx;
        lastAt = now;
    }

    // ------------------------------------------------------------------ text items

    private static final int G_BELL = 1, G_BT = 2, G_DROP = 3, G_UMB = 4, G_WIFI = 5, G_ALARM = 6,
            G_VIB = 7, G_SIL = 8, G_DND = 9, G_SUN = 10, G_SPACE = 99;

    private static final class Part {
        String text;
        float size;
        Typeface tf;
        int col;
        int glyph;
        float w;
    }

    /** A group that stays together on a line; low priority groups drop out first when it is full. */
    private final class Item {
        final int prio;
        final float ref;
        final List<Part> parts = new ArrayList<>();
        float width = -1;

        Item(int prio, float ref) {
            this.prio = prio;
            this.ref = ref;
        }

        Item t(String text, float size, Typeface tf, int col) {
            Part p = new Part();
            p.text = text;
            p.size = size;
            p.tf = tf;
            p.col = col;
            tp.setTypeface(tf);
            tp.setTextSize(size);
            p.w = tp.measureText(text);
            parts.add(p);
            return this;
        }

        Item g(int glyph, float w, int col) {
            Part p = new Part();
            p.glyph = glyph;
            p.w = w;
            p.col = col;
            parts.add(p);
            return this;
        }

        Item sp(float w) {
            return g(G_SPACE, w, 0);
        }

        float w() {
            if (width < 0) {
                float x = 0;
                for (Part p : parts) x += p.w;
                width = x;
            }
            return width;
        }
    }

    private float drawItem(Canvas c, Item it, float x, float cy) {
        float base = cy + it.ref * 0.36f;
        for (Part p : it.parts) {
            if (p.glyph == 0) {
                tp.setTypeface(p.tf);
                tp.setTextSize(p.size);
                tp.setColor(p.col);
                // over the Sun's bright glow: a soft dark edge keeps the text readable
                boolean sh = SkyView.glowOn > 0f && Math.abs(x + p.w * 0.5f - SkyView.glowX) < SkyView.glowR + p.w * 0.5f;
                if (sh) tp.setShadowLayer(4f, 0f, 1f, 0xB0000000);
                c.drawText(p.text, x, base, tp);
                if (sh) tp.clearShadowLayer();
            } else if (p.glyph != G_SPACE) {
                glyph(c, p.glyph, x, cy, p.w, p.col);
            }
            x += p.w;
        }
        return x;
    }

    /** Draws left to right from x, dropping the lowest priority item until it fits before maxX. */
    private float drawLine(Canvas c, List<Item> items, float x, float cy, float maxX, float gap) {
        List<Item> use = new ArrayList<>(items);
        while (use.size() > 1) {
            float total = gap * (use.size() - 1);
            for (Item it : use) total += it.w();
            if (x + total <= maxX) break;
            Item low = use.get(0);
            for (Item it : use) if (it.prio < low.prio) low = it;
            use.remove(low);
        }
        float end = x;
        for (int i = 0; i < use.size(); i++) {
            end = drawItem(c, use.get(i), end, cy);
            if (i < use.size() - 1) end += gap;
        }
        return end;
    }

    /** Right-aligned at rightX, but only as much as fits to the right of minX. */
    private void drawTail(Canvas c, List<Item> items, float rightX, float cy, float minX, float gap) {
        List<Item> use = new ArrayList<>(items);
        while (!use.isEmpty()) {
            float total = gap * (use.size() - 1);
            for (Item it : use) total += it.w();
            if (rightX - total >= minX) break;
            Item low = use.get(0);
            for (Item it : use) if (it.prio < low.prio) low = it;
            use.remove(low);
        }
        float total = gap * Math.max(0, use.size() - 1);
        for (Item it : use) total += it.w();
        float x = rightX - total;
        for (int i = 0; i < use.size(); i++) {
            x = drawItem(c, use.get(i), x, cy);
            if (i < use.size() - 1) x += gap;
        }
    }

    /** Where drawTail would start the line (same dropping rule). */
    private float tailStart(List<Item> items, float rightX, float minX, float gap) {
        List<Item> use = new ArrayList<>(items);
        while (!use.isEmpty()) {
            float total = gap * (use.size() - 1);
            for (Item it : use) total += it.w();
            if (rightX - total >= minX) return rightX - total;
            Item low = use.get(0);
            for (Item it : use) if (it.prio < low.prio) low = it;
            use.remove(low);
        }
        return rightX;
    }

    // ------------------------------------------------------------------ colours

    private int teal() { return SysViews.teal(dk); }
    private int blue() { return SysViews.blue(dk); }
    private int green() { return SysViews.green(dk); }
    private int yellow() { return SysViews.yellow(dk); }
    private int orange() { return SysViews.orange(dk); }
    private int red() { return SysViews.red(dk); }
    private int purple() { return SysViews.purple(dk); }
    /** Secondary text (units, labels): light grey on a dark bar so it stays readable, dimmed text on a light one. */
    private int grey() { return dk ? 0xFFCDCDCD : SysViews.dim(color); }

    private static int fade(int col, float k) {
        int a = Math.round(((col >>> 24) & 0xFF) * k);
        return (col & 0x00FFFFFF) | (a << 24);
    }

    private int heat(float t, float warm, float hot, float burn) {
        return t >= burn ? red() : t >= hot ? orange() : t >= warm ? yellow() : color;
    }

    private int dbmColor(int dbm) {
        return dbm >= -95 ? green() : dbm >= -105 ? yellow() : dbm >= -115 ? orange() : red();
    }

    // ------------------------------------------------------------------ drawing

    private void fonts() {
        if (builtFrom == base && bold != null) return;
        builtFrom = base;
        Typeface b = base != null ? base : Typeface.DEFAULT;
        try {
            bold = Typeface.create(b, 700, false);
            semi = Typeface.create(b, 600, false);
            reg = Typeface.create(b, 500, false);
        } catch (Throwable t) {
            bold = Typeface.create(b, Typeface.BOLD);
            semi = bold;
            reg = b;
        }
    }

    private long brightAt = 0;
    private int brightA = 255;

    /**
     * Bar opacity (140..255) that follows the screen brightness you set: full from half brightness up,
     * easing down to ~55 % at the lowest, so the bar never glares in a dark room. Checked every 2 s.
     */
    private int brightAlpha() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - brightAt < 2000) return brightA;
        brightAt = now;
        try {
            float frac = -1;
            String src = "";
            // the display's real brightness right now (follows auto-brightness and your slider)
            try {
                Object bi = getContext().getDisplay().getClass().getMethod("getBrightnessInfo").invoke(getContext().getDisplay());
                if (bi != null) {
                    Class<?> k = bi.getClass();
                    float br = k.getField("brightness").getFloat(bi), mn = k.getField("brightnessMinimum").getFloat(bi),
                            mx = k.getField("brightnessMaximum").getFloat(bi);
                    if (mx > mn) {
                        frac = (br - mn) / (mx - mn);
                        src = "display " + br;
                    }
                }
            } catch (Throwable ignored) { }
            if (frac < 0) {
                android.content.ContentResolver cr = getContext().getContentResolver();
                try {
                    frac = android.provider.Settings.System.getFloat(cr, "screen_brightness_float");   // 0..1 where available
                    src = "setting float " + frac;
                } catch (Throwable e) {
                    int v = android.provider.Settings.System.getInt(cr, "screen_brightness", 255);
                    int max = 255;
                    try {
                        android.os.PowerManager pm = (android.os.PowerManager) getContext().getSystemService(android.content.Context.POWER_SERVICE);
                        max = (Integer) pm.getClass().getMethod("getMaximumScreenBrightnessSetting").invoke(pm);
                    } catch (Throwable ignored) { }
                    frac = v / (float) Math.max(1, max);
                    src = "setting " + v + "/" + max;
                }
            }
            frac = Math.max(0f, Math.min(1f, frac));
            int na = Math.round(140 + 115 * Math.min(1f, frac / 0.5f));
            if (Math.abs(na - brightA) >= 8)
                de.robv.android.xposed.XposedBridge.log("NetSpeedXposed: [dim] brightness " + Math.round(frac * 100) + "% (" + src + ") -> bar opacity "
                        + Math.round(na * 100 / 255f) + "%");
            brightA = na;
        } catch (Throwable t) {
            brightA = 255;
        }
        return brightA;
    }

    @Override
    protected void onDraw(Canvas c) {
        int layer = -1;
        try {
            if (Cfg.b(Cfg.DIM_NIGHT, Cfg.D_DIM_NIGHT)) {
                int a = brightAlpha();
                if (a < 250) layer = c.saveLayerAlpha(0, 0, getWidth(), getHeight(), a);
            }
        } catch (Throwable ignored) { layer = -1; }
        try {
            draw2(c);
        } catch (Throwable t) {
            // never take System UI down over a drawing slip
        }
        if (layer >= 0) c.restoreToCount(layer);
    }

    private void draw2(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        fonts();
        dk = SysViews.dark(color);
        if (pro && chips) {
            drawChips(c, w, h);
            return;
        }
        if (pro) {
            drawPro(c, w, h);
            return;
        }
        s = Math.min(w / DESIGN_W, h / DESIGN_H);
        W = w / s;
        oy = -3f;
        vs = Math.max(1f, Math.min(1.36f, (h / s) / DESIGN_H));
        c.save();
        try {
            c.scale(s, s);
            // each part on its own, so one slip doesn't blank the others
            try { drawClock(c); } catch (Throwable ignored) { }
            try { drawDevice(c); } catch (Throwable ignored) { }
            try { drawActivity(c); } catch (Throwable ignored) { }
            try { drawSims(c); } catch (Throwable ignored) { }
        } finally {
            c.restore();
        }
    }

    private float y(float line) {
        return oy + line * vs;
    }

    /** Plain text, baseline set so that cy is the visual middle of the digits. */
    private float text(Canvas c, String str, float x, float cy, float size, Typeface tf, int col) {
        tp.setTypeface(tf);
        tp.setTextSize(size);
        tp.setColor(col);
        c.drawText(str, x, cy + size * 0.36f, tp);
        return x + tp.measureText(str);
    }

    private float tw(String str, float size, Typeface tf) {
        tp.setTypeface(tf);
        tp.setTextSize(size);
        return tp.measureText(str);
    }

    // ---- clock, seconds line, date, sun

    private void drawClock(Canvas c) {
        long now = System.currentTimeMillis();
        if (now - h24CheckedAt > 30_000) {
            h24CheckedAt = now;
            try {
                h24 = true;   // always 24-hour
            } catch (Throwable ignored) { }
        }
        Date d = new Date(now);
        String time = (h24 ? t24 : t12).format(d);
        float x = 22;
        float end = text(c, time, x, y(38), 60, bold, color);
        if (!h24 && end + 4 + 30 < 210) text(c, ampm.format(d).toLowerCase(Locale.getDefault()), end + 4, y(52), 20, semi, grey());
        // seconds line
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(SysViews.faint(color));
        rf.set(24, y(70), 182, y(73));
        c.drawRect(rf, gp);
        gp.setColor(teal());
        rf.set(24, y(70), 24 + 158 * ((now % 60_000L) / 60_000f), y(73));
        c.drawRect(rf, gp);
        text(c, dateFmt.format(d), 24, y(90), 25, semi, color);
        // sunrise - sunset, refreshed every few minutes
        if (now - sunAt > 5 * 60_000L || sunTxt.isEmpty()) {
            sunAt = now;
            try {
                double[] sun = SunTimes.today(latLon[0], latLon[1]);
                sunTxt = SunTimes.hhmm(sun[0]) + " – " + SunTimes.hhmm(sun[1]);
            } catch (Throwable t) {
                sunTxt = "";
            }
        }
        if (!sunTxt.isEmpty()) {
            glyph(c, G_SUN, 24, y(112), 17, orange());
            text(c, sunTxt, 50, y(112), 24, bold, orange());
        }
    }

    // ---- CPU / battery temperature, watts, amps

    private void drawDevice(Canvas c) {
        float x = 214;
        if (!Float.isNaN(cpu)) {
            int col = heat(cpu, 45, 55, 65);
            text(c, "CPU", x, y(CY1), 20, semi, teal());
            text(c, Math.round(cpu) + "°", x + 52, y(CY1), 29, bold, col);
        }
        if (!Float.isNaN(bat)) {
            int col = heat(bat, 38, 42, 45);
            text(c, "BAT", x, y(CY2), 20, semi, teal());
            text(c, Math.round(bat) + "°", x + 52, y(CY2), 29, bold, col);
        }
        if (!Float.isNaN(watts) && !Float.isNaN(amps)) {
            float aw = Math.abs(watts);
            String ws = (amps > 0 ? "+" : "\u2212") + String.format(Locale.US, aw < 10 ? "%.1f" : "%.0f", aw);
            float e = text(c, ws, x, y(CY3), 29, bold, powerColor);
            text(c, "W", e + 1, y(CY3), 22, bold, powerColor);
            String as = (amps > 0 ? "+" : "\u2212") + String.format(Locale.US, "%.2f", Math.abs(amps));
            e = text(c, as, x, y(CY4), 25, semi, powerColor);
            text(c, "A", e + 1, y(CY4), 19, semi, powerColor);
        }
    }

    // ---- speed, notifications, Bluetooth

    private static String[] fmtSpeed(long bps) {
        if (bps < 1024) return new String[]{String.valueOf(bps), "B/s"};
        double kb = bps / 1024.0;
        if (kb < 100) return new String[]{String.format(Locale.US, "%.1f", kb), "K/s"};
        if (kb < 1024) return new String[]{String.format(Locale.US, "%.0f", kb), "K/s"};
        double mb = kb / 1024.0;
        if (mb < 100) return new String[]{String.format(Locale.US, "%.1f", mb), "M/s"};
        return new String[]{String.format(Locale.US, "%.0f", mb), "M/s"};
    }

    private void speedLine(Canvas c, boolean isDown, long bps, float x, float cy) {
        boolean idle = bps < 1024;
        int arrow = idle ? grey() : isDown ? green() : blue();
        int val = idle ? grey() : bps >= (10L << 20) ? teal() : color;
        String[] f = fmtSpeed(bps);
        float e = text(c, isDown ? "↓" : "↑", x, cy, 29, bold, arrow);
        e = text(c, f[0], e + 3, cy, 28, bold, val);
        text(c, f[1], e + 1, cy, 20, semi, grey());
    }

    private void drawActivity(Canvas c) {
        float x = 344;
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(SysViews.faint(color));
        rf.set(328, y(2), 330.5f, y(118));
        c.drawRect(rf, gp);
        float limit = W / 2f - 100;
        speedLine(c, true, down, x, y(CY1));
        speedLine(c, false, up, x, y(CY2));

        // bell + count
        glyph(c, G_BELL, x, y(CY3), 26, color);
        String n = notifAll < 0 ? "?" : String.valueOf(notifAll);
        int ncol = notifAll == 0 ? grey() : color;
        float e = text(c, n, x + 32, y(CY3), 29, bold, ncol);
        if (notifSilent > 0) text(c, "(" + notifSilent + ")", e + 6, y(CY3), 22, semi, grey());

        // Bluetooth
        if (btOn) {
            glyph(c, G_BT, x, y(CY4), 22, devs.isEmpty() ? grey() : blue());
            float dx = x + 32;
            List<Item> items = new ArrayList<>();
            for (int i = 0; i < devs.size() && i < 3; i++) {
                SysState.Dev d = devs.get(i);
                int kc = d.kind == 'W' ? blue() : d.kind == 'H' ? purple() : d.kind == 'P' ? green() : color;
                Item it = new Item(10 - i * 3, 27).t(String.valueOf(d.kind), 27, bold, kc).sp(7);
                if (d.battery >= 0) it.t(d.battery + "%", 27, bold, SysViews.levelColor(d.battery, dk));
                else it.t("--", 27, bold, grey());
                items.add(it);
            }
            if (devs.size() > 3) items.add(new Item(1, 22).t("+" + (devs.size() - 3), 22, semi, grey()));
            drawLine(c, items, dx, y(CY4), limit, 14);
        }
    }

    // ---- SIM blocks, weather, tails

    private void drawSims(Canvas c) {
        float x0 = W / 2f + 98;
        float colW = 240;
        float rightEdge = W - 18;
        float wxMaxX = rightEdge;
        for (int i = 0; i < sims.size() && i < 2; i++) drawSim(c, sims.get(i), x0 + i * colW);

        // weather: line 3 (icon, temp, feels, high/low, rain) and line 4 (humidity, wind, AQI, UV)
        float end3 = x0, end4 = x0;
        if (wxTemp != null) {
            if (wxIcon != null) {
                try {
                    wxIcon.setBounds(Math.round(x0), Math.round(y(CY3) - 17), Math.round(x0 + 34), Math.round(y(CY3) + 17));
                    if (wxTint) wxIcon.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
                    else wxIcon.clearColorFilter();
                    wxIcon.draw(c);
                } catch (Throwable ignored) { }
            }
            List<Item> l3 = new ArrayList<>();
            float tempNum = Float.NaN;
            try {
                tempNum = Float.parseFloat(wxTemp);
            } catch (Throwable ignored) { }
            l3.add(new Item(10, 30).t(wxTemp + "°", 30, bold, color));
            end3 = drawLine(c, l3, x0 + 44, y(CY3), wxMaxX, 16);

            end4 = x0;
        }

        // tails: whatever is on, right-aligned on lines 3 and 4, only if there is room
        List<Item> t3 = new ArrayList<>();
        if (alarmAt > 0) {
            String f = alarmAt - System.currentTimeMillis() < 20 * 3600_000L ? "HH:mm" : "EEE HH:mm";
            String lbl = new SimpleDateFormat(f, Locale.US).format(new Date(alarmAt));
            t3.add(new Item(5, 24).g(G_ALARM, 24, color).sp(5).t(lbl, 24, semi, color));
        }
        if (ringer != 0) {
            int g = ringer == 1 ? G_VIB : ringer == 2 ? G_SIL : G_DND;
            t3.add(new Item(7, 24).g(g, 26, ringer == 1 ? orange() : ringer == 2 ? red() : purple()));
        }
        drawTail(c, t3, rightEdge, y(CY3), end3 + 18, 14);

        List<Item> t4 = new ArrayList<>();
        for (int i = 0; i < tray.size() && i < 4; i++) {
            final Bitmap b = tray.get(i);
            float ih = 24, iw = b.getHeight() == 0 ? ih : ih * b.getWidth() / b.getHeight();
            t4.add(new Item(2 + i, 20).g(1000 + i, iw, grey()));
        }
        if (wifiOn) {
            int lc = wifiLevel >= 3 ? green() : wifiLevel == 2 ? yellow() : wifiLevel == 1 ? orange() : red();
            String band = SysState.band(wifiFreq).replace(" GHz", "G");
            Item it = new Item(9, 22).g(G_WIFI, 28, lc).sp(5).t(band, 21, semi, grey()).sp(6);
            it.t(String.valueOf(wifiRssi), 23, bold, lc);
            t4.add(it);
        }
        drawTailWithBitmaps(c, t4, rightEdge, y(CY4), end4 + 18, 12);
    }

    /** Like drawTail, but glyph ids 1000+ are tray bitmaps. */
    private void drawTailWithBitmaps(Canvas c, List<Item> items, float rightX, float cy, float minX, float gap) {
        List<Item> use = new ArrayList<>(items);
        while (!use.isEmpty()) {
            float total = gap * (use.size() - 1);
            for (Item it : use) total += it.w();
            if (rightX - total >= minX) break;
            Item low = use.get(0);
            for (Item it : use) if (it.prio < low.prio) low = it;
            use.remove(low);
        }
        float total = gap * Math.max(0, use.size() - 1);
        for (Item it : use) total += it.w();
        float x = rightX - total;
        for (int i = 0; i < use.size(); i++) {
            Item it = use.get(i);
            Part p = it.parts.get(0);
            if (p.glyph >= 1000) {
                int bi = p.glyph - 1000;
                if (bi < tray.size()) {
                    Bitmap b = tray.get(bi);
                    ip.setColorFilter(new PorterDuffColorFilter(grey(), PorterDuff.Mode.SRC_IN));
                    rf.set(x, cy - 12, x + p.w, cy + 12);
                    c.drawBitmap(b, null, rf, ip);
                }
                x += p.w;
            } else {
                x = drawItem(c, it, x, cy);
            }
            if (i < use.size() - 1) x += gap;
        }
    }

    private void drawSim(Canvas c, SimInfo si, float x) {
        float k = si.data ? 1f : 0.55f;
        if (si.data) {
            gp.setStyle(Paint.Style.FILL);
            gp.setColor(fade(color, 0.16f));
            rf.set(x - 12, y(2), x + 228, y(64));
            c.drawRoundRect(rf, 14, 14, gp);
        }
        float cy1 = y(CY1), cy2 = y(CY2);
        // line 1: network, signal (dBm), number of cells heard
        String rat = si.inService && si.rat != null && !si.rat.isEmpty() ? si.rat : "\u2014";
        String dbm = si.inService && si.dbm != Bands.NO_DBM ? String.valueOf(si.dbm) : null;
        String cells = si.cells > 0 ? String.valueOf(si.cells) : null;
        float total = tw(rat, 36, bold) + (si.eco ? 8 + tw("E", 26, bold) : 0)
                + (dbm != null ? 10 + tw(dbm, 28, bold) : 0)
                + (cells != null ? 12 + tw(cells, 26, bold) : 0);
        float f = total > 224 ? 224 / total : 1f;
        float e = text(c, rat, x, cy1, 36 * f, bold, fade(color, k));
        if (si.eco) e = text(c, "E", e + 8 * f, cy1, 26 * f, bold, fade(green(), k));
        if (dbm != null) e = text(c, dbm, e + 10 * f, cy1, 28 * f, bold, fade(dbmColor(si.dbm), k));
        if (cells != null) {
            text(c, cells, e + 12 * f, cy1, 26 * f, bold, fade(color, k));
        }

        // line 2: small boxed Vo badge (VoNR / VoLTE / VoWiFi), band + distance, live arrows on the data SIM
        float bx0 = x;
        if (si.voice != null) {
            String vo = "Vo" + si.voice;
            float vw = tw(vo, 16, bold) + 10;
            gp.setStyle(Paint.Style.STROKE);
            gp.setStrokeWidth(2f);
            gp.setColor(fade(green(), k));
            rf.set(x + 1, cy2 - 11, x + 1 + vw, cy2 + 11);
            c.drawRoundRect(rf, 5, 5, gp);
            text(c, vo, x + 6, cy2, 16, bold, fade(green(), k));
            bx0 = x + vw + 8;
        }
        String band = si.band != null ? si.band : "?";
        float avail = 226 - (bx0 - x) - (si.data ? 54 : 0);
        String full = si.dist != null ? band + " · " + si.dist : band;
        String shown = tw(full, 22, semi) <= avail ? full : band;
        float fs = 22;
        float sw = tw(shown, fs, semi);
        if (sw > avail) fs = 22 * avail / sw;
        float ex = text(c, shown, bx0, cy2, fs, semi, fade(blue(), k));
        if (si.data) {
            float ax = ex + 10;
            ax = text(c, "↓", ax, cy2, 24, bold, dataIn ? green() : color);
            text(c, "↑", ax - 2, cy2, 24, bold, dataOut ? blue() : color);
        }
    }

    // ---- battery

    // ------------------------------------------------------------------ glyphs

    /** Small symbols, drawn around cy, starting at x, w wide. */
    private void glyph(Canvas c, int id, float x, float cy, float w, int col) {
        float cx = x + w / 2f;
        switch (id) {
            case G_BELL: {
                float h = w * 0.92f;
                gp.setStyle(Paint.Style.FILL);
                gp.setColor(col);
                float bw = h * 0.78f;
                rf.set(cx - bw / 2, cy - h * 0.5f, cx + bw / 2, cy + h * 0.3f);
                c.drawArc(rf, 180, 180, true, gp);
                c.drawRect(cx - bw / 2, cy - h * 0.1f, cx + bw / 2, cy + h * 0.28f, gp);
                c.drawRect(cx - bw * 0.66f, cy + h * 0.2f, cx + bw * 0.66f, cy + h * 0.34f, gp);
                c.drawCircle(cx, cy + h * 0.44f, h * 0.1f, gp);
                break;
            }
            case G_BT: {
                float h = 30;
                stroke(3f, col);
                float x0 = x + 2, x1 = x + w - 2, mx = (x0 + x1) / 2f;
                path.reset();
                path.moveTo(x0, cy - h * 0.22f);
                path.lineTo(x1, cy + h * 0.2f);
                path.lineTo(mx, cy + h * 0.45f);
                path.lineTo(mx, cy - h * 0.45f);
                path.lineTo(x1, cy - h * 0.2f);
                path.lineTo(x0, cy + h * 0.22f);
                c.drawPath(path, gp);
                break;
            }
            case G_WAVE: {
                stroke(2.6f, col);
                float hw = w * 0.42f;
                for (int k = 0; k < 2; k++) {
                    float yy = cy - 4 + k * 9;
                    path.reset();
                    path.moveTo(cx - hw, yy);
                    path.cubicTo(cx - hw / 2, yy - 6, cx - hw / 2, yy + 6, cx, yy);
                    path.cubicTo(cx + hw / 2, yy - 6, cx + hw / 2, yy + 6, cx + hw, yy);
                    c.drawPath(path, gp);
                }
                gp.setStyle(Paint.Style.FILL);
                break;
            }
            case G_DROP: {
                gp.setStyle(Paint.Style.FILL);
                gp.setColor(col);
                float r = w * 0.34f;
                c.drawCircle(cx, cy + 4, r, gp);
                path.reset();
                path.moveTo(cx, cy - 13);
                path.lineTo(cx - r * 0.95f, cy + 1);
                path.lineTo(cx + r * 0.95f, cy + 1);
                path.close();
                c.drawPath(path, gp);
                break;
            }
            case G_UMB: {
                gp.setStyle(Paint.Style.FILL);
                gp.setColor(col);
                rf.set(x + 1, cy - 14, x + w - 1, cy + 10);
                c.drawArc(rf, 180, 180, true, gp);
                stroke(3f, col);
                c.drawLine(cx, cy - 2, cx, cy + 12, gp);
                break;
            }
            case G_WIFI: {
                float bx = cx, by = cy + 10;
                stroke(3.2f, col);
                for (int i = 1; i <= 3; i++) {
                    float r = 6.5f * i;
                    rf.set(bx - r, by - r, bx + r, by + r);
                    c.drawArc(rf, 225, 90, false, gp);
                }
                gp.setStyle(Paint.Style.FILL);
                c.drawCircle(bx, by - 0.5f, 2.4f, gp);
                break;
            }
            case G_ALARM: {
                float h = w * 0.8f;
                stroke(2.6f, col);
                c.drawCircle(cx, cy + 2, h * 0.38f, gp);
                c.drawLine(cx, cy + 2, cx, cy - h * 0.18f, gp);
                c.drawLine(cx, cy + 2, cx + h * 0.16f, cy + h * 0.1f, gp);
                c.drawLine(cx - h * 0.44f, cy - h * 0.28f, cx - h * 0.26f, cy - h * 0.46f, gp);
                c.drawLine(cx + h * 0.44f, cy - h * 0.28f, cx + h * 0.26f, cy - h * 0.46f, gp);
                break;
            }
            case G_VIB: {
                float h = w * 0.9f;
                stroke(2.6f, col);
                rf.set(cx - h * 0.2f, cy - h * 0.48f, cx + h * 0.2f, cy + h * 0.48f);
                c.drawRoundRect(rf, h * 0.08f, h * 0.08f, gp);
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    c.drawLine(cx + sgn * h * 0.34f, cy - h * 0.22f, cx + sgn * h * 0.34f, cy + h * 0.22f, gp);
                    c.drawLine(cx + sgn * h * 0.5f, cy - h * 0.12f, cx + sgn * h * 0.5f, cy + h * 0.12f, gp);
                }
                break;
            }
            case G_SIL: {
                float h = w * 0.9f, bw = h * 0.7f;
                gp.setStyle(Paint.Style.FILL);
                gp.setColor(col);
                rf.set(cx - bw / 2, cy - h * 0.5f, cx + bw / 2, cy + h * 0.3f);
                c.drawArc(rf, 180, 180, true, gp);
                c.drawRect(cx - bw / 2, cy - h * 0.1f, cx + bw / 2, cy + h * 0.28f, gp);
                c.drawRect(cx - bw * 0.62f, cy + h * 0.22f, cx + bw * 0.62f, cy + h * 0.34f, gp);
                stroke(3f, col);
                c.drawLine(cx - h * 0.5f, cy - h * 0.5f, cx + h * 0.5f, cy + h * 0.5f, gp);
                break;
            }
            case G_DND: {
                float h = w * 0.9f;
                gp.setStyle(Paint.Style.FILL);
                gp.setColor(col);
                c.drawCircle(cx, cy, h * 0.46f, gp);
                gp.setColor(dk ? 0xFF1C1C1E : 0xFFFFFFFF);
                rf.set(cx - h * 0.26f, cy - h * 0.07f, cx + h * 0.26f, cy + h * 0.07f);
                c.drawRoundRect(rf, h * 0.05f, h * 0.05f, gp);
                break;
            }
            case G_SUN: {
                gp.setStyle(Paint.Style.FILL);
                gp.setColor(col);
                float r = w * 0.34f, sx = x + w / 2f + 1;
                c.drawCircle(sx, cy, r, gp);
                for (int a = 0; a < 360; a += 45) {
                    double ra = Math.toRadians(a);
                    c.drawCircle((float) (sx + w * 0.62 * Math.cos(ra)), (float) (cy + w * 0.62 * Math.sin(ra)), 1.7f, gp);
                }
                break;
            }
            case G_HEAD: {
                stroke(3f, col);
                float r = w * 0.42f;
                rf.set(cx - r, cy - r, cx + r, cy + r);
                c.drawArc(rf, 180, 180, false, gp);
                gp.setStyle(Paint.Style.FILL);
                rf.set(cx - r - 2, cy - 1, cx - r + 6, cy + r * 0.85f);
                c.drawRoundRect(rf, 3, 3, gp);
                rf.set(cx + r - 6, cy - 1, cx + r + 2, cy + r * 0.85f);
                c.drawRoundRect(rf, 3, 3, gp);
                break;
            }
            case G_BOLT: {
                gp.setStyle(Paint.Style.FILL);
                gp.setColor(col);
                float hh = w * 1.15f;
                path.reset();
                path.moveTo(x + w * 0.62f, cy - hh / 2);
                path.lineTo(x + w * 0.08f, cy + hh * 0.08f);
                path.lineTo(x + w * 0.46f, cy + hh * 0.08f);
                path.lineTo(x + w * 0.34f, cy + hh / 2);
                path.lineTo(x + w * 0.94f, cy - hh * 0.12f);
                path.lineTo(x + w * 0.54f, cy - hh * 0.12f);
                path.close();
                c.drawPath(path, gp);
                break;
            }
            case G_EYE: {
                // eye: almond outline + pupil
                stroke(2.2f, col);
                float hw = w * 0.48f, hh = w * 0.30f;
                path.reset();
                path.moveTo(cx - hw, cy);
                path.quadTo(cx, cy - hh * 1.9f, cx + hw, cy);
                path.quadTo(cx, cy + hh * 1.9f, cx - hw, cy);
                c.drawPath(path, gp);
                gp.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, w * 0.16f, gp);
                break;
            }
            case G_CLOUD: {
                // cloud: three puffs on a flat base
                gp.setStyle(Paint.Style.FILL);
                gp.setColor(col);
                float r = w * 0.2f, base = cy + w * 0.22f;
                c.drawCircle(cx - w * 0.22f, base - r, r, gp);
                c.drawCircle(cx + w * 0.02f, base - r * 1.55f, r * 1.3f, gp);
                c.drawCircle(cx + w * 0.26f, base - r * 0.9f, r * 0.9f, gp);
                rf.set(cx - w * 0.42f, base - r, cx + w * 0.44f, base);
                c.drawRoundRect(rf, r, r, gp);
                break;
            }
            case G_SUNSET: {
                gp.setStyle(Paint.Style.FILL);
                gp.setColor(col);
                float r = w * 0.32f, sy = cy + 4;
                rf.set(cx - r, sy - r, cx + r, sy + r);
                c.drawArc(rf, 180, 180, true, gp);
                for (int a = 180; a <= 360; a += 45) {
                    double ra = Math.toRadians(a);
                    c.drawCircle((float) (cx + w * 0.55 * Math.cos(ra)), (float) (sy + w * 0.55 * Math.sin(ra)), 1.7f, gp);
                }
                c.drawRect(cx - w * 0.55f, sy + 1, cx + w * 0.55f, sy + 3.5f, gp);
                break;
            }
            default:
                break;
        }
    }

    private void stroke(float width, int col) {
        gp.setStyle(Paint.Style.STROKE);
        gp.setStrokeWidth(width);
        gp.setStrokeCap(Paint.Cap.ROUND);
        gp.setStrokeJoin(Paint.Join.ROUND);
        gp.setColor(col);
    }

    // ================================================================== pro layout (4 rows, 1280 x 206)
    //
    //  strip : ISS / satellite · solar time + sun phase · moon phase name
    //  left  : clock / zone | C / B temperature | amps / watts | GPU ...... ↓↑ speed by the camera   (row 1)
    //          sunrise-sunset · alarm · notifications | system-icon slot          (row 2)
    //          GPS satellites                                                     (row 3)
    //  centre: battery-health ring around the camera, "93% · 402" under it
    //  right : one column per SIM (network, operator, bars, ↓↑, Vo / dBm, SNR / band, distance, cells),
    //          battery; weather on rows 3 and 4 (temperature, feels, rain, UV, AQI / high-low, humidity, wind)

    boolean pro;
    int soh = -1, cycles = -1, cpuLoad = -1, sats = -1, ping = -1, deep = -1;
    float wxPres = Float.NaN, wxPresTrend = Float.NaN;
    boolean climbOn;
    float climbUp, climbDown;       // today's total climb / descent in metres
    float[] presSeries, uvSeries;   // pressure last 3 h (hourly), UV index by hour of today (24)
    String wake = "";
    boolean vpn;
    private long flagsAt = 0;
    private int sysFlags = 0;   // 1 airplane, 2 battery saver, 4 NFC, 8 hotspot
    /** Camera hole centre and radius in this view's pixels (NaN = unknown). */
    float camX = Float.NaN, camY = Float.NaN, camR = Float.NaN;

    private static final float PRO_H = 206f;
    /** The data SIM's box is this much taller than its four old lines, to hold the radio details. */
    /**
     * Box height below the band line (2026-11-14, was a fixed 52): sized to what the SIM boxes actually hold,
     * so the white line ends right under the place name and its detail line instead of leaving a gap.
     * Rows are 22 apart: place name (+1 if it has to wrap) plus one row per detail line, at least one.
     */
    private float boxExtra(int n) {
        float h = 44f;
        for (SimInfo si : sims) {
            float r = 22f + 22f * Math.max(1, detailLines(si).size());
            if (placeW(si) > boxW(n)) r += 22f;
            h = Math.max(h, r);
        }
        return h - 16f;
    }
    private static final float P_CLK = 56, P1A = 41, P1B = 77, P2 = 106, P3 = 158, P4 = 188;
    /** CPU / BAT temperatures: one line straight under the other, no gap. */
    private static final float T1A = 45, T1B = 73;
    private static final int G_HEAD = 11, G_BOLT = 12, G_SUNSET = 13, G_EYE = 14, G_CLOUD = 15, G_WAVE = 16;


    // ---- background layer: world map with disaster pins, and device effects (gauge-line look only)

    private float fxMapX(double lon) { return (float) ((lon + 180) / 360 * W); }
    private float fxMapY(double lat) { return y((float) ((78 - lat) / 136 * PRO_H)); }

    private static final double[][] CITIES = {{72.9, 19.1}, {77.2, 28.6}, {139.7, 35.7}, {121.5, 31.2}, {116.4, 39.9}, {-0.1, 51.5},
            {2.3, 48.9}, {37.6, 55.8}, {31.2, 30.0}, {3.4, 6.5}, {-74, 40.7}, {-118.2, 34}, {-99.1, 19.4}, {-46.6, -23.5},
            {151.2, -33.9}, {55.3, 25.2}, {103.8, 1.35}, {106.8, -6.2}, {29, 41}, {28, -26.2}, {90.4, 23.8}, {100.5, 13.8}};

    /** World map over the weather sky: land outlines, the night side shaded, city lights on it, the sun overhead, a ring where you are. */
    private int[] skyW, skyH;
    private int[] skyWin;

    private static int wxDeg(String t) {
        if (t == null) return Integer.MIN_VALUE;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+").matcher(t);
        return m.find() ? Integer.parseInt(m.group()) : Integer.MIN_VALUE;
    }

    /** Haze from the air quality and a tint from the temperature, over the sky and under the map. */
    /** The Sun is drawn under the haze veil and the world-map shading: cut a soft hole in them where it is, so it shines instead of looking dull. */
    private int holeBegin(Canvas c) {
        if (SkyView.glowOn > 0f && s > 0f) {
            try { return c.saveLayer(null, null); } catch (Throwable t) { return -1; }
        }
        return -1;
    }

    private void holeEnd(Canvas c, int lyr) {
        if (lyr < 0) return;
        float gx = SkyView.glowX / s, gy = SkyView.glowY / s, gr = SkyView.glowR / s * 1.15f;
        gp.setStyle(Paint.Style.FILL);
        gp.setShader(new android.graphics.RadialGradient(gx, gy, gr, new int[]{0xFF000000, 0xE0000000, 0}, new float[]{0f, 0.5f, 1f}, android.graphics.Shader.TileMode.CLAMP));
        gp.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT));
        c.drawCircle(gx, gy, gr, gp);
        gp.setXfermode(null);
        gp.setShader(null);
        c.restoreToCount(lyr);
    }

    private void wxVeil(Canvas c) {
        final float H = PRO_H;
        if (wxAqi > 60) {
            float k = Math.min(1f, (wxAqi - 60) / 140f);
            gp.setStyle(Paint.Style.FILL);
            gp.setColor(((int) (80 * k) << 24) | 0xB39A74);
            c.drawRect(0, y(0), W, y(H), gp);
            gp.setColor(((int) (64 * k) << 24) | 0xA58A63);
            c.drawRect(0, y(70), W, y(H), gp);
        }
        int t = wxDeg(wxTemp);
        if (t != Integer.MIN_VALUE) {
            gp.setStyle(Paint.Style.FILL);
            if (t <= 18) {
                float k = Math.min(1f, (18 - t) / 10f);
                gp.setColor(((int) (56 * k) << 24) | 0x2A7FFF);
                c.drawRect(0, y(0), W, y(H), gp);
            } else if (t >= 32) {
                float k = Math.min(1f, (t - 32) / 8f);
                gp.setColor(((int) (76 * k) << 24) | 0xFF6A2A);
                c.drawRect(0, y(0), W, y(H), gp);
                gp.setShader(new android.graphics.RadialGradient(W / 2, y(H) + 20, W * 0.55f, new int[]{((int) (64 * k) << 24) | 0xFF3B1A, 0}, null, android.graphics.Shader.TileMode.CLAMP));
                c.save();
                c.scale(1f, 0.28f, W / 2, y(H));
                c.drawRect(0, y(H) - W, W, y(H) + 400, gp);
                c.restore();
                gp.setShader(null);
            }
        }
    }

    private static final int B_TOWER = 0, B_STEP = 1, B_FLATS = 2, B_MID = 3, B_HOUSE = 4, B_TEMPLE = 5, B_DOME = 6, B_CHIM = 7,
            B_MAST = 8, B_TREE = 9, B_LIGHT = 10, B_PALM = 11, B_BOAT = 12;
    private static final int SKY_BG = 0xF205080E;

    private void sRect(Canvas c, float x, float w, float h) { gp.setStyle(Paint.Style.FILL); gp.setColor(SKY_BG); c.drawRect(x, -h, x + w, 0, gp); }

    private void sWin(Canvas c, java.util.Random r, float x, float w, float h, int n, float p) {
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(0xD9FFD27A);
        for (int i = 0; i < n; i++) {
            if (r.nextFloat() > p) continue;
            float wx = x + 3 + r.nextInt(Math.max(1, (int) w - 8)), wy = -h + 4 + r.nextInt(Math.max(1, (int) h - 8));
            c.drawRect(wx, wy, wx + 3, wy + 3, gp);
        }
    }

    private void sPoly(Canvas c, float... p) {
        Path pa = new Path();
        pa.moveTo(p[0], p[1]);
        for (int i = 2; i + 1 < p.length; i += 2) pa.lineTo(p[i], p[i + 1]);
        pa.close();
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(SKY_BG);
        c.drawPath(pa, gp);
    }

    /** One building / object with its left edge at x (baseline y = 0, up is negative); returns its width. */
    private float sBuild(Canvas c, int type, java.util.Random r, float x) {
        float w, h;
        switch (type) {
            case B_TOWER: w = 16 + r.nextInt(9); h = 50 + r.nextInt(29); sRect(c, x, w, h); sRect(c, x + w / 2, 2, h + 12); sWin(c, r, x, w, h, 8, .8f); return w;
            case B_STEP: w = 20 + r.nextInt(9); h = 44 + r.nextInt(21); sRect(c, x, w, h); sRect(c, x + 4, w - 8, h + 12); sWin(c, r, x, w, h, 6, .8f); return w;
            case B_FLATS: {
                w = 22 + r.nextInt(13); h = 30 + r.nextInt(17); sRect(c, x, w, h);
                gp.setColor(0xFF0A1018);
                for (float yy = -h + 5; yy < -4; yy += 8) c.drawRect(x + 2, yy, x + w - 2, yy + 1.5f, gp);
                sWin(c, r, x, w, h, 7, .8f); return w;
            }
            case B_MID: w = 16 + r.nextInt(11); h = 22 + r.nextInt(15); sRect(c, x, w, h); sRect(c, x + 3, 6, h + 5); sWin(c, r, x, w, h, 4, .8f); return w;
            case B_HOUSE: w = 14 + r.nextInt(9); h = 8 + r.nextInt(6); sRect(c, x, w, h); sPoly(c, x - 2, -h, x + w / 2, -h - 8, x + w + 2, -h); sWin(c, r, x, w, h, 1, .5f); return w;
            case B_TEMPLE: w = 22; h = 14; sRect(c, x, w, h); sPoly(c, x + 3, -h, x + w / 2, -h - 24, x + w - 3, -h); sRect(c, x + w / 2 - 1, 1.5f, h + 30); return w;
            case B_DOME: {
                w = 26; h = 12; sRect(c, x, w, h);
                gp.setStyle(Paint.Style.FILL); gp.setColor(SKY_BG);
                rf.set(x + 2, -h - 11, x + 20, -h + 5); c.drawOval(rf, gp);
                sRect(c, x + w + 2, 4, h + 26); sPoly(c, x + w + 1, -h - 26, x + w + 4, -h - 34, x + w + 7, -h - 26); return w + 8;
            }
            case B_CHIM: w = 26; h = 14; sRect(c, x, w, h); sRect(c, x + 4, 5, h + 30); sRect(c, x + 14, 4, h + 22); sWin(c, r, x, w, h, 2, .8f); return w;
            case B_MAST: {
                stk(2f, SKY_BG);
                c.drawLine(x, 0, x + 6, -52, gp); c.drawLine(x + 12, 0, x + 6, -52, gp); c.drawLine(x + 3, -26, x + 9, -26, gp);
                gp.setStyle(Paint.Style.FILL); gp.setColor(0xFFFF5A4F); c.drawCircle(x + 6, -54, 2, gp); return 14;
            }
            case B_TREE: {
                h = 14 + r.nextInt(15); sRect(c, x + 6, 2, 8);
                gp.setStyle(Paint.Style.FILL); gp.setColor(SKY_BG);
                rf.set(x, -h - 6, x + 14, 0); c.drawOval(rf, gp); return 16;
            }
            case B_LIGHT:
                sPoly(c, x, 0, x + 3, -40, x + 11, -40, x + 14, 0);
                gp.setStyle(Paint.Style.FILL); gp.setColor(0xFFFFD27A); c.drawRect(x + 2, -45, x + 12, -40, gp); return 14;
            case B_PALM: {
                h = 30 + r.nextInt(15);
                Path tr = new Path(); tr.moveTo(x, 0); tr.quadTo(x + 4, -h / 2f, x + 6, -h);
                stk(3f, SKY_BG); c.drawPath(tr, gp);
                for (int a : new int[]{-50, -20, 15, 45}) {
                    float ex = x + 6 + 22 * (float) Math.cos(Math.toRadians(a - 90)), ey = -h + 12 * (float) Math.sin(Math.toRadians(a)) + 4;
                    Path fr = new Path(); fr.moveTo(x + 6, -h); fr.quadTo(x + 6 + (ex - x - 6) / 2, -h - 8, ex, ey);
                    stk(2.5f, SKY_BG); c.drawPath(fr, gp);
                }
                return 22;
            }
            default: sPoly(c, x, -9, x + 18, -9, x + 15, -4, x + 3, -4); sPoly(c, x + 8, -9, x + 8, -21, x + 14, -9); return 20;
        }
    }

    private void sRun(Canvas c, int[] kinds, long seed, int gapMin, int gapSpan) {
        java.util.Random r = new java.util.Random(seed);
        float x = 0;
        while (x < W) { x += sBuild(c, kinds[r.nextInt(kinds.length)], r, x) + gapMin + r.nextInt(gapSpan); }
    }

    /** Skyline along the bottom edge for the kind of place you are in: city, town, village, hills or coast. */
    private void wxSkyline(Canvas c) {
        final float H = PRO_H;
        c.save();
        c.translate(0, y(H));
        try {
            boolean hills = Scene.hills;
            int u = Scene.urban;
            boolean coast = Scene.coast;
            gp.setStyle(Paint.Style.FILL);
            if (hills) {
                Path ridge = new Path();
                ridge.moveTo(0, 0);
                for (float x = 0; x <= W + 10; x += 10) ridge.lineTo(x, -(22 + 30 * (float) Math.abs(Math.sin(x / 170)) + 16 * (float) Math.sin(x / 61 + 1) + 8 * (float) Math.sin(x / 23)));
                ridge.lineTo(W + 10, 0);
                ridge.close();
                gp.setColor(SKY_BG);
                c.drawPath(ridge, gp);
                java.util.Random r = new java.util.Random(2);
                int[] on = {B_HOUSE, B_HOUSE, B_TREE, B_TEMPLE, B_TREE};
                for (float x = 30; x < W - 20; x += 60 + r.nextInt(50)) {
                    float yy = -(22 + 30 * (float) Math.abs(Math.sin(x / 170)) + 16 * (float) Math.sin(x / 61 + 1) + 8 * (float) Math.sin(x / 23)) + 2;
                    c.save(); c.translate(0, yy); sBuild(c, on[r.nextInt(on.length)], r, x); c.restore();
                }
                if (!Float.isNaN(Scene.elev) && Scene.elev >= 1200) {
                    gp.setColor(0x59CFE0F5);
                    for (float sx : new float[]{W * 0.23f, W * 0.5f, W * 0.77f}) sPoly(c, sx - 8, -56, sx, -66, sx + 8, -56);
                }
            } else if (coast && u != Scene.CITY) {
                gp.setColor(0xE612405F);
                c.drawRect(0, -17, W, 0, gp);
                stk(1.5f, 0x997FE3D6);
                Path wv = new Path();
                for (float x = 0; x <= W; x += 10) { float yy = -15 + 2 * (float) Math.sin(x / 20); if (x == 0) wv.moveTo(x, yy); else wv.lineTo(x, yy); }
                c.drawPath(wv, gp);
                java.util.Random r = new java.util.Random(9);
                int[] ks = {B_PALM, B_HOUSE, B_PALM, B_BOAT, B_BOAT, B_LIGHT, B_PALM, B_HOUSE, B_PALM, B_HOUSE};
                float[] xs = {0.03f, 0.07f, 0.1f, 0.5f, 0.55f, 0.84f, 0.875f, 0.9f, 0.94f, 0.97f};
                for (int i = 0; i < ks.length; i++) { c.save(); if (ks[i] == B_BOAT) c.translate(0, 4); sBuild(c, ks[i], r, W * xs[i]); c.restore(); }
                gp.setStyle(Paint.Style.FILL);
                gp.setColor(0xD905080E);
                c.drawRect(0, -7, W, 0, gp);
            } else if (u == Scene.VILLAGE) {
                sRun(c, new int[]{B_HOUSE, B_HOUSE, B_HOUSE, B_TREE, B_TREE, B_TEMPLE, B_TREE, B_HOUSE}, 3, 18, 29);
            } else if (u == Scene.TOWN) {
                sRun(c, new int[]{B_MID, B_MID, B_HOUSE, B_HOUSE, B_FLATS, B_TEMPLE, B_DOME, B_TREE, B_MAST, B_HOUSE}, 5, 8, 15);
            } else {
                sRun(c, new int[]{B_TOWER, B_TOWER, B_STEP, B_FLATS, B_FLATS, B_MID, B_MID, B_TEMPLE, B_CHIM, B_MAST, B_DOME}, 7, 2, 5);
                if (coast) {   // a city on the coast: the sea line too
                    gp.setStyle(Paint.Style.FILL);
                    gp.setColor(0xCC12405F);
                    c.drawRect(0, -5, W, 0, gp);
                }
            }
        } finally {
            c.restore();
        }
    }

    private void fxBackdrop(Canvas c) {
        if (!graphsOn()) return;
        boolean extras = Cfg.b(Cfg.WX_FX, Cfg.D_WX_FX);
        int hl = holeBegin(c);
        try {
            if (extras) { try { wxVeil(c); } catch (Throwable ignored) { } }
            try { if (Cfg.b(Cfg.BG_MAP, Cfg.D_BG_MAP)) fxMap(c); } catch (Throwable ignored) { }
        } finally {
            try { holeEnd(c, hl); } catch (Throwable ignored) { }
        }
        // wxSkyline() (city/town/village/hills/coast buildings along the bottom edge) dropped
        // 2026-11-14 per request: weather tint and stars stay, the skyline doesn't.
        try { belowDots(c); } catch (Throwable ignored) { }
    }

    /** The Sun / Moon under the horizon: small dots on the bottom edge, drawn over the skyline so it can't hide them. */
    private void belowDots(Canvas c) {
        float sf = SkyView.belowSunF, mf = SkyView.belowMoonF;
        if (sf < 0 && mf < 0) return;
        final float H = PRO_H, m = 6f;
        gp.setStyle(Paint.Style.FILL);
        if (sf >= 0) {
            float x = (W - m) - sf * (W - 2 * m), yy = y(H - 3f);
            gp.setShader(new android.graphics.RadialGradient(x, yy, 17f, new int[]{0xAAFF9A40, 0}, null, android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(x, yy, 17f, gp);
            gp.setShader(null);
            gp.setColor(0xFFFFB060);
            c.drawCircle(x, yy, 3.8f, gp);
        }
        if (mf >= 0) {
            float x = (W - m) - mf * (W - 2 * m), yy = y(H - 3f), r = 4.2f;
            gp.setShader(new android.graphics.RadialGradient(x, yy, r * 4.5f, new int[]{0x99C9D8FF, 0}, null, android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(x, yy, r * 4.5f, gp);
            gp.setShader(null);
            double ph = SkyView.belowMoonPhase;
            gp.setColor(0x99A0A8B8);
            c.drawCircle(x, yy, r, gp);
            float k = (float) Math.cos(2 * Math.PI * ph);
            Path mp = new Path();
            boolean wax = ph < 0.5;
            RectF o = new RectF(x - r, yy - r, x + r, yy + r);
            mp.addArc(o, wax ? -90 : 90, 180);
            RectF in = new RectF(x - r * Math.abs(k), yy - r, x + r * Math.abs(k), yy + r);
            mp.arcTo(in, wax ? 90 : -90, k > 0 ? -180 : 180);
            mp.close();
            gp.setColor(0xFFEAF0FF);
            c.drawPath(mp, gp);
        }
    }

    private void fxLand(Canvas c, int fill, int stroke) {
        Path pa = new Path();
        for (float[] poly : MapData.LAND) {
            pa.reset();
            for (int i = 0; i + 1 < poly.length; i += 2) {
                float px = fxMapX(poly[i]), py = fxMapY(poly[i + 1]);
                if (i == 0) pa.moveTo(px, py); else pa.lineTo(px, py);
            }
            pa.close();
            gp.setStyle(Paint.Style.FILL);
            gp.setColor(fill);
            c.drawPath(pa, gp);
            gp.setStyle(Paint.Style.STROKE);
            gp.setStrokeWidth(1f);
            gp.setColor(stroke);
            c.drawPath(pa, gp);
        }
    }

    private void fxMap(Canvas c) {
        final float H = PRO_H;
        // sub-solar point now (NOAA): declination and hour angle
        java.util.Calendar u = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        double doy = u.get(java.util.Calendar.DAY_OF_YEAR), hrs = u.get(java.util.Calendar.HOUR_OF_DAY) + u.get(java.util.Calendar.MINUTE) / 60.0;
        double g = 2 * Math.PI / 365 * (doy - 1 + (hrs - 12) / 24);
        double eq = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g) - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g)
                + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double sunLon = -15.0 * (hrs - 12) - eq / 4.0;
        while (sunLon < -180) sunLon += 360;
        while (sunLon > 180) sunLon -= 360;
        double dd = decl, tanD = Math.tan(dd);
        // night and day sides, split by the sunrise/sunset line
        float yTop = y(0), yBot = y(H);
        boolean south = dd >= 0;          // night lies on the far side of the terminator from the sun's latitude
        float edgeY = south ? yBot : yTop, dayEdgeY = south ? yTop : yBot;
        Path nt = new Path(), dy = new Path(), tl = new Path();
        nt.moveTo(0, edgeY);
        dy.moveTo(0, dayEdgeY);
        boolean first = true;
        for (float x = 0; x <= W + 6; x += 6) {
            double lon = x / W * 360 - 180, hh = Math.toRadians(lon - sunLon);
            double latT = Math.toDegrees(Math.atan(-Math.cos(hh) / (Math.abs(tanD) < 1e-4 ? 1e-4 : tanD)));
            float py = Math.max(yTop, Math.min(yBot, fxMapY(latT)));
            nt.lineTo(x, py);
            dy.lineTo(x, py);
            if (first) { tl.moveTo(x, py); first = false; } else tl.lineTo(x, py);
        }
        nt.lineTo(W + 6, edgeY);
        nt.close();
        dy.lineTo(W + 6, dayEdgeY);
        dy.close();
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(0x9E000308);          // night side: clearly darker
        c.drawPath(nt, gp);
        gp.setColor(0x38FFDC8C);          // day side: warm wash
        c.drawPath(dy, gp);
        // land: dim blue on the night side, bright warm on the day side
        fxLand(c, dk ? 0x33789FD7 : 0x335A8CC0, dk ? 0x598CB9EB : 0x595A8CC0);
        c.save();
        c.clipPath(dy);
        fxLand(c, 0x57FFECAA, 0xD9FFF5C8);
        c.restore();
        // sunrise / sunset line
        gp.setStyle(Paint.Style.STROKE);
        gp.setStrokeWidth(10f);
        gp.setColor(0x2EFFD98A);
        c.drawPath(tl, gp);
        gp.setStrokeWidth(2f);
        gp.setColor(0xE6FFE9A8);
        gp.setPathEffect(new android.graphics.DashPathEffect(new float[]{7, 5}, 0));
        c.drawPath(tl, gp);
        gp.setPathEffect(null);
        // city lights on the night side
        double sd = Math.sin(dd), cd = Math.cos(dd);
        gp.setStyle(Paint.Style.FILL);
        for (double[] ct : CITIES) {
            double la = Math.toRadians(ct[1]), h = Math.toRadians(ct[0] - sunLon);
            double elev = Math.sin(la) * sd + Math.cos(la) * cd * Math.cos(h);
            if (elev >= 0) continue;
            float px = fxMapX(ct[0]), py = fxMapY(ct[1]);
            gp.setColor(0x33FFD27A);
            c.drawCircle(px, py, 4.5f, gp);
            gp.setColor(0xFFFFD27A);
            c.drawCircle(px, py, 1.9f, gp);
        }
        // sun overhead
        float sx = fxMapX(sunLon), sy = fxMapY(Math.toDegrees(dd));
        gp.setColor(0x33FFC21A);
        c.drawCircle(sx, sy, 11, gp);
        gp.setColor(0xFFFFC21A);
        c.drawCircle(sx, sy, 5, gp);
        // you
        double mlat = fix != null ? fix[0] : latLon[0], mlon = fix != null ? fix[1] : latLon[1];
        float mx = fxMapX(mlon), my = fxMapY(mlat);
        gp.setStyle(Paint.Style.STROKE);
        gp.setStrokeWidth(2.5f);
        gp.setColor(0xFFFFFFFF);
        c.drawCircle(mx, my, 9, gp);
        gp.setStyle(Paint.Style.FILL);
        c.drawCircle(mx, my, 3, gp);
    }

    private void drawPro(Canvas c, int w, int h) {
        s = Math.min(w / DESIGN_W, h / PRO_H);
        W = w / s;
        vs = 1f;
        oy = Math.max(0f, (h / s - PRO_H) / 2f);
        c.save();
        try {
            c.scale(s, s);
            try { fxBackdrop(c); } catch (Throwable ignored) { }
            float cx = Float.isNaN(camX) ? W / 2f : camX / s;
            float cr = Float.isNaN(camR) ? 24f : Math.max(14f, Math.min(34f, camR / s));
            float ry = Float.isNaN(camY) ? oy + 50f : camY / s;
            float hw = Math.max(cr + 10f, tw(healthText(), 23, bold) / 2f + 6f);
            float leftEnd = cx - hw - 12f, rightStart = cx + hw + 12f, rightEnd = W - 8f;
            // battery health · cycles · altitude sits right against the SIM boxes instead of centred under the camera:
            // the rows on the left may then run up to it
            healthX = Float.NaN;
            float sl = predictSimLeft(cx + cr + 22f, rightEnd);
            if (!Float.isNaN(sl)) {
                float hwid = tw(healthText(), 23, bold);
                healthX = sl - 6f - hwid;
                rightStart = cx + cr + 22f;
                leftEnd = Math.min(cx - cr - 14f, healthX - 14f);
            }
            l4End = 0;
            w4Start = rightEnd;
            camRight = cx + cr;
            // battery health · cycles · altitude now sits on row 2, ending under the camera's right edge (after the
            // speed meter); the system-icon frame stops before it and rows 3-4 may run on to the SIM boxes
            hP2W = tw(healthText(), HP2_SIZE, bold);
            hP2X = (!Float.isNaN(sl) ? sl - 8f : camRight) - hP2W;   // row 3, hugging the SIM boxes
            wxEnd = !Float.isNaN(sl) ? sl - 14f : leftEnd;
            // row 1 may run up to the camera hole itself; the lower rows keep clear of the wider health text
            try { proLeft(c, leftEnd, Math.max(leftEnd, cx - cr - 14f)); } catch (Throwable ignored) { }
            try { proRight(c, rightStart, rightEnd); } catch (Throwable ignored) { }
            try { proCam(c, cx, ry, cr); } catch (Throwable ignored) { }
            try { proSky(c, cx); } catch (Throwable t) { skyDbg = "moon/quake line failed: " + t; }
            try { proTail4(c); } catch (Throwable ignored) { }
        } finally {
            c.restore();
        }
    }

    /** Text whose baseline matches text of size ref centred on cy (for small units beside big numbers). */
    private float textB(Canvas c, String str, float x, float cy, float ref, float size, Typeface tf, int col) {
        tp.setTypeface(tf);
        tp.setTextSize(size);
        tp.setColor(col);
        c.drawText(str, x, cy + ref * 0.36f, tp);
        return x + tp.measureText(str);
    }

    /**
     * Leftmost x (design units) where text whose top is at topU (design units) is not cut by the
     * screen's rounded top-left corner. 0 when the corner is unknown or the line is below it.
     */
    private float cornerX(float topU) {
        try {
            android.view.WindowInsets wi = getRootWindowInsets();
            if (wi == null || android.os.Build.VERSION.SDK_INT < 31) return 0;
            android.view.RoundedCorner rc = wi.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT);
            if (rc == null || rc.getRadius() <= 0) return 0;
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            float r = rc.getRadius();
            float ccx = rc.getCenter().x - loc[0], ccy = rc.getCenter().y - loc[1];
            float top = topU * s;
            if (top >= ccy) return 0;
            float dy = ccy - top;
            float x = ccx - (float) Math.sqrt(Math.max(0, r * r - dy * dy));
            return Math.max(0, x / s);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Rightmost x (design units) clear of the screen's rounded top-right corner for text whose top is at topU. */
    private float cornerRightX(float topU) {
        try {
            android.view.WindowInsets wi = getRootWindowInsets();
            if (wi == null || android.os.Build.VERSION.SDK_INT < 31) return W;
            android.view.RoundedCorner rc = wi.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_RIGHT);
            if (rc == null || rc.getRadius() <= 0) return W;
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            float r = rc.getRadius();
            float ccx = rc.getCenter().x - loc[0], ccy = rc.getCenter().y - loc[1];
            float top = topU * s;
            if (top >= ccy) return W;
            float dy = ccy - top;
            return (ccx + (float) Math.sqrt(Math.max(0, r * r - dy * dy))) / s;
        } catch (Throwable t) {
            return W;
        }
    }

    private long zoneAt = 0;
    private String zoneTxt = "";

    /** "IST" for the phone's time zone, no GMT offset; "" when the zone has no short name. Checked once a minute. */
    private String zone(long now) {
        if (true) return "";   // time-zone label removed on request
        if (now - zoneAt < 60_000) return zoneTxt;
        zoneAt = now;
        try {
            java.util.TimeZone tz = java.util.TimeZone.getDefault();
            boolean dst = tz.inDaylightTime(new Date(now));
            String name = tz.getDisplayName(dst, java.util.TimeZone.SHORT, Locale.ENGLISH);
            if ("Asia/Kolkata".equals(tz.getID()) || "Asia/Calcutta".equals(tz.getID())) name = "IST";
            zoneTxt = name == null || name.startsWith("GMT") || name.startsWith("UTC") ? "" : name;
        } catch (Throwable t) {
            zoneTxt = "";
        }
        return zoneTxt;
    }

    private long moonEvAt = 0;
    private long[] moonEv;

    /** Next moonrise / moonset {time, 1 rise}; recomputed every 5 min or right after it happens. */
    private long[] moonEvent(long now) {
        if (moonEv != null && now - moonEvAt < 300_000 && moonEv[0] > now) return moonEv;
        if (moonEv == null && now - moonEvAt < 60_000 && moonEvAt != 0) return null;
        moonEvAt = now;
        try {
            moonEv = Astro.nextMoonEvent(now, fix != null ? fix[0] : latLon[0], fix != null ? fix[1] : latLon[1]);
        } catch (Throwable t) {
            moonEv = null;
        }
        return moonEv;
    }

    private static String minus(String v) {
        return v.replace('-', '−');
    }

    // ---- left half

    private void proLeft(Canvas c, float L1, float top1) {
        long now = System.currentTimeMillis();
        if (now - h24CheckedAt > 30_000) {
            h24CheckedAt = now;
            try {
                h24 = true;   // always 24-hour
            } catch (Throwable ignored) { }
        }
        Date d = new Date(now);
        float cx0 = Math.max(20, cornerX(y(P1A) - 15) + 4);
        float e = text(c, (h24 ? t24 : t12).format(d), cx0, y(P1A), look2 ? 50 : 38, bold, color);
        if (!h24) e = textB(c, ampm.format(d).toLowerCase(Locale.getDefault()), e + 3, y(P1A), 38, 17, semi, grey());
        final float clkEnd = e;
        // time zone name only (no GMT offset); nothing when the zone has no name
        String z = zone(now);
        float zx0 = Math.max(20, cornerX(y(P1B) - 9) + 4);
        float ze = zx0;
        if (!z.isEmpty()) ze = textB(c, z, zx0, y(P1B), 22, 22, bold, color) + 10;
        // next moonrise / moonset beside the zone: small moon, arrow, time
        long[] mev = moonEvent(now);
        if (mev != null) {
            String mt = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(mev[0]));
            boolean rise = mev[1] == 1;
            moon(c, ze + 8, y(P1B), 8, moonPhase());
            float me = textB(c, rise ? "↑" : "↓", ze + 18, y(P1B), 22, 19, bold, rise ? teal() : grey());
            ze = textB(c, mt, me + 1, y(P1B), 22, 21, bold, rise ? color : grey());
        }
        // next sunrise / sunset right after the moon time: sun glyph and clock time
        String[] sun = nextSun(now);
        boolean tlDone = false;
        if (false) {
            try {
                float room = Math.max(L1, wxEnd) - ze - 14;
                if (room >= 210) { ze = dayTimeline(c, ze + 12, Math.min(room, 420), y(P1B), now); tlDone = true; }
            } catch (Throwable ignored) { }
        }
        if (sun != null && !tlDone) {
            boolean sr = "r".equals(sun[1]);
            int sc = sr ? yellow() : orange();
            Item si = new Item(8, 22).g(sr ? G_SUN : G_SUNSET, 21, sc).sp(4).t(sun[0], 22, bold, sc);
            ze = drawItem(c, si, ze + 10, y(P1B));
        }
        e = Math.max(e, ze);
        // weekday ("Mon") in small grey right after the clock, on the clock's baseline
        String wd = new SimpleDateFormat("EEE", Locale.getDefault()).format(d);
        float wdEnd = look2 ? textB(c, wd, clkEnd + 5, y(P1A), 50, 18, semi, grey()) : textB(c, wd, clkEnd + 6, y(P1A), 38, 19, semi, grey());
        // day type and moon phase as initials, dot-separated ("D\u00b7WxG"), faint, right after the weekday
        try {
            String ini = moonInitials();
            if (ini != null && look2) wdEnd = textB(c, ini, wdEnd + 8, y(P1A), 50, 15, semi, fade(grey(), 0.75f));
        } catch (Throwable ignored) { }
        // time left in the current period (day until sunset, night until sunrise), thin faint row above the weekday
        try {
            if (look2) {
                double[] st = SunTimes.today(latLon[0], latLon[1]);
                if (!Double.isNaN(st[0]) && !Double.isNaN(st[1])) {
                    Calendar cl = Calendar.getInstance();
                    cl.setTimeInMillis(now);
                    double mm = cl.get(Calendar.HOUR_OF_DAY) * 60 + cl.get(Calendar.MINUTE);
                    boolean dayNow = mm >= st[0] && mm < st[1];
                    double left = dayNow ? st[1] - mm : (mm < st[0] ? st[0] - mm : st[0] + 1440 - mm);
                    int lm = (int) Math.round(left);
                    String lt = (lm / 60) + "h " + (lm % 60) + "m";
                    float gx = clkEnd + 5;
                    if (dayNow) glyph(c, G_SUN, gx, y(P1A) - 12, 17, yellow()); else moon(c, gx + 8, y(P1A) - 12, 7, moonPhase());
                    textB(c, lt, gx + 21, y(P1A) - 15, 50, 17, semi, fade(dayNow ? yellow() : blue(), 0.85f));
                }
            }
        } catch (Throwable ignored) { }
        e = Math.max(e, wdEnd);

        // network speed goes right after the last column (drawn at the end); it must stay clear of the camera
        float speedW = tw("↓", 30, bold) + 3 + tw("999", 30, bold) + 2 + tw("K/s", 20, semi);
        float lim = top1 - speedW - 6;

        if (look2) {
            look2Device(c, ze + 12, top1 - 6, wdEnd + 14);   // watts start right after the sunrise/sunset time (row 2), not after the clock column
        } else {
            // C / B temperatures right after the clock, then amps / watts, then the GPU temperature
            float tx = e + 8;
            // while the heat throttle is capping the CPU / GPU the labels turn red and get a down arrow
            boolean thr = HeatWarn.throttled;
            String cLab = thr ? "C\u2193" : "C", bLab = thr ? "B\u2193" : "B";
            int labCol = thr ? (dk ? 0xFFFF5440 : SysViews.red(false)) : grey();
            float lw = Math.max(tw(cLab, 20, semi), tw(bLab, 20, semi)) + 4;
            float colW = lw + tw("99°", 30, bold);
            if (tx + colW <= lim && (!Float.isNaN(cpu) || !Float.isNaN(bat))) {
                if (!Float.isNaN(cpu)) {
                    textB(c, cLab, tx, y(T1A), 30, 20, semi, labCol);
                    textB(c, Math.round(cpu) + "°", tx + lw, y(T1A), 30, 30, bold, heat(cpu, 45, 55, 65));
                }
                if (!Float.isNaN(bat)) {
                    textB(c, bLab, tx, y(T1B), 30, 20, semi, labCol);
                    textB(c, Math.round(bat) + "°", tx + lw, y(T1B), 30, 30, bold, heat(bat, 38, 42, 45));
                }
                tx += colW + 10;
            }
            if (!Float.isNaN(amps) && !Float.isNaN(watts)) {
                String sg = amps > 0 ? "+" : "−";
                float aw = Math.abs(watts);
                String av = sg + String.format(Locale.US, "%.2f", Math.abs(amps));
                String wv = sg + String.format(Locale.US, aw < 10 ? "%.1f" : "%.0f", aw);
                float bolt = charging ? 25 : 0;
                // fixed width from the widest likely values, so the columns after it don't come and go
                float pw = bolt + Math.max(tw("−9.99", 30, bold) + tw("A", 20, semi), tw("−99.9", 30, bold) + tw("W", 20, semi)) + 2;
                if (tx + pw <= lim) {
                    if (charging) glyph(c, G_BOLT, tx, y(T1A), 20, powerColor);
                    float ae = textB(c, av, tx + bolt, y(T1A), 30, 30, bold, powerColor);
                    textB(c, "A", ae + 1, y(T1A), 30, 20, semi, grey());
                    float we = textB(c, wv, tx + bolt, y(T1B), 30, 30, bold, powerColor);
                    textB(c, "W", we + 1, y(T1B), 30, 20, semi, grey());
                    tx += pw + 10;
                }
            }
            // one column: GPU temperature on top, CPU usage under it
            if (!Float.isNaN(gpu) || cpuLoad >= 0) {
                float gl = Math.max(tw("G", 20, semi), tw("C", 20, semi)) + 4;
                // width of the numbers actually shown, so the speed sits right against them
                float vw = Math.max(Float.isNaN(gpu) ? 0 : tw(Math.round(gpu) + "°", 30, bold),
                        cpuLoad >= 0 ? tw(cpuLoad + "%", 27, bold) : 0);
                // shrink (down to 70 %) rather than vanish when the row is a little short
                float need = gl + vw, f = Math.min(1f, (lim - tx) / need);
                if (f >= 0.7f) {
                    c.save();
                    c.scale(f, f, tx, (y(T1A) + y(T1B)) / 2f);
                    try {
                        if (!Float.isNaN(gpu)) {
                            textB(c, "G", tx, y(T1A), 30, 20, semi, grey());
                            textB(c, Math.round(gpu) + "°", tx + gl, y(T1A), 30, 30, bold, heat(gpu, 45, 55, 65));
                        }
                        if (cpuLoad >= 0) {
                            int cc = cpuLoad < 50 ? green() : cpuLoad < 80 ? yellow() : red();
                            textB(c, "C", tx, y(T1B), 30, 20, semi, grey());
                            textB(c, cpuLoad + "%", tx + gl, y(T1B), 30, 27, bold, cc);
                        }
                    } finally {
                        c.restore();
                    }
                    tx += need * f + 4;
                }
            }
            // speed almost touching the GPU / CPU column
            float sx = Math.min(tx, top1 - speedW);
            if (Cfg.b(Cfg.SPEED_STOCK, Cfg.D_SPEED_STOCK)) {
                proSpeed(c, true, down, sx, y(P1A));
                proSpeed(c, false, up, sx, y(P1B));
            }
            // uptime in the gap right of the speeds, before the camera; only at full size
            try {
                long up = android.os.SystemClock.elapsedRealtime() / 60_000L;
                String uv = up < 60 ? up + "m" : up < 1440 ? (up / 60) + "h" + (up % 60 < 10 ? "0" : "") + (up % 60)
                        : (up / 1440) + "d" + ((up % 1440) / 60) + "h";
                float ux = sx + speedW + 10;
                if (ux + Math.max(tw(uv, 26, bold), tw("up", 20, semi)) <= top1) {
                    textB(c, "up", ux, y(T1A), 30, 20, semi, grey());
                    textB(c, uv, ux, y(T1B), 30, 26, bold, color);
                }
            } catch (Throwable ignored) { }

        }

        // row 2: alarm, notifications, then the system icons in a frame
        List<Item> r2 = new ArrayList<>();
        if (alarmAt > 0 && Cfg.b(Cfg.SH_ALARM, Cfg.D_SH_ALARM)) {
            String f = alarmAt - now < 20 * 3600_000L ? "HH:mm" : "EEE HH:mm";
            String lbl = new SimpleDateFormat(f, Locale.US).format(new Date(alarmAt));
            r2.add(new Item(6, 27).g(G_ALARM, 26, color).sp(6).t(lbl, 27, semi, color));
        }
        String n = notifAll < 0 ? "?" : String.valueOf(notifAll);
        int ncol = notifAll == 0 ? grey() : orange();
        Item ni = new Item(9, 27).g(G_BELL, 24, ncol).sp(6).t(n, 27, bold, ncol);
        if (notifSilent > 0) ni.sp(5).t("(" + notifSilent + ")", 20, semi, grey());
        if (Cfg.b(Cfg.SH_NOTIF, Cfg.D_SH_NOTIF)) r2.add(ni);
        float r2End = L1;
        float e2 = drawLine(c, r2, 22, y(P2), r2End - 120, 18);
        proSlot(c, e2 + 16, r2End, y(P2));

        // row 3: GPS pin + satellite count, only while an app is actually using GPS
        float e3 = 0;   // the GPS pin + satellite count moved into the place line under row 2

        // rows 3 and 4: the weather, moved here from under the SIMs (they now show their towers' areas)
        l4End = 22;
        try { proWeatherLeft(c, e3 > 0 ? e3 + 22 : 22, 22, Math.max(L1, wxEnd), 1e9f); } catch (Throwable ignored) { }
        // where the weather is for: place name in the thin gap between the notification row and the weather
        try {
            // the weather's own place: the name of the coordinates its forecast was fetched with (PLACE until the first fetch)
            String pl = !TowerArea.WX_NAME.isEmpty() ? TowerArea.WX_NAME : TowerArea.PLACE;
            boolean hasPl = pl != null && !pl.isEmpty();
            // altitude: GPS height, else the ground height from the weather service (~); GPS accuracy + satellites only while GPS is on
            String alt = fix != null && !Double.isNaN(fix[2]) ? "\u25B2" + Math.round(fix[2])
                    : !Float.isNaN(groundElev) ? "\u25B2~" + Math.round(groundElev) : null;
            String gps = null;
            if (sats > 0) {
                String acc = fix != null && fix.length > 3 && !Double.isNaN(fix[3]) ? " \u00b1" + Math.round(fix[3]) + " m" : "";
                gps = "GPS" + acc + " \u00b7 " + sats + " sats";
            }
            if (hasPl || alt != null || gps != null) {
                float px = Math.max(22, cornerX(y(P2) + 18) + 4);
                // below the camera the line may run on up to the SIM boxes
                final float lineEnd0 = Math.max(L1, wxEnd);
                final String moonT = null;                                    // (the day type + moon phase now sit as initials beside the clock)
                final float moonTw = moonT == null ? 0 : tw(moonT, 18, semi);
                float lineEnd = lineEnd0;                                     // the earth frames keep their whole width: nothing of them is cut for the moon text
                String cl = climbOn ? "\u2191" + Math.round(climbUp) + " \u2193" + Math.round(climbDown) : null;
                // rotating frames: each shows the place, then one group of earth facts; empty frames are skipped
                java.util.List<String> fr = new java.util.ArrayList<>();
                double fla = fix != null ? fix[0] : latLon[0], flo = fix != null ? fix[1] : latLon[1];
                java.util.List<String>[] fs = new java.util.List[11];
                for (int q = 0; q < 11; q++) fs[q] = new java.util.ArrayList<>();
                if (Cfg.b(Cfg.RIVER, Cfg.D_RIVER)) {
                    String rv0 = RiverFlow.text(), dm0 = EarthLine.dam;
                    if (rv0 != null && !rv0.isEmpty() && dm0 != null && !dm0.isEmpty()) {
                        // keep the river and the nearest dam/weir on one line: if they do not fit, drop the river's distance and the word "Dam"
                        String l0 = hasPl ? "\uD83D\uDCCD " + pl : "";
                        if (px + tw(join(l0, rv0 + " \u00b7 " + dm0), 18, semi) > lineEnd) {
                            rv0 = rv0.replaceAll("\\s*\\(\\d+(\\.\\d+)?\\s*k?m\\)", "");
                            dm0 = dm0.replaceAll("(?i)\\s+Dam(?=\\s+\\d)", "");
                        }
                    }
                    addFrame(fs[0], rv0, dm0);
                    try {   // where this river ends up, as a second frame in the river group
                        for (String ws : Watershed.chains(Watershed.riverName(RiverFlow.text()))) {
                            if (px + tw(ws, 18, semi) <= lineEnd || ws.indexOf('\u2192') == ws.lastIndexOf('\u2192')) { addFrame(fs[0], ws); break; }
                        }
                    } catch (Throwable ignored) { }
                }
                try {   // forest region, what the map shows around you, and the plants people have logged here
                    Habitat.Data hb = Habitat.cur;
                    if (Cfg.b(Cfg.EL_HABITAT, Cfg.D_EL_HABITAT) && hb != null && Dam.km(fla, flo, hb.lat, hb.lon) <= 8) {
                        if (!hb.region().isEmpty()) addFrame(fs[10], "\uD83C\uDF33 " + hb.region());
                        if (hb.coverOk) {
                            String ct = hb.coverText(4);
                            if (!ct.isEmpty()) addFrame(fs[10], ("\uD83C\uDF33 " + ct.replace(" \u00b7 ", "\u0001")).split("\u0001"));
                        }
                        if (!Double.isNaN(hb.leafKm) && hb.leafKm <= 15 && !hb.leaf.isEmpty())
                            addFrame(fs[10], "\uD83C\uDF33 Forest " + Math.round(hb.leafKm) + " km " + hb.leafDir, hb.leaf);
                        if (!hb.names.isEmpty()) {
                            java.util.List<String> pn = hb.top(5);
                            pn.set(0, "\uD83C\uDF3F " + pn.get(0));
                            addFrame(fs[10], pn.toArray(new String[0]));
                        }
                    }
                } catch (Throwable ignored) { }
                if (Cfg.b(Cfg.EL_RAIN, Cfg.D_EL_RAIN)) addFrame(fs[1], EarthLine.rain);
                if (Cfg.b(Cfg.EL_SOIL, Cfg.D_EL_SOIL) && !EarthLine.soilType.isEmpty()) addFrame(fs[2], "\uD83C\uDF31 " + EarthLine.soilType, EarthLine.soilMoist, EarthLine.soilSplit);
                if (Cfg.b(Cfg.EL_ROCK, Cfg.D_EL_ROCK) && !EarthLine.rockType.isEmpty()) addFrame(fs[2], "\uD83E\uDEA8 " + EarthLine.rockType, EarthLine.rockAge, EarthLine.rockUnit);
                try {   // nearest wetland / water body, then the nearest Ramsar site within 200 km
                    if (Cfg.b(Cfg.EL_WETLAND, Cfg.D_EL_WETLAND)) {
                        Wetland.Hit w1 = Wetland.wetland, w2 = Wetland.ramsar;
                        if (w1 != null) addFrame(fs[9], "\uD83E\uDD86 Wetland " + w1.text());
                        if (w2 != null && w2.km <= 200) addFrame(fs[9], "\uD83E\uDD86 Ramsar " + w2.text().replace(" \u00b7 wetland", ""));
                    }
                } catch (Throwable ignored) { }
                if (Cfg.b(Cfg.EL_POWER, Cfg.D_EL_POWER)) addFrame(fs[3], EarthLine.powerLine, EarthLine.powerSub);
                if (Cfg.b(Cfg.EL_LAND, Cfg.D_EL_LAND)) addFrame(fs[4], alt, cl, gps, EarthLine.peak);
                else if (gps != null) addFrame(fs[4], gps);
                if (Cfg.b(Cfg.EL_QUAKE, Cfg.D_EL_QUAKE)) addFrame(fs[5], EarthLine.quake);
                if (Cfg.b(Cfg.EL_EARTH, Cfg.D_EL_EARTH)) addFrame(fs[6], EarthLine.district);
                try {   // tectonic plate and nearest plate boundary (with the faults)
                    if (Cfg.b(Cfg.EARTH_SLOT, Cfg.D_EARTH_SLOT)) {
                        String tPc = EarthSci.plateCode(fla, flo);
                        if (tPc != null) {
                            double[] tMo = EarthSci.motion(tPc);
                            addFrame(fs[8], "\uD83C\uDF0D " + EarthSci.plateTitle(tPc), tMo == null ? null : EarthSci.arrow(tMo[1]) + " \u2248" + Math.round(tMo[0]) + " cm/yr " + EarthSci.dir16(tMo[1]));
                        }
                        EarthSci.Bound tB = EarthSci.nearest(fla, flo);
                        if (tB != null) addFrame(fs[8], tB.label + " " + tB.pair, EarthSci.fmtKm(tB.km) + " km " + EarthSci.dir16(tB.az), tB.rate >= 0 ? String.format(Locale.US, "%.1f cm/yr", tB.rate) : null);
                    }
                } catch (Throwable ignored) { }
                try {   // nearest mapped faults: the nearest of any kind, then the nearest named one
                    if (Cfg.b(Cfg.FAULT, Cfg.D_FAULT)) {
                        java.util.List<Faults.Hit> fh = Faults.nearest(fla, flo, 8);
                        double fLim = Cfg.i(Cfg.FAULT_KM, Cfg.D_FAULT_KM);
                        Faults.Hit h1 = fh.isEmpty() ? null : fh.get(0), h2 = null;
                        for (Faults.Hit x : fh) if (x.named()) { h2 = x; break; }
                        if (h1 != null && h1.km <= fLim) addFrame(fs[8], Faults.text(h1), Faults.detail(h1));
                        if (h2 != null && h2 != h1 && h2.km <= fLim) addFrame(fs[8], Faults.text(h2), Faults.detail(h2));
                        if (fs[8].isEmpty() && h1 != null) addFrame(fs[8], "No mapped fault within " + Math.round(fLim) + " km");
                    }
                } catch (Throwable ignored) { }
                try {   // nearest dam: coloured dot for the state, name, % full, distance, hydro MW
                    Dam.Reading dg = Dam.curRead;
                    Dam.Res dr = Dam.cur;
                    if (Cfg.b(Cfg.DAM, Cfg.D_DAM) && dg != null && dr != null && System.currentTimeMillis() - Dam.fetchedAt < 3 * 86400_000L) {
                        int ds = Dam.state(dg.pct);
                        String dn = dr.name.equals(dr.name.toUpperCase(Locale.ROOT)) ? dr.name.substring(0, 1) + dr.name.substring(1).toLowerCase(Locale.ROOT) : dr.name;
                        String[] dot = {"\uD83D\uDD34", "\uD83D\uDFE0", "\uD83D\uDFE2", "\uD83D\uDD35", "\uD83D\uDFE3"};
                        String rv = dr.river == null ? "" : dr.river;
                        boolean rvq = rv.contains("(unconfirmed)");
                        rv = rv.replaceAll("\\s*\\(.*\\)", "").trim();   // "Bhama (Bhima tributary)" -> "Bhama"; the tributary note stays in the details dialog
                        addFrame(fs[7], dot[ds] + " " + dn + " dam" + (rv.isEmpty() ? "" : " on " + rv + (rvq ? "?" : "")) + " " + (rv.isEmpty() ? "" : "\u00b7 ") + Math.round(dg.pct) + "% full", Dam.kmText(Dam.curKm),
                                dr.mw > 0 ? Math.round(dr.mw) + " MW" : null, dr.approx ? "approx." : null);
                    }
                } catch (Throwable ignored) { }
                // order presets over: 0 river 1 rain 2 soil 3 power 4 land 5 quake 6 earth 7 dam 8 faults 9 wetland
                final int[][] ORD = {{0, 7, 9, 1, 2, 10, 3, 4, 5, 6, 8}, {6, 4, 0, 7, 9, 10, 1, 2, 3, 5, 8}, {0, 7, 9, 10, 1, 2, 4, 3, 5, 6, 8}, {5, 1, 0, 7, 9, 3, 4, 2, 10, 6, 8}};
                int[] od = ORD[Math.max(0, Math.min(ORD.length - 1, Cfg.i(Cfg.EL_ORDER, Cfg.D_EL_ORDER)))];
                for (int q : od) fr.addAll(fs[q]);
                if (fr.isEmpty()) addFrame(fr, alt, cl);
                int spd = Math.max(3, Math.min(8, Cfg.i(Cfg.EL_SPEED, Cfg.D_EL_SPEED)));
                // fixed size 18; a frame that does not fit is split into two (or more) screens of whole pieces
                final float sz = 18;
                String lead = hasPl ? "\uD83D\uDCCD " + pl : "";
                java.util.List<String> screens = new java.util.ArrayList<>();
                for (String frame : fr) {
                    String cur = null;
                    // a frame that only fits without the place name drops "Ravet" (marked with \u0002, removed when drawn)
                    boolean noLead = !lead.isEmpty() && px + tw(join(lead, frame.replace("\u0001", " \u00b7 ")), sz, semi) > lineEnd
                            && px + tw(frame.replace("\u0001", " \u00b7 "), sz, semi) <= lineEnd;
                    if (noLead) { screens.add("\u0002" + frame.replace("\u0001", " \u00b7 ")); continue; }
                    for (String piece : frame.split("\u0001")) {
                        if (cur == null) { cur = piece; continue; }
                        String trial = join(lead, cur + " \u00b7 " + piece);
                        if (px + tw(trial, sz, semi) <= lineEnd) cur += " \u00b7 " + piece;
                        else { screens.add(cur); cur = piece; }
                    }
                    if (cur != null) screens.add(cur);
                }
                if (screens.isEmpty()) screens.add("");
                String body = screens.get((int) ((System.currentTimeMillis() / (spd * 1000L)) % screens.size()));
                boolean dropLead = body.startsWith("\u0002");
                String t = dropLead ? body.substring(1) : join(lead, body);
                boolean withGps = gps != null && t.contains(gps);   // the GPS text lives in the altitude frame, not on every frame
                while (t.length() > 4 && px + tw(t, sz, semi) > lineEnd) t = t.substring(0, t.length() - 2) + "\u2026";
                if (!t.isEmpty()) text(c, t, px, y(P2) + 26, sz, semi, withGps ? (sats >= 8 ? green() : yellow()) : grey());
                if (moonT != null) {
                    float tEnd = px + tw(t, sz, semi), mx = lineEnd0 - moonTw;
                    if (tEnd + 16 <= mx) text(c, moonT, mx, y(P2) + 26, sz, semi, grey());       // room beside the frame: full strength
                    else {                                                                         // frame runs long: the moon text shows faintly behind its end
                        c.saveLayerAlpha(mx - 4, y(P2) + 26 - 16, lineEnd0 + 4, y(P2) + 26 + 16, 80);
                        text(c, moonT, mx, y(P2) + 26, sz, semi, grey());
                        c.restore();
                    }
                }
            }
        } catch (Throwable ignored) { }
    }


    /** Adds one rotating frame made of the non-empty pieces, or nothing when every piece is empty. */
    private static void addFrame(java.util.List<String> fr, String... pieces) {
        StringBuilder sb = new StringBuilder();
        for (String p : pieces) if (p != null && !p.isEmpty()) sb.append(sb.length() > 0 ? "\u0001" : "").append(p);
        if (sb.length() > 0) fr.add(sb.toString());
    }

    private static String join(String lead, String body) {
        return lead.isEmpty() ? body : body.isEmpty() ? lead : lead + " \u00b7 " + body;
    }

    /** 24 h strip: night / twilight / day / golden hour, a dot for now, sunrise and sunset times, and the next event as a countdown. */
    private float dayTimeline(Canvas c, float x0, float room, float cy, long now) {
        double la = fix != null ? fix[0] : latLon[0], lo = fix != null ? fix[1] : latLon[1];
        double[] sd = SunTimes.today(la, lo), cv = SunTimes.today(la, lo, 96), gd = SunTimes.today(la, lo, 84);
        if (Double.isNaN(sd[0]) || Double.isNaN(sd[1]) || Double.isNaN(cv[0]) || Double.isNaN(gd[0])) return x0;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        double nowM = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE) + cal.get(java.util.Calendar.SECOND) / 60.0;
        String tUp = SunTimes.hhmm(sd[0]), tDn = SunTimes.hhmm(sd[1]);
        float tUpW = tw("\u2191" + tUp, 17, bold), tDnW = tw("\u2193" + tDn, 17, bold);
        float barW = Math.min(110f, room - tUpW - tDnW - 80);
        if (barW < 70) return x0;
        float ux = x0, bx = ux + tUpW + 4, ex = bx + barW;
        textB(c, "\u2191" + tUp, ux, cy, 22, 17, bold, yellow());
        float by0 = cy - 5, by1 = cy + 5;
        // segments
        double[][] seg = {{0, cv[0], 0}, {cv[0], sd[0], 1}, {sd[0], gd[0], 3}, {gd[0], gd[1], 2}, {gd[1], sd[1], 3}, {sd[1], cv[1], 1}, {cv[1], 1440, 0}};
        int[] col = {0xFF0C1A30, 0xFF4B3B78, 0xFF3F7FBF, 0xFFC9A23A};
        gp.setStyle(Paint.Style.FILL);
        for (double[] sg : seg) {
            if (sg[1] <= sg[0]) continue;
            int cc = sg[2] == 0 ? col[0] : sg[2] == 1 ? col[1] : sg[2] == 2 ? col[2] : col[3];
            gp.setColor(cc);
            rf.set(bx + barW * (float) (sg[0] / 1440), by0, bx + barW * (float) (sg[1] / 1440), by1);
            c.drawRect(rf, gp);
        }
        gp.setStyle(Paint.Style.STROKE);
        gp.setStrokeWidth(1f);
        gp.setColor(fade(color, 0.35f));
        rf.set(bx, by0, ex, by1);
        c.drawRoundRect(rf, 5, 5, gp);
        float nx = bx + barW * (float) (nowM / 1440);
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(0xFFFFFFFF);
        c.drawRect(nx - 1, by0 - 3, nx + 1, by1 + 3, gp);
        c.drawCircle(nx, cy, 3.6f, gp);
        float ds = textB(c, "\u2193" + tDn, ex + 4, cy, 22, 17, bold, orange());
        // next event
        double[] ev = {cv[0], sd[0], sd[1], cv[1]};
        String[] nm = {"dawn", "rise", "set", "dusk"};
        int bi = -1;
        double bd = 1e9;
        for (int i = 0; i < 4; i++) {
            double dm = ev[i] - nowM;
            if (dm < -3) continue;
            if (dm < bd) { bd = dm; bi = i; }
        }
        String msg;
        int mcol;
        if (bi < 0) { bd = 1440 - nowM + cv[0]; msg = "dawn " + SunTimes.dur(bd); mcol = 0xFFB18CFF; }
        else {
            msg = nm[bi] + (bd < 1 ? " now" : " " + SunTimes.dur(bd));
            mcol = bi == 1 ? yellow() : bi == 2 ? orange() : 0xFFB18CFF;
        }
        float mx = ds + 10, avail = x0 + room - mx;
        float sz = 18;
        while (sz > 13 && tw(msg, sz, semi) > avail) sz -= 0.5f;
        if (tw(msg, sz, semi) <= avail) text(c, msg, mx, cy, sz, semi, mcol);
        return Math.max(ds, tw(msg, sz, semi) <= avail ? mx + tw(msg, sz, semi) : ds);
    }

    /** Map-pin outline with a dot: round head, pointed tip, 18 wide x 24 tall, centred on cy. */
    private void pin(Canvas c, float x, float cy, int col) {
        float r = 7.5f, hx = x + 9, hy = cy - 4;
        android.graphics.Path p = new android.graphics.Path();
        rf.set(hx - r, hy - r, hx + r, hy + r);
        p.arcTo(rf, 140, 260, true);                       // head, open at the bottom
        p.lineTo(hx, cy + 12);                             // down to the tip
        p.close();
        gp.setStyle(Paint.Style.STROKE);
        gp.setStrokeWidth(2.4f);
        gp.setStrokeJoin(Paint.Join.ROUND);
        gp.setColor(col);
        c.drawPath(p, gp);
        gp.setStyle(Paint.Style.FILL);
        c.drawCircle(hx, hy, 2.8f, gp);
    }

    private void proSpeed(Canvas c, boolean isDown, long bps, float x, float cy) {
        boolean idle = bps < 1024;
        int arrow = idle ? grey() : isDown ? green() : blue();
        int val = idle ? grey() : bps >= (10L << 20) ? teal() : color;
        String[] f = fmtSpeed(bps);
        float e = textB(c, isDown ? "↓" : "↑", x, cy, 30, 30, bold, arrow);
        e = textB(c, f[0], e + 3, cy, 30, 30, bold, val);
        textB(c, f[1], e + 2, cy, 30, 20, semi, grey());
    }

    /** {"06:30", "r"} for the next sunrise, {"18:09", "s"} for the next sunset. */
    private String[] nextSun(long now) {
        try {
            double[] sun = SunTimes.today(latLon[0], latLon[1]);
            if (Double.isNaN(sun[0]) || Double.isNaN(sun[1])) return null;
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(now);
            double m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            if (m >= sun[0] && m < sun[1]) return new String[]{SunTimes.hhmm(sun[1]), "s"};
            return new String[]{SunTimes.hhmm(sun[0]), "r"};
        } catch (Throwable t) {
            return null;
        }
    }

    /** Framed slot for the system icons: Wi-Fi, Bluetooth, ringer / DND, VPN, then the stock tray icons. */
    private void proSlot(Canvas c, float x0, float x1, float cy) {
        if (x1 - x0 < 44) return;
        // no frame any more: the icons sit straight on the row and device values fill what is left
        float x = x0 + 2, lim = x1 - 6;
        int hidden = 0;
        if (wifiOn) {
            int lc = wifiLevel >= 3 ? green() : wifiLevel == 2 ? yellow() : wifiLevel == 1 ? orange() : red();
            float need = 28 + (wifiRssi != 0 ? 4 + tw(String.valueOf(wifiRssi), 20, bold) : 0);
            if (x + need <= lim) {
                glyph(c, G_WIFI, x, cy - 2, 28, lc);
                x += 30;
                if (wifiRssi != 0) x = textB(c, minus(String.valueOf(wifiRssi)), x, cy, 22, 20, bold, lc) + 8;
            } else hidden++;
        }
        if (btOn) {
            int bl = devs.isEmpty() ? -1 : devs.get(0).battery;
            String bp = bl >= 0 && Cfg.b(Cfg.SH_BTBAT, Cfg.D_SH_BTBAT) ? bl + "%" : null;
            float need = 20 + (bp != null ? 4 + 11 + 4 + tw(bp, 18, bold) : 0);
            if (x + need <= lim) {
                glyph(c, G_BT, x, cy, 18, devs.isEmpty() ? grey() : blue());
                x += 22;
                if (bp != null) {
                    int bc = SysViews.levelColor(bl, dk);
                    float bx = x + 2, bt = cy - 10, bb = cy + 10, bw = 9;
                    gp.setStyle(Paint.Style.FILL);
                    gp.setColor(color);
                    rf.set(bx + 2.5f, bt - 2.5f, bx + bw - 2.5f, bt);
                    c.drawRect(rf, gp);                                         // nub
                    gp.setStyle(Paint.Style.STROKE);
                    gp.setStrokeWidth(1.6f);
                    rf.set(bx, bt, bx + bw, bb);
                    c.drawRoundRect(rf, 2, 2, gp);                              // body
                    gp.setStyle(Paint.Style.FILL);
                    gp.setColor(bc);
                    float fh = (bb - bt - 3) * Math.max(0, Math.min(100, bl)) / 100f;
                    rf.set(bx + 1.5f, bb - 1.5f - fh, bx + bw - 1.5f, bb - 1.5f);
                    c.drawRect(rf, gp);                                         // fill
                    x = textB(c, bp, bx + bw + 4, cy, 18, 18, bold, bc) + 8;
                } else x += 4;
            } else hidden++;
        }
        if (ringer != 0) {
            if (x + 24 <= lim) {
                int g = ringer == 1 ? G_VIB : ringer == 2 ? G_SIL : G_DND;
                glyph(c, g, x, cy, 22, ringer == 1 ? orange() : ringer == 2 ? red() : purple());
                x += 30;
            } else hidden++;
        }
        if (vpn) {
            float vw = tw("VPN", 17, bold) + 10;
            if (x + vw <= lim) {
                gp.setStyle(Paint.Style.STROKE);
                gp.setStrokeWidth(2f);
                gp.setColor(green());
                rf.set(x, cy - 11, x + vw, cy + 11);
                c.drawRoundRect(rf, 5, 5, gp);
                textB(c, "VPN", x + 5, cy, 17, 17, bold, green());
                x += vw + 8;
            } else hidden++;
        }
        long nowF = System.currentTimeMillis();
        if (nowF - flagsAt > 4000) {
            flagsAt = nowF;
            int fl = 0;
            try {
                android.content.Context cx = getContext();
                try { if (android.provider.Settings.Global.getInt(cx.getContentResolver(), "airplane_mode_on", 0) == 1) fl |= 1; } catch (Throwable ignored) { }
                try { if (((android.os.PowerManager) cx.getSystemService(android.content.Context.POWER_SERVICE)).isPowerSaveMode()) fl |= 2; } catch (Throwable ignored) { }
                try {
                    android.nfc.NfcAdapter na = android.nfc.NfcAdapter.getDefaultAdapter(cx);
                    if (na != null && na.isEnabled()) fl |= 4;
                } catch (Throwable ignored) { }
                try {
                    android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) cx.getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
                    if (Boolean.TRUE.equals(wm.getClass().getMethod("isWifiApEnabled").invoke(wm))) fl |= 8;
                } catch (Throwable ignored) { }
            } catch (Throwable ignored) { }
            sysFlags = fl;
        }
        String[] chipT = {"✈", "SAVER", "NFC", "HOTSPOT"};
        for (int k = 0; k < 4; k++) {
            if ((sysFlags & (1 << k)) == 0) continue;
            int cc = k == 0 ? red() : k == 1 ? yellow() : k == 2 ? blue() : teal();
            float vw = tw(chipT[k], 17, bold) + 10;
            if (x + vw > lim) { hidden++; continue; }
            gp.setStyle(Paint.Style.STROKE);
            gp.setStrokeWidth(2f);
            gp.setColor(cc);
            rf.set(x, cy - 11, x + vw, cy + 11);
            c.drawRoundRect(rf, 5, 5, gp);
            textB(c, chipT[k], x + 5, cy, 17, 17, bold, cc);
            x += vw + 8;
        }
        for (int i = 0; i < tray.size(); i++) {
            Bitmap b = tray.get(i);
            float ih = 24, iw = b.getHeight() == 0 ? ih : ih * b.getWidth() / b.getHeight();
            if (x + iw > lim) { hidden += tray.size() - i; break; }
            ip.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            rf.set(x, cy - ih / 2, x + iw, cy + ih / 2);
            c.drawBitmap(b, null, rf, ip);
            x += iw + 6;
        }
        if (hidden > 0) {
            String p = "+" + hidden;
            if (x + tw(p, 18, bold) <= x1 - 4) x = textB(c, p, x, cy, 18, 18, bold, grey()) + 10;
        } else if (x > x0 + 4) x += 4;
        try { slotValues(c, x, x1 - 4, cy); } catch (Throwable ignored) { }
    }

    /** Technical device values after the system icons, in priority order; whatever does not fit is dropped (lowest priority first). */
    private void slotValues(Canvas c, float x0, float x1, float cy) {
        int dim = fade(grey(), 0.78f);
        List<Item> its = new ArrayList<>();
        if (false && ping > 0) {   // the data SIM box already shows the ping
            int qc = ping < 60 ? green() : ping < 150 ? yellow() : ping < 400 ? orange() : red();
            its.add(new Item(10, 24).t("PING ", 14, semi, dim).t(String.valueOf(ping), 22, bold, qc).t("ms", 14, semi, dim));
        }
        if (!Float.isNaN(ghz) && ghz > 0 && Cfg.b(Cfg.SH_GHZ, Cfg.D_SH_GHZ))
            its.add(new Item(9, 24).t("C ", 14, semi, dim).t(String.format(Locale.US, "%.1f", ghz), 22, bold, teal()).t("GHz", 14, semi, dim));
        if (ramFree >= 0 && Cfg.b(Cfg.SH_RAM, Cfg.D_SH_RAM)) {
            double g = ramFree / 1073741824.0;
            boolean low = ramTotal > 0 && ramFree * 10 < ramTotal;
            its.add(new Item(8, 24).t("R ", 14, semi, dim).t(String.format(Locale.US, "%.1f", g), 22, bold, low ? orange() : green()).t("G", 14, semi, dim));
        }
        if (hz > 0 && Cfg.b(Cfg.SH_HZ, Cfg.D_SH_HZ)) its.add(new Item(7, 24).t(String.valueOf(hz), 22, bold, blue()).t("Hz", 14, semi, dim));
        if (deep >= 0 && Cfg.b(Cfg.SH_SLEEP, Cfg.D_SH_SLEEP)) its.add(new Item(6, 24).t("S ", 14, semi, dim).t(deep + "%", 22, bold, deep >= 60 ? green() : deep >= 30 ? yellow() : orange()));
        int ho = handoversToday();
        if (Cfg.b(Cfg.SH_TWR, Cfg.D_SH_TWR)) its.add(new Item(5, 24).t("\u2194", 20, bold, hoRecent() ? orange() : dim).t(String.valueOf(ho), 22, bold, ho > 30 ? orange() : color).t(" twr", 14, semi, dim));
        if (x1 - x0 < 60) return;
        drawLine(c, its, x0, cy, x1, 14);
    }

    // ---- centre: battery health and cycles under the camera (numbers only)

    /** GPS height when there is one, else the ground height at your position from the weather service ("~"). */
    private String altText() {
        if (fix != null && !Double.isNaN(fix[2])) return " · " + Math.round(fix[2]) + "m";
        if (!Float.isNaN(groundElev)) return " · ~" + Math.round(groundElev) + "m";
        return "";
    }

    private String healthText() {
        if (!Cfg.b(Cfg.SH_HEALTH, Cfg.D_SH_HEALTH)) return "";
        return (soh > 0 ? soh + "%" : "--") + " · " + (cycles >= 0 ? String.valueOf(cycles) : "--");   // altitude removed
    }

    private void proCam(Canvas c, float cx, float cy, float r) {
        try {
            float room = cy - r - 2;                         // free height above the hole
            if (room >= 9) {
                float size = Math.min(21f, room * 1.2f);
                // real phase and lit fraction for your place and time
                double ph;
                int lit;
                try {
                    Astro.Moon mo = Astro.moon(System.currentTimeMillis(), fix != null ? fix[0] : latLon[0], fix != null ? fix[1] : latLon[1]);
                    ph = mo.phase;
                    lit = (int) Math.round(mo.illum * 100);
                } catch (Throwable t) {
                    ph = moonPhase();
                    lit = (int) Math.round(50 * (1 - Math.cos(2 * Math.PI * ph)));
                }
                String nm = phaseName(lit, ph < 0.5);
                // phase name on one line, same size as the solar time / day-time name beside it
                float half = tw(nm, size, semi) / 2f;
                float top = Math.max(size * 0.38f + 1, room / 2f + 1);
                // best: the phase name in the thin band above the SIM boxes, which frees the whole strip up to
                // the SIM boxes for the solar time and the ISS line; otherwise slide it right up to the SIM boxes
                float nameX = cx - half;
                boolean upTop = false, solUp = false;
                stripC0 = stripC1 = 0;
                if (!Float.isNaN(simLeft)) {
                    float bandH = y(P1A) - 24 - 1;                // above the SIM frame's top edge
                    // whole letters inside the band: cap height ~0.72 x size, descenders ~0.2 x size
                    // a bit smaller than the band, anchored to the SIM boxes' top line so tall letters can't reach the edge
                    float bs = Math.min(size, bandH * 1.15f);
                    float bx = simLeft + 6, bw = tw(nm, bs, semi);
                    float by = bandH / 2f + 0.5f;
                    float bEnd = cornerRightX(by - bs * 0.4f) - 6;
                    String[] solB = solarNow();
                    float sw = solB == null ? 0 : tw(solB[0], bs, bold) + 8 + tw(solB[1], bs, semi);
                    // long phase names ("Nautical twilight"): shorten the word first, then the text a little,
                    // so the solar time stays up beside the moon phase instead of dropping to the centre
                    // auto-fit: full words a little smaller first; only if that isn't enough, short words
                    if (solB != null && bx + sw + 16 + bw > bEnd) {
                        String[][] tries = {
                                {solB[1], nm},
                                {solB[1].replace("Astronomical twilight", "Astro twilight").replace("Nautical twilight", "Nautical tw.")
                                        .replace("Civil twilight", "Civil tw."), nm},
                                {solB[1].replace("Astronomical twilight", "Astro twilight").replace("Nautical twilight", "Nautical tw.")
                                        .replace("Civil twilight", "Civil tw."), nm.replace("Waning ", "Wan. ").replace("Waxing ", "Wax. ")}};
                        float floor = Math.max(11f, bs * 0.85f);
                        for (int ti = 0; ti < tries.length; ti++) {
                            float min = ti == tries.length - 1 ? 11f : floor;
                            float bs2 = bs;
                            float sw2 = tw(solB[0], bs2, bold) + 8 + tw(tries[ti][0], bs2, semi), bw2 = tw(tries[ti][1], bs2, semi);
                            while (bs2 > min && bx + sw2 + 16 + bw2 > bEnd) {
                                bs2 -= 0.5f;
                                sw2 = tw(solB[0], bs2, bold) + 8 + tw(tries[ti][0], bs2, semi);
                                bw2 = tw(tries[ti][1], bs2, semi);
                            }
                            if (bx + sw2 + 16 + bw2 <= bEnd) {
                                solB = new String[]{solB[0], tries[ti][0]};
                                nm = tries[ti][1]; bs = bs2; sw = sw2; bw = bw2;
                                break;
                            }
                        }
                    }
                    stripC0 = stripC1 = 0;
                    float solX = bx;
                    if (bs >= 11 && solB != null && bx + sw + 16 + bw <= bEnd) {
                        // strip text too long for the room left of the camera hole: slide the solar time next to the phase name
                        // and let the strip carry on after the hole (the day name gets "…" when it has to give way)
                        try {
                            String stx = satText;
                            float ss0 = 18f;
                            float stop0 = Math.max(top, ss0 * 0.5f + 1);
                            float sx00 = Math.max(20, cornerX(stop0 - ss0 * 0.4f) + 4);
                            float hc = Float.isNaN(camX) ? W / 2f : camX / s;
                            float hrad = Float.isNaN(camR) ? 24f : Math.max(14f, Math.min(34f, camR / s));
                            float camLx = hc - hrad - 4, camRx = hc + hrad + 4;
                            if (camRx < bEnd) {
                                // always sit beside the phase name when there is room; the day name only gives way when the strip text needs the space
                                float want = stx == null || stx.isEmpty() ? 0 : Math.max(0, Math.min(tw(stx, ss0, semi) - (camLx - sx00), 280f));
                                String dn = solB[1];
                                float w0 = tw(solB[0], bs, bold) + 8;
                                float swX = w0 + tw(dn, bs, semi);
                                while (dn.length() > 4 && (bEnd - bw - 14 - swX - 12) - camRx < want) {
                                    dn = dn.replace("\u2026", "");
                                    dn = dn.substring(0, dn.length() - 1).trim() + "\u2026";
                                    swX = w0 + tw(dn, bs, semi);
                                }
                                float sxNew = bEnd - bw - 14 - swX;
                                if (sxNew > bx && sxNew - 12 - camRx >= 40) {
                                    solB = new String[]{solB[0], dn};
                                    sw = swX;
                                    solX = sxNew;
                                    stripC0 = camRx;
                                    stripC1 = sxNew - 12;
                                }
                            }
                        } catch (Throwable ignored) { }
                    }
                    if (bs >= 11 && solB != null && bx + sw + 16 + bw <= bEnd) {
                        // solar time and phase name both above the SIM boxes; the name pushed to the right end
                        if (!simUp) {
                            float e1 = text(c, solB[0], solX, by, bs, bold, yellow());
                            text(c, solB[1], e1 + 8, by, bs, semi, color);
                            text(c, nm, bEnd - bw, by, bs, semi, grey());
                        }
                        upTop = solUp = true;
                        nameX = simLeft + 14;                     // the ISS line may now run up to the SIM boxes
                        half = 0;
                    } else if (bs >= 11 && bx + bw <= bEnd) {
                        if (!simUp) text(c, nm, bEnd - bw, by, bs, semi, grey());   // name only, as far right as it goes
                        upTop = true;
                        nameX = simLeft + 14;                     // so the solar time (nameX - 22) ends 8 short of the SIM boxes
                        half = 0;
                    } else {
                        nameX = Math.max(nameX, simLeft - 10 - 2 * half);
                    }
                }
                if (!upTop && !simUp) text(c, nm, nameX, top, size, semi, grey());
                // ISS / satellite line at the left of the strip, above the clock (a bit larger than the strip text)
                try {
                    String sat = satText;
                    if (sat != null && !sat.isEmpty()) {
                        float ss = 18f;
                        float stop = Math.max(top, ss * 0.5f + 1);
                        float sx0 = Math.max(20, cornerX(stop - ss * 0.4f) + 4);
                        float maxX = nameX - 22;
                        // the camera hole sits in this row: the ISS line stops before it
                        float camL = (Float.isNaN(camX) ? W / 2f : camX / s) - (Float.isNaN(camR) ? 24f : Math.max(14f, Math.min(34f, camR / s))) - 4;
                        maxX = Math.min(maxX, camL);
                        String[] sol0 = solarNow();
                        if (sol0 != null && !solUp && !simUp) maxX -= tw(sol0[0], size, bold) + 8 + tw(sol0[1], size, semi) + 14;
                        int scol = satLive ? teal() : grey();
                        String st = sat;
                        // too long: shorten the satellite's name first, keeping "ISS ..." and the city / country after it
                        String nmS = satNameS, cityS = satCityS;
                        if (sx0 + tw(st, ss, semi) > maxX && nmS != null && !nmS.isEmpty() && satIssS != null) {
                            String n2 = nmS;
                            while (n2.length() > 3) {
                                n2 = n2.substring(0, n2.length() - 1);
                                st = satIssS + " · " + n2.trim() + "…" + cityS;
                                if (sx0 + tw(st, ss, semi) <= maxX) break;
                            }
                        }
                        if (stripC1 > stripC0 && sx0 + tw(st, ss, semi) > camL) {
                            // carry on after the camera hole, at a word break
                            String[] wd = st.split(" ");
                            StringBuilder left = new StringBuilder();
                            int k = 0;
                            while (k < wd.length) {
                                String t2 = left.length() == 0 ? wd[k] : left + " " + wd[k];
                                if (sx0 + tw(t2, ss, semi) > camL) break;
                                left = new StringBuilder(t2);
                                k++;
                            }
                            if (left.length() > 0 && k < wd.length) {
                                StringBuilder rest = new StringBuilder();
                                for (int q = k; q < wd.length; q++) rest.append(q > k ? " " : "").append(wd[q]);
                                String rs = rest.toString();
                                while (rs.length() > 4 && stripC0 + tw(rs, ss, semi) > stripC1) rs = rs.substring(0, rs.length() - 2) + "…";
                                text(c, left.toString(), sx0, stop, ss, semi, scol);
                                text(c, rs, stripC0, stop, ss, semi, scol);
                                st = "";
                            }
                        }
                        while (st.length() > 4 && sx0 + tw(st, ss, semi) > maxX) st = st.substring(0, st.length() - 2) + "…";
                        if (!st.isEmpty() && sx0 + tw(st, ss, semi) <= maxX) text(c, st, sx0, stop, ss, semi, scol);
                    }
                } catch (Throwable ignored) { }
                try {
                    String[] sol = solUp || simUp ? null : solarNow();
                    if (sol != null) {
                        float sx1 = nameX - 22;
                        float w1 = tw(sol[0], size, bold), w2 = tw(sol[1], size, semi);
                        float sx0 = sx1 - w1 - 8 - w2;
                        if (sx0 >= Math.max(20, cornerX(top - size * 0.4f) + 4)) {
                            float e1 = text(c, sol[0], sx0, top, size, bold, yellow());
                            text(c, sol[1], e1 + 8, top, size, semi, color);
                        }
                    }
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        String a = healthText();
        // under the camera hole, between the speeds and the 4G / 5G data counters; shrunk to fit that gap
        float HS = HP2_SIZE;
        float room = 2 * r + 22;
        float fw = tw(a, HS, bold);
        if (fw > room) HS = Math.max(12f, HS * room / fw);
        float ly = y(P1A) - 6;   // the thin gap under the ISS / satellite line, centred over the camera
        hP2X = cx - tw(a, HS, bold) / 2f;
        int hc = soh <= 0 ? color : soh >= 85 ? green() : soh >= 75 ? yellow() : soh >= 65 ? orange() : red();
        String pct = soh > 0 ? soh + "%" : "--";
        String alt = "";
        // small battery outline before the health figure
        {
            float bh = Math.max(12f, HS * 0.8f), bw = bh * 0.52f, bx = hP2X - bw - 7f;
            gp.setStyle(Paint.Style.FILL);
            gp.setColor(hc);
            rf.set(bx + bw * 0.3f, ly - bh / 2f - 2f, bx + bw * 0.7f, ly - bh / 2f);
            c.drawRect(rf, gp);                                    // nub
            gp.setStyle(Paint.Style.STROKE);
            gp.setStrokeWidth(1.8f);
            rf.set(bx, ly - bh / 2f, bx + bw, ly + bh / 2f);
            c.drawRoundRect(rf, 2.5f, 2.5f, gp);                   // body
            gp.setStyle(Paint.Style.FILL);
            float fh = (bh - 4f) * (soh > 0 ? Math.min(100, soh) / 100f : 0.5f);
            rf.set(bx + 2f, ly + bh / 2f - 2f - fh, bx + bw - 2f, ly + bh / 2f - 2f);
            c.drawRect(rf, gp);                                    // fill = health
        }
        float e = text(c, pct, hP2X, ly, HS, bold, hc);
        e = text(c, a.substring(pct.length(), a.length() - alt.length()), e, ly, HS, bold, color);
        if (!alt.isEmpty()) e = text(c, alt, e, ly, HS, bold, teal());
        healthEnd = row3End;   // row 3 is the weather's now: the quake fallback goes after it
    }

    // ---- towers switched today (both SIMs), reset at midnight
    private static volatile int hoCount;
    private static volatile int hoDay = -1;

    private static volatile long hoAt = 0;

    private static boolean hoRecent() {
        return System.currentTimeMillis() - hoAt < 10_000;
    }

    private static final int[] hoSim = new int[3];
    private static volatile int hoSimDay = -1;

    /** Tower switches today for SIM slot 1 or 2. */
    static int handoversFor(int slot) {
        int d = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR);
        return d == hoSimDay && slot >= 1 && slot <= 2 ? hoSim[slot] : 0;
    }

    static void handover(long now, int slot) {
        int d = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR);
        if (d != hoSimDay) { hoSimDay = d; hoSim[1] = 0; hoSim[2] = 0; }
        if (slot >= 1 && slot <= 2) hoSim[slot]++;
        handover(now);
    }

    static void handover(long now) {
        hoAt = System.currentTimeMillis();
        int d = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR);
        if (d != hoDay) { hoDay = d; hoCount = 0; }
        hoCount++;
    }

    private static int handoversToday() {
        int d = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR);
        return d == hoDay ? hoCount : 0;
    }

    /** "☀11h18m" by day (sunrise to sunset), "☾12h42m" by night (sunset to next sunrise). */
    private String dayNightLen() {
        double[] st = SunTimes.today(fix != null ? fix[0] : latLon[0], fix != null ? fix[1] : latLon[1]);
        if (st == null || Double.isNaN(st[0]) || Double.isNaN(st[1])) return null;
        java.util.Calendar c = java.util.Calendar.getInstance();
        double nowMin = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        double dayLen = st[1] - st[0];
        boolean day = nowMin >= st[0] && nowMin < st[1];
        int m = (int) Math.round(day ? dayLen : 1440 - dayLen);
        return (day ? "☀" : "☾") + (m / 60) + "h" + String.format(Locale.US, "%02d", m % 60) + "m";
    }

    /** Two tiny lines right of the pressure / ping strip, left of SIM 1's tower name: day or night length, towers switched today. */
    private void proGap2(Canvas c, float x0, float x1) {
        if (x1 - x0 < 30) return;
        String dl = dayNightLen();
        int ho = handoversToday();
        float cy = (y(P2) + 19 + y(P3) - 16) / 2f;
        float sz = 16;
        if (dl != null) fit(c, x0, cy - 8, tw(dl, sz, bold), x1 - x0, () -> text(c, dl, 0, 0, sz, bold, dl.startsWith("☀") ? yellow() : color));
        final boolean hot = hoRecent();
        final String hs = "↔" + ho + " twr" + (hot ? " now" : "");
        fit(c, x0, cy + 9, tw(hs, sz, bold), x1 - x0, () -> text(c, hs, 0, 0, sz, bold, hot || ho > 30 ? orange() : grey()));
    }

    /** Thin strip between row 2 and row 3, ending under the camera: pressure + trend, ping. */
    private void proGap(Canvas c, float cx, float cr) {
        List<Item> its = new ArrayList<>();
        if (!Float.isNaN(wxPres)) {
            String ar = Float.isNaN(wxPresTrend) || Math.abs(wxPresTrend) < 0.5f ? "→" : wxPresTrend > 0 ? "↑" : "↓";
            int pc = Float.isNaN(wxPresTrend) ? grey() : wxPresTrend <= -1.5f ? orange() : wxPresTrend >= 1.5f ? green() : color;
            its.add(new Item(5, 18).t(String.valueOf(Math.round(wxPres)), 18, bold, color).t(ar, 17, bold, pc));
        }
        if (ping > 0) {
            int qc = ping < 60 ? green() : ping < 150 ? yellow() : ping < 400 ? orange() : red();
            its.add(new Item(4, 18).t("⇄", 16, bold, grey()).t(String.valueOf(ping), 18, bold, qc).t("ms", 13, semi, grey()));
        }
        if (its.isEmpty()) return;
        float gy = (y(P2) + 19 + y(P3) - 16) / 2f;
        float xR = cx + Math.min(cr, 10);
        drawTail(c, its, xR, gy, Math.max(30, xR - 260), 10);
    }

    /** Row 4 centre: moon phase disc + illumination, then the latest nearby quake (24 h). */
    private float skyX, skyRow, skyF = 1f;
    private long skyAt;
    private int skyDrawn, skyHidden;
    private String skyWhy = "";

    private void hidden(String why) {
        skyHidden++;
        skyWhy = why;
        skyDbg = "moon/quake drawn " + skyDrawn + ", hidden " + skyHidden + " (last: " + why + ")";
    }

    /** A frame that would hide the line keeps the last good placement for 15 s, so it doesn't blink. */
    private boolean holdSky(Canvas c, List<Item> its, double ph) {
        if (System.currentTimeMillis() - skyAt > 15_000L || its.isEmpty()) return false;
        c.save();
        c.scale(skyF, skyF, skyX, skyRow);
        try {
            float e = drawItem(c, its.get(0), skyX, skyRow);
            if (its.size() > 1) drawItem(c, its.get(1), e, skyRow);
        } finally {
            c.restore();
        }
        return true;
    }

    private float skyEnd4 = 0;

    /** Row 4, right end (under the SIM boxes): day/night length, towers switched today, pressure + trend, ping. */
    private void proTail4(Canvas c) {
        if (graphsOn()) return;
        List<Item> its = new ArrayList<>();
        String dl = dayNightLen();
        if (dl != null) its.add(new Item(3, 22).t(dl, 20, bold, dl.startsWith("☀") ? yellow() : color));
        if (!Float.isNaN(wxPres)) {
            String ar = Float.isNaN(wxPresTrend) || Math.abs(wxPresTrend) < 0.5f ? "→" : wxPresTrend > 0 ? "↑" : "↓";
            int pc = Float.isNaN(wxPresTrend) ? grey() : wxPresTrend <= -1.5f ? orange() : wxPresTrend >= 1.5f ? green() : color;
            its.add(new Item(5, 22).t(String.valueOf(Math.round(wxPres)), 20, bold, color).t(ar, 19, bold, pc));
        }
        float minX = Math.max(skyEnd4 + 16, Math.max(l4End + 16, Float.isNaN(simLeft) ? W * 0.6f : simLeft - 40));
        drawTail(c, its, W - 8, y(P4), minX, 14);
    }

    /** The row-4 sky frames: cloud type + base + cover, which way each cloud layer drifts, and the kind of wind. Each with shorter variants. */
    private List<List<Item>> wxSkyFrames() {
        List<List<Item>> out = new ArrayList<>();
        if (wxHum >= 0) {   // humidity, then wind (arrow + speed, gust when there is room)
            List<Item> v = new ArrayList<>();
            v.add(new Item(3, 24).g(G_DROP, 21, teal()).sp(5).t(wxHum + "%", 23, bold, color));
            v.add(new Item(3, 24).g(G_DROP, 21, teal()).sp(5).t(String.valueOf(wxHum), 24, bold, color));
            out.add(v);
        }
        boolean fresh = System.currentTimeMillis() - WxSky.at < 3 * 3600_000L;
        if (!Float.isNaN(wxWind)) {
            List<Item> v = new ArrayList<>();
            String gu0 = fresh ? WxSky.windGust : "", ww0 = fresh ? WxSky.windWord : "";
            String sp0 = (wxArrow == null ? "" : wxArrow) + Math.round(wxWind);
            if (!ww0.isEmpty() && !gu0.isEmpty())
                v.add(new Item(3, 24).t(ww0, 21, bold, wxWind < 20 ? green() : wxWind < 39 ? yellow() : orange()).sp(7).t(sp0, 22, bold, color).sp(7).t("gust", 16, semi, grey()).sp(4).t(gu0, 21, bold, yellow()));
            if (!gu0.isEmpty()) v.add(new Item(3, 24).t(sp0, 23, bold, color).sp(4).t("km/h", 15, semi, grey()).sp(7).t("gust", 16, semi, grey()).sp(4).t(gu0, 21, bold, yellow()));
            v.add(new Item(3, 24).t(sp0, 23, bold, color).sp(4).t("km/h", 15, semi, grey()));
            v.add(new Item(3, 24).t(sp0, 23, bold, color));
            out.add(v);
        }
        String kd = fresh ? WxSky.kind : "";
        if ("Clear sky".equals(kd)) kd = "Clear";
        if (kd != null && !kd.isEmpty()) {
            List<Item> v = new ArrayList<>();
            String bs = WxSky.base, cv = WxSky.cover;
            if (!bs.isEmpty() && !cv.isEmpty())
                v.add(new Item(3, 24).g(G_CLOUD, 22, blue()).sp(5).t(kd, 21, bold, color).sp(7).t("base", 16, semi, grey()).sp(4).t(bs, 21, bold, blue())
                        .sp(7).t("cover", 16, semi, grey()).sp(4).t(cv, 21, bold, color));
            if (!bs.isEmpty())
                v.add(new Item(3, 24).g(G_CLOUD, 22, blue()).sp(5).t(kd, 21, bold, color).sp(7).t("base", 16, semi, grey()).sp(4).t(bs, 21, bold, blue()));
            v.add(new Item(3, 24).g(G_CLOUD, 22, blue()).sp(5).t(kd, 21, bold, color));
            out.add(v);
        }
        String[][] ly = fresh ? WxSky.layers : null;
        if (ly != null && ly.length > 0) {
            List<Item> v = new ArrayList<>();
            for (int n = ly.length; n >= 1; n--) {
                Item it = new Item(3, 24).g(G_CLOUD, 22, blue());
                for (int i = 0; i < n; i++) it.sp(i == 0 ? 5 : 8).t(ly[i][0], 16, semi, grey()).sp(4).t(ly[i][1], 21, bold, color);
                v.add(it);
            }
            out.add(v);
        }
        return out;
    }

    /** The earth slot on row 4: the plate, the nearest plate boundary, the El Nino state; each with shorter versions. */
    private final List<String> earthNames = new ArrayList<>();

    private List<List<Item>> earthFrames() {
        List<List<Item>> out = new ArrayList<>();
        earthNames.clear();
        double la = fix != null ? fix[0] : latLon[0], lo = fix != null ? fix[1] : latLon[1];
        try {
            if (!Double.isNaN(EarthSci.oni) && System.currentTimeMillis() - EarthSci.at < 40L * 86400_000L) {
                String ph = EarthSci.phase(), st = EarthSci.strengthShort();
                double o = EarthSci.oni;
                int pcl = o >= 1.5 ? red() : o >= 0.5 ? orange() : o <= -0.5 ? blue() : grey();
                String val = String.format(Locale.US, "%+.1f\u00b0", o);
                List<Item> v = new ArrayList<>();
                String wh = EarthSci.whereShort();
                String stl = EarthSci.strength();
                if (!stl.isEmpty() && !wh.isEmpty()) v.add(new Item(3, 24).t(ph, 22, bold, pcl).sp(6).t(val, 22, bold, color).sp(6).t(stl, 16, semi, grey()).sp(6).t(wh, 16, semi, grey()));
                if (!stl.isEmpty()) v.add(new Item(3, 24).t(ph, 22, bold, pcl).sp(6).t(val, 22, bold, color).sp(6).t(stl, 16, semi, grey()));
                if (!st.isEmpty() && !wh.isEmpty()) v.add(new Item(3, 24).t(ph, 22, bold, pcl).sp(6).t(val, 22, bold, color).sp(6).t(st, 16, semi, grey()).sp(6).t(wh, 16, semi, grey()));
                if (!st.isEmpty()) v.add(new Item(3, 24).t(ph, 22, bold, pcl).sp(6).t(val, 22, bold, color).sp(6).t(st, 16, semi, grey()));
                v.add(new Item(3, 24).t(ph, 22, bold, pcl).sp(6).t(val, 22, bold, color));
                v.add(new Item(3, 24).t(ph, 19, bold, pcl));
                out.add(v); earthNames.add("El Ni\u00f1o");
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private void proSky(Canvas c, float cx) {
        skyEnd4 = 0;
        double ph = moonPhase();
        int lit = (int) Math.round(50 * (1 - Math.cos(2 * Math.PI * ph)));
        boolean waxing = ph < 0.5;
        try {
            Astro.Moon mo = Astro.moon(System.currentTimeMillis(), fix != null ? fix[0] : latLon[0], fix != null ? fix[1] : latLon[1]);
            ph = mo.phase;
            lit = (int) Math.round(mo.illum * 100);
            waxing = mo.waxing;
        } catch (Throwable ignored) { }
        List<Item> its = new ArrayList<>();
        // no moon here any more (disc and % are in the moonrise / moonset picture): empty lead item, quake only
        its.add(new Item(5, 24));
        boolean q = !Float.isNaN(quakeMag) && (quakeKm < 0 || quakeKm <= 2500 || quakeMag >= 6.0f);
        // quake variants, most informative first: full country name, then its code, then less
        List<Item> qv = new ArrayList<>();
        if (q) {
            int qc = quakeMag < 4 ? yellow() : quakeMag < 5.5 ? orange() : red();
            String mg = "M" + String.format(Locale.US, "%.1f", quakeMag);
            String cc = quakeCc != null && !quakeCc.isEmpty() ? quakeCc : null;
            String full = cc != null ? countryName(cc) : null;
            if (full != null && quakeKm >= 0)
                qv.add(new Item(3, 24).t(mg, 24, bold, qc).sp(6).t(quakeKm + "km", 20, semi, grey()).sp(6).t(full, 20, bold, color));
            if (cc != null && quakeKm >= 0)
                qv.add(new Item(3, 24).t(mg, 24, bold, qc).sp(6).t(quakeKm + "km", 20, semi, grey()).sp(6).t(cc, 20, bold, color));
            if (full != null) qv.add(new Item(3, 24).t(mg, 24, bold, qc).sp(6).t(full, 20, bold, color));
            if (quakeKm >= 0) qv.add(new Item(3, 24).t(mg, 24, bold, qc).sp(6).t(quakeKm + "km", 20, semi, grey()));
            if (cc != null) qv.add(new Item(3, 24).t(mg, 24, bold, qc).sp(6).t(cc, 20, bold, color));
            qv.add(new Item(3, 24).t(mg, 24, bold, qc));
        } else {
            qv.add(new Item(3, 24).t("no quake", 18, semi, grey()));
        }
        long hNow = System.currentTimeMillis();
        boolean hazPinned = false;
        if (Cfg.b(Cfg.HAZARDS, Cfg.D_HAZARDS)) {
            Hazards.Ev hv = Hazards.disaster(hNow) ? Hazards.pick(hNow) : null;
            if (hv != null) {
                hazPinned = hv.lvl >= 1 || (hv.km >= 0 && hv.km <= 1500);   // a green / yellow alert far away takes turns with the cloud and wind frames
                // GDACS orange / red alerts take the quake's place, rotating every 8 s when there are several
                qv.clear();
                String dot = (hv.india ? "🇮🇳" : ""), ic = Hazards.icon(hv.type);
                String nm = hv.name == null ? "" : hv.name, pl = hv.place;
                String dk = hv.km >= 0 ? hv.km + "km " + hv.dir : "";
                int nc = hv.lvl >= 2 ? red() : hv.lvl == 1 ? orange() : hv.lvl == 0 ? yellow() : green();
                // storms: the sea or ocean it is over, as the longest version (dropped first when the row is tight)
                String sea = Hazards.seaShort(Hazards.seaOf(hv));
                if (sea != null && !dk.isEmpty())
                    qv.add(new Item(3, 24).t(dot + ic, 20, bold, nc).sp(5).t(nm.isEmpty() ? sea : nm, 21, bold, nc)
                            .t(nm.isEmpty() ? "" : " \u00b7 ", 18, semi, grey()).t(nm.isEmpty() ? "" : sea, 19, semi, color)
                            .sp(6).t(dk, 19, semi, grey()));
                if (!nm.isEmpty() && pl != null && !dk.isEmpty())
                    qv.add(new Item(3, 24).t(dot + ic, 20, bold, nc).sp(5).t(nm, 21, bold, nc).t(" · ", 18, semi, grey())
                            .t(pl, 19, semi, color).sp(6).t(dk, 19, semi, grey()));
                if (nm.isEmpty() && pl != null)       // no name (fires, floods): the place instead
                    qv.add(new Item(3, 24).t(dot + ic, 20, bold, nc).sp(5).t(pl, 20, bold, color)
                            .sp(6).t(dk, 19, semi, grey()));
                if (!nm.isEmpty() && !dk.isEmpty())
                    qv.add(new Item(3, 24).t(dot + ic, 20, bold, nc).sp(5).t(nm, 21, bold, nc).sp(6).t(dk, 19, semi, grey()));
                if (!dk.isEmpty()) qv.add(new Item(3, 24).t(dot + ic, 20, bold, nc).sp(5).t(dk, 19, semi, grey()));
                if (!nm.isEmpty()) qv.add(new Item(3, 24).t(dot + ic, 20, bold, nc).sp(5).t(nm, 21, bold, nc));
                qv.add(new Item(3, 24).t(dot + ic, 20, bold, nc));
            }
            int g = Hazards.storm(hNow);
            if (g > 0) {
                // magnetic storm: "🧲Kp6 G2" after whatever row 4 shows; the G drops first when tight
                int kc = g == 1 ? yellow() : g == 2 ? orange() : g == 3 ? red() : purple();
                String kpS = "Kp" + (int) Math.floor(Hazards.kp);
                List<Item> withG = new ArrayList<>(), noG = new ArrayList<>();
                for (Item v : qv) {
                    Item a = new Item(3, 24); a.parts.addAll(v.parts); a.sp(10).t("🧲" + kpS, 20, bold, kc).sp(4).t("G" + g, 18, semi, kc);
                    Item b = new Item(3, 24); b.parts.addAll(v.parts); b.sp(10).t("🧲" + kpS, 20, bold, kc);
                    withG.add(a); noG.add(b);
                }
                qv.clear();
                for (int i = 0; i < withG.size(); i++) { qv.add(withG.get(i)); qv.add(noG.get(i)); }
            }
        }
        Item qi = qv.get(0);
        its.add(qi);
        // row 4 runs on under the SIM boxes while their area names stay on one line
        float lo = l4End + 18, hi = Math.max(w4Start, Math.min(q4End, W - 8)) - 18;
        hi = Math.max(hi, W - 12f);   // row 4 runs on to the right bezel: nothing sits beside it down there; the frames below take what is left in order of priority (weather frame, disaster, earth slot)
        float rowY = y(P4);
        final boolean lay2 = Cfg.b(Cfg.WX_ROW4, Cfg.D_WX_ROW4) && !Cfg.b(Cfg.SEA_ROW3, Cfg.D_SEA_ROW3);
        float wxLo = lo, wxHi = hi;   // the weather's own free stretch on row 4
        if (lay2) {
            // layout 2: the disaster / quake item sits on row 3 after the tide and sea temperature
            try {
                if (Cfg.b(Cfg.WX_SKY, Cfg.D_WX_SKY)) {
                    List<List<Item>> wf = wxSkyFrames();
                    float wxMax = Math.min(330f, wxHi - wxLo);
                    if (!wf.isEmpty() && wxMax >= 60f) {
                        List<Item> vs = wf.get((int) ((hNow / 5000L) % wf.size()));
                        for (Item v : vs) if (v.w() <= wxMax) { skyEnd4 = drawItem(c, v, wxLo - 4, y(P4)); break; }
                    }
                }
            } catch (Throwable ignored) { }
            rowY = y(P3);
            lo = Math.max(18f, (row3End > 0 ? row3End : 22f) + 18);
            hi = w4Start - 18;
            if (hi - lo < 60) { hidden(String.format(Locale.US, "row 3 room %.0f..%.0f", lo, hi)); if (!holdSky(c, its, ph)) return; return; }
        } else
        if (hi - lo < 120) {
            // no room beside the weather: use row 3 right of the health text instead
            lo = Math.max(l4End + 18, healthEnd + 18);
            hi = w4Start - 18;
            rowY = y(P3);
            if (hi - lo < 60) { hidden(String.format(Locale.US, "room %.0f..%.0f", lo, hi)); if (!holdSky(c, its, ph)) return; return; }
        }
        // the cloud / drift / wind frames: their own rotating item right after the wind; the quake or disaster item sits to the right of it
        try {
            if (lay2) {
            } else if (rowY == y(P4) && Cfg.b(Cfg.WX_SKY, Cfg.D_WX_SKY)) {
                List<List<Item>> wf = wxSkyFrames();
                float wxMax = Math.min(300f, (hi - lo) - 135f);   // what the disaster item needs is left to it
                if (!wf.isEmpty() && wxMax >= 60f) {
                    List<Item> vs = wf.get((int) ((hNow / 5000L) % wf.size()));
                    Item pick = null;
                    for (Item v : vs) if (v.w() <= wxMax) { pick = v; break; }
                    if (pick != null) {
                        float wx0 = lo - 4;
                        float we = drawItem(c, pick, wx0, rowY);
                        lo = we + 16;
                    }
                }
            }
        } catch (Throwable ignored) { }
        float room = hi - lo;
        float base = its.get(0).w();
        // the first variant that fits at 85 % or more wins; otherwise the shortest one, shrunk down to 65 %
        float f = 1f, total = base + qi.w();
        boolean placed = false;
        for (Item v : qv) {
            float t = base + v.w();
            if (t * 0.85f <= room) { qi = v; total = t; f = Math.min(1f, room / t); placed = true; break; }
        }
        if (!placed) {
            qi = qv.get(qv.size() - 1);
            total = base + qi.w();
            f = Math.max(0.65f, Math.min(1f, room / total));
            if (total * f > room + 0.5f) { qi = null; total = its.get(0).w(); f = Math.max(0.65f, Math.min(1f, room / total)); }
            if (total * f > room + 0.5f) { hidden(String.format(Locale.US, "needs %.0f, room %.0f", total * f, room)); holdSky(c, its, ph); return; }
        }
        if (qi != null) its.set(1, qi); else its.remove(1);
        float tw = total * f;
        float x = Math.max(lo, Math.min(hi - tw, lo));   // starts right after the weather text (was centred)
        c.save();
        c.scale(f, f, x, rowY);
        try {
            float e = drawItem(c, its.get(0), x, rowY);
            if (its.size() > 1) drawItem(c, its.get(1), e, rowY);
        } finally {
            c.restore();
        }
        if (rowY == y(P4)) skyEnd4 = x + tw;   // (layout 2 sets skyEnd4 to the end of the weather frame instead)
        try {
            if (rowY == y(P4) && Cfg.b(Cfg.EARTH_SLOT, Cfg.D_EARTH_SLOT)) {
                float ex0 = x + tw + 22, eroom = hi - ex0;
                if (eroom >= 60) {
                    List<Item> shown = new ArrayList<>();
                    List<List<Item>> efs = earthFrames();
                    StringBuilder sh = new StringBuilder(), hid = new StringBuilder();
                    for (int fi = 0; fi < efs.size(); fi++) {
                        Item fit = null;
                        for (Item v : efs.get(fi)) if (v.w() <= eroom) { fit = v; break; }
                        String nm = fi < earthNames.size() ? earthNames.get(fi) : "?";
                        if (fit != null) { shown.add(fit); sh.append(sh.length() > 0 ? ", " : "").append(nm).append(" ").append(Math.round(fit.w())).append("px"); }
                        else hid.append(hid.length() > 0 ? ", " : "").append(nm).append(" (even the shortest is wider than the room)");
                    }
                    Info.row4 = Diag.fmt("weather ends at x %.0f; disaster item x %.0f to %.0f; earth slot starts x %.0f, room %.0f px up to x %.0f; shown: %s%s", l4End, x, x + tw, ex0, eroom, hi, sh.length() > 0 ? sh : "none", hid.length() > 0 ? "; left out: " + hid : "");
                    if (!shown.isEmpty()) {
                        Item pk = shown.get((int) ((System.currentTimeMillis() / 5000L) % shown.size()));
                        skyEnd4 = Math.max(skyEnd4, drawItem(c, pk, ex0, rowY));
                    }
                }
            }
        } catch (Throwable ignored) { }
        skyX = x; skyRow = rowY; skyF = f; skyAt = System.currentTimeMillis(); skyDrawn++;
        skyDbg = String.format(Locale.US, "moon/quake drawn %d, hidden %d%s (now x %.0f, room %.0f..%.0f, width %.0f, row %s)",
                skyDrawn, skyHidden, skyWhy.isEmpty() ? "" : " last: " + skyWhy, x, lo, hi, tw, rowY == y(P4) ? "4" : "3");
    }

    /**
     * {"☉ 22:58", "Night"}: apparent solar time here, and the sun's phase right now from its elevation
     * (day, golden hour, civil / nautical / astronomical twilight as dawn or dusk, night). NOAA formulas.
     */
    private String[] solarNow() {
        double lat = fix != null ? fix[0] : latLon[0], lon = fix != null ? fix[1] : latLon[1];
        long now = System.currentTimeMillis();
        double jd = now / 86400000.0 + 2440587.5, t = (jd - 2451545.0) / 36525.0;
        double l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)) % 360;
        double m = Math.toRadians(357.52911 + t * (35999.05029 - 0.0001537 * t));
        double e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);
        double cc = Math.sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) + Math.sin(2 * m) * (0.019993 - 0.000101 * t)
                + Math.sin(3 * m) * 0.000289;
        double om = Math.toRadians(125.04 - 1934.136 * t);
        double lam = Math.toRadians(l0 + cc - 0.00569 - 0.00478 * Math.sin(om));
        double eps = Math.toRadians(23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
                + 0.00256 * Math.cos(om));
        double dec = Math.asin(Math.sin(eps) * Math.sin(lam));
        double y = Math.tan(eps / 2) * Math.tan(eps / 2), l0r = Math.toRadians(l0);
        double eot = 4 * Math.toDegrees(y * Math.sin(2 * l0r) - 2 * e * Math.sin(m) + 4 * e * y * Math.sin(m) * Math.cos(2 * l0r)
                - 0.5 * y * y * Math.sin(4 * l0r) - 1.25 * e * e * Math.sin(2 * m));          // minutes
        double utcMin = ((now % 86400000L) + 86400000L) % 86400000L / 60000.0;
        double solarMin = ((utcMin + 4 * lon + eot) % 1440 + 1440) % 1440;
        double ha = Math.toRadians(solarMin / 4 - 180);                                     // hour angle
        double la = Math.toRadians(lat);
        double el = Math.toDegrees(Math.asin(Math.sin(la) * Math.sin(dec) + Math.cos(la) * Math.cos(dec) * Math.cos(ha)));
        String ph;
        if (el >= 6) ph = Math.abs(solarMin - 720) < 15 ? "Solar noon" : "Day";
        else if (el >= -0.833) ph = "Golden hour";
        else if (el >= -6) ph = "Civil twilight";
        else if (el >= -12) ph = "Nautical twilight";
        else if (el >= -18) ph = "Astronomical twilight";
        else ph = Math.min(solarMin, 1440 - solarMin) < 15 ? "Solar midnight" : "Night";
        int sm = (int) Math.floor(solarMin);
        return new String[]{String.format(Locale.US, "☉ %02d:%02d", sm / 60, sm % 60), ph};
    }

    /** Moon phase 0..1 (0 new, 0.5 full). */
    private static double moonPhase() {
        double days = System.currentTimeMillis() / 86400000.0 + 2440587.5 - 2451550.1;
        return ((days % 29.530588853) + 29.530588853) % 29.530588853 / 29.530588853;
    }

    /**
     * Name from how much is lit (the way calendars name it): quarters only near 50 %, new / full only
     * near 0 / 100 %, crescent below half, gibbous above.
     */
    static String phaseName(int litPct, boolean waxing) {
        if (litPct <= 2) return "New moon";
        if (litPct >= 98) return "Full moon";
        if (Math.abs(litPct - 50) <= 3) return waxing ? "First quarter" : "Last quarter";
        if (litPct < 50) return waxing ? "Waxing crescent" : "Waning crescent";
        return waxing ? "Waxing gibbous" : "Waning gibbous";
    }

    private static final String[] MOON_NAMES = {"New moon", "Waxing crescent", "First quarter", "Waxing gibbous",
            "Full moon", "Waning gibbous", "Last quarter", "Waning crescent"};

    /** Small moon: dark disc, lit side, terminator ellipse. */
    private void moon(Canvas c, float mx, float my, float r, double ph) {
        int litCol = 0xFFF2E9C9, darkCol = fade(color, 0.22f);
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(darkCol);
        c.drawCircle(mx, my, r, gp);
        boolean waxing = ph < 0.5;
        float k = (float) Math.cos(2 * Math.PI * ph);     // 1 new, -1 full
        android.graphics.Path p = new android.graphics.Path();
        rf.set(mx - r, my - r, mx + r, my + r);
        // lit half (right while waxing, left while waning, as seen from the north)
        p.addArc(rf, waxing ? -90 : 90, 180);
        p.close();
        gp.setColor(litCol);
        c.drawPath(p, gp);
        float ew = Math.abs(k) * r;
        rf.set(mx - ew, my - r, mx + ew, my + r);
        gp.setColor(k > 0 ? darkCol : litCol);
        c.drawOval(rf, gp);
    }

    // ---- right half

    /** Left edge of the SIM blocks (design units), for the strip above to stop short of; NaN = no SIM drawn. */
    private float simLeft = Float.NaN;

    private final float[] colX = new float[2], colW = new float[2];
    private float camRight = Float.NaN;

    /**
     * Two-bar signal meter in the gap between the camera hole and the first SIM box (data SIM):
     * top bar = strength (RSRP, -125..-80 dBm), bottom bar = cleanliness (SNR, -5..25 dB),
     * each filling and turning green / yellow / orange / red. Shows at a glance whether a bad link is
     * weak, noisy or both.
     */
    private void proMeter(Canvas c) {
        if (Float.isNaN(simLeft) || Float.isNaN(camRight)) return;
        SimInfo d = null;
        for (SimInfo si : sims) if (si.data) d = si;
        if (d == null || !d.inService || d.dbm == Bands.NO_DBM) return;
        float x0 = camRight + 10, x1 = simLeft - 8;
        if (x1 - x0 < 34) return;
        float w = Math.min(x1 - x0, 80);
        x0 = x1 - w;                                        // hug the SIM box
        float sig = Math.max(0, Math.min(1, (d.dbm + 125f) / 45f));
        int sc = dbmColor(d.dbm);
        bar(c, x0, y(P1A) + 6, w, "SIG", sig, sc);   // kept below the ISS line above
        if (!Float.isNaN(d.snr)) {
            float q = Math.max(0, Math.min(1, (d.snr + 5f) / 30f));
            int nc = d.snr >= 13 ? green() : d.snr >= 5 ? yellow() : d.snr >= 0 ? orange() : red();
            bar(c, x0, y(P2) - 2, w, "SNR", q, nc);
        }
    }

    /**
     * Data SIM radio details in the gap between the camera and the first SIM box:
     * line 1 = 5G mode ("SA" / "NSA·B3"), line 2 = CQI (modem's 0-15 channel quality) or RSRQ when CQI is missing.
     */
    private void proRadio(Canvas c) {
        if (Float.isNaN(simLeft) || Float.isNaN(camRight)) return;
        SimInfo d = null;
        int di = -1;
        for (int i = 0; i < sims.size(); i++) if (sims.get(i).data) { d = sims.get(i); di = i; }
        if (d == null || !d.inService) return;
        float x1 = simLeft - 8, room = x1 - (camRight + 10);
        if (room < 30) return;
        // the gap sits beside the first box: when the data SIM is another box, say which ("2·")
        String pre = di > 0 ? d.slot + "·" : "";
        if (!pre.isEmpty()) room -= tw(pre, 15, bold);
        if (d.nrMode != null) {
            int col = d.nrMode.equals("SA") ? green() : d.nrMode.startsWith("4G") ? orange() : teal();
            float e = fitRight(c, d.nrMode, x1, y(P1A) + 2, 20, room, bold, col);
            if (e > 0 && !pre.isEmpty()) textB(c, pre, e - tw(pre, 15, bold), y(P1A) + 2, 20, 15, bold, grey());
        }
        // ping, between the 5G mode and the 4G data line
        if (ping > 0) {
            int qc = ping < 60 ? green() : ping < 150 ? yellow() : ping < 400 ? orange() : red();
            String pv = ping + "ms";
            fitRight(c, pv, x1, (y(P1A) + 2 + y(P1B) + 6) / 2f, 17, room, bold, qc);
        }
        // line 2: 4G data used today, coloured by how close it is to the limit
        {
            float f = (float) used4g / Math.max(1, limit4g);
            int col = f < 0.5f ? green() : f < 0.8f ? yellow() : f < 1f ? orange() : red();
            String v = used4g < 100_000_000L ? String.format(Locale.US, "%.0fM", used4g / 1e6)
                    : String.format(Locale.US, "%.2fG", used4g / 1e9);
            float e = fitRight(c, v, x1, y(P1B) + 6, 22, room, bold, col);
            if (e > 0) textB(c, "4G", e - 3 - tw("4G", 15, semi), y(P1B) + 6, 22, 15, semi, grey());
        }
        // line 3: 5G data used today (no limit on it, so one calm colour)
        {
            String v = used5g < 100_000_000L ? String.format(Locale.US, "%.0fM", used5g / 1e6)
                    : String.format(Locale.US, "%.2fG", used5g / 1e9);
            float e = fitRight(c, v, x1, y(P2) + 4, 22, room, bold, teal());
            if (e > 0) textB(c, "5G", e - 3 - tw("5G", 15, semi), y(P2) + 4, 22, 15, semi, grey());
        }
        if (false && d.cqi >= 0) {
            int col = d.cqi >= 10 ? green() : d.cqi >= 7 ? yellow() : d.cqi >= 4 ? orange() : red();
            float e = fitRight(c, String.valueOf(d.cqi), x1, y(P1B) + 6, 22, room, bold, col);
            if (e > 0) textB(c, pre + "CQI", e - 3 - tw(pre + "CQI", 15, semi), y(P1B) + 6, 22, 15, semi, grey());
        }
    }

    /** Right-aligned at x1, shrunk to fit room; returns its left edge (0 if not drawn). */
    private float fitRight(Canvas c, String t, float x1, float cy, float size, float room, Typeface tf, int col) {
        float sz = size;
        while (sz > 13 && tw(t, sz, tf) > room - 26) sz -= 1;
        if (tw(t, sz, tf) > room) return 0;
        float x = x1 - tw(t, sz, tf);
        textB(c, t, x, cy, size, sz, tf, col);
        return x;
    }

    /** "IN" -> "India", "US" -> "USA"; null for codes that aren't countries (sea / ridge initials). */
    private static String countryName(String cc) {
        if (cc == null || cc.length() != 2) return null;
        switch (cc) {
            case "US": return "USA";
            case "GB": return "UK";
            case "AE": return "UAE";
            case "RU": return "Russia";
            case "IR": return "Iran";
            case "KR": return "S Korea";
            case "KP": return "N Korea";
            case "TW": return "Taiwan";
            case "BO": return "Bolivia";
            case "VE": return "Venezuela";
            case "TZ": return "Tanzania";
            case "SY": return "Syria";
            case "PG": return "PNG";
        }
        for (String iso : Locale.getISOCountries()) {
            if (iso.equals(cc)) {
                String n = new Locale("", cc).getDisplayCountry(Locale.ENGLISH);
                return n == null || n.isEmpty() || n.length() > 16 ? null : n;
            }
        }
        return null;
    }

    /** Small label above a rounded bar: track in faint text colour, fill in col. */
    private void bar(Canvas c, float x, float cy, float w, String label, float f, int col) {
        textB(c, label, x, cy - 12, 14, 14, semi, grey());
        float t = cy + 2, b = cy + 12;
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(fade(color, 0.22f));
        rf.set(x, t, x + w, b);
        c.drawRoundRect(rf, 5, 5, gp);
        gp.setColor(col);
        rf.set(x, t, x + Math.max(10, w * f), b);
        c.drawRoundRect(rf, 5, 5, gp);
    }
    private int colN;

    private float healthX = Float.NaN;
    private static final float HP2_SIZE = 15;
    private float hP2X = 1e9f, hP2W = 0, wxEnd = 0, row3End = 0, seaLineEnd = 0, seaTextEnd = 0;

    /** Natural width of a SIM box's first line (network, bars, voice, mode), same sums as proSim. */
    private float line1W(SimInfo si) {
        boolean live = si.inService;
        String rat = si.dataOn && live && si.rat != null && !si.rat.isEmpty() ? si.rat : si.dataOn && live ? "\u2014" : null;
        return (si.data ? DBOX_W + DBOX_GAP + ARROW_W + 4 : 0) + (rat != null ? ratW(rat) + 2 : 0) + (si.eco ? 6 + tw("E", 22, bold) : 0)
                + BARS_W + (si.voice != null ? 8 + voW(si.voice) : 0) + modePingW(si);
    }

    /**
     * Where the SIM boxes' right edge may be. Classic: the whole box stays clear of the screen's top-right curve. Folded: only
     * the first line (network, bars, voice) is up there and it is shorter than the box, so the group may sit further right as
     * long as that line still clears the curve; the lines under it are lower, below the curve.
     */
    private float simRight(float R1, int n) {
        if (tower && n > 0) return R1;                  // tower column: the boxes run to the right edge
        float cl = cornerRightX(y(P1A) - 24 - upPx()) - 4;
        float lim = Math.min(R1, cl);
        if (!fold || n <= 0 || sims.size() < n) return lim;
        try {
            SimInfo last = sims.get(n - 1);
            float eq = boxW(n);
            float wa = line1W(last);
            float f = Math.min(eq / Math.max(1f, wa), 1.04f);
            float by1 = cl + eq + 7f - wa * f;                  // first line ends right at the curve
            float cap2 = cornerRightX(y(P1B) - 30) - 2f;        // second line, lower, with a little margin
            return Math.min(R1, Math.max(cl, Math.min(by1, cap2)));
        } catch (Throwable t) {
            return lim;
        }
    }

    /** Where the SIM boxes will start (same sums as proRight), before they're drawn. */
    private float predictSimLeft(float R0, float R1) {
        int n = Math.min(2, sims.size());
        if (n == 0) return Float.NaN;
        if (stretch) R0 -= STRETCH_CAM;                  // stretched: the boxes start right against the camera cutout
        if (colOn()) R1 -= GW + 8;
        float simR = simRight(R1, n);
        final float pad = 7, gap = 2;
        float total = gap * (n - 1);
        float eq = boxW(n);
        for (int i = 0; i < n; i++) total += eq + 2 * pad;
        if (stretch && simR - (R0 - STRETCH_TOUCH) > total) total = simR - (R0 - STRETCH_TOUCH);   // boxes fill up to the camera cutout
        float f = total > simR - R0 ? (simR - R0) / total : 1f;
        if (stretch && total * f < simR - (R0 - STRETCH_TOUCH) + 0.5f) f = 1f;
        return simR - total * f;
    }

    private void proRight(Canvas c, float R0, float R1) {
        simLeft = Float.NaN;
        colN = 0;
        int n = Math.min(2, sims.size());
        final float R1full = R1;
        if (stretch) R0 -= STRETCH_CAM;
        if (colOn()) R1 -= GW + 8;
        float simR = simRight(R1, n);
        if (n > 0) {
            final float pad = 7, gap = 2;
            float[] bw = new float[n];
            float total = gap * (n - 1);
            for (int i = 0; i < n; i++) {
                bw[i] = boxW(n) + 2 * pad;
                total += bw[i];
            }
            boolean fill = stretch && simR - (R0 - STRETCH_TOUCH) > total;
            if (fill) {                                   // boxes fill up to the camera cutout, equal shares
                float add = (simR - (R0 - STRETCH_TOUCH) - total) / n;
                for (int i = 0; i < n; i++) bw[i] += add;
                total += add * n;
            }
            float f = total > simR - R0 && !fill ? (simR - R0) / total : 1f;
            float x = simR - total * f;
            simLeft = x;
            for (int i = 0; i < n; i++) {
                SimInfo si = sims.get(i);
                float b = bw[i] * f;
                // (the thin underline that used to mark the data SIM is gone: the boxed d does that now)
                l1Cap = tower ? cornerRightX(y(P1A) - 24 - upPx()) - 4f : 1e9f;
                c.save();
                c.translate(0, -upPx());
                if (tower && Cfg.b(Cfg.TC_TINT, Cfg.D_TC_TINT)) { try { tcTint(c, si, x, b); } catch (Throwable ignored) { } }
                try { proSim(c, si, x + pad * f, b - 2 * pad * f); } catch (Throwable ignored) { }
                if (tower) { try { tcColumn(c, si, x + pad * f, b - 2 * pad * f); } catch (Throwable ignored) { } }
                c.restore();
                colX[i] = x; colW[i] = b; colN = i + 1;
                x += b + gap * f;
            }
        }
        // corner beside the SIMs: today's 5G share, or the data SIM's signal quality, or nothing
        int corner = Cfg.i(Cfg.CORNER, Cfg.D_CORNER);
        try {
            if (graphsOn()) { if (fold) { c.save(); c.translate(0, -upPx()); foldData(c); for (int qi = 0; qi < colN && qi < sims.size(); qi++) foldQOther(c, qi); c.restore(); } else graphColumn(c, R1full); }
            else if (corner == 1) proNrShare(c, simR + 3, W - 4);
            else if (corner == 2) proQuality(c, simR + 3, W - 4);
        } catch (Throwable ignored) { }
        try { proTowerAreas(c); } catch (Throwable ignored) { }
    }

    /** Height the data SIM's place name takes under its box (0 none, 22 one line, 44 two), same rules as drawPlace. */
    private float placeH(SimInfo d, float cw) {
        if (dupPlace(d)) return qWords(d) == null ? 0 : 22f;
        String ar = placeText(d);
        if (ar == null) return 0;
        String bearing = d.bearing != null ? "  " + d.bearing : "";
        float a = tw(ar, 20, bold);
        if (a + (bearing.isEmpty() ? 0 : tw(bearing, 17, bold)) <= cw || a <= cw) return 22f;
        String[] two = split2(ar);
        if (two != null) {
            float sz = 20;
            while (sz > 13 && (tw(two[0], sz, bold) > cw || tw(two[1], sz, bold) > cw)) sz -= 1;
            if (tw(two[0], sz, bold) <= cw && tw(two[1], sz, bold) <= cw) return 44f;
        }
        return 22f;
    }

    /**
     * Folded layout: what the far-right column used to show (data used today on 4G / 5G, and the data SIM's signal
     * quality Q) goes on one line under the data SIM's box, below its place and detail lines. Left out when the box
     * is already tall enough to reach row 4.
     */
    private void foldData(final Canvas c) {
        int di = -1;
        for (int i = 0; i < sims.size() && i < 2 && i < colN; i++) if (sims.get(i).data) di = i;
        if (di < 0) return;
        final SimInfo d = sims.get(di);
        final float x = colX[di] + 8, cw = colW[di] - 16;
        final boolean showData = dataTodayW(d) > 0 && Cfg.b(Cfg.SH_DATA, Cfg.D_SH_DATA);
        final int q = quality(d);
        if (!showData && q < 0) return;
        final float cy = foldRowY(d, cw);
        final boolean mo = monthly();
        final String pl = mo ? "M" : "D";
        final String v4 = dataText(true, mo), v5 = dataText(false, mo);
        final float f4 = (float) used4g / Math.max(1, limit4g);
        final int c4 = mo ? green() : f4 < 0.5f ? green() : f4 < 0.8f ? yellow() : f4 < 1f ? orange() : red();
        final float bw0 = tw(pl, 14, bold) + 9;
        final String qv = q + "%";
        final int qc = q >= 70 ? green() : q >= 45 ? yellow() : q >= 25 ? orange() : red();
        final boolean qbox = Cfg.b(Cfg.Q_BOX, Cfg.D_Q_BOX);
        final int dim = fade(grey(), 0.85f);
        float wq = q >= 0 ? (showData ? 12 : 0) + (qbox ? 25 : tw("Q", 15, semi) + 4) + tw(qv, 20, bold) : 0;
        float wd = showData ? bw0 + 5 + tw(v4, 20, bold) + tw(" · ", 17, semi) + tw(v5, 20, bold) : 0;
        float wl = showData ? tw("4G ", 15, semi) + tw("5G ", 15, semi) : 0;
        final boolean lab = Cfg.b(Cfg.DATA_LABELS, Cfg.D_DATA_LABELS) && wd + wl + wq <= cw * 1.05f;
        final float w = wd + (lab ? wl : 0) + wq;
        fit(c, x, cy, w, cw, () -> {
            float e = 0;
            if (showData) {
                gp.setStyle(Paint.Style.FILL);
                gp.setColor(mo ? 0xFFB388FF : 0xFF4DD0E1);
                rf.set(0, -9, bw0, 9);
                c.drawRoundRect(rf, 4, 4, gp);
                textB(c, pl, 4.5f, 0, 20, 14, bold, 0xFF1A1A1A);
                e = bw0 + 5;
                if (lab) e = textB(c, "4G ", e, 0, 20, 15, semi, dim);
                e = textB(c, v4, e, 0, 20, 20, bold, c4);
                e = textB(c, " · ", e, 0, 20, 17, semi, dim);
                if (lab) e = textB(c, "5G ", e, 0, 20, 15, semi, dim);
                e = textB(c, v5, e, 0, 20, 20, bold, teal());
                e += 12;
            }
            if (q >= 0) {
                if (qbox) {
                    gp.setStyle(Paint.Style.STROKE);
                    gp.setStrokeWidth(2f);
                    gp.setColor(dim);
                    rf.set(e + 1, -11, e + 21, 11);
                    c.drawRoundRect(rf, 4, 4, gp);
                    gp.setStyle(Paint.Style.FILL);
                    textB(c, "Q", e + 11 - tw("Q", 15, bold) / 2f, -0.5f, 15, 15, bold, dim);
                    e += 25;
                } else e = textB(c, "Q", e, 0, 15, 15, semi, dim) + 4;
                textB(c, qv, e, 0, 20, 20, bold, qc);
            }
        });
    }

    /** Where a box's last row (data and Q, or Q) goes: under its lines, but never lower than the limit, so it is never dropped. */
    private float foldRowY(SimInfo d, float cw) {
        int nLines = 0;
        for (java.util.List<DSeg> ln : detailLines(d)) if (!ln.isEmpty()) nLines++;
        float ph = placeH(d, cw);
        float y0 = y(P2) + 4;
        float lastCy = nLines > 0 ? y0 + ph + 22f * (nLines - 1) : y0 + Math.max(0f, ph - 22f);
        return Math.min(Math.max(y(P3), lastCy + 24f), y(P4) - 14f + upPx());
    }

    /** Whether this SIM's box gets a last row (data and Q for the data SIM, Q for the other, folded layouts only). */
    private boolean hasFoldRow(SimInfo d) {
        if (!fold || !graphsOn()) return false;
        int q = quality(d);
        if (d.data) return q >= 0 || (dataTodayW(d) > 0 && Cfg.b(Cfg.SH_DATA, Cfg.D_SH_DATA));
        return Cfg.b(Cfg.Q_SECOND, Cfg.D_Q_SECOND) && q >= 0;
    }

    /** The SIM that is not carrying data: its own boxed Q and percent in the bottom row of its box. */
    private void foldQOther(final Canvas c, int i) {
        if (!Cfg.b(Cfg.Q_SECOND, Cfg.D_Q_SECOND)) return;
        final SimInfo d = sims.get(i);
        if (d.data) return;
        final int q = quality(d);
        if (q < 0) return;
        final float x = colX[i] + 8, cw = colW[i] - 16;
        final float cy = foldRowY(d, cw);
        final String qv = q + "%";
        final int qc = q >= 70 ? green() : q >= 45 ? yellow() : q >= 25 ? orange() : red();
        final boolean qbox = Cfg.b(Cfg.Q_BOX, Cfg.D_Q_BOX);
        final int dim = fade(grey(), 0.85f);
        float w = (qbox ? 25 : tw("Q", 15, semi) + 4) + tw(qv, 20, bold);
        fit(c, x, cy, w, cw, () -> {
            float e = 0;
            if (qbox) {
                gp.setStyle(Paint.Style.STROKE);
                gp.setStrokeWidth(2f);
                gp.setColor(dim);
                rf.set(e + 1, -11, e + 21, 11);
                c.drawRoundRect(rf, 4, 4, gp);
                gp.setStyle(Paint.Style.FILL);
                textB(c, "Q", e + 11 - tw("Q", 15, bold) / 2f, -0.5f, 15, 15, bold, dim);
                e += 25;
            } else e = textB(c, "Q", e, 0, 15, 15, semi, dim) + 4;
            textB(c, qv, e, 0, 20, 20, bold, qc);
        });
    }

    /** Data SIM signal quality 0-100: 60 % from RSRP (-125..-80 dBm), 40 % from SNR (-5..25 dB). */
    private int quality(SimInfo si) {
        if (!si.inService || si.dbm == Bands.NO_DBM) return -1;
        float a = Math.max(0, Math.min(1, (si.dbm + 125f) / 45f));
        if (Float.isNaN(si.snr)) return Math.round(a * 100);
        float b = Math.max(0, Math.min(1, (si.snr + 5f) / 30f));
        return Math.round((0.6f * a + 0.4f * b) * 100);
    }

    /** "Q" over "82%" in the corner space beside the SIMs (lines 1b and 2, below the corner curve). */
    private void proQuality(Canvas c, float x0, float x1) {
        // phone battery level above the Q label
        if (battPct >= 0 && !battPctMoved && x1 - x0 >= 14) {
            final String bv = (charging ? "⚡" : "") + battPct + "%";
            final int bc = charging ? green() : battPct >= 50 ? green() : battPct >= 20 ? yellow() : battPct >= 10 ? orange() : red();
            final float bcw = x1 - x0;
            fit(c, x0, y(P1A) + 2, tw(bv, 19, bold), bcw, () -> text(c, bv, 0, 0, 19, bold, bc));
        }
        SimInfo d = null;
        for (SimInfo si : sims) if (si.data) d = si;
        if (d == null) return;
        int q = quality(d);
        if (q < 0 || x1 - x0 < 14) return;
        final String v = q + "%";
        final int col = q >= 70 ? green() : q >= 45 ? yellow() : q >= 25 ? orange() : red();
        final float cw = x1 - x0;
        fit(c, x0, y(P1B), tw("Q", 16, semi), cw, () -> text(c, "Q", 0, 0, 16, semi, grey()));
        fit(c, x0, y(P2), tw(v, 20, bold), cw, () -> text(c, v, 0, 0, 20, bold, col));
    }

    /** "5G" over "92%" in the corner beside the SIMs: how much of today was on 5G. */
    private void proNrShare(Canvas c, float x0, float x1) {
        if (nrShare < 0 || x1 - x0 < 14) return;
        final String v = nrShare + "%";
        final int col = nrShare >= 80 ? green() : nrShare >= 50 ? yellow() : nrShare >= 25 ? orange() : red();
        final float cw = x1 - x0;
        fit(c, x0, y(P1B), tw("5G", 16, semi), cw, () -> text(c, "5G", 0, 0, 16, semi, grey()));
        fit(c, x0, y(P2), tw(v, 20, bold), cw, () -> text(c, v, 0, 0, 20, bold, col));
    }

    /** Cell tower: mast with a cross brace, a dot on top and two signal arcs. About 18 wide, 26 tall, centred on cy. */
    private void towerIcon(Canvas c, float x, float cy, int col) {
        float mx = x + 9;
        stroke(2.4f, col);
        c.drawLine(mx, cy - 3, x + 1.5f, cy + 12, gp);
        c.drawLine(mx, cy - 3, x + 16.5f, cy + 12, gp);
        stroke(1.8f, col);
        c.drawLine(x + 4.5f, cy + 6, x + 13.5f, cy + 6, gp);
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(col);
        c.drawCircle(mx, cy - 6, 2.6f, gp);
        stroke(1.8f, col);
        rf.set(mx - 6, cy - 12, mx + 6, cy);
        c.drawArc(rf, 205, 130, false, gp);
        rf.set(mx - 10, cy - 16, mx + 10, cy + 4);
        c.drawArc(rf, 210, 120, false, gp);
    }

    private static final float DISH_W = 22;

    /** Phone picking up signals: phone outline (earpiece + home dot) with two waves arriving from the right. */
    private void dish(Canvas c, float x, float cy, int col) {
        stroke(2f, col);
        rf.set(x + 1, cy - 10, x + 11, cy + 10);
        c.drawRoundRect(rf, 2.5f, 2.5f, gp);                     // phone body
        c.drawLine(x + 4.5f, cy - 7, x + 7.5f, cy - 7, gp);      // earpiece
        gp.setStyle(Paint.Style.FILL);
        c.drawCircle(x + 6, cy + 7, 1.2f, gp);                   // home dot
        stroke(2f, col);
        rf.set(x + 9, cy - 7, x + 17, cy + 1);
        c.drawArc(rf, -60, 120, false, gp);                      // inner wave
        rf.set(x + 9, cy - 11, x + 23, cy + 5);
        c.drawArc(rf, -55, 110, false, gp);                      // outer wave
    }

    private static final float BARS_W = 4 * 9.5f;

    /** Roaming badge text: "R·KA" when the circle is known, else "R". */
    private static String roamText(SimInfo si) {
        return si.circle != null ? "R·" + si.circle : "R";
    }

    /** "VoNR" / "VoLTE" / "VoWiFi" text as shown in the pill. */
    private static String voText(String type) {
        return "Vo" + ("WiFi".equalsIgnoreCase(type) ? "WiFi" : type);
    }

    /** Width of the voice pill. */
    private float voW(String type) {
        return tw(voText(type), 17, bold) + 12;
    }

    /** Voice pill: "VoNR" in a thin rounded outline, centred on the line. */
    private float voBadge(Canvas c, String type, float x, int col) {
        String t = voText(type);
        float w = tw(t, 17, bold) + 12;
        stroke(1.8f, col);
        rf.set(x, -12, x + w, 12);
        c.drawRoundRect(rf, 6, 6, gp);
        tp.setTypeface(bold);
        tp.setTextSize(17);
        tp.setColor(col);
        c.drawText(t, x + 6, 6, tp);
        return x + w;
    }

    private static final float LOCK_W = 8 + 13;
    private static final float ARROW_W = 11;
    /** Width of the small boxed "d" that marks the SIM carrying mobile data, and the gap after it. */
    private static final float DBOX_W = 21, DBOX_GAP = 5;

    /** A small outlined box with a "d" in it, centred on cy; returns the x after it. */
    private float dBox(Canvas c, float x, float cy) {
        int col = fade(color, 0.85f);
        gp.setStyle(Paint.Style.STROKE);
        gp.setStrokeWidth(2.2f);
        gp.setColor(col);
        rf.set(x + 1, cy - 12, x + DBOX_W - 1, cy + 12);
        c.drawRoundRect(rf, 4, 4, gp);
        gp.setStyle(Paint.Style.FILL);
        float lw = tw("d", 21, bold);
        textB(c, "d", x + (DBOX_W - lw) / 2f, cy - 0.5f, 21, 21, bold, col);   // ref 21: baseline about 7.5 below centre, so the d sits mid-box
        return x + DBOX_W + DBOX_GAP;
    }

    /** "5G+" drawn as 5G with a small raised plus. */
    private float ratW(String rat) {
        if (rat.endsWith("+") && rat.length() > 1) return tw(rat.substring(0, rat.length() - 1), 34, bold) + 1 + tw("+", 22, bold);
        return tw(rat, 34, bold);
    }

    private float drawRat(Canvas c, String rat, float x, int col) {
        if (!(rat.endsWith("+") && rat.length() > 1)) return textB(c, rat, x, 0, 34, 34, bold, col);
        float e = textB(c, rat.substring(0, rat.length() - 1), x, 0, 34, 34, bold, col);
        return textB(c, "+", e + 1, -9, 34, 22, bold, col);
    }

    /** Stacked activity triangles centred on cy: up (blue) above, down (green) below. */
    private void arrows(Canvas c, float x, float cy, boolean up, boolean down) {
        int idle = fade(color, 0.22f);
        gp.setStyle(Paint.Style.FILL);
        float w = ARROW_W, h = 9;
        path.reset();
        path.moveTo(x, cy - 2); path.lineTo(x + w, cy - 2); path.lineTo(x + w / 2, cy - 2 - h); path.close();
        gp.setColor(up ? blue() : idle);
        c.drawPath(path, gp);
        path.reset();
        path.moveTo(x, cy + 2); path.lineTo(x + w, cy + 2); path.lineTo(x + w / 2, cy + 2 + h); path.close();
        gp.setColor(down ? green() : idle);
        c.drawPath(path, gp);
    }

    /** True when this SIM has a band lock set in settings. */
    private static boolean locked(SimInfo si) {
        try { return !Cfg.s(si.slot == 2 ? Cfg.BANDS2 : Cfg.BANDS1).isEmpty(); } catch (Throwable t) { return false; }
    }

    /** Small padlock, 13 wide, centred on cy. */
    private void lockIcon(Canvas c, float x, float cy, int col) {
        gp.setColor(col);
        gp.setStyle(Paint.Style.FILL);
        rf.set(x, cy - 2, x + 13, cy + 9);
        c.drawRoundRect(rf, 2, 2, gp);
        gp.setStyle(Paint.Style.STROKE);
        gp.setStrokeWidth(2.2f);
        rf.set(x + 2.5f, cy - 10, x + 10.5f, cy + 2);
        c.drawArc(rf, 180, 180, false, gp);
        gp.setStyle(Paint.Style.FILL);
    }

    /** Natural width of one SIM's block (its widest line). */
    private static final float S1 = 0.88f, S2 = 0.82f, S3 = 0.8f, S4 = 0.9f, BOX_MIN = 235f;
    /** Largest scale a line may grow to when its box has spare width (the lines sit 25 px apart, so only a little). */
    private static final float G1 = 1.04f, G2 = 1.0f, G3 = 1.0f, G4 = 1.12f;

    private String modeText(SimInfo si) {
        return si.dataOn && si.inService ? si.nrMode : null;
    }

    private String pingText(SimInfo si) {
        return si.data && si.inService && ping > 0 ? ping + "ms" : null;
    }

    /** Width of "SA · 28ms" after the voice pill (data SIM only), 0 when there is none. */
    private float modePingW(SimInfo si) {
        String m = modeText(si), p = null;   // ping moved to the detail line (2026-11-14)
        if (m == null && p == null) return 0;
        return 8 + (m != null ? tw(m, 21, bold) : 0) + (m != null && p != null ? tw(" · ", 19, semi) : 0)
                + (p != null ? tw(p, 21, bold) : 0);
    }

    private String dataTodayText(SimInfo si, boolean f4) { return dataText(f4, monthly()); }

    private String dataText(boolean f4, boolean mo) {
        long u = f4 ? (mo ? m4g : used4g) : (mo ? m5g : used5g);
        return u < 100_000_000L ? String.format(Locale.US, "%.0fM", u / 1e6) : String.format(Locale.US, "%.2fG", u / 1e9);
    }

    /** Monthly figures now? Setting 1 always; setting 3 flips between D and M every 5 s; 0 and 2 never (2 draws both itself). */
    private boolean monthly() {
        int m = Cfg.i(Cfg.DATA_PERIOD, Cfg.D_DATA_PERIOD);
        return m == 1 || (m == 3 && (System.currentTimeMillis() / 5000L) % 2 == 1);
    }
    /** Width of the little coloured "D" / "M" box that says which period the data figures are for. */
    private float periodBoxW() { return tw(monthly() ? "M" : "D", 15, bold) + 10 + 4; }

    private float dataTodayW(SimInfo si) {
        if (!si.data || !si.inService) return 0;
        return periodBoxW() + tw("4G ", 15, semi) + tw(dataTodayText(si, true), 20, bold)
                + tw(" · ", 17, semi) + tw("5G ", 15, semi) + tw(dataTodayText(si, false), 20, bold);
    }

    /** Both SIM boxes get the width of the wider one, so they match. */
    private static final float STRETCH_W = 50, STRETCH_TOUCH = 0, STRETCH_CAM = 18;   // touch: how far past the usual gap beside the camera the boxes may reach

    private float boxW(int n) {
        float w = 0;
        for (int i = 0; i < n; i++) w = Math.max(w, simW(sims.get(i)));
        return stretch ? w + STRETCH_W : w;
    }

    private float simW(SimInfo si) {
        boolean live = si.inService;
        String rat = live && si.rat != null && !si.rat.isEmpty() ? si.rat : "—";
        float wa = (si.data ? DBOX_W + DBOX_GAP + ARROW_W + 4 : 0) + ratW(rat) + (si.eco ? 6 + tw("E", 22, bold) : 0)
                + 0 + 10 + BARS_W + (si.voice != null ? 8 + voW(si.voice) : 0) + modePingW(si);
        String dbm = live && si.dbm != Bands.NO_DBM ? minus(String.valueOf(si.dbm)) : null;
        String snr = live && !Float.isNaN(si.snr) ? minus(String.valueOf(Math.round(si.snr))) : null;
        float wb = (dbm != null ? tw(dbm, 28, bold) + 4 + tw("↑", 24, bold) : 0)
                + (snr != null ? 14 + tw("/", 22, semi) + 6 + tw(snr, 28, bold) + (hideSnrWord(si) ? 0 : 4 + tw("good", 15, semi)) : 0)
                + (locked(si) ? LOCK_W : 0) + (si.newCell ? 32 : 0);
        String band = si.band != null ? si.band : "?";
        float w2 = tw(band, 26, semi) + (si.dist != null ? 10 + tw(si.dist, 26, semi) : 0)
                + (si.cells > 0 ? 12 + DISH_W + 4 + tw(String.valueOf(si.cells), 24, bold) : 0);
        float w4 = detailW(si);
        return Math.max(BOX_MIN, Math.max(Math.max(wa * S1, wb * S2), Math.max(w2 * S3, w4 * S4)));
    }

    /** Like fit(), but the line is drawn at scale sc (smaller text) and shrinks further if still too wide. */
    private static float grow(float sc) {
        return sc == S1 ? G1 : sc == S2 ? G2 : sc == S3 ? G3 : G4;
    }
    private void fitK(Canvas c, float x, float cy, float width, float cw, float sc, Runnable draw) {
        float f = Math.min(cw / width, grow(sc));
        c.save();
        c.translate(x, cy);
        c.scale(f, f);
        try { draw.run(); } finally { c.restore(); }
    }

    /** Draws [draw] scaled down to fit width cw if it is wider. */
    private void fit(Canvas c, float x, float cy, float width, float cw, Runnable draw) {
        float f = width > cw ? cw / width : 1f;
        c.save();
        c.translate(x, cy);
        if (f < 1f) c.scale(f, f);
        try { draw.run(); } finally { c.restore(); }
    }

    private void proSim(final Canvas c, final SimInfo si, float x, float cw) {
        final boolean live = si.inService;
        slots.clear();
        final float k = si.data ? 1f : 0.8f;
        // network-type label (5G/4G) only on the SIM actually carrying data (2026-11-14 per request) -
        // showing it on the other SIM too read as if that one were on 5G/4G data as well.
        final String rat = si.dataOn && live && si.rat != null && !si.rat.isEmpty() ? si.rat
                : si.dataOn && live ? "—" : null;
        final String vo = si.voice;
        final float barsW = BARS_W;
        // four lines in the box: link / signal / cell / data today (data SIM only)
        final float yL1 = y(P1A) - 4, yL2 = y(P1B) - 13, yL3 = y(P2) - 18, yL4 = y(P2) + 4;
        final String mode = modeText(si), pingT = null;   // ping now sits on the detail line, after the time on tower
        float wa = (si.data ? DBOX_W + DBOX_GAP + ARROW_W + 4 : 0) + (rat != null ? ratW(rat) + 2 : 0) + (si.eco ? 6 + tw("E", 22, bold) : 0)
                + barsW + (vo != null ? 8 + voW(vo) : 0) + modePingW(si);
        // line 1: network, bars, live arrows, voice, and for the data SIM "SA · 28ms"
        fitK(c, x, yL1, wa, Math.min(cw, l1Cap - x), S1, () -> {
            float e = 0;
            if (si.data) {
                // one narrow column: ▲ upload on top, ▼ download below; faint while idle
                e = dBox(c, e, 0);
                arrows(c, e, 0, dataOut, dataIn);
                e += ARROW_W + 4;
            }
            if (rat != null) { e = drawRat(c, rat, e, fade(color, k)); e += 2; }
            if (si.eco) e = textB(c, "E", e + 6, 0, 34, 22, bold, green());
            boolean has = live && si.dbm != Bands.NO_DBM;
            int lvl = !has ? 0 : si.dbm >= -90 ? 4 : si.dbm >= -100 ? 3 : si.dbm >= -110 ? 2 : si.dbm >= -120 ? 1 : 0;
            int bc = has ? dbmColor(si.dbm) : grey();
            gp.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 4; i++) {
                float bh = 26f * (i + 1) / 4f, l = e + i * 9.5f;
                gp.setColor(i < lvl ? bc : fade(color, 0.22f));
                rf.set(l, 12 - bh, l + 6.5f, 12);
                c.drawRoundRect(rf, 2, 2, gp);
            }
            e += barsW;
            if (vo != null) e = voBadge(c, vo, e + 8, fade(green(), k));
            if (mode != null || pingT != null) {
                e += 8;
                // 17 -> 21 (2026-11-14): "SA" and the ping reading were hard to make out at the old size
                if (mode != null) {
                    int mc = mode.equals("SA") ? green() : mode.startsWith("4G") ? orange() : teal();
                    e = textB(c, mode, e, 0, 28, 21, bold, mc);
                }
                if (mode != null && pingT != null) e = textB(c, " · ", e, 0, 28, 19, semi, grey());
                if (pingT != null) {
                    int qc = ping < 60 ? green() : ping < 150 ? yellow() : ping < 400 ? orange() : red();
                    e = textB(c, pingT, e, 0, 28, 21, bold, qc);
                }
            }
        });
        // line 2: signal (dBm) with its direction arrow, then SINR with its word; units dropped, colours say it
        final String dbm = live && si.dbm != Bands.NO_DBM ? minus(String.valueOf(si.dbm)) : null;
        final String snr = live && !Float.isNaN(si.snr) ? minus(String.valueOf(Math.round(si.snr))) : null;
        if (dbm != null || snr != null) {
            float wb = (dbm != null ? tw(dbm, 28, bold) + 4 + tw("↑", 24, bold) : 0)
                    + (snr != null ? 14 + tw("/", 22, semi) + 6 + tw(snr, 28, bold) + (hideSnrWord(si) ? 0 : 4 + tw("good", 15, semi)) : 0)
                    + (dbm != null ? 12 + tw(trendLabel(si), 19, bold) : 0)
                    + (locked(si) ? LOCK_W : 0) + (si.newCell ? 32 : 0);
            final boolean lk = locked(si);
            fitK(c, x, yL2, wb, cw, S2, () -> {
                float e = 0;
                if (dbm != null) {
                    e = textB(c, dbm, 0, 0, 28, 28, bold, fade(dbmColor(si.dbm), k));
                    // signal direction over the last minute: ↑ stronger, ↓ weaker, → steady
                    String trd = si.trend;
                    boolean up = trd != null && trd.startsWith("+");
                    e = textB(c, trd == null ? "→" : up ? "↑" : "↓", e + 3, 0, 28, 24, bold,
                            trd == null ? grey() : up ? green() : red());
                }
                if (snr != null) {
                    int sc = si.snr >= 13 ? green() : si.snr >= 5 ? yellow() : si.snr >= 0 ? orange() : red();
                    if (dbm != null) e = textB(c, "/", e + 8, 0, 28, 22, semi, grey()) + 6;
                    e = textB(c, snr, e, 0, 28, 28, bold, fade(sc, k));
                    if (!hideSnrWord(si))   // the boxed Q says the same thing (folded layouts)
                        e = textB(c, si.snr >= 13 ? "good" : si.snr >= 5 ? "ok" : si.snr >= 0 ? "poor" : "bad",
                                e + 4, 0, 28, 15, semi, fade(sc, k));
                }
                if (dbm != null) {
                    // "\u0394" = change in signal over the last minute, with its number: "\u0394\u22125dB", "\u0394+3dB", "\u03940" when steady
                    String trd2 = si.trend;
                    e = textB(c, trendLabel(si), e + 12, 0, 28, 19, bold,
                            trd2 == null ? grey() : trd2.startsWith("+") ? green() : red());
                }
                if (lk) lockIcon(c, e + 8, 0, teal());           // bands locked in settings
                if (lk) e += LOCK_W;
                if (si.newCell) {   // new tower: small blue box with an N
                    gp.setStyle(Paint.Style.FILL);
                    gp.setColor(0xFF2F7DE8);
                    rf.set(e + 10, -11, e + 32, 11);
                    c.drawRoundRect(rf, 5, 5, gp);
                    textB(c, "N", e + 21 - tw("N", 16, bold) / 2f, 0, 16, 16, bold, 0xFFFFFFFF);
                }
            });
        }
        // line 3: band, distance (📡 symbol + reading - the SIM box's one place for this, 2026-11-14), cells heard
        final String band = si.band != null ? si.band : "?";
        final String dist = si.dist;
        final String cells = si.cells > 0 ? String.valueOf(si.cells) : null;
        float w2 = tw(band, 26, semi) + (dist != null ? 10 + tw("📡 ", 18, bold) + tw(dist, 26, semi) : 0)
                + (cells != null ? 12 + DISH_W + 4 + tw(cells, 24, bold) : 0);
        fitK(c, x, yL3, w2, cw, S3, () -> {
            float e = textB(c, band, 0, 0, 26, 26, semi, fade(blue(), k));
            if (dist != null) {
                if (Cfg.b(Cfg.TOWER_MAST, Cfg.D_TOWER_MAST)) {
                    towerIcon(c, e + 10, 0, fade(grey(), k));
                    e += 10 + tw("📡 ", 18, bold);
                } else e = textB(c, "📡 ", e + 10, 0, 26, 18, bold, fade(grey(), k));
                e = textB(c, dist, e, 0, 26, 26, semi, fade(color, k));
            }
            if (cells != null) {
                dish(c, e + 12, 0, grey());
                e += 12 + DISH_W - 1;
                textB(c, cells, e + 5, 0, 26, 24, bold, grey());
            }
        });
        slots.add(new float[]{yL3, x + Math.min(w2 + 10, cw)});   // generous: the line's real end is a little past the sum of its parts
        // lines 4-7: radio details of this SIM's serving cell and tower
        if (live) simDetail(c, si, x, cw, y(P2) + 4);
    }

    private static final class DSeg {
        final String t; final float sz; final Typeface f; final int col;
        boolean chip;   // drawn as dark text on a filled, coloured rounded tag
        DSeg(String t, float sz, Typeface f, int col) { this.t = t; this.sz = sz; this.f = f; this.col = col; }
        DSeg chip() { chip = true; return this; }
    }

    private float dsW(DSeg sg) { return tw(sg.t, sg.sz, sg.f) + (sg.chip ? 11 : 0); }

    /** One detail segment at x (inside a fit() block, line centre = 0); returns its end x. */
    private float dsDraw(Canvas c, DSeg sg, float x) {
        if (!sg.chip) return textB(c, sg.t, x, 0, 22, sg.sz, sg.f, sg.col);
        float w = tw(sg.t, sg.sz, sg.f) + 10;
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(sg.col);
        rf.set(x, -8, x + w, 11.5f);
        c.drawRoundRect(rf, 5, 5, gp);
        textB(c, sg.t, x + 5, 0, 22, sg.sz, sg.f, 0xFF101010);
        return x + w + 5;
    }

    private java.util.List<java.util.List<DSeg>> detailLines(SimInfo si) {
        java.util.List<java.util.List<DSeg>> L = new java.util.ArrayList<>();
        int g = grey();
        // PCI, ARFCN, RSRQ and TAC dropped (2026-11-14: cluttered, hardly visible). CQI kept.
        // The place name moved out of this list into drawPlace() so it can wrap to two lines.
        java.util.List<DSeg> b = new java.util.ArrayList<>();
        if (si.cqi >= 0) {
            int cc = si.cqi >= 10 ? green() : si.cqi >= 7 ? yellow() : si.cqi >= 4 ? orange() : red();
            b.add(new DSeg("CQI ", 16, semi, g)); b.add(new DSeg(String.valueOf(si.cqi), 20, bold, cc));
        }
        if (!b.isEmpty()) L.add(b);
        // "on cell Ns" dropped (2026-11-14 per request); the tower distance already has its one spot
        // on line 3, next to the band, now with a \uD83D\uDCE1 symbol - not repeated here too.
        java.util.List<DSeg> d = new java.util.ArrayList<>();
        // time on this tower, number and unit only ("45s", "3m", "1h05"); the words "on cell" stay dropped
        if (si.onCell != null) d.add(new DSeg((d.isEmpty() ? "" : " \u00b7 ") + si.onCell, 20, bold, color));
        if (si.hoToday > 0) d.add(new DSeg((d.isEmpty() ? "" : " \u00b7 ") + "\u00d7" + si.hoToday, 17, semi, g));
        // ping (data SIM only) after the tower-switch count
        String pg = pingText(si);
        if (pg != null) {
            int qc = ping < 60 ? green() : ping < 150 ? yellow() : ping < 400 ? orange() : red();
            d.add(new DSeg((d.isEmpty() ? "" : " \u00b7 ") + pg, 20, bold, qc));
        }
        if (!d.isEmpty()) L.add(d);
        // tower line: height against you and line of sight, "\u25b2 +12m \u00b7 LOS clear" / "\u25bc \u22123m \u00b7 rise 8m"
        java.util.List<DSeg> tl = new java.util.ArrayList<>();
        final boolean est = si.towerEst;
        if (si.towerH != null) {
            boolean up = si.towerH.startsWith("+");
            int hc = est ? g : up ? green() : orange();
            tl.add(new DSeg((est ? "~" : "") + (up ? "\u25b2 " : "\u25bc "), 17, bold, hc));
            tl.add(new DSeg(si.towerH.replace(" m", "m"), 20, bold, hc));
        }
        if (si.los >= 0) {
            boolean clr = si.los <= 2;
            tl.add(new DSeg((tl.isEmpty() ? (est ? "~" : "") : " \u00b7 ") + (clr ? "LOS clear" : "rise " + si.los + "m"), 20, bold, est ? g : clr ? green() : orange()));
        }
        if (!tl.isEmpty()) L.add(tl);
        return L;
    }

    private String trendLabel(SimInfo si) {
        return si.trend == null ? "\u03940" : "\u0394" + si.trend;
    }

    private float detailW(SimInfo si) {
        float m = 0;
        for (java.util.List<DSeg> ln : detailLines(si)) {
            float w = 0;
            for (DSeg sg : ln) w += dsW(sg) + (sg.chip ? 5 : 0);
            m = Math.max(m, w);
        }
        return Math.max(m, placeW(si));
    }

    /** Widest the place name (pin + roam tag + name + bearing) would need at natural size, for box-width fitting. */
    private float placeW(SimInfo si) {
        if (dupPlace(si)) { String[] qp = qWords(si); return qp == null ? 0 : qWordsW(qp); }
        String ar = placeText(si);
        if (ar == null) return 0;
        float w = tw(ar, 20, bold);
        if (si.bearing != null) w += tw("  " + si.bearing, 17, bold);
        return w;
    }

    /** Folded layouts: the quality word after SINR (good/ok/poor) is left out when this SIM shows its boxed Q. */
    private boolean hideSnrWord(SimInfo si) {
        return fold && graphsOn() && quality(si) >= 0 && (si.data || Cfg.b(Cfg.Q_SECOND, Cfg.D_Q_SECOND));
    }

    /** True when this (later) SIM's place text is the same as the first SIM's: it is then shown once, in the first box. */
    private boolean dupPlace(SimInfo si) {
        return false;   // 2026-11-22: the place name shows in every box again
    }

    /** Row that takes the place name's spot when it is a duplicate: plain verdicts, "Strong · Clean". Null if none. */
    private String[] qWords(SimInfo si) {
        if (!Cfg.b(Cfg.Q_SECOND, Cfg.D_Q_SECOND) || quality(si) < 0) return null;
        float a = Math.max(0, Math.min(1, (si.dbm + 125f) / 45f));
        String w1 = a >= 0.7f ? "Strong" : a >= 0.45f ? "Fair" : a >= 0.25f ? "Weak" : "Poor";
        if (Float.isNaN(si.snr)) return new String[]{w1, null};
        float b = Math.max(0, Math.min(1, (si.snr + 5f) / 30f));
        return new String[]{w1, b >= 0.7f ? "Clean" : b >= 0.45f ? "Okay" : b >= 0.25f ? "Noisy" : "Bad"};
    }

    private int qWordCol(String w) {
        return "Strong".equals(w) || "Clean".equals(w) ? green() : "Fair".equals(w) || "Okay".equals(w) ? yellow()
                : "Weak".equals(w) || "Noisy".equals(w) ? orange() : red();
    }

    private float qWordsW(String[] q) {
        return tw(q[0], 20, bold) + (q[1] == null ? 0 : tw(" \u00b7 ", 20, bold) + tw(q[1], 20, bold));
    }

    private String placeText(SimInfo si) {
        return dupPlace(si) ? null : placeTextRaw(si);
    }

    private String placeTextRaw(SimInfo si) {
        // TowerArea.PLACE read live here (2026-11-14), not just via the cached si.area: the SimInfo list is
        // rebuilt only occasionally, and could keep whatever si.area was at that moment - empty, say, from
        // before your location first resolved - even once TowerArea.PLACE has long since had a real answer.
        // The weather-location row above the boxes already reads PLACE live each frame; this now matches it.
        String base = !TowerArea.PLACE.isEmpty() ? TowerArea.PLACE : si.area;
        return base == null || base.isEmpty() ? null : (si.roaming && si.circle != null ? "R\u00b7" + si.circle + " " : "") + base;
    }

    /** Faint colour behind a SIM box (Tower column layout), from its signal quality; edge-to-edge in height. */
    private void tcTint(Canvas c, SimInfo si, float x, float b) {
        int col = !si.inService || si.dbm == Bands.NO_DBM ? grey() : dbmColor(si.dbm);
        float top = y(24), bot = y(PRO_H) - 5f;
        gp.setStyle(Paint.Style.FILL);
        gp.setColor((col & 0x00FFFFFF) | (44 << 24));
        rf.set(x + 1, top, x + b - 1, bot);
        c.drawRoundRect(rf, 12, 12, gp);
        stroke(1.6f, (col & 0x00FFFFFF) | (120 << 24));
        c.drawRoundRect(rf, 12, 12, gp);
    }

    /** 24-wide icons for the tower column: 0 site (mast with a tag), 1 channel width, 2 neighbour (tower and a handover arrow). */
    private void tcIcon(Canvas c, int kind, float x, float cy, int col) {
        stroke(2f, col);
        if (kind == 0) {
            c.drawLine(x + 8, cy - 4, x + 2, cy + 10, gp);
            c.drawLine(x + 8, cy - 4, x + 14, cy + 10, gp);
            c.drawLine(x + 4.5f, cy + 3.5f, x + 11.5f, cy + 3.5f, gp);
            gp.setStyle(Paint.Style.FILL);
            c.drawCircle(x + 8, cy - 7, 2.2f, gp);
            stroke(1.6f, col);
            rf.set(x + 13, cy - 12, x + 21, cy - 6);
            c.drawRect(rf, gp);
        } else if (kind == 1) {
            c.drawLine(x + 1, cy - 7, x + 1, cy + 7, gp);
            c.drawLine(x + 22, cy - 7, x + 22, cy + 7, gp);
            c.drawLine(x + 4, cy, x + 19, cy, gp);
            gp.setStyle(Paint.Style.FILL);
            Path p = new Path();
            p.moveTo(x + 4, cy); p.lineTo(x + 9, cy - 4); p.lineTo(x + 9, cy + 4); p.close();
            p.moveTo(x + 19, cy); p.lineTo(x + 14, cy - 4); p.lineTo(x + 14, cy + 4); p.close();
            c.drawPath(p, gp);
        } else {
            c.drawLine(x + 6, cy - 3, x + 1, cy + 10, gp);
            c.drawLine(x + 6, cy - 3, x + 11, cy + 10, gp);
            gp.setStyle(Paint.Style.FILL);
            c.drawCircle(x + 6, cy - 6, 2.2f, gp);
            stroke(2f, col);
            rf.set(x + 8, cy - 10, x + 21, cy + 4);
            c.drawArc(rf, 270, 180, false, gp);
            gp.setStyle(Paint.Style.FILL);
            Path p = new Path();
            p.moveTo(x + 14.5f, cy + 9); p.lineTo(x + 19, cy + 5.5f); p.lineTo(x + 19, cy + 12.5f); p.close();
            c.drawPath(p, gp);
        }
    }

    /** Tower column: site, channel width, strongest neighbour - each on the lowest free line that has room at the box's right edge. */
    private void tcColumn(Canvas c, SimInfo si, float x, float cw) {
        if (!si.inService) return;
        final float xr = x + cw - 2;
        String[] txt = new String[3];
        int[] col = new int[3];
        if (Cfg.b(Cfg.TC_SITE, Cfg.D_TC_SITE) && si.site != null) { txt[0] = si.site.replace('-', '\u00b7'); col[0] = blue(); }
        if (Cfg.b(Cfg.TC_BW, Cfg.D_TC_BW) && si.bw > 0) { txt[1] = si.bw + " MHz"; col[1] = grey(); }
        else if (Cfg.b(Cfg.TC_BW, Cfg.D_TC_BW) && si.bw <= 0 && si.band != null) {
            // the modem reports no width for 5G SA: a typical-for-the-band estimate, marked with a ~
            String bd = si.band.split("[+ ,]")[0];
            int est = "n78".equals(bd) ? 100 : "n28".equals(bd) ? 10 : 0;
            if (est > 0) { txt[1] = "~" + est + " MHz"; col[1] = grey(); }
        }
        if (Cfg.b(Cfg.TC_NB, Cfg.D_TC_NB) && si.nbr != null) {
            txt[2] = si.nbr;
            col[2] = si.dbm != Bands.NO_DBM && si.nbrDbm > si.dbm ? green() : orange();
        }
        int from = 0;
        for (int i = 0; i < 3; i++) {
            if (txt[i] == null) continue;
            float w = 28 + tw(txt[i], 19, bold);
            boolean placed = false;
            for (int j = from; j < slots.size(); j++) {
                float[] sl = slots.get(j);
                if (xr - sl[1] >= w + 16) {
                    tcIcon(c, i, xr - w, sl[0], col[i]);
                    textB(c, txt[i], xr - w + 28, sl[0], 22, 19, bold, col[i]);
                    from = j + 1;
                    placed = true;
                    break;
                }
            }
            if (!placed && !slots.isEmpty()) {   // no free space: nothing is left out, it is drawn faint over the last line
                float[] sl = slots.get(Math.min(slots.size() - 1, Math.max(from, 0)));
                c.saveLayerAlpha(xr - w - 4, sl[0] - 16, xr + 4, sl[0] + 16, 90);
                tcIcon(c, i, xr - w, sl[0], col[i]);
                textB(c, txt[i], xr - w + 28, sl[0], 22, 19, bold, col[i]);
                c.restore();
            }
        }
    }

    private void simDetail(final Canvas c, SimInfo si, float x, float cw, float y0) {
        float cy = y0;
        boolean first = true;
        for (final java.util.List<DSeg> ln : detailLines(si)) {
            // the place name (if any) is drawn between the first line (CQI) and the rest
            if (first) {
                float ph = drawPlace(c, si, x, cw, cy);
                if (ph > 0 && ph < 30f) slots.add(new float[]{cy, x + Math.min(placeW(si), cw)});
                else if (ph > 0) slots.add(new float[]{cy + 22f, x + cw});   // two-line name: both lines are full
                cy += ph; first = false;
            }
            if (ln.isEmpty()) continue;
            final float lcy = cy;
            float w = 0;
            for (DSeg sg : ln) w += dsW(sg) + (sg.chip ? 5 : 0);
            // the last row (data / Q) is the more important one: a line it lands on shows faintly behind it
            boolean under = hasFoldRow(si) && Math.abs(lcy - foldRowY(si, cw)) < 21f;
            if (under) c.saveLayerAlpha(x - 6, lcy - 16, x + cw + 6, lcy + 16, 90);
            fit(c, x, lcy, w, cw, () -> {
                float e = 0;
                for (DSeg sg : ln) e = dsDraw(c, sg, e);
            });
            if (under) c.restore();
            slots.add(new float[]{lcy, x + Math.min(w, cw)});
            cy += 22f;
        }
        if (first) drawPlace(c, si, x, cw, cy);   // no other lines at all: still show the place name
    }

    /**
     * Place name (pin + optional roaming tag + name + bearing) under the SIM box: one line at natural
     * size when it fits, otherwise wrapped onto two lines (long names like "Pimpri-Chinchwad" split at
     * the hyphen/space), shrinking first and cutting with "\u2026" only as a last resort. Returns the height used.
     */
    private float drawPlace(Canvas c, SimInfo si, float x, float cw, float cy) {
        if (dupPlace(si)) {
            final String[] qp = qWords(si);
            if (qp == null) return 0;
            fit(c, x, cy, qWordsW(qp), cw, () -> {
                float e = textB(c, qp[0], 0, 0, 20, 20, bold, qWordCol(qp[0]));
                if (qp[1] != null) {
                    e = textB(c, " \u00b7 ", e, 0, 20, 20, bold, grey());
                    textB(c, qp[1], e, 0, 20, 20, bold, qWordCol(qp[1]));
                }
            });
            return 22f;
        }
        String ar = placeText(si);
        if (ar == null) return 0;
        int g = grey();
        String pin = "";   // pin symbol dropped (2026-11-14 per request)
        String bearing = si.bearing != null ? "  " + si.bearing : "";
        float pinW = tw(pin, 17, bold);
        if (pinW + tw(ar, 20, bold) + (bearing.isEmpty() ? 0 : tw(bearing, 17, bold)) <= cw) {
            float e = textB(c, pin, x, cy, 22, 17, bold, g);
            e = textB(c, ar, e, cy, 22, 20, bold, color);
            if (!bearing.isEmpty()) textB(c, bearing, e, cy, 22, 17, bold, yellow());
            return 22f;
        }
        if (pinW + tw(ar, 20, bold) <= cw) {
            float e = textB(c, pin, x, cy, 22, 17, bold, g);
            textB(c, ar, e, cy, 22, 20, bold, color);
            return 22f;
        }
        String[] two = split2(ar);
        if (two != null) {
            float sz = 20;
            while (sz > 13 && (pinW + tw(two[0], sz, bold) > cw || tw(two[1], sz, bold) > cw)) sz -= 1;
            if (pinW + tw(two[0], sz, bold) <= cw && tw(two[1], sz, bold) <= cw) {
                float e = textB(c, pin, x, cy, 22, 17, bold, g);
                textB(c, two[0], e, cy, 22, sz, bold, color);
                textB(c, two[1], x, cy + 22f, 22, sz, bold, color);
                return 44f;
            }
        }
        // last resort: force it onto the canvas by scaling the whole line down (like every other line in
        // this box already does via fit()), so it's always visible, just smaller - rather than shrinking the
        // font character by character, which could reach a point where even the pin's own width doesn't fit
        // cw and the text gets trimmed to nothing. (2026-11-14: this is what was making the name disappear.)
        float w = pinW + tw(ar, 18, bold);
        fit(c, x, cy, w, cw, () -> {
            float e = textB(c, pin, 0, 0, 22, 17, bold, g);
            textB(c, ar, e, 0, 22, 18, bold, color);
        });
        return 22f;
    }

    /**
     * Each SIM's tower area under its box (rows 3-4): one line when it fits, two lines for long names,
     * smaller text before cutting with "…".
     */
    private boolean towerBelow, towerAnyBelow;
    /** The name sits right after the roaming tag (left-aligned) instead of centred in the space. */
    private boolean nameLeft;
    private float cx(float bx, float bw, float w) { return nameLeft ? bx : bx + Math.max(0, (bw - w) / 2f); }
    private android.graphics.Bitmap scratchBmp;
    private Canvas scratchCv;

    /** Two passes: a dry run finds out whether any SIM's extras don't fit beside its name; if so, all SIMs put them below, so names match. */
    private void proTowerAreas(Canvas c) {
        if (scratchCv == null) {
            scratchBmp = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888);
            scratchCv = new Canvas(scratchBmp);
        }
        towerBelow = false;
        towerAnyBelow = false;
        try { proTowerAreas0(scratchCv); } catch (Throwable ignored) { }
        towerBelow = towerAnyBelow;
        proTowerAreas0(c);
    }

    private void proTowerAreas0(Canvas c) {
        w4Start = Float.isNaN(simLeft) ? W - 8 : simLeft;
        q4End = w4Start;
        q3End = w4Start;
        boolean q3Open = true;                              // row 3 free under the boxes so far
        boolean q4Open = true;                              // row 4 free under the boxes so far (left to right)
        float boxBottom = y(P2) + 17;                      // the SIM box's white line
        for (int i = 0; i < colN && i < sims.size(); i++) {
            SimInfo si = sims.get(i);
            // no service: "No coverage" replaces the tower-area line
            // very weak signal (RSRP -115 dBm or lower): "Weak signal" goes in front of the area name
            boolean weakSig = si.inService && si.dbm != Bands.NO_DBM && si.dbm <= -115;
            if (si.inService) { if (q4Open) q4End = colX[i] + colW[i]; if (q3Open) q3End = colX[i] + colW[i]; continue; }   // details are inside the box, which ends above row 4: row 4 may run on under it
            if (!si.inService) {
                float nsz = 23, nbw = colW[i] - 8;
                while (nsz > 15 && tw("No coverage", nsz, semi) > nbw) nsz -= 0.5f;
                text(c, "No coverage", colX[i] + 4 + (nbw - tw("No coverage", nsz, semi)) / 2f, boxBottom + 3 + nsz * 0.5f, nsz, semi, grey());
                q3Open = false;
                if (q4Open) q4End = colX[i] + colW[i];
                continue;
            }
            boolean hasArea = si.area != null && !si.area.isEmpty(), hasNet = si.netName != null && !si.netName.isEmpty();
            if (!hasArea && !hasNet && weakSig) {
                float wsz = 23, wbw = colW[i] - 8;
                while (wsz > 15 && tw("Weak signal", wsz, semi) > wbw) wsz -= 0.5f;
                text(c, "Weak signal", colX[i] + 4 + (wbw - tw("Weak signal", wsz, semi)) / 2f, boxBottom + 3 + wsz * 0.5f, wsz, semi, grey());
                q3Open = false;
                if (q4Open) q4End = colX[i] + colW[i];
                continue;
            }
            if (!hasArea && !hasNet) { if (q4Open) q4End = colX[i] + colW[i]; if (q3Open) q3End = colX[i] + colW[i]; continue; }
            float bx = colX[i] + 4, bw = colW[i] - 8;
            nameLeft = false;
            float top = boxBottom;
            final float fullBx = bx, fullBw = bw;
            if (si.roaming) {
                // yellow "R·KA" tag: beside the area name when both fit, else on its own line above it
                String rt = roamText(si);
                float tagW = tw(rt, 18, bold) + 10;
                String nm0 = hasNet && hasArea ? si.netName + " · " + si.area : hasNet ? si.netName : hasArea ? si.area : "";
                boolean beside = nm0.isEmpty() || tw(nm0, 18, semi) + tagW + 6 <= bw;
                float tx = beside && !nm0.isEmpty() ? bx : bx + (bw - tagW) / 2f, ty = top + 3 + 11;
                gp.setStyle(Paint.Style.FILL);
                gp.setColor(0xFFFFD21F);
                rf.set(tx, ty - 11, tx + tagW, ty + 11);
                c.drawRoundRect(rf, 5, 5, gp);
                textB(c, rt, tx + 5, ty, 18, 18, bold, 0xFF2A2000);
                if (!hasArea && !hasNet) continue;
                if (beside) { bx += tagW + 6; bw -= tagW + 6; nameLeft = !nm0.isEmpty(); }
                else top += 26;                      // name goes on the next line(s)
            }
            int col = si.data ? color : grey();
            // "Dolphin · ~Andheri": the network's own name first; when too long, the area name shortens first
            String nm = hasNet && hasArea ? si.netName + " · " + si.area : hasNet ? si.netName : si.area;
            if (weakSig) nm = "Weak signal · " + nm;
            if (hasNet && hasArea && tw(nm, 20, semi) > bw && split2(nm) == null) {
                String a = si.area;
                while (a.length() > 3 && tw(si.netName + " · " + a, 20, semi) > bw) a = a.substring(0, a.length() - 2) + "…";
                nm = si.netName + " · " + a;
            }
            float yEnd;
            boolean pciDone = false;
            // beside the name: direction to the tower and time on it; signal trend after
            java.util.List<String> bes = new java.util.ArrayList<>();
            if (si.bearing != null) bes.add(si.bearing);
            if (si.onCell != null) bes.add(si.onCell);
            if (si.hoToday > 0) bes.add("×" + si.hoToday);
            String pci = null;
            while (!bes.isEmpty()) {
                String cand = String.join(" · ", bes);
                if (tw(nm, 18, semi) + tw(" · " + cand, 18 * 0.85f, semi) <= bw) { pci = cand; break; }
                bes.remove(bes.size() - 1);
            }
            java.util.List<String> rest = new java.util.ArrayList<>();
            if (si.bearing != null && (pci == null || !pci.contains(si.bearing))) rest.add(si.bearing);
            if (si.onCell != null && (pci == null || !pci.contains(si.onCell))) rest.add(si.onCell);
            if (si.hoToday > 0 && (pci == null || !pci.contains("×" + si.hoToday))) rest.add("×" + si.hoToday);
            float szP = 23;
            if (pci != null) while (szP > 18 && tw(nm, szP, semi) + tw(" · " + pci, szP * 0.85f, semi) > bw) szP -= 0.5f;
            if (pci != null && !towerBelow && tw(nm, szP, semi) + tw(" · " + pci, szP * 0.85f, semi) <= bw) {
                // short name: "~Ravet · ↗NE · 3m" on one line
                float wN = tw(nm, szP, semi), wP = tw(" · " + pci, szP * 0.85f, semi);
                float x0 = cx(bx, bw, wN + wP), cy = top + 3 + szP * 0.5f;
                text(c, nm, x0, cy, szP, semi, col);
                text(c, " · " + pci, x0 + wN, cy, szP * 0.85f, semi, grey());
                yEnd = top + 3 + szP;
                pciDone = true;
            } else {
                if (pci == null && (si.bearing != null || si.onCell != null || si.hoToday > 0)) towerAnyBelow = true;
                yEnd = drawAreaName(c, nm, bx, bw, top, col);
            }
            // whatever room is left below: channel, tracking area, data used today
            if (!pciDone) {   // nothing went beside the name: everything goes below
                rest.clear();
                if (si.bearing != null) rest.add(si.bearing);
                if (si.onCell != null) rest.add(si.onCell);
                if (si.hoToday > 0) rest.add("×" + si.hoToday);
            }
            float used = yEnd;
            try { used = Math.max(used, cellExtras(c, si, fullBx, fullBw, yEnd, rest)); } catch (Throwable ignored) { }
            if (q3Open && used < y(P3) - 17) q3End = colX[i] + colW[i]; else q3Open = false;
            if (q4Open && used < y(P4) - 13) q4End = colX[i] + colW[i];
            else q4Open = false;
        }
    }

    /** The area name under a box: one line (smaller if long), two balanced lines, or cut with "…". Returns its bottom. */
    private float drawAreaName(Canvas c, String nm, float bx, float bw, float top, int col) {
        float sz = 23;   // same top size as the one-line form beside the name, so both SIMs match
        while (sz > 15 && tw(nm, sz, semi) > bw) sz -= 0.5f;
        if (tw(nm, sz, semi) <= bw) {
            text(c, nm, cx(bx, bw, tw(nm, sz, semi)), top + 3 + sz * 0.5f, sz, semi, col);
            return top + 3 + sz;
        }
        String[] two = split2(nm);
        if (two != null) {
            float s2 = 23;
            while (s2 > 12 && Math.max(tw(two[0], s2, semi), tw(two[1], s2, semi)) > bw) s2 -= 1;
            if (Math.max(tw(two[0], s2, semi), tw(two[1], s2, semi)) <= bw) {
                float l1 = top + 3 + s2 * 0.5f, l2 = l1 + s2 * 1.1f;
                text(c, two[0], cx(bx, bw, tw(two[0], s2, semi)), l1, s2, semi, col);
                text(c, two[1], cx(bx, bw, tw(two[1], s2, semi)), l2, s2, semi, col);
                return l2 + s2 * 0.5f;
            }
        }
        sz = 20;
        while (sz > 12 && tw(nm, sz, semi) > bw) sz -= 1;
        String t = nm;
        while (t.length() > 3 && tw(t, sz, semi) > bw) t = t.substring(0, t.length() - 2) + "…";
        text(c, t, cx(bx, bw, tw(t, sz, semi)), top + 3 + sz * 0.5f, sz, semi, col);
        return top + 3 + sz;
    }

    /**
     * Cell details in the space left under a SIM box, one line if there's room: (PCI when it didn't fit beside
     * the name) · channel · TAC · data today. Items drop from the end until the line fits; nothing if no room.
     */
    private float cellExtras(Canvas c, SimInfo si, float bx, float bw, float yTop, java.util.List<String> lead) {
        java.util.List<String> parts = new java.util.ArrayList<>(lead);
        if (si.dataToday >= 0) parts.add(si.dataToday < 1_000_000_000L
                ? String.format(java.util.Locale.US, "%.0fM", si.dataToday / 1e6)
                : String.format(java.util.Locale.US, "%.1fG", si.dataToday / 1e9));
        float bottom = y(204);
        while (!parts.isEmpty()) {
            String line = String.join(" · ", parts);
            float sz = 17;
            while (sz > 13.5f && tw(line, sz, semi) > bw) sz -= 0.5f;
            if (yTop + 2 + sz > bottom) return yTop;            // no room under this box
            if (tw(line, sz, semi) <= bw) {
                text(c, line, bx + (bw - tw(line, sz, semi)) / 2f, yTop + 2 + sz * 0.5f, sz, semi, grey());
                return yTop + 2 + sz;
            }
            parts.remove(parts.size() - 1);
        }
        return yTop;
    }

    /** "Pimpri Chinchwad" -> {"Pimpri", "Chinchwad"}; null when there is no space to split at. */
    private static String[] split2(String s) {
        String[] w = s.trim().split("\\s+");
        if (w.length < 2) {
            int h = s.indexOf('-');
            return h > 0 && h < s.length() - 1 ? new String[]{s.substring(0, h + 1), s.substring(h + 1)} : null;
        }
        String[] best = null;
        for (int k = 1; k < w.length; k++) {
            String a = String.join(" ", java.util.Arrays.copyOfRange(w, 0, k));
            String b = String.join(" ", java.util.Arrays.copyOfRange(w, k, w.length));
            if (best == null || Math.abs(a.length() - b.length()) < Math.abs(best[0].length() - best[1].length())) best = new String[]{a, b};
        }
        return best;
    }

    /**
     * Tide on row 3 after the AQI pill. Richest form that fits wins, and the place name is kept as long as
     * possible by dropping the wave icon and height first:
     * "≈ ↑ +1.4m H 22:14 Alibag" → "≈↑ H22:14 Alibag" → "↑H22:14 Alibag" → "≈↑ +1.4m H22:14" → "↑H22:14".
     */
    /** Gap between weather items: the normal one when it fits, smaller (down to 1, touching) when space is short. */
    private float tightGap(List<Item> items, float x, float maxX, float normal) {
        if (items.size() < 2) return normal;
        float sum = 0;
        for (Item it : items) sum += it.w();
        float g = (maxX - x - sum) / (items.size() - 1);
        return Math.max(1f, Math.min(normal, g));
    }

    /** Width of the smallest tide form ("↑H22:14"), 0 when there's no tide to show. */
    private float tideMinW(boolean withName) {
        Tide.Now t;
        if (!Cfg.b(Cfg.TIDE, Cfg.D_TIDE) || (t = Tide.now(System.currentTimeMillis())) == null) return 0;
        Item it = new Item(1, 25).t("↑", 24, bold, color).t("H", 18, semi, color).t("22:14", 23, bold, color);
        if (withName && t.place != null) it.sp(5).t(t.place, 18, semi, color);
        return it.w() + 1;
    }

    /** Sea temperature at the tide place, right after the tide; drawn only at full size. */
    private float proSea(Canvas c, float x0, float x1) {
        float st = Tide.seaTemp(System.currentTimeMillis());
        if (Float.isNaN(st)) return x0 - 14;
        int col = st < 24 ? blue() : st < 28 ? teal() : st < 30 ? yellow() : orange();
        Item it = new Item(1, 25).t("🌊", 21, bold, col).sp(3).t(Math.round(st) + "°", 25, bold, col);   // wave emoji + temperature
        String sd = seaDist();
        if (sd != null) it.sp(4).t(sd, 19, semi, grey());      // merged (2026-11-14): coast distance right after the sea temperature
        if (x0 + it.w() > x1) return x0 - 14;
        return drawItem(c, it, x0, y(P3));
    }

    /** "~92km" / "~800m" to the tide place or nearest coast, null when unknown. */
    private String seaDist() {
        try {
            if (Float.isNaN(Tide.seaTemp(System.currentTimeMillis()))) return null;
            double sk = Tide.seaKm(fix != null ? fix[0] : latLon[0], fix != null ? fix[1] : latLon[1]);
            return sk < 1 ? Math.round(sk * 10) * 100 + "m" : Math.round(sk) + "km";
        } catch (Throwable e) { return null; }
    }

    /** Width (with the 14 px gap before it) of the sea temperature + smallest coast-distance form; 0 when there is none. */
    private float seaDistW() {
        try {
            float st = Tide.seaTemp(System.currentTimeMillis());
            if (Float.isNaN(st)) return 0;
            double sk = Tide.seaKm(fix != null ? fix[0] : latLon[0], fix != null ? fix[1] : latLon[1]);
            String dist = sk < 1 ? Math.round(sk * 10) * 100 + "m" : Math.round(sk) + "km";
            return 14 + new Item(1, 25).t("🌊", 21, bold, grey()).sp(3).t(Math.round(st) + "°", 25, bold, grey())
                    .sp(4).t(dist, 19, semi, grey()).w() - 10;
        } catch (Throwable e) { return 0; }
    }

    /** Nearest dam on row 3: a pill coloured by how full it is ("62%") and the dam's name when there's room. */
    private float proDam(Canvas c, float x0, float x1) {
        Dam.Reading g = Dam.curRead;
        Dam.Res r = Dam.cur;
        if (!Cfg.b(Cfg.DAM, Cfg.D_DAM) || g == null || r == null) return -1;
        if (System.currentTimeMillis() - Dam.fetchedAt > 3 * 86400_000L) return -1;
        String p = Dam.pctText(g.pct);
        float pw = tw(p, 22, bold) + 22;
        String nm = r.name.length() > 14 ? r.name.substring(0, 14).trim() : r.name;
        nm = nm.substring(0, 1) + nm.substring(1).toLowerCase(Locale.ROOT);
        float nw = tw(nm, 19, semi);
        boolean withName = x0 + pw + 6 + nw <= x1;
        if (x0 + pw > x1) return -1;
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(Dam.STATE_COLORS[Dam.state(g.pct)]);
        rf.set(x0, y(P3) - 17, x0 + pw, y(P3) + 17);
        c.drawRoundRect(rf, 17, 17, gp);
        textB(c, p, x0 + 11, y(P3), 22, 22, bold, 0xFFFFFFFF);
        float end = x0 + pw;
        if (withName) end = drawItem(c, new Item(1, 25).t(nm, 19, semi, grey()), end + 6, y(P3));
        return end;
    }

    private float proTide(Canvas c, float x0, float x1) {
        if (!Cfg.b(Cfg.TIDE, Cfg.D_TIDE) || x1 - x0 < 40) return 0;
        Tide.Now t = Tide.now(System.currentTimeMillis());
        if (t == null) return 0;
        int ac = t.rising ? blue() : teal();
        long nowT = System.currentTimeMillis();
        long minsTo = (t.nextAt - nowT) / 60000L;
        boolean soon = minsTo >= 0 && minsTo <= 60;
        int hc = soon ? orange() : t.spring ? SysViews.amber(dk) : color;
        String arrow = t.rising ? "↑" : "↓";
        String hl = t.nextHigh ? "H" : "L";
        String tm = new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new java.util.Date(t.nextAt));
        String ht = String.format(Locale.US, "%+.1f", t.height);
        String pl = t.place == null ? "" : t.place;
        Item[] v0 = {
                new Item(1, 25).g(G_WAVE, 22, ac).t(arrow, 24, bold, ac).sp(5).t(hl, 19, semi, grey()).t(tm, 24, bold, hc)
                        .sp(6).t(pl, 19, semi, grey()),
                new Item(1, 25).t(arrow, 24, bold, ac).t(hl, 18, semi, grey()).t(tm, 23, bold, hc).sp(5).t(pl, 18, semi, grey()),
                new Item(1, 25).t(arrow, 22, bold, ac).t(hl, 16, semi, grey()).t(tm, 21, bold, hc).sp(3).t(pl, 15, semi, grey()),
                new Item(1, 25).t(arrow, 24, bold, ac).t(hl, 18, semi, grey()).t(tm, 23, bold, hc),
        };
        Item[] v = v0;
        if (soon) {
            String in = "in " + minsTo + "m";
            Item[] w = new Item[v0.length + 3];
            Item nm2 = new Item(1, 25).t(arrow, 24, bold, ac).t(hl, 18, semi, grey()).t(tm, 23, bold, hc).sp(5).t(minsTo + "m", 18, semi, hc)
                    .sp(5).t(pl, 18, semi, grey());
            w[0] = new Item(1, 25).t(arrow, 24, bold, ac).t(hl, 18, semi, grey()).t(tm, 23, bold, hc).sp(5).t(in, 18, semi, hc)
                    .sp(5).t(pl, 18, semi, grey());
            w[1] = nm2;
            w[2] = new Item(1, 25).t(arrow, 24, bold, ac).t(hl, 18, semi, grey()).t(tm, 23, bold, hc).sp(5).t(in, 18, semi, hc);
            System.arraycopy(v0, 0, w, 3, v0.length);
            v = w;
        }
        // keep room for the coast distance beside the sea temperature when a form of the tide allows it
        float reserve = 0;
        if (reserve > 0) for (Item it : v) {
            if (x0 + it.w() + reserve <= x1) return drawItem(c, it, x0, y(P3));
        }
        for (Item it : v) {
            if (x0 + it.w() <= x1) {
                return tideSlot(c, it, x0);
            }
        }
        return 0;
    }

    private static final String[] ARR_TO = {"\u2191", "\u2197", "\u2192", "\u2198", "\u2193", "\u2199", "\u2190", "\u2196"};
    private static final String[] DIR_8 = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    /** The tide slot: the tide, then (every 5 s) the waves, the sea state and the ocean current, each no wider than the tide text so nothing to the right moves. */
    private float tideSlot(Canvas c, Item tide, float x0) {
        float slotW = tide.w();
        try {
            if (!seaInline && Cfg.b(Cfg.SEA_FRAMES, Cfg.D_SEA_FRAMES)) {
                List<Item> fr = seaFrames(slotW);
                if (!fr.isEmpty()) {
                    int k = (int) ((System.currentTimeMillis() / 5000L) % (fr.size() + 1));
                    if (k > 0) { drawItem(c, fr.get(k - 1), x0, y(P3)); return x0 + slotW; }
                }
            }
        } catch (Throwable ignored) { }
        return drawItem(c, tide, x0, y(P3));
    }

    /** Wave, sea-state and current items, each as variants from the longest to the shortest. */
    private List<List<Item>> seaVariants() {
        List<List<Item>> out = new ArrayList<>();
        float[] sv = Tide.sea(System.currentTimeMillis());
        if (sv == null) return out;
        float hs = sv[0], per = sv[1];
        if (!Float.isNaN(hs)) {
            int sc = hs < 1.25f ? green() : hs < 2.5f ? teal() : hs < 4f ? yellow() : orange();
            String hh = String.format(Locale.US, "%.1f", hs);
            List<Item> w = new ArrayList<>();
            if (!Float.isNaN(per))
                w.add(new Item(1, 25).g(G_WAVE, 22, blue()).sp(4).t(hh, 24, bold, color).t("m", 17, semi, grey()).sp(6).t("\u00b7", 18, bold, grey()).sp(6)
                        .t(String.valueOf(Math.round(per)), 24, bold, color).t("s", 17, semi, grey()));
            w.add(new Item(1, 25).g(G_WAVE, 22, blue()).sp(4).t(hh, 24, bold, color).t("m", 17, semi, grey()));
            out.add(w);
            List<Item> st = new ArrayList<>();
            String sw = Tide.stateWord(hs).replace(" sea", "");
            st.add(new Item(1, 25).t(sw, 22, bold, sc));
            st.add(new Item(1, 25).t(sw, 19, bold, sc));
            out.add(st);
        } else { out.add(null); out.add(null); }
        float kn = sv[2] / 1.852f;
        if (!Float.isNaN(kn) && kn >= 0.1f) {
            String dir = "", ar = "";
            if (!Float.isNaN(sv[3])) { int o = (int) Math.round(((sv[3] % 360) + 360) % 360 / 45.0) % 8; dir = DIR_8[o]; ar = ARR_TO[o]; }
            String kt = String.format(Locale.US, "%.1f", kn);
            List<Item> cu = new ArrayList<>();
            String cn = null;
            try { double[] xy = Tide.placeXY(); if (xy != null) cn = EarthSci.currentName(xy[0], xy[1]); } catch (Throwable ignored) { }
            if (cn != null) {
                cu.add(new Item(1, 25).t(ar, 24, bold, blue()).sp(3).t(dir, 22, bold, color).sp(5).t(kt, 24, bold, color).t("kn", 16, semi, grey()).sp(8).t(cn, 17, semi, color));
                String sc2 = EarthSci.shortCurrent(cn);
                if (!sc2.equals(cn)) cu.add(new Item(1, 25).t(ar, 24, bold, blue()).sp(3).t(dir, 22, bold, color).sp(5).t(kt, 24, bold, color).t("kn", 16, semi, grey()).sp(8).t(sc2, 17, semi, color));
            }
            cu.add(new Item(1, 25).t(ar, 24, bold, blue()).sp(3).t(dir, 22, bold, color).sp(5).t(kt, 24, bold, color).t("kn", 16, semi, grey()).sp(5).t("current", 16, semi, grey()));
            cu.add(new Item(1, 25).t(ar, 24, bold, blue()).sp(3).t(dir, 22, bold, color).sp(5).t(kt, 24, bold, color).t("kn", 16, semi, grey()));
            cu.add(new Item(1, 25).t(ar, 22, bold, blue()).t(kt, 22, bold, color).t("kn", 15, semi, grey()));
            out.add(cu);
        } else out.add(null);
        return out;
    }

    /** One item per kind for the tide slot's rotation: the first variant no wider than maxW. */
    private List<Item> seaFrames(float maxW) {
        List<Item> out = new ArrayList<>();
        for (List<Item> vs : seaVariants()) if (vs != null) for (Item i : vs) if (i.w() <= maxW) { out.add(i); break; }
        return out;
    }

    private boolean seaInline = false, seaInlineFit = true;
    private String seaNote = "";

    /** Row 3 after the sea temperature, up to the SIM boxes: waves + sea state together, then the current, taking turns every 5 s. Returns the end x (x0 when nothing fits). */
    private float seaInlineDraw(Canvas c, float x0, float x1) {
        List<List<Item>> vv = seaVariants();
        if (vv.size() < 3) { seaNote = "no marine data for the tide place"; return x0; }
        float room = x1 - x0 - 14;
        List<Item> fr = new ArrayList<>();
        List<Item> lw = vv.get(0), ls = vv.get(1), lc = vv.get(2);
        Item both = null;
        if (lw != null && ls != null) {
            for (int lv = 0; lv < 3 && both == null; lv++) {
                Item w = lw.get(Math.min(lv, lw.size() - 1)), st = ls.get(Math.min(lv, ls.size() - 1));
                if (w.w() + 12 + st.w() <= room) { both = new Item(1, 25); both.parts.addAll(w.parts); both.sp(12); both.parts.addAll(st.parts); }
            }
        }
        if (both != null) fr.add(both);
        else {
            if (lw != null) for (Item i : lw) if (i.w() <= room) { fr.add(i); break; }
            if (ls != null) for (Item i : ls) if (i.w() <= room) { fr.add(i); break; }
        }
        if (lc != null) for (Item i : lc) if (i.w() <= room) { fr.add(i); break; }
        seaNote = fr.size() + " sea frame(s) fit and take turns every 5 s";
        if (fr.isEmpty()) { seaNote = "no sea frame fits: the sea takes turns in the tide slot instead"; return x0; }
        Item pick = fr.get((int) ((System.currentTimeMillis() / 5000L) % fr.size()));
        float wMax = 0;
        for (Item i : fr) wMax = Math.max(wMax, i.w());
        seaTextEnd = x0 + 14 + wMax;                       // the widest frame, so what follows does not jump as the frames turn
        drawItem(c, pick, x0 + 14, y(P3));
        return x1;
    }

    /** Today's tide behind the tide text: smooth curve over 24 h, a dot for now, small ticks at high and low water. */
    private void tideCurve(Canvas c, float x0, float x1, float top, float bot) {
        long[] tt = Tide.times;
        float[] hh = Tide.heights;
        if (tt == null || hh == null || tt.length < 6 || x1 - x0 < 50) return;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0);
        long t0 = cal.getTimeInMillis(), t1 = t0 + 24L * 3600_000L, nowT = System.currentTimeMillis();
        int n = 0;
        float mn = 1e9f, mx = -1e9f;
        float[] xs = new float[tt.length], ys = new float[tt.length];
        for (int i = 0; i < tt.length && i < hh.length; i++) {
            if (tt[i] < t0 || tt[i] > t1 || Float.isNaN(hh[i])) continue;
            xs[n] = x0 + (x1 - x0) * (tt[i] - t0) / (float) (t1 - t0);
            ys[n] = hh[i];
            mn = Math.min(mn, hh[i]); mx = Math.max(mx, hh[i]);
            n++;
        }
        if (n < 6 || mx - mn < 0.1f) return;
        Path line = new Path(), fill = new Path();
        fill.moveTo(xs[0], bot);
        for (int i = 0; i < n; i++) {
            float py = bot - (bot - top) * (ys[i] - mn) / (mx - mn);
            if (i == 0) line.moveTo(xs[i], py); else line.lineTo(xs[i], py);
            fill.lineTo(xs[i], py);
        }
        fill.lineTo(xs[n - 1], bot);
        fill.close();
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(0x302FD1C1);
        c.drawPath(fill, gp);
        stk(2.5f, 0xB32FD1C1);
        c.drawPath(line, gp);
        // high / low water ticks
        gp.setStyle(Paint.Style.FILL);
        for (int i = 1; i < n - 1; i++) {
            boolean hi = ys[i] > ys[i - 1] && ys[i] >= ys[i + 1], lo = ys[i] < ys[i - 1] && ys[i] <= ys[i + 1];
            if (!hi && !lo) continue;
            float py = bot - (bot - top) * (ys[i] - mn) / (mx - mn);
            gp.setColor(hi ? 0xFF4AA8FF : 0xFFFFC21A);
            c.drawCircle(xs[i], py, 3.4f, gp);
        }
        // now
        float f = (nowT - t0) / (float) (t1 - t0);
        float nx = x0 + (x1 - x0) * f;
        float h = Float.NaN;
        for (int i = 0; i + 1 < n; i++) if (nx >= xs[i] && nx <= xs[i + 1]) {
            float u = (nx - xs[i]) / (xs[i + 1] - xs[i]);
            h = ys[i] + (ys[i + 1] - ys[i]) * u;
        }
        if (!Float.isNaN(h)) {
            float py = bot - (bot - top) * (h - mn) / (mx - mn);
            gp.setColor(0xFFFFFFFF);
            c.drawRect(nx - 0.8f, top - 2, nx + 0.8f, bot + 2, gp);
            c.drawCircle(nx, py, 3.6f, gp);
        }
    }

    /** Colour emoji for an OmniJaws (Yahoo) condition code; null = keep the ROM's own icon. */
    private static String weatherEmoji(int code) {
        switch (code) {
            case 32: case 34: case 36: return "☀️";
            case 31: case 33: return "🌙";
            case 30: return "⛅";
            case 28: return "🌥️";
            case 26: case 27: case 29: return "☁️";
            case 19: case 20: case 21: case 22: return "🌫️";
            case 23: case 24: return "💨";
            case 25: return "🥶";
            case 5: case 6: case 8: case 9: case 10: case 11: case 12: case 35: case 39: case 40: case 45: return "🌧️";
            case 3: case 4: case 37: case 38: case 47: return "⛈️";
            case 0: return "🌪️";
            case 1: case 2: return "🌀";
            case 7: case 13: case 14: case 15: case 16: case 41: case 42: case 46: return "❄️";
            case 17: case 18: case 43: return "🌨️";
            default: return null;
        }
    }

    /** Layout 2: all the weather on row 4 (icon, temperature, high / low, UV, AQI pill, visibility, cloud, rain); row 3 is the tide, the sea temperature and then the disaster item. */
    private void weatherRow4(Canvas c, float x3, float x4, float maxX4, float maxX) {
        float y4 = y(P4);
        float x = x4;
        String wxEmoji = wxCode >= 0 ? weatherEmoji(wxCode) : null;
        if (wxEmoji != null) {
            drawItem(c, new Item(1, 25).t(wxEmoji, 27, bold, color), x, y4);
            x += 33;
        } else if (wxIcon != null) {
            try {
                wxIcon.setBounds(Math.round(x), Math.round(y4 - 14), Math.round(x + 29), Math.round(y4 + 14));
                if (wxTint) wxIcon.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
                else wxIcon.clearColorFilter();
                wxIcon.draw(c);
            } catch (Throwable ignored) { }
            x += 34;
        }
        String a = wxAqi >= 0 ? String.valueOf(wxAqi) : null;
        float pw = a == null ? 0 : tw(a, 18, bold) + 16;
        List<Item> l3 = new ArrayList<>();
        l3.add(new Item(10, 30).t(wxTemp + "\u00b0", 30, bold, color));
        if (!Float.isNaN(wxMax) && !Float.isNaN(wxMin)) {
            l3.add(new Item(3, 20).t("\u2191", 13, bold, orange()).t(String.valueOf(Math.round(wxMax)), 17, bold, color)
                    .sp(3).t("\u2193", 13, bold, blue()).t(String.valueOf(Math.round(wxMin)), 17, bold, color));
        }
        if (!Float.isNaN(wxUv) && Math.round(wxUv) > 0) {
            int uv = Math.round(wxUv);
            int uc = uv < 3 ? green() : uv < 6 ? yellow() : uv < 8 ? orange() : red();
            l3.add(new Item(4, 24).t("UV", 16, semi, grey()).sp(4).t(String.valueOf(uv), 22, bold, uc));
        }
        float g3 = tightGap(l3, x, maxX4 - (pw > 0 ? pw + 8 : 0), 9);
        float e = drawLine(c, l3, x, y4, maxX4 - (pw > 0 ? pw + g3 : 0), g3);
        if (a != null && e + g3 + pw <= maxX4) {
            int ac = wxAqi <= 50 ? 0xFF2E9E4F : wxAqi <= 100 ? 0xFFC9A400 : wxAqi <= 150 ? 0xFFE07B1A
                    : wxAqi <= 200 ? 0xFFD93A2B : wxAqi <= 300 ? 0xFF8E3FB5 : 0xFF7A1F2B;
            gp.setStyle(Paint.Style.FILL);
            gp.setColor(ac);
            rf.set(e + g3, y4 - 13, e + g3 + pw, y4 + 13);
            c.drawRoundRect(rf, 13, 13, gp);
            textB(c, a, e + g3 + 8, y4, 18, 18, bold, 0xFFFFFFFF);
            e += g3 + pw;
        }
        List<Item> l4 = new ArrayList<>();
        if (!Float.isNaN(wxVis) || wxCloud >= 0 || wxRain >= 0) {
            Item vc = new Item(3, 25);
            if (!Float.isNaN(wxVis)) {
                String v = wxVis >= 10000 ? Math.round(wxVis / 1000f) + "" : wxVis >= 1000
                        ? String.format(Locale.US, "%.1f", wxVis / 1000f) : String.valueOf(Math.round(wxVis));
                String u = wxVis >= 1000 ? "" : "m";
                int col = wxVis >= 5000 ? color : wxVis >= 2000 ? yellow() : orange();
                vc.g(G_EYE, 22, grey()).sp(4).t(v, 25, bold, col).t(u, 17, semi, grey());
            }
            final boolean calm0 = wxCloud == 0 && wxRain == 0;   // both 0%: nothing to say
            final boolean showC = wxCloud >= 0 && !calm0, showR = wxRain >= 0 && !calm0;
            if (!Float.isNaN(wxVis) && showC) vc.sp(5).t("\u00b7", 18, bold, grey()).sp(5);
            if (showC) vc.g(G_CLOUD, 24, grey()).sp(4).t(wxCloud + "%", 25, bold, wxCloud == 0 ? grey() : color);
            if (showR) {
                if (showC) vc.sp(3).t("/", 20, semi, grey()).sp(3);
                else if (!Float.isNaN(wxVis)) vc.sp(7);
                vc.g(G_UMB, 21, blue()).sp(3).t(wxRain + "%", 25, bold, wxRain == 0 ? grey() : color);
            }
            l4.add(vc);
        }
        final boolean rot = Cfg.b(Cfg.WX_SKY, Cfg.D_WX_SKY);
        if (!rot && wxHum >= 0) l4.add(new Item(6, 25).g(G_DROP, 20, teal()).sp(5).t(String.valueOf(wxHum), 25, bold, color));
        if (!rot && !Float.isNaN(wxWind))
            l4.add(new Item(5, 25).t(wxArrow == null ? "" : wxArrow, 24, bold, grey()).sp(4).t(String.valueOf(Math.round(wxWind)), 25, bold, color));
        float s4 = e + 14;
        l4End = drawLine(c, l4, s4, y4, maxX4, tightGap(l4, s4, maxX4, 10));
        if (l4End < s4) l4End = e;

        // row 3: tide, then the sea temperature; the disaster item follows (proSky)
        row3End = 0;
        seaLineEnd = 0;
        seaTextEnd = 0;
        try {
            if (Cfg.b(Cfg.TIDE, Cfg.D_TIDE)) {
                final boolean side = Cfg.b(Cfg.SEA_ROW3, Cfg.D_SEA_ROW3);
                // row 3 may run on under the SIM boxes while none of them reaches down to it, but not into the data box at the far right
                float r3 = Math.min(Math.min(W - 8f, W * 0.83f), w4Start) - 10f;   // stops at the SIM boxes; what does not fit takes turns
                if (!side) r3 = Math.max(maxX, 0f);
                seaInline = side && seaInlineFit;
                float te = proTide(c, x3, side ? r3 : maxX);
                row3End = te;
                if (te > 0) row3End = proSea(c, te + 14, side ? r3 : Math.max(maxX, wxEnd));
                if (side && te > 0) {
                    float preInline = row3End;
                    float se = seaInlineDraw(c, row3End, r3);
                    Info.row3 = Diag.fmt("tide + sea temp end at x %.0f, limit x %.0f (SIM box edge), room for the sea slot %.0f px; %s", preInline, r3, r3 - preInline - 14, seaNote);
                    seaInlineFit = se > row3End || Tide.sea(System.currentTimeMillis()) == null;   // nothing fitted: next frame the sea takes turns in the tide slot
                    if (se > row3End) row3End = se;
                }
                seaLineEnd = seaTextEnd > 0 ? seaTextEnd : row3End;   // where the tide / sea line itself ends (row3End is moved on later by what follows it)
                seaInline = false;
            }
        } catch (Throwable ignored) { seaInline = false; }
    }

    /** Initials of the day type and the moon phase, "D\u00b7WxG"; null when off. */
    private String moonInitials() {
        String full = moonDayText();
        if (full == null) return null;
        String[] p = full.split(" \u00b7 ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) sb.append('\u00b7');
            sb.append(initialsOf(p[i]));
        }
        return sb.toString();
    }

    private static String initialsOf(String name) {
        switch (name) {
            case "Day": return "D";
            case "Night": return "N";
            case "Solar noon": return "SN";
            case "Solar midnight": return "SM";
            case "Golden hour": return "GH";
            case "Civil twilight": return "CT";
            case "Nautical twilight": return "NT";
            case "Astronomical twilight": return "AT";
            case "New moon": return "NM";
            case "Full moon": return "FM";
            case "First quarter": return "FQ";
            case "Last quarter": case "Third quarter": return "LQ";
            case "Waxing crescent": return "WxC";
            case "Waxing gibbous": return "WxG";
            case "Waning gibbous": return "WnG";
            case "Waning crescent": return "WnC";
            default: {
                StringBuilder b = new StringBuilder();
                for (String w : name.split(" ")) if (!w.isEmpty()) b.append(Character.toUpperCase(w.charAt(0)));
                return b.toString();
            }
        }
    }

    /** "Day \u00b7 Waxing gibbous": the day type and the moon phase name that used to sit above the SIM boxes; null when off. */
    private String moonDayText() {
        if (!simUp || !Cfg.b(Cfg.SEA_MOON, Cfg.D_SEA_MOON)) return null;
        String[] sol = solarNow();
        String nm;
        try {
            Astro.Moon mo = Astro.moon(System.currentTimeMillis(), fix != null ? fix[0] : latLon[0], fix != null ? fix[1] : latLon[1]);
            nm = phaseName((int) Math.round(mo.illum * 100), mo.phase < 0.5);
        } catch (Throwable t) {
            double ph = moonPhase();
            nm = phaseName((int) Math.round(50 * (1 - Math.cos(2 * Math.PI * ph))), ph < 0.5);
        }
        return (sol != null ? sol[1] + " \u00b7 " : "") + nm;
    }

    /** The weather in the bottom-left: row 3 icon, temperature, rain, UV, AQI pill; row 4 visibility, high/low, humidity, wind. */
    private void proWeatherLeft(Canvas c, float x3, float x4, float maxX4, float maxX3) {
        float maxX = Math.min(maxX4, maxX3);   // row 3 stops before the battery health under the camera
        if (wxTemp == null) return;
        if (Cfg.b(Cfg.WX_ROW4, Cfg.D_WX_ROW4)) { weatherRow4(c, x3, x4, maxX4, maxX); return; }
        float x = x3;
        String wxEmoji = wxCode >= 0 ? weatherEmoji(wxCode) : null;
        if (wxEmoji != null) {
            drawItem(c, new Item(1, 25).t(wxEmoji, 33, bold, color), x, y(P3));
            x += 42;
        } else if (wxIcon != null) {
            try {
                wxIcon.setBounds(Math.round(x), Math.round(y(P3) - 18), Math.round(x + 36), Math.round(y(P3) + 18));
                if (wxTint) wxIcon.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
                else wxIcon.clearColorFilter();
                wxIcon.draw(c);
            } catch (Throwable ignored) { }
            x += 42;
        }
        String a = wxAqi >= 0 ? String.valueOf(wxAqi) : null;   // coloured pill, number only (colour = level)
        float pw = a == null ? 0 : tw(a, 22, bold) + 22;
        List<Item> l3 = new ArrayList<>();
        l3.add(new Item(10, 38).t(wxTemp + "°", 38, bold, color));
        // today's high / low right beside the temperature (it used to sit on row 4)
        if (!Float.isNaN(wxMax) && !Float.isNaN(wxMin)) {
            l3.add(new Item(3, 22).t("↑", 15, bold, orange()).t(String.valueOf(Math.round(wxMax)), 19, bold, color)
                    .sp(3).t("↓", 15, bold, blue()).t(String.valueOf(Math.round(wxMin)), 19, bold, color));
        }
        if (!Float.isNaN(wxUv) && Math.round(wxUv) > 0) {   // hidden at UV 0 (2026-11-14)
            int uv = Math.round(wxUv);
            int uc = uv < 3 ? green() : uv < 6 ? yellow() : uv < 8 ? orange() : red();
            l3.add(new Item(4, 26).t("UV", 19, semi, grey()).sp(5).t(String.valueOf(uv), 25, bold, uv == 0 ? grey() : uc));
        }
        // gaps shrink (down to touching) before anything drops, leaving the smallest tide form room after AQI
        // prefer room for the tide with its place name ("↓L21:23 Murud"); only if the weather can't fit even with
        // touching items does the tide fall back to its bare form
        float sum3 = 0;
        for (Item it : l3) sum3 += it.w();
        float wxMinW = x + sum3 + Math.max(0, l3.size() - 1) + (pw > 0 ? pw + 1 : 0);
        float tideMin = tideMinW(true);
        if (tideMin > 0 && wxMinW + tideMin + 6 > maxX) tideMin = tideMinW(false);
        float g3 = tightGap(l3, x, maxX - (pw > 0 ? pw + 12 : 0) - (tideMin > 0 ? tideMin + 6 : 0), 10);
        float e = drawLine(c, l3, x, y(P3), maxX - (pw > 0 ? pw + g3 : 0), g3);
        if (a != null && e + g3 + pw <= maxX) {
            int ac = wxAqi <= 50 ? 0xFF2E9E4F : wxAqi <= 100 ? 0xFFC9A400 : wxAqi <= 150 ? 0xFFE07B1A
                    : wxAqi <= 200 ? 0xFFD93A2B : wxAqi <= 300 ? 0xFF8E3FB5 : 0xFF7A1F2B;
            gp.setStyle(Paint.Style.FILL);
            gp.setColor(ac);
            rf.set(e + g3, y(P3) - 17, e + g3 + pw, y(P3) + 17);
            c.drawRoundRect(rf, 17, 17, gp);
            textB(c, a, e + g3 + 11, y(P3), 22, 22, bold, 0xFFFFFFFF);
            e += g3 + pw;
        }
        row3End = e;
        try { row3End = Math.max(e, proTide(c, e + (pw > 0 || g3 < 6 ? 6 : g3), maxX)); } catch (Throwable ignored) { }
        try { if (row3End > e) row3End = proSea(c, row3End + 14, Math.max(maxX, wxEnd)); } catch (Throwable ignored) { }

        List<Item> l4 = new ArrayList<>();
        if (!Float.isNaN(wxVis) || wxCloud >= 0 || wxRain >= 0) {
            Item vc = new Item(3, 25);
            if (!Float.isNaN(wxVis)) {
                String v = wxVis >= 10000 ? Math.round(wxVis / 1000f) + "" : wxVis >= 1000
                        ? String.format(Locale.US, "%.1f", wxVis / 1000f) : String.valueOf(Math.round(wxVis));
                String u = wxVis >= 1000 ? "" : "m";   // the eye icon already says visibility; km is the norm, only metres get a unit
                int col = wxVis >= 5000 ? color : wxVis >= 2000 ? yellow() : orange();
                vc.g(G_EYE, 22, grey()).sp(4).t(v, 25, bold, col).t(u, 17, semi, grey());
            }
            if (!Float.isNaN(wxVis) && wxCloud >= 0) vc.sp(5).t("·", 18, bold, grey()).sp(5);
            if (wxCloud >= 0) vc.g(G_CLOUD, 24, grey()).sp(4).t(wxCloud + "%", 25, bold, wxCloud == 0 ? grey() : color);
            // rain chance sits with the cloud cover: both say "will it be wet"
            if (wxRain >= 0) {
                if (wxCloud >= 0) vc.sp(3).t("/", 20, semi, grey()).sp(3);
                else if (!Float.isNaN(wxVis)) vc.sp(7);
                vc.g(G_UMB, 21, blue()).sp(3).t(wxRain + "%", 25, bold, wxRain == 0 ? grey() : color);
            }
            l4.add(vc);
        }
        final boolean rot = Cfg.b(Cfg.WX_SKY, Cfg.D_WX_SKY);   // humidity and wind take turns in the rotating slot instead (proSky)
        if (!rot && wxHum >= 0) l4.add(new Item(6, 25).g(G_DROP, 20, teal()).sp(5).t(String.valueOf(wxHum), 25, bold, color));   // drop icon says what it is: no % sign
        if (!rot && !Float.isNaN(wxWind)) {
            l4.add(new Item(5, 25).t(wxArrow == null ? "" : wxArrow, 24, bold, grey()).sp(4)
                    .t(String.valueOf(Math.round(wxWind)), 25, bold, color));   // the arrow already says wind
        }
        // the coast distance now sits with the sea temperature (proSea)
        l4End = drawLine(c, l4, x4, y(P4), maxX4, tightGap(l4, x4, maxX4, 10));
    }


    // ---- gauge-line look: bigger coloured numbers, dim labels, thin gauge lines under the heat figures
    boolean look2;

    private int heatCol(float t, float warm, float hot, float burn) {
        return t >= burn ? red() : t >= hot ? orange() : t >= warm ? yellow() : green();
    }

    // ================================================================== earth-science graphs (right column)
    static final float GW = 84;
    private boolean graphsOn() { return pro && look2 && !chips; }
    /** Folded layout ("Status bar layout" in settings): no far-right column; its data and Q sit under the data SIM box. */
    boolean fold = true;
    boolean simUp;                                   // SIM boxes shifted up into the (dropped) moon / time-name band
    private static final float UP = 16f;           // design units: the band height, y(P1A) - 25
    private float upPx() { return simUp ? UP * vs : 0f; }
    boolean tower = false;        // Tower column layout: stretched, tinted boxes with tower details on the right
    private float l1Cap = 1e9f;                                   // right limit for a box's first line (the screen's top corner)
    private final java.util.ArrayList<float[]> slots = new java.util.ArrayList<>();   // {line centre, right end of its text} of the box being drawn
    boolean stretch = false;      // folded, and the SIM boxes also take the width the column used to have
    private boolean colOn() { return graphsOn() && !fold; }

    private void graphColumn(Canvas c, float R1) {
        final float x1 = Math.min(R1 - 2, W - 20), x0 = x1 - GW;
        int dim = fade(grey(), 0.85f);
        // battery % and data-SIM quality
        float cy0 = y(22);
        float xr = Math.min(x1, cornerRightX(cy0 - 14) - 4);
        float xe = x0;
        if (battPct >= 0 && !battPctMoved) {
            String bv = (charging ? "\u26a1" : "") + battPct + "%";
            int bc = charging || battPct >= 50 ? green() : battPct >= 20 ? yellow() : battPct >= 10 ? orange() : red();
            xe = textB(c, bv, x0, cy0, 19, 19, bold, bc) + 10;
        }
        SimInfo d = null;
        for (SimInfo si : sims) if (si.data) d = si;
        int q = -1;   // Q is shown big in the column below
        if (q >= 0) {
            String qv = q + "%";
            int qc = q >= 70 ? green() : q >= 45 ? yellow() : q >= 25 ? orange() : red();
            float qw = tw("Q", 12, semi) + 3 + tw(qv, 19, bold);
            float qx = Math.max(xe, xr - qw);
            qx = textB(c, "Q", qx, cy0, 19, 12, semi, dim) + 3;
            textB(c, qv, qx, cy0, 19, 19, bold, qc);
        }
        c.drawLine(x0 - 6, y(36), x1, y(36), sepPaint());
        try { graphSignal(c, x0, x1, d, dim); } catch (Throwable ignored) { }
    }

    // SINR history of both SIMs: one sample every ~10 s, last 10 minutes drawn
    private final float[][] sgSnr = new float[2][100];
    private final int[][] sgPci = new int[2][100];
    private final long[] sgAt = new long[100];
    private int sgN;
    private long sgLast;

    private static int sgA(int col, int a) { return (col & 0x00FFFFFF) | (a << 24); }

    private void sgSample() {
        long now = System.currentTimeMillis();
        if (now - sgLast < 10_000) return;
        sgLast = now;
        if (sgN == sgAt.length) {
            for (int k = 0; k < 2; k++) {
                System.arraycopy(sgSnr[k], 1, sgSnr[k], 0, sgN - 1);
                System.arraycopy(sgPci[k], 1, sgPci[k], 0, sgN - 1);
            }
            System.arraycopy(sgAt, 1, sgAt, 0, sgN - 1);
            sgN--;
        }
        for (int k = 0; k < 2; k++) {
            SimInfo si = k < sims.size() ? sims.get(k) : null;
            sgSnr[k][sgN] = si == null || !si.inService ? Float.NaN : si.snr;
            sgPci[k][sgN] = si == null ? -1 : si.pci;
        }
        sgAt[sgN] = now;
        sgN++;
    }

    /** Right corner: the data SIM's Q and percentage, and data used today on 4G and 5G, all in small text. */
    private void graphSignal(Canvas c, float x0, float x1o, SimInfo d, int dim) {
        if (d == null) return;
        float xr = Math.min(x1o, cornerRightX(y(40)) - 2);
        int q = quality(d);
        if (q >= 0) {
            int qc = q >= 70 ? green() : q >= 45 ? yellow() : q >= 25 ? orange() : red();
            String qv = q + "%";
            float cy = y(58);
            if (Cfg.b(Cfg.Q_BOX, Cfg.D_Q_BOX)) {   // the Q in a small outlined box
                gp.setStyle(Paint.Style.STROKE);
                gp.setStrokeWidth(2f);
                gp.setColor(dim);
                rf.set(x0 + 1, cy - 11, x0 + 21, cy + 11);
                c.drawRoundRect(rf, 4, 4, gp);
                gp.setStyle(Paint.Style.FILL);
                textB(c, "Q", x0 + 11 - tw("Q", 15, bold) / 2f, cy - 0.5f, 15, 15, bold, dim);
            } else textB(c, "Q", x0, cy, 24, 15, semi, dim);
            int qs = 24;   // shrink the percentage until it clears the Q box
            float room = xr - (x0 + (Cfg.b(Cfg.Q_BOX, Cfg.D_Q_BOX) ? 25 : 14));
            while (qs > 12 && tw(qv, qs, bold) > room) qs--;
            textB(c, qv, xr - tw(qv, qs, bold), cy, qs, qs, bold, qc);
        }
        if (dataTodayW(d) <= 0 || !Cfg.b(Cfg.SH_DATA, Cfg.D_SH_DATA)) return;
        final float f4 = (float) used4g / Math.max(1, limit4g);
        final int c4 = monthly() ? green() : f4 < 0.5f ? green() : f4 < 0.8f ? yellow() : f4 < 1f ? orange() : red();
        c.drawLine(x0 - 2, y(75), xr, y(75), sepPaint());
        if (Cfg.i(Cfg.DATA_PERIOD, Cfg.D_DATA_PERIOD) == 2) {   // D and M both at once: a box and two figures each, 4G then 5G by colour
            final int c4d = f4 < 0.5f ? green() : f4 < 0.8f ? yellow() : f4 < 1f ? orange() : red();
            periodPair(c, "D", 0xFF4DD0E1, false, x0, xr, y(93), y(115), c4d);
            periodPair(c, "M", 0xFFB388FF, true, x0, xr, y(139), y(161), green());
            if (Cfg.b(Cfg.DATA_LABELS, Cfg.D_DATA_LABELS)) {   // a tiny key under the figures
                float ck = y(184);
                float e2 = textB(c, "4G", x0, ck, 15, 13, semi, c4d);
                e2 = textB(c, " · ", e2, ck, 15, 13, semi, dim);
                textB(c, "5G", e2, ck, 15, 13, semi, teal());
            }
            return;
        }
        String pl = monthly() ? "M" : "D";
        float cyp = y(93);
        float bw0 = tw(pl, 14, bold) + 9;
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(monthly() ? 0xFFB388FF : 0xFF4DD0E1);
        rf.set(x0, cyp - 9, x0 + bw0, cyp + 9);
        c.drawRoundRect(rf, 4, 4, gp);
        float e = textB(c, pl, x0 + 4.5f, cyp, 20, 14, bold, 0xFF1A1A1A) + 9;
        textB(c, monthly() ? "month" : "today", e, cyp, 20, 15, semi, dim);
        float cy4 = y(118), cy5 = y(144);
        String v4 = dataTodayText(d, true), v5 = dataTodayText(d, false);
        boolean lbl = Cfg.b(Cfg.DATA_LABELS, Cfg.D_DATA_LABELS);
        graphRow(c, lbl ? "4G" : "", v4, x0, xr, cy4, dim, c4);
        graphRow(c, lbl ? "5G" : "", v5, x0, xr, cy5, dim, teal());
    }

    /** A coloured D or M box with the 4G figure beside it, and the 5G figure on the line below (right-aligned, shrunk to fit). */
    private void periodPair(Canvas c, String pl, int boxCol, boolean mo, float x0, float xr, float cy4, float cy5, int col4) {
        float bw0 = tw(pl, 14, bold) + 9;
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(boxCol);
        rf.set(x0, cy4 - 9, x0 + bw0, cy4 + 9);
        c.drawRoundRect(rf, 4, 4, gp);
        textB(c, pl, x0 + 4.5f, cy4, 20, 14, bold, 0xFF1A1A1A);
        String v4 = dataText(true, mo), v5 = dataText(false, mo);
        float sz = 20;
        while (sz > 13 && tw(v4, sz, bold) > xr - (x0 + bw0 + 4)) sz -= 0.5f;
        textB(c, v4, xr - tw(v4, sz, bold), cy4, 20, sz, bold, col4);
        sz = 20;
        while (sz > 13 && tw(v5, sz, bold) > xr - x0) sz -= 0.5f;
        textB(c, v5, xr - tw(v5, sz, bold), cy5, 20, sz, bold, teal());
    }

    /**
     * "4G ........ 76M": label at the left, value right-aligned. In a narrow column the two used to draw over each other
     * (2026-11-14): the value now shrinks to fit beside the label, and the label is dropped (the colour still says
     * which it is) when even the smallest value will not fit beside it.
     */
    private void graphRow(Canvas c, String lab, String v, float x0, float xr, float cy, int dim, int col) {
        float lw = lab.isEmpty() ? 0 : tw(lab, 15, semi) + 5, room = xr - x0;
        float sz = 22;
        while (sz > 14 && tw(v, sz, bold) > room - lw) sz -= 0.5f;
        if (tw(v, sz, bold) <= room - lw) {
            if (!lab.isEmpty()) textB(c, lab, x0, cy, 22, 15, semi, dim);
        } else {
            sz = 22;
            while (sz > 12 && tw(v, sz, bold) > room) sz -= 0.5f;
        }
        textB(c, v, xr - tw(v, sz, bold), cy, 22, sz, bold, col);
    }

    /** Tower map: you in the centre (north up), the towers your SIMs are on, and the neighbouring cells. */
    private void towerMap(Canvas c, float x0, float x1o, int dim) {
        final float top = y(54), bot = y(PRO_H) - 4;
        float x1 = Math.min(x1o, cornerRightX(top) - 4);
        if (x1 - x0 < 60) return;
        float bw = x1 - x0, bh = bot - top;
        // card
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(dk ? 0x66000000 : 0x1F000000);
        rf.set(x0, top, x1, bot);
        c.drawRoundRect(rf, 10, 10, gp);
        TowerMap.Snap sn = TowerMap.SNAP;
        float headCy = top + 11;
        textB(c, "CELLS", x0 + 6, headCy, 14, 13, semi, dim);
        if (sn == null || System.currentTimeMillis() - sn.at > 600_000L) {
            text(c, "no tower fix yet", x0 + 6, top + bh / 2, 14, semi, dim);
            return;
        }
        textB(c, String.valueOf(sn.cells), x0 + 6 + tw("CELLS ", 13, semi), headCy, 14, 16, bold, color);
        text(c, "N\u2191", x1 - 6 - tw("N\u2191", 13, semi), headCy, 13, semi, dim);
        // foot: serving PCI and handovers
        SimInfo d = null;
        for (SimInfo si : sims) if (si.data) d = si;
        String foot = (d != null && d.pci >= 0 ? "PCI " + d.pci : "") + (d != null ? (d.pci >= 0 ? " \u00b7 " : "") + d.hoToday + " HO" : "");
        float footCy = bot - 10;
        float fsz = 14;
        while (fsz > 10 && tw(foot, fsz, semi) > bw - 10) fsz -= 0.5f;
        if (!foot.isEmpty()) text(c, foot, x0 + 6, footCy, fsz, semi, dim);
        // plot area
        float pcx = x0 + bw / 2f, pcy = top + 24 + (footCy - 10 - (top + 24)) / 2f;
        float pr = Math.min(bw / 2f - 6, (footCy - 10 - (top + 24)) / 2f);
        if (pr < 12) return;
        // scale: smallest of 0.5/1/2/4 km that holds every point
        double maxM = 150;
        for (TowerMap.Pt t : sn.pts) maxM = Math.max(maxM, TowerMap.dist(sn.meLat, sn.meLon, t.lat, t.lon)[0]);
        double[] steps = {250, 500, 1000, 2000, 4000, 6000};
        double ring = steps[steps.length - 1];
        for (double st : steps) if (maxM * 1.08 <= st) { ring = st; break; }
        float kpx = (float) (pr / ring);
        // rings
        gp.setStyle(Paint.Style.STROKE);
        gp.setStrokeWidth(1f);
        gp.setColor(fade(color, 0.18f));
        for (double rr = ring; rr > 0; rr /= 2) { if (rr < 400) break; c.drawCircle(pcx, pcy, (float) (rr * kpx), gp); }
        String rl = ring >= 1000 ? (ring / 1000 == Math.floor(ring / 1000) ? (int) (ring / 1000) + "" : (ring / 1000) + "") + "km" : (int) ring + "m";
        text(c, rl, pcx + 3, pcy + pr - 4, 12, semi, dim);
        // cells
        for (int pass = 0; pass < 2; pass++) {
            for (TowerMap.Pt t : sn.pts) {
                if ((t.kind != 0) != (pass == 1)) continue;
                double[] db = TowerMap.dist(sn.meLat, sn.meLon, t.lat, t.lon);
                double a = Math.toRadians(db[1]);
                float px = pcx + (float) (Math.sin(a) * db[0] * kpx), py = pcy - (float) (Math.cos(a) * db[0] * kpx);
                if (t.rough) {   // position not known yet: parked at a fixed spot, dashed, until it is learned
                    a = Math.toRadians(t.kind == 1 ? 40 : 320);
                    px = pcx + (float) (Math.sin(a) * pr * 0.55); py = pcy - (float) (Math.cos(a) * pr * 0.55);
                }
                px = Math.max(x0 + 6, Math.min(x1 - 6, px));
                py = Math.max(top + 24, Math.min(footCy - 12, py));
                if (t.kind == 0) {
                    int col = t.rsrp >= -85 ? green() : t.rsrp >= -100 ? yellow() : t.rsrp >= -110 ? orange() : t.rsrp < 0 ? red() : dim;
                    gp.setStyle(Paint.Style.FILL);
                    gp.setColor(col);
                    c.drawCircle(px, py, 3.2f, gp);
                    if (t.star) {
                        text(c, "\u2605", px - 7, py - 7, 15, bold, 0xFFFFFFFF);
                    }
                } else {
                    int col = t.kind == 1 ? green() : 0xFF2FD1C1;
                    stk(1.6f, fade(col, 0.8f));
                    if (t.rough) gp.setPathEffect(new android.graphics.DashPathEffect(new float[]{4, 4}, 0));
                    c.drawLine(pcx, pcy, px, py, gp);
                    gp.setPathEffect(null);
                    gp.setStyle(Paint.Style.FILL);
                    gp.setColor(col);
                    Path tri = new Path();
                    tri.moveTo(px, py - 7); tri.lineTo(px - 6.5f, py + 5); tri.lineTo(px + 6.5f, py + 5); tri.close();
                    c.drawPath(tri, gp);
                    String lab = (t.kind == 1 ? (t.band == null ? "" : t.band) : "SIM2") + (t.rough ? "?" : "");
                    if (t.kind == 1 && d != null && d.dbm != Bands.NO_DBM) lab += " " + d.dbm;
                    float lw = tw(lab, 13, bold);
                    float lx = px + 8 + lw > x1 - 2 ? px - 8 - lw : px + 8;
                    gp.setStyle(Paint.Style.FILL);
                    gp.setColor(0xB0000000);
                    rf.set(lx - 2, py - 9, lx + lw + 2, py + 10);
                    c.drawRoundRect(rf, 4, 4, gp);
                    text(c, lab, lx, py + 1, 13, bold, col);
                }
            }
        }
        // you
        gp.setStyle(Paint.Style.STROKE);
        gp.setStrokeWidth(2.2f);
        gp.setColor(0xFFFFFFFF);
        c.drawCircle(pcx, pcy, 6, gp);
        gp.setStyle(Paint.Style.FILL);
        c.drawCircle(pcx, pcy, 2f, gp);
    }

    private Paint sepP;
    private Paint sepPaint() {
        if (sepP == null) { sepP = new Paint(Paint.ANTI_ALIAS_FLAG); sepP.setStrokeWidth(1f); }
        sepP.setColor(fade(color, 0.18f));
        return sepP;
    }

    private void stk(float w, int col) {
        gp.setStyle(Paint.Style.STROKE);
        gp.setStrokeWidth(w);
        gp.setStrokeCap(Paint.Cap.ROUND);
        gp.setStrokeJoin(Paint.Join.ROUND);
        gp.setColor(col);
    }

    private void graphPressure(Canvas c, float x0, float x1, int dim) {
        float[] ps = presSeries;
        if (ps == null || ps.length < 2) return;
        float cyL = y(50);
        final float x1o = x1;
        x1 = Math.min(x1o, cornerRightX(cyL - 12) - 8);       // the screen's round corner cuts the right end
        textB(c, "hPa 3h", x0, cyL, 12, 12, semi, dim);
        float last = ps[ps.length - 1], tr = last - ps[0];
        String ar = Math.abs(tr) < 0.5f ? "\u2192" : tr > 0 ? "\u2191" : "\u2193";
        int pc = tr <= -1.5f ? orange() : tr >= 1.5f ? green() : color;
        String val = Math.round(last) + ar;
        textB(c, val, x1 - tw(val, 15, bold), cyL, 12, 15, bold, pc);
        float mn = ps[0], mx = ps[0];
        for (float v : ps) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
        if (mx - mn < 2f) { float m = (mx + mn) / 2; mn = m - 1; mx = m + 1; }
        x1 = Math.min(x1o, cornerRightX(y(58)) - 8);
        float top = y(58), bot = y(80), px = 0, py = 0;
        Path pa = new Path();
        for (int i = 0; i < ps.length; i++) {
            px = x0 + 3 + (x1 - x0 - 6) * i / (ps.length - 1);
            py = bot - (bot - top) * (ps[i] - mn) / (mx - mn);
            if (i == 0) pa.moveTo(px, py); else pa.lineTo(px, py);
        }
        stk(3f, pc);
        c.drawPath(pa, gp);
        gp.setStyle(Paint.Style.FILL);
        c.drawCircle(px, py, 3.5f, gp);
    }

    private void graphUv(Canvas c, float x0, float x1, int dim) {
        float[] uv = uvSeries;
        if (uv == null || uv.length < 24) return;
        float pk = 0; int pkH = 12;
        for (int h = 0; h < 24; h++) if (uv[h] > pk) { pk = uv[h]; pkH = h; }
        float cyL = y(94);
        final float x1o = x1;
        x1 = Math.min(x1o, cornerRightX(cyL - 12) - 8);
        textB(c, "UV", x0, cyL, 12, 12, semi, dim);
        Calendar cal = Calendar.getInstance();
        float nowH = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f;
        float cur = Float.isNaN(wxUv) ? interp(uv, nowH) : wxUv;
        String val = Math.round(cur) + " / pk " + Math.round(pk);
        int vc = cur >= 8 ? red() : cur >= 6 ? orange() : cur >= 3 ? yellow() : green();
        textB(c, val, x1 - tw(val, 14, bold), cyL, 12, 14, bold, vc);
        if (pk < 0.5f) return;
        final float h0 = 5.5f, h1 = 19.5f;           // daylight window drawn
        x1 = Math.min(x1o, cornerRightX(y(104)) - 8);
        float top = y(104), bot = y(128), scale = Math.max(pk, 4f);
        float wx = x1 - x0 - 6;
        Path line = new Path(), fill = new Path();
        float fx = 0;
        boolean first = true;
        for (float h = h0; h <= h1 + 0.01f; h += 0.5f) {
            float px = x0 + 3 + wx * (h - h0) / (h1 - h0);
            float py = bot - (bot - top) * Math.min(1f, interp(uv, h) / scale);
            if (first) { line.moveTo(px, py); first = false; } else line.lineTo(px, py);
            if (h <= nowH) {
                if (fill.isEmpty()) fill.moveTo(px, bot);
                fill.lineTo(px, py);
                fx = px;
            }
        }
        stk(2.5f, fade(color, 0.35f));
        c.drawPath(line, gp);                          // whole day, faint
        if (nowH > h0 && fx > 0) {
            fill.lineTo(fx, bot);
            fill.close();
            gp.setStyle(Paint.Style.FILL);
            gp.setColor(fade(vc, 0.55f));
            c.drawPath(fill, gp);
        }
        float px = x0 + 3 + wx * (Math.max(h0, Math.min(h1, pkH + 0.5f)) - h0) / (h1 - h0);
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(color);
        c.drawCircle(px, bot - (bot - top) * Math.min(1f, pk / scale), 3.2f, gp);
        stk(1.5f, fade(color, 0.3f));
        c.drawLine(x0 + 3, bot + 1, x1 - 3, bot + 1, gp);
    }

    private static float interp(float[] a, float h) {
        int i = Math.max(0, Math.min(22, (int) Math.floor(h)));
        float f = Math.max(0f, Math.min(1f, h - i));
        return a[i] + (a[i + 1] - a[i]) * f;
    }

    private void graphSun(Canvas c, float x0, float x1, int dim) {
        double[] st = SunTimes.today(fix != null ? fix[0] : latLon[0], fix != null ? fix[1] : latLon[1]);
        if (st == null || Double.isNaN(st[0]) || Double.isNaN(st[1])) return;
        Calendar cal = Calendar.getInstance();
        double nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        double len = st[1] - st[0];
        boolean day = nowMin >= st[0] && nowMin <= st[1];
        float cyL = y(140);
        final float x1o = x1;
        x1 = Math.min(x1o, cornerRightX(cyL - 12) - 8);
        textB(c, "SUN", x0, cyL, 12, 12, semi, dim);
        int m = (int) Math.round(len);
        String dl = (m / 60) + "h" + String.format(Locale.US, "%02d", m % 60);
        textB(c, dl, x1 - tw(dl, 14, bold), cyL, 12, 14, bold, day ? yellow() : grey());
        float base = y(172), hgt = y(172) - y(150), wx = x1 - x0 - 8, ax = x0 + 4;
        float fr = day ? (float) ((nowMin - st[0]) / len) : nowMin < st[0] ? 0f : 1f;
        Path done = new Path(), rest = new Path();
        for (int i = 0; i <= 24; i++) {
            float f = i / 24f;
            float px = ax + wx * f, py = base - hgt * (float) Math.sin(Math.PI * f);
            Path tgt = f <= fr ? done : rest;
            if (f <= fr) { if (done.isEmpty()) done.moveTo(px, py); else done.lineTo(px, py); }
            if (f >= fr - 0.05f) { if (rest.isEmpty()) rest.moveTo(px, py); else rest.lineTo(px, py); }
        }
        stk(1.5f, fade(color, 0.3f));
        c.drawLine(x0, base, x1, base, gp);
        stk(2f, fade(color, 0.45f));
        gp.setPathEffect(new android.graphics.DashPathEffect(new float[]{4, 4}, 0));
        c.drawPath(rest, gp);
        gp.setPathEffect(null);
        if (day) {
            stk(3.5f, yellow());
            c.drawPath(done, gp);
            float px = ax + wx * fr, py = base - hgt * (float) Math.sin(Math.PI * fr);
            gp.setStyle(Paint.Style.FILL);
            gp.setColor(yellow());
            c.drawCircle(px, py, 5f, gp);
        }
        float ty = y(188);
        textB(c, SunTimes.hhmm(st[0]), x0, ty, 11, 11, semi, dim);
        String ss = SunTimes.hhmm(st[1]);
        textB(c, ss, x1 - tw(ss, 11, semi), ty, 11, 11, semi, dim);
    }

    /** Row 1: CPU / GPU / BAT / load with a gauge under each; row 2: watts, amps, speeds. Lowest priority drops first. */
    private void look2Device(Canvas c, float x0, float lim, float xTop) {
        int dim = fade(grey(), 0.78f);
        boolean thr = HeatWarn.throttled;
        final float y1 = y(40), y2 = y(76);
        List<Object[]> hs = new ArrayList<>();   // {label, value, colour, fraction, priority}
        if (!Float.isNaN(cpu)) hs.add(new Object[]{thr ? "C\u2193" : "C", Math.round(cpu) + "\u00b0", heatCol(cpu, 45, 55, 65), (cpu - 30f) / 60f, 10});
        if (!Float.isNaN(bat)) hs.add(new Object[]{"B", Math.round(bat) + "\u00b0", heatCol(bat, 38, 42, 45), (bat - 25f) / 25f, 9});
        if (!Float.isNaN(gpu)) hs.add(new Object[]{thr ? "G\u2193" : "G", Math.round(gpu) + "\u00b0", heatCol(gpu, 45, 55, 65), (gpu - 30f) / 60f, 6});
        if (cpuLoad >= 0 && Cfg.b(Cfg.SH_LOAD, Cfg.D_SH_LOAD)) hs.add(new Object[]{"LOAD", cpuLoad + "%", cpuLoad < 50 ? green() : cpuLoad < 80 ? yellow() : red(), cpuLoad / 100f, 4});
        final float gap = 8;   // C / G / B initials, tight spacing
        // phone battery level right after the BAT temperature (2026-11-14), when the row has the room for it
        final String bpS = battPct >= 0 ? (charging ? "\u26a1" : "") + battPct + "%" : null;
        final float bpW = bpS == null ? 0 : 6 + tw(bpS, 22, bold);
        battPctMoved = false;
        while (hs.size() > 1) {
            float tot = gap * (hs.size() - 1);
            for (Object[] h : hs) { tot += tw((String) h[0], 15, semi) + 2 + tw((String) h[1], 28, bold); if ("B".equals(h[0])) tot += bpW; }   // BAT always keeps room for its %
            if (xTop + tot <= lim) break;
            Object[] low = hs.get(0);
            for (Object[] h : hs) if ((Integer) h[4] < (Integer) low[4]) low = h;
            hs.remove(low);
        }
        // fixed order on screen: CPU, GPU, BAT, load
        java.util.Collections.sort(hs, (p, q) -> Integer.compare(order((String) p[0]), order((String) q[0])));
        boolean hasBat = false;
        float totW = gap * Math.max(0, hs.size() - 1);
        for (Object[] h : hs) { totW += tw((String) h[0], 15, semi) + 2 + tw((String) h[1], 28, bold); if ("B".equals(h[0])) hasBat = true; }
        final boolean bpShow = bpS != null && hasBat && xTop + totW + bpW <= lim;
        battPctMoved = bpShow;
        float x = xTop;
        for (Object[] h : hs) {
            String lab = (String) h[0], val = (String) h[1];
            int col = (Integer) h[2];
            float fr = Math.max(0.06f, Math.min(1f, (Float) h[3]));
            float lw = tw(lab, 15, semi) + 2, vw = tw(val, 28, bold), w = lw + vw;
            textB(c, lab, x, y1, 28, 15, semi, dim);
            textB(c, val, x + lw, y1, 28, 28, bold, col);
            if (bpShow && "B".equals(lab)) {
                int bcc = charging ? green() : battPct >= 50 ? green() : battPct >= 20 ? yellow() : battPct >= 10 ? orange() : red();
                textB(c, bpS, x + w + 6, y1, 28, 22, bold, bcc);
                w += bpW;
            }
            x += w + gap;
        }
        List<Item> pn = new ArrayList<>();
        if (!Float.isNaN(amps) && !Float.isNaN(watts)) {
            String sg = amps > 0 ? "+" : "\u2212";
            float aw = Math.abs(watts);
            String wv = sg + String.format(Locale.US, aw < 10 ? "%.1f" : "%.0f", aw);
            String av = sg + String.format(Locale.US, "%.2f", Math.abs(amps));
            pn.add(new Item(10, 26).t((charging ? "\u26a1" : "") + wv, 25, bold, powerColor).t(" W", 14, semi, dim));
            pn.add(new Item(5, 26).t(av, 20, bold, powerColor).t(" A", 14, semi, dim));
        }
        String[] fd = fmtSpeed(down), fu = fmtSpeed(up);
        if (Cfg.b(Cfg.SPEED_STOCK, Cfg.D_SPEED_STOCK)) pn.add(new Item(8, 26).t("\u2193", 25, bold, down < 1024 ? dim : green()).t(fd[0], 25, bold, color).t(" " + fd[1], 13, semi, dim));
        if (Cfg.b(Cfg.SPEED_STOCK, Cfg.D_SPEED_STOCK)) pn.add(new Item(7, 26).t("\u2191", 25, bold, up < 1024 ? dim : blue()).t(fu[0], 25, bold, color).t(" " + fu[1], 13, semi, dim));
        drawLine(c, pn, x0, y2, lim, 14);
    }

    private int speedCol(long bps) {
        return color;
    }

    private static String fmtBytes(long b) {
        double mb = b / 1048576.0;
        return mb < 1000 ? String.format(Locale.US, "%.0fM", mb) : String.format(Locale.US, "%.1fG", mb / 1024.0);
    }

    /** Last 90 s of download (green) and upload (blue) behind the speed numbers. */
    private void speedGraph(Canvas c, float x0, float x1, float cy) {
        if (hN < 3 || x1 - x0 < 40) return;
        long mx = 256 << 10;
        for (int i = 0; i < hN; i++) mx = Math.max(mx, Math.max(hDown[i], hUp[i]));
        float top = cy - 17, bot = cy + 17;
        for (int pass = 0; pass < 2; pass++) {
            long[] h = pass == 0 ? hUp : hDown;
            Path pa = new Path();
            pa.moveTo(x0, bot);
            for (int i = 0; i < hN; i++) {
                int idx = (hPos - hN + i + h.length * 2) % h.length;
                float px = x0 + (x1 - x0) * i / (hN - 1);
                float f = (float) Math.sqrt(Math.min(1.0, h[idx] / (double) mx));
                pa.lineTo(px, bot - (bot - top) * f);
            }
            pa.lineTo(x1, bot);
            pa.close();
            gp.setStyle(Paint.Style.FILL);
            gp.setColor(pass == 0 ? 0x484AA8FF : 0x5534C759);
            c.drawPath(pa, gp);
        }
    }

    private static int order(String l) {
        return l.startsWith("C") ? 0 : l.startsWith("G") ? 1 : l.equals("B") ? 2 : 3;
    }

    // ================================================================== chip look (colourful bar)
    //
    //  top line : ISS / CSS chips ........ notification + ringer chips
    //  time     : big clock + weekday, heat capsule, power + network capsules beside it
    //  line 3   : moon / sun / day-length capsule, battery health, alarm, GPS, system-icon slot
    //  weather  : [temp hi lo] [UV] [AQI] [tide | sea | coast]  /  [vis | cloud | rain | humidity | wind] + quake
    //  right    : one tall card per SIM (data SIM filled and outlined), then battery %, Q %, pressure chips

    boolean chips;
    private static final float CH_H = 216f, CPAD = 9f, CGAP = 5f;
    private static final int INK = 0xFF10202E, INK2 = 0xFF4A5E70;
    private static final int[] P_RED = {0xFFFFD1CC, 0xFF8C1A12}, P_AMB = {0xFFFFE3A8, 0xFF7A4B00},
            P_GRN = {0xFFC8ECD0, 0xFF0D5A22}, P_ORG = {0xFFFFD0A8, 0xFF8A3000}, P_YEL = {0xFFFFF0A6, 0xFF6B5200},
            P_BLU = {0xFFCFE0FF, 0xFF0B3F86}, P_DARK = {0xFF10202E, 0xFF9DFFB5}, P_SUN = {0xFFFFD9A0, 0xFF7A3D00},
            P_MOON = {0xFFDCD6FF, 0xFF3A2C8A}, P_NOTIF = {0xFFE6D3FF, 0xFF4A1D8F}, P_ISS = {0xFFBFE3FF, 0xFF06406E},
            P_CSS = {0xFFD9D6FF, 0xFF2E2A8A}, P_WX = {0xFFFFE0B8, 0xFF8A3D00}, P_TIDE = {0xFFBFEAF0, 0xFF065A68},
            P_SEA = {0xFFA8E3EE, 0xFF034E5C}, P_COAST = {0xFFF0E0BF, 0xFF6B4A10}, P_VIS = {0xFFE3E9EF, 0xFF2C3E50},
            P_CLOUD = {0xFFDFE5EC, 0xFF36485A}, P_RAIN = {0xFFBCD8FF, 0xFF0A3A82}, P_HUM = {0xFFB6ECE4, 0xFF0A5A50},
            P_WIND = {0xFFD2F0C4, 0xFF2A5A10}, P_GREY = {0xFFE3E9EF, 0xFF4A5E70}, P_VIO = {0xFFE1D4FF, 0xFF4A1D8F};

    private static final class Seg {
        final String t;
        final int bg, fg;
        Seg(String t, int bg, int fg) { this.t = t; this.bg = bg; this.fg = fg; }
    }

    private static Seg sg(String t, int[] p) { return new Seg(t, p[0], p[1]); }
    private static Seg sg(String t, int bg, int fg) { return new Seg(t, bg, fg); }
    private static Seg[] cp(Seg... s) { return s; }
    private static int[] heatP(float v, float warm, float hot) { return v >= hot ? P_RED : v >= warm ? P_AMB : P_GRN; }
    private static int[] lvlP(int pct) { return pct >= 70 ? P_GRN : pct >= 45 ? P_AMB : pct >= 25 ? P_ORG : P_RED; }

    private float capW(float size, Seg[] s) {
        float w = 0;
        for (Seg x : s) w += tw(x.t, size, bold) + 2 * CPAD;
        return w;
    }

    /** One capsule: segments side by side in one rounded shape, thin light dividers between them. */
    private float cap(Canvas c, float x, float cy, float h, float size, Seg[] s) {
        float r = h / 2f, px = x;
        for (int i = 0; i < s.length; i++) {
            float sw = tw(s[i].t, size, bold) + 2 * CPAD;
            boolean f = i == 0, l = i == s.length - 1;
            path.reset();
            rf.set(px, cy - r, px + sw, cy + r);
            path.addRoundRect(rf, new float[]{f ? r : 0, f ? r : 0, l ? r : 0, l ? r : 0, l ? r : 0, l ? r : 0, f ? r : 0, f ? r : 0},
                    Path.Direction.CW);
            gp.setStyle(Paint.Style.FILL);
            gp.setColor(s[i].bg);
            c.drawPath(path, gp);
            if (i > 0) {
                gp.setColor(0xB3FFFFFF);
                rf.set(px, cy - r, px + 1, cy + r);
                c.drawRect(rf, gp);
            }
            text(c, s[i].t, px + CPAD, cy, size, bold, s[i].fg);
            px += sw;
        }
        return px;
    }

    private float rowW(List<Seg[]> row, float size) {
        float t = 0;
        for (Seg[] s : row) t += capW(size, s) + CGAP;
        return Math.max(0, t - CGAP);
    }

    /** Draws capsules left to right; smaller text first, then the last ones drop, when the row is too long. Returns the end x. */
    private float capsRow(Canvas c, float x, float cy, float h, float size, float limit, List<Seg[]> items) {
        List<Seg[]> use = new ArrayList<>(items);
        float sz = size, min = size * 0.84f;
        while (!use.isEmpty() && x + rowW(use, sz) > limit) {
            if (sz - 0.5f >= min) sz -= 0.5f;
            else if (use.size() > 1) { use.remove(use.size() - 1); sz = size; }
            else break;
        }
        float e = x;
        for (int i = 0; i < use.size(); i++) {
            e = cap(c, e, cy, h, sz, use.get(i));
            if (i < use.size() - 1) e += CGAP;
        }
        return e;
    }

    /** First candidate row that fits at full size wins; otherwise the last one, shrunk / trimmed. */
    private float capsBest(Canvas c, float x, float cy, float h, float size, float limit, List<List<Seg[]>> cands) {
        List<Seg[]> pick = cands.get(cands.size() - 1);
        for (List<Seg[]> r : cands) if (x + rowW(r, size) <= limit) { pick = r; break; }
        return capsRow(c, x, cy, h, size, limit, pick);
    }

    private void drawChips(Canvas c, int w, int h) {
        s = Math.min(w / DESIGN_W, h / CH_H);
        W = w / s;
        vs = 1f;
        oy = Math.max(0f, (h / s - CH_H) / 2f);
        c.save();
        try {
            c.scale(s, s);
            float cx = Float.isNaN(camX) ? W / 2f : camX / s;
            float cr = Float.isNaN(camR) ? 24f : Math.max(14f, Math.min(34f, camR / s));
            float camBot = (Float.isNaN(camY) ? 50f : camY / s) + cr + 4f;
            int n = Math.min(2, sims.size());
            float colX = W - 124f, card2 = W - 368f, cardW = 236f;
            float cardsLeft = n >= 2 ? card2 - 248f : n == 1 ? card2 : colX;
            float lim = cardsLeft - 8f;                         // rows below the camera run up to the cards
            float limCam = Math.min(lim, cx - cr - 10f);       // rows beside the camera stop before it
            try { chipsLeft(c, lim, limCam, camBot); } catch (Throwable ignored) { }
            for (int i = 0; i < n; i++) {
                float x = n >= 2 ? card2 - 248f + i * 248f : card2;
                try { chipCard(c, sims.get(i), i, x, oy + 10f, cardW); } catch (Throwable ignored) { }
            }
            try { chipsRight(c, colX); } catch (Throwable ignored) { }
        } finally {
            c.restore();
        }
    }

    private static String hhmm(long t) { return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(t)); }

    private void chipsLeft(Canvas c, float lim, float limCam, float camBot) {
        long now = System.currentTimeMillis();
        float h1 = 26, hr = 28;
        // ---- top line: satellites, notifications
        float yS = oy + 19;
        float sLim = yS - 14 < camBot ? limCam : lim;
        // notification chips sit at the right end of the line
        List<Seg[]> nr = new ArrayList<>();
        String nTxt = notifAll < 0 ? "?" : String.valueOf(notifAll);
        nr.add(cp(sg("🔔 " + nTxt + (notifSilent > 0 ? " (" + notifSilent + ")" : ""), notifAll == 0 ? P_GREY : P_NOTIF)));
        if (ringer != 0) nr.add(cp(sg(ringer == 1 ? "📳" : ringer == 2 ? "🔕" : "⛔", ringer == 1 ? P_AMB : P_RED)));
        float nW = rowW(nr, 16.5f);
        float x0 = Math.max(14, cornerX(yS - 16) + 4);
        List<Seg[]> sat = new ArrayList<>();
        String st = satText == null ? "" : satText;
        if (!st.isEmpty()) {
            if (satIssS != null && !satIssS.isEmpty()) {
                sat.add(cp(sg("🛰 " + satIssS, satLive ? P_ISS : P_GREY)));
                String nm2 = (satNameS == null ? "" : satNameS) + (satCityS == null ? "" : satCityS);
                if (!nm2.isEmpty()) sat.add(cp(sg(nm2, satLive ? P_CSS : P_GREY)));
            } else sat.add(cp(sg("🛰 " + st, satLive ? P_ISS : P_GREY)));
        }
        capsRow(c, x0, yS, h1, 16.5f, sLim - nW - 10, sat);
        capsRow(c, sLim - nW, yS, h1, 16.5f, sLim + 1, nr);

        // ---- clock + weekday, heat / power / network block beside it
        float yT = oy + 66;
        Date d = new Date(now);
        float tx = Math.max(14, cornerX(yT - 32) + 4);
        // the chips carry their own colours, but the clock sits on the bar itself: white with a soft dark edge reads on any wallpaper
        tp.setShadowLayer(5f, 0f, 1.5f, 0xE6000000);
        float e = text(c, t24.format(d), tx, yT, 62, bold, 0xFFFFFFFF);
        e = textB(c, new SimpleDateFormat("EEE", Locale.getDefault()).format(d), e + 8, yT, 62, 20, semi, 0xFFE6E6E6);
        tp.clearShadowLayer();
        float bx = Math.max(e + 14, 268);

        boolean thr = HeatWarn.throttled;
        List<Seg[]> heat = new ArrayList<>();
        List<Seg> hs = new ArrayList<>();
        if (!Float.isNaN(cpu)) hs.add(sg((thr ? "CPU↓ " : "CPU ") + Math.round(cpu) + "°", heatP(cpu, 45, 60)));
        if (!Float.isNaN(gpu)) hs.add(sg((thr ? "GPU↓ " : "GPU ") + Math.round(gpu) + "°", heatP(gpu, 45, 60)));
        if (!Float.isNaN(bat)) hs.add(sg("BAT " + Math.round(bat) + "°", heatP(bat, 38, 42)));
        if (cpuLoad >= 0) hs.add(sg("C " + cpuLoad + "%", cpuLoad < 50 ? P_GRN : cpuLoad < 80 ? P_AMB : P_RED));
        if (!hs.isEmpty()) heat.add(hs.toArray(new Seg[0]));
        float yH = oy + 50;
        capsRow(c, bx, yH, hr, 16f, yH - 14 < camBot ? limCam : lim, heat);

        List<Seg[]> pn = new ArrayList<>();
        if (!Float.isNaN(amps) && !Float.isNaN(watts)) {
            String sgn = amps > 0 ? "+" : "−";
            float aw = Math.abs(watts);
            pn.add(cp(sg((charging ? "⚡" : "") + sgn + String.format(Locale.US, "%.2f", Math.abs(amps)) + " A", P_YEL),
                    sg(sgn + String.format(Locale.US, aw < 10 ? "%.1f" : "%.0f", aw) + " W", P_YEL)));
        }
        String[] fd = fmtSpeed(down), fu = fmtSpeed(up);
        pn.add(cp(sg("↓ " + fd[0] + " " + fd[1], down < 1024 ? P_GREY : P_GRN), sg("↑ " + fu[0] + " " + fu[1], up < 1024 ? P_GREY : P_BLU)));
        capsRow(c, bx, oy + 84, hr, 16f, lim, pn);

        // ---- moon / sun / day length, battery health, alarm, GPS, system icons
        float y3 = oy + 118;
        List<Seg> sm = new ArrayList<>();
        long[] mev = moonEvent(now);
        if (mev != null) sm.add(sg("◐ " + (mev[1] == 1 ? "↑" : "↓") + hhmm(mev[0]), P_MOON));
        String[] sun = nextSun(now);
        if (sun != null) sm.add(sg(("r".equals(sun[1]) ? "🌄 " : "🌅 ") + sun[0], P_SUN));
        String dl = dayNightLen();
        if (dl != null) sm.add(sg(dl, dl.startsWith("☀") ? P_YEL : P_MOON));
        List<Seg[]> r3 = new ArrayList<>();
        if (!sm.isEmpty()) r3.add(sm.toArray(new Seg[0]));
        r3.add(cp(sg(healthText(), P_DARK)));
        if (alarmAt > 0) {
            String fmt = alarmAt - now < 20 * 3600_000L ? "HH:mm" : "EEE HH:mm";
            r3.add(cp(sg("⏰ " + new SimpleDateFormat(fmt, Locale.US).format(new Date(alarmAt)), P_AMB)));
        }
        if (sats > 0) r3.add(cp(sg("📍 " + sats, sats >= 8 ? P_GRN : P_AMB)));
        float e3 = capsRow(c, 14, y3, hr, 16f, lim - 50, r3);
        proSlot(c, e3 + 10, lim, y3);

        // ---- weather row 1: temperature, UV, AQI, tide
        float y4 = oy + 152;
        if (wxTemp != null) {
            List<Seg[]> base = new ArrayList<>();
            String em = wxCode >= 0 ? weatherEmoji(wxCode) : null;
            List<Seg> wsg = new ArrayList<>();
            wsg.add(sg((em != null ? em + " " : "") + wxTemp + "°", P_WX));
            if (!Float.isNaN(wxMax) && !Float.isNaN(wxMin)) {
                wsg.add(sg("↑" + Math.round(wxMax), P_WX[0], 0xFFC0560A));
                wsg.add(sg("↓" + Math.round(wxMin), P_WX[0], 0xFF0B5FB3));
            }
            base.add(wsg.toArray(new Seg[0]));
            if (!Float.isNaN(wxUv)) {
                int uv = Math.round(wxUv);
                base.add(cp(sg("UV " + uv, uv == 0 ? P_GREY : uv < 3 ? P_GRN : uv < 6 ? P_YEL : uv < 8 ? P_ORG : P_RED)));
            }
            if (wxAqi >= 0) {
                int ac = wxAqi <= 50 ? 0xFF2E9E4F : wxAqi <= 100 ? 0xFFC9A400 : wxAqi <= 150 ? 0xFFE07B1A
                        : wxAqi <= 200 ? 0xFFD93A2B : wxAqi <= 300 ? 0xFF8E3FB5 : 0xFF7A1F2B;
                base.add(cp(sg("AQI " + wxAqi, ac, 0xFFFFFFFF)));
            }
            List<List<Seg[]>> cands = new ArrayList<>();
            Tide.Now td = Cfg.b(Cfg.TIDE, Cfg.D_TIDE) ? Tide.now(now) : null;
            float sea = Float.NaN;
            String coast = null;
            try {
                sea = Tide.seaTemp(now);
                if (!Float.isNaN(sea)) {
                    double sk = Tide.seaKm(fix != null ? fix[0] : latLon[0], fix != null ? fix[1] : latLon[1]);
                    coast = sk < 1 ? "~" + Math.round(sk * 10) * 100 + "m" : "~" + Math.round(sk) + "km";
                }
            } catch (Throwable ignored) { }
            if (td != null) {
                String tm = hhmm(td.nextAt), ar = td.rising ? "↑" : "↓", hl = td.nextHigh ? "H" : "L";
                long mins = (td.nextAt - now) / 60000L;
                String main = "≈" + ar + " " + hl + " " + tm + (mins >= 0 && mins <= 60 ? " in " + mins + "m" : "");
                for (int lv = 0; lv < 4; lv++) {
                    List<Seg> ts = new ArrayList<>();
                    boolean pl = lv == 0 && td.place != null;
                    ts.add(sg(main + (pl ? " · " + td.place : ""), P_TIDE));
                    if (!Float.isNaN(sea) && lv <= 2) ts.add(sg("🌊 " + Math.round(sea) + "°", P_SEA));
                    if (coast != null && lv <= 1) ts.add(sg("🏖 " + coast, P_COAST));
                    List<Seg[]> row = new ArrayList<>(base);
                    row.add(ts.toArray(new Seg[0]));
                    cands.add(row);
                }
            } else {
                List<Seg> ts = new ArrayList<>();
                if (!Float.isNaN(sea)) ts.add(sg("🌊 " + Math.round(sea) + "°", P_SEA));
                if (coast != null) ts.add(sg("🏖 " + coast, P_COAST));
                List<Seg[]> row = new ArrayList<>(base);
                if (!ts.isEmpty()) row.add(ts.toArray(new Seg[0]));
                cands.add(row);
            }
            capsBest(c, 14, y4, 30, 16.5f, lim, cands);
        }

        // ---- weather row 2: visibility, cloud, rain, humidity, wind; then the quake / hazard chip
        float y5 = oy + 188;
        List<Seg> w2 = new ArrayList<>();
        if (!Float.isNaN(wxVis)) {
            String v = wxVis >= 10000 ? String.valueOf(Math.round(wxVis / 1000f)) : wxVis >= 1000
                    ? String.format(Locale.US, "%.1f", wxVis / 1000f) : Math.round(wxVis) + "m";
            w2.add(sg("👁 " + v, P_VIS));
        }
        if (wxCloud >= 0) w2.add(sg("☁ " + wxCloud + "%", P_CLOUD));
        if (wxRain >= 0) w2.add(sg("☂ " + wxRain + "%", P_RAIN));
        if (wxHum >= 0) w2.add(sg("💧 " + wxHum, P_HUM));
        if (!Float.isNaN(wxWind)) w2.add(sg((wxArrow == null ? "" : wxArrow + " ") + Math.round(wxWind), P_WIND));
        List<Seg[]> base2 = new ArrayList<>();
        if (!w2.isEmpty()) base2.add(w2.toArray(new Seg[0]));
        List<List<Seg[]>> cands2 = new ArrayList<>();
        for (Seg[] qv : quakeChips(now)) {
            List<Seg[]> row = new ArrayList<>(base2);
            row.add(qv);
            cands2.add(row);
        }
        if (cands2.isEmpty()) cands2.add(base2);
        cands2.add(base2);
        capsBest(c, 14, y5, hr, 15.5f, lim, cands2);
    }

    /** Quake (or hazard alert) chips, richest first; magnetic storm chip appended to each when there is one. */
    private List<Seg[]> quakeChips(long now) {
        List<Seg[]> out = new ArrayList<>();
        List<Seg> v = new ArrayList<>();
        try {
            Hazards.Ev hv = Cfg.b(Cfg.HAZARDS, Cfg.D_HAZARDS) && Hazards.disaster(now) ? Hazards.pick(now) : null;
            int[] p;
            if (hv != null) {
                p = hv.lvl >= 2 ? P_RED : hv.lvl == 1 ? P_ORG : hv.lvl == 0 ? P_YEL : P_GRN;
                String dot = (hv.india ? "🇮🇳" : "") + Hazards.icon(hv.type);
                String nm = hv.name == null ? "" : hv.name, pl = hv.place;
                String dk = hv.km >= 0 ? hv.km + "km " + hv.dir : "";
                String sea2 = Hazards.seaOf(hv);
                if (sea2 != null && !dk.isEmpty()) v.add(sg(dot + " " + (nm.isEmpty() ? "" : nm + " · ") + sea2 + " " + dk, p));
                if (!nm.isEmpty() && pl != null && !dk.isEmpty()) v.add(sg(dot + " " + nm + " · " + pl + " " + dk, p));
                if (!nm.isEmpty() && !dk.isEmpty()) v.add(sg(dot + " " + nm + " " + dk, p));
                if (!nm.isEmpty()) v.add(sg(dot + " " + nm, p));
                v.add(sg(dot, p));
            } else if (!Float.isNaN(quakeMag) && (quakeKm < 0 || quakeKm <= 2500 || quakeMag >= 6.0f)) {
                p = quakeMag < 4 ? P_YEL : quakeMag < 5.5 ? P_ORG : P_RED;
                String mg = "● M" + String.format(Locale.US, "%.1f", quakeMag);
                String cc = quakeCc != null && !quakeCc.isEmpty() ? quakeCc : null;
                String full = cc != null ? countryName(cc) : null;
                if (full != null && quakeKm >= 0) v.add(sg(mg + " " + full + " " + quakeKm + "km", p));
                if (full != null) v.add(sg(mg + " " + full, p));
                if (quakeKm >= 0) v.add(sg(mg + " " + quakeKm + "km", p));
                v.add(sg(mg, p));
            }
            int g = Hazards.storm(now);
            if (g > 0) {
                int[] kp = g == 1 ? P_YEL : g == 2 ? P_ORG : P_RED;
                Seg storm = sg("🧲 Kp" + (int) Math.floor(Hazards.kp) + " G" + g, kp);
                if (v.isEmpty()) out.add(cp(storm));
                for (Seg a : v) out.add(cp(a, storm));
                for (Seg a : v) out.add(cp(a));
                return out;
            }
        } catch (Throwable ignored) { }
        for (Seg a : v) out.add(cp(a));
        return out;
    }

    private void chipsRight(Canvas c, float colX) {
        float rx = W - 10f, hh = 26f;
        List<Seg[]> col = new ArrayList<>();
        if (battPct >= 0) {
            int[] p = charging ? P_GRN : battPct >= 50 ? P_GRN : battPct >= 20 ? P_AMB : battPct >= 10 ? P_ORG : P_RED;
            col.add(cp(sg((charging ? "⚡" : "") + battPct + "%", p)));
        }
        SimInfo d = null;
        for (SimInfo si : sims) if (si.data) d = si;
        int q = d == null ? -1 : quality(d);
        if (q >= 0) col.add(cp(sg("Q " + q + "%", lvlP(q))));
        if (!Float.isNaN(wxPres)) {
            String ar = Float.isNaN(wxPresTrend) || Math.abs(wxPresTrend) < 0.5f ? "→" : wxPresTrend > 0 ? "↑" : "↓";
            int[] p = Float.isNaN(wxPresTrend) ? P_GREY : wxPresTrend <= -1.5f ? P_ORG : wxPresTrend >= 1.5f ? P_GRN : P_GREY;
            col.add(cp(sg(Math.round(wxPres) + ar, p)));
        } else col.add(cp(sg("-- hPa", P_GREY)));   // no pressure reading yet
        // chips stay inside the screen's rounded top-right corner and shrink a little rather than get cut
        for (int i = 0; i < col.size(); i++) {
            float cy = oy + 30 + i * 33;
            float edge = Math.min(W - 8f, cornerRightX(cy - hh / 2f) - 4f);
            float sz = 15.5f;
            while (sz > 11f && capW(sz, col.get(i)) > edge - colX) sz -= 0.5f;
            cap(c, edge - capW(sz, col.get(i)), cy, hh, sz, col.get(i));
        }
    }

    private static int lvlCol(int level) {
        return level >= 4 ? 0xFF1E8E3E : level == 3 ? 0xFF6B9E1F : level == 2 ? 0xFFB5760A : level == 1 ? 0xFFD9650B : 0xFFC62828;
    }

    /** One SIM as a tall card: link, signal, quality gauge, band, data today and the tower name at the bottom. */
    private void chipCard(Canvas c, SimInfo si, int idx, float x, float t, float cw) {
        final boolean live = si.inService;
        float ch = CH_H - 20f;
        rf.set(x, t, x + cw, t + ch);
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(si.data ? 0xEBFFFFFF : 0x99FFFFFF);
        c.drawRoundRect(rf, 26, 26, gp);
        if (si.data) {
            gp.setStyle(Paint.Style.STROKE);
            gp.setStrokeWidth(2.5f);
            gp.setColor(INK);
            rf.set(x + 1.25f, t + 1.25f, x + cw - 1.25f, t + ch - 1.25f);
            c.drawRoundRect(rf, 25, 25, gp);
            float tw0 = tw("DATA", 11.5f, bold) + 18;
            gp.setStyle(Paint.Style.FILL);
            rf.set(x + cw - 20 - tw0, t - 8, x + cw - 20, t + 8);
            c.drawRoundRect(rf, 8, 8, gp);
            text(c, "DATA", x + cw - 20 - tw0 + 9, t, 11.5f, bold, 0xFFFFFFFF);
        }
        float ix = x + 13, iw = cw - 26;
        // header: solar time + phase on the first card, moon phase on the second; voice / mode / ping on the right
        String left;
        try {
            if (idx == 0) {
                String[] sol = solarNow();
                left = sol == null ? "" : sol[0] + " " + sol[1];
            } else {
                Astro.Moon mo = Astro.moon(System.currentTimeMillis(), fix != null ? fix[0] : latLon[0], fix != null ? fix[1] : latLon[1]);
                left = phaseName((int) Math.round(mo.illum * 100), mo.waxing);
            }
        } catch (Throwable e) { left = ""; }
        StringBuilder rt = new StringBuilder();
        if (live && si.voice != null) rt.append(voText(si.voice));
        String mode = modeText(si), pingT = pingText(si);
        if (mode != null) rt.append(rt.length() > 0 ? " · " : "").append(mode);
        if (pingT != null) rt.append(rt.length() > 0 ? " · " : "").append(pingT);
        String right = rt.toString();
        float hsz = 13f;
        while (hsz > 10f && tw(left, hsz, semi) + 10 + tw(right, hsz, bold) > iw) hsz -= 0.5f;
        while (left.length() > 4 && tw(left, hsz, semi) + 10 + tw(right, hsz, bold) > iw) left = left.substring(0, left.length() - 2);
        float hy = t + 16;
        text(c, left, ix, hy, hsz, semi, INK2);
        text(c, right, ix + iw - tw(right, hsz, bold), hy, hsz, bold, INK2);

        // network + bars
        float ny = t + 40;
        String rat = live && si.rat != null && !si.rat.isEmpty() ? si.rat : "—";
        boolean plus = rat.endsWith("+") && rat.length() > 1;
        float e = ix;
        if (si.data) {
            gp.setStyle(Paint.Style.FILL);
            path.reset();
            path.moveTo(e, ny - 2); path.lineTo(e + 10, ny - 2); path.lineTo(e + 5, ny - 10); path.close();
            gp.setColor(dataOut ? 0xFF0B5FB3 : 0x2610202E);
            c.drawPath(path, gp);
            path.reset();
            path.moveTo(e, ny + 2); path.lineTo(e + 10, ny + 2); path.lineTo(e + 5, ny + 10); path.close();
            gp.setColor(dataIn ? 0xFF1E8E3E : 0x2610202E);
            c.drawPath(path, gp);
            e += 15;
        }
        e = text(c, plus ? rat.substring(0, rat.length() - 1) : rat, e, ny, 32, bold, live ? INK : INK2);
        if (plus) e = textB(c, "+", e + 1, ny - 8, 32, 20, bold, INK);
        if (si.eco) e = textB(c, "E", e + 6, ny, 32, 20, bold, 0xFF1E8E3E);
        e += 9;
        boolean has = live && si.dbm != Bands.NO_DBM;
        int lvl = !has ? 0 : si.dbm >= -90 ? 4 : si.dbm >= -100 ? 3 : si.dbm >= -110 ? 2 : si.dbm >= -120 ? 1 : 0;
        gp.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) {
            float bh = 22f * (i + 1) / 4f, l = e + i * 8f;
            gp.setColor(i < lvl ? lvlCol(lvl) : 0x2E10202E);
            rf.set(l, ny + 11 - bh, l + 5.5f, ny + 11);
            c.drawRoundRect(rf, 1.5f, 1.5f, gp);
        }

        // signal chips
        float sy = t + 72;
        if (has) {
            int[] p = si.dbm >= -95 ? P_GRN : si.dbm >= -105 ? P_AMB : si.dbm >= -115 ? P_ORG : P_RED;
            float ex = cap(c, ix, sy, 28, 16.5f, cp(sg(minus(String.valueOf(si.dbm)), p)));
            String trd = si.trend;
            boolean upT = trd != null && trd.startsWith("+");
            ex = text(c, trd == null ? "→" : upT ? "↑" : "↓", ex + 6, sy, 20, bold, trd == null ? INK2 : upT ? 0xFF1E8E3E : 0xFFC62828) + 6;
            if (live && !Float.isNaN(si.snr)) {
                int[] sp = si.snr >= 13 ? P_GRN : si.snr >= 5 ? P_AMB : si.snr >= 0 ? P_ORG : P_RED;
                ex = cap(c, ex, sy, 28, 16.5f, cp(sg(minus(String.valueOf(Math.round(si.snr))) + " " + (si.snr >= 13 ? "good" : si.snr >= 5 ? "ok" : si.snr >= 0 ? "poor" : "bad"), sp)));
            }
            if (locked(si)) lockIcon(c, ex + 7, sy, 0xFF0A7F94);
        }
        // quality gauge
        int q = quality(si);
        float gy = t + 94;
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(0x2E10202E);
        rf.set(ix, gy - 3, ix + iw, gy + 3);
        c.drawRoundRect(rf, 3, 3, gp);
        if (q > 0) {
            gp.setShader(new android.graphics.LinearGradient(ix, 0, ix + iw, 0, 0xFFE0A21B, 0xFF3BB35A, android.graphics.Shader.TileMode.CLAMP));
            rf.set(ix, gy - 3, ix + iw * q / 100f, gy + 3);
            c.drawRoundRect(rf, 3, 3, gp);
            gp.setShader(null);
        }
        // band, distance, cells heard
        float by = t + 115;
        if (live) {
            String band = si.band != null ? si.band : "?";
            float ex = cap(c, ix, by, 24, 15.5f, cp(sg(band, band.startsWith("n") ? P_VIO : P_BLU))) + 8;
            if (si.dist != null) ex = text(c, si.dist, ex, by, 15f, semi, INK) + 8;
            if (si.cells > 0) {
                dish(c, ex, by, 0xFF0A7F94);
                text(c, String.valueOf(si.cells), ex + DISH_W + 3, by, 15f, bold, 0xFF0A7F94);
            }
        }
        // data used today (data SIM)
        float dy = t + 140;
        if (si.data && live) {
            boolean mo = monthly();
            float ex = cap(c, ix, dy, 20, 13.5f, cp(sg(mo ? "M" : "D", mo ? 0xFFB388FF : 0xFF4DD0E1, INK)));
            float f4 = (float) used4g / Math.max(1, limit4g);
            int c4 = mo || f4 < 0.5f ? 0xFF1E8E3E : f4 < 0.8f ? 0xFFB5760A : f4 < 1f ? 0xFFD9650B : 0xFFC62828;
            ex = text(c, "4G", ex + 6, dy, 14f, semi, INK2) + 4;
            ex = text(c, dataTodayText(si, true), ex, dy, 17f, bold, c4);
            ex = text(c, " · 5G", ex, dy, 14f, semi, INK2) + 4;
            text(c, dataTodayText(si, false), ex, dy, 17f, bold, 0xFF0A7F94);
        }
        chipTower(c, si, ix, iw, t);
    }

    /** Tower name big at the card's bottom; long names wrap to a second line; roaming tag has its own slot at the right. */
    private void chipTower(Canvas c, SimInfo si, float bx, float bw, float t) {
        boolean weak = si.inService && si.dbm != Bands.NO_DBM && si.dbm <= -115;
        boolean hasArea = si.area != null && !si.area.isEmpty(), hasNet = si.netName != null && !si.netName.isEmpty();
        String nm;
        if (!si.inService) nm = "No coverage";
        else if (!hasArea && !hasNet) {
            if (!weak) return;
            nm = "Weak signal";
        } else {
            nm = hasNet && hasArea ? si.netName + " · " + si.area : hasNet ? si.netName : si.area;
            if (weak) nm = "Weak signal · " + nm;
        }
        int ink = si.data ? INK : 0xFF2A3B4A;
        List<String> ch = new ArrayList<>();
        if (si.inService) {
            if (si.onCell != null) ch.add(si.onCell);
            if (si.hoToday > 0) ch.add("×" + si.hoToday);
            if (si.bearing != null) ch.add(si.bearing);
        }
        float slot = si.roaming ? tw(roamText(si), 13, bold) + 14 + 6 : 0;
        float y1 = t + 160, y2 = t + 183, yOne = t + 175;
        float sz = 22f;
        // one line when the name and all the chips fit
        int kAll = ch.size();
        if (tw(nm, sz, bold) + chipsW(ch, kAll) <= bw - slot) {
            drawTowerLine(c, nm, ch, kAll, bx, yOne, sz, ink);
            roamTag(c, si, bx + bw, yOne);
            return;
        }
        String[] two = split2(nm);
        if (two != null) {
            float s2 = 22f;
            while (s2 > 14f && (tw(two[0], s2, bold) > bw - slot || tw(two[1], s2, bold) > bw)) s2 -= 0.5f;
            if (tw(two[0], s2, bold) <= bw - slot && tw(two[1], s2, bold) <= bw) {
                text(c, two[0], bx, y1, s2, bold, ink);
                int k = kAll;
                while (k > 0 && tw(two[1], s2, bold) + chipsW(ch, k) > bw) k--;
                drawTowerLine(c, two[1], ch, k, bx, y2, s2, ink);
                roamTag(c, si, bx + bw, y1);
                return;
            }
        }
        // no good split: one line, smaller, chips dropped, "…" only as a last resort
        float s1 = 22f;
        while (s1 > 14f && tw(nm, s1, bold) > bw - slot) s1 -= 0.5f;
        String tx = nm;
        while (tx.length() > 3 && tw(tx, s1, bold) > bw - slot) tx = tx.substring(0, tx.length() - 2) + "…";
        int k = ch.size();
        while (k > 0 && tw(tx, s1, bold) + chipsW(ch, k) > bw - slot) k--;
        drawTowerLine(c, tx, ch, k, bx, yOne, s1, ink);
        roamTag(c, si, bx + bw, yOne);
    }

    private float chipsW(List<String> ch, int k) {
        float w = 0;
        for (int i = 0; i < k; i++) w += tw(ch.get(i), 13, bold) + 14 + 4;
        return k > 0 ? w + 3 : 0;
    }

    private void drawTowerLine(Canvas c, String nm, List<String> ch, int k, float x, float cy, float sz, int ink) {
        float e = text(c, nm, x, cy, sz, bold, ink) + 7;
        for (int i = 0; i < k; i++) {
            e = cap(c, e, cy + 1, 20, 13f, cp(sg(ch.get(i), 0x1A10202E, 0xFF36485A))) + 4;
        }
    }

    private void roamTag(Canvas c, SimInfo si, float rightX, float cy) {
        if (!si.roaming) return;
        String rt = roamText(si);
        float w = tw(rt, 13, bold) + 14;
        cap(c, rightX - w, cy, 20, 13f, cp(sg(rt, 0xFFFFD21F, 0xFF2A2000)));
    }
}
