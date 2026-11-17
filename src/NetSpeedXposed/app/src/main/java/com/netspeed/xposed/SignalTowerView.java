package com.netspeed.xposed;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * One icon for both SIMs: a lattice tower with antenna panels, SIM 1's signal as waves on the
 * left and SIM 2's on the right (4 arcs = 4 bars; lit = signal, faint = missing). Next to
 * each side: the network that SIM is on (4G+ / LTE / 5G) with a small SIM number, its voice
 * type (VoLTE / VoNR / VoWiFi) and, on the SIM carrying mobile data, ↓↑ arrows that light up
 * with traffic plus the level as "3/4".
 */
final class SignalTowerView extends View {

    /** What one side shows. */
    static final class Side {
        boolean present;     // a SIM in this slot
        boolean inService;
        int level = -1;      // 0..4
        String net;          // "4G+", "LTE", "5G" ...
        String band;         // "n78", "B1" ...
        int cells = -1;      // towers heard (-1 = unknown)
        String voice;        // "VoLTE" ...
        boolean data;        // carries mobile data
        boolean in, out;     // data flowing
        boolean inCall;      // a call on this SIM
        String dbm;          // "-100", drawn under the wave fan (null = none)
        String dist;         // "1.0km", "≈600m" on the band line (null = none)
        String sinr;         // "13" (dB), under the fan after the dBm (null = none)
        int sinrColor;
        String opLetter;     // operator badge letter ("J"), null = show SIM number
        int opColor;
        String tag;          // "E" = NetSwitch holds this SIM on 4G (green eco, amber otherwise)
        int tagColor;

        boolean same(Side o) {
            return o != null && present == o.present && inService == o.inService && level == o.level
                    && eq(net, o.net) && eq(band, o.band) && eq(voice, o.voice) && data == o.data && in == o.in && out == o.out && inCall == o.inCall && eq(dbm, o.dbm) && eq(dist, o.dist) && eq(sinr, o.sinr) && sinrColor == o.sinrColor && eq(opLetter, o.opLetter) && opColor == o.opColor && eq(tag, o.tag) && tagColor == o.tagColor && cells == o.cells;
        }

        Side copy() {
            Side s = new Side();
            s.present = present; s.inService = inService; s.level = level; s.net = net; s.band = band;
            s.voice = voice; s.data = data; s.in = in; s.out = out; s.inCall = inCall; s.dbm = dbm; s.opLetter = opLetter; s.dist = dist; s.sinr = sinr; s.sinrColor = sinrColor; s.opColor = opColor; s.tag = tag; s.tagColor = tagColor; s.cells = cells;
            return s;
        }

        private static boolean eq(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    private Side[] sides = {new Side(), new Side()};
    private int color = 0xFFFFFFFF, voColor = 0xFF34C759, bandColor = 0xFF78BEFF;
    private float textPx = 20f;
    private float sizeScale = 1f;
    private boolean darkBar = true;

    // ---- animations (each has its own switch in the settings)
    private boolean pingShown;                       // ping dot after the arrows on the data SIM
    private final long[] popAt = {0, 0};             // network type changed: pop start (uptime ms)
    private long seenTestAt, seenPingAt;
    private long pingTickAt;                         // ping dot tick start (uptime ms)
    private static final long POP_MS = 520, TICK_MS = 380, SHINE_EVERY = 3500, SHINE_MS = 750;

    /** Two-colour gradient for a network type (bright on a dark bar, deeper on a light one). */
    private int[] netColors(String net) {
        if (net == null) return null;
        String n = net.toUpperCase(java.util.Locale.US);
        if (n.startsWith("5G")) return darkBar ? new int[]{0xFFB388FF, 0xFF18FFFF} : new int[]{0xFF5E35B1, 0xFF00838F};
        if (n.contains("4G+") || n.contains("LTE+") || n.contains("LTE-A") || n.contains("LTE A"))
            return darkBar ? new int[]{0xFFFFB340, 0xFFFF4F7B} : new int[]{0xFFE65100, 0xFFC2185B};
        if (n.startsWith("4G") || n.startsWith("LTE")) return darkBar ? new int[]{0xFF4CE07A, 0xFF2EE6D6} : new int[]{0xFF1E8E3E, 0xFF00796B};
        if (n.startsWith("3G") || n.startsWith("H")) return darkBar ? new int[]{0xFFFFE14D, 0xFFFFA22E} : new int[]{0xFFA67C00, 0xFFD45500};
        if (n.startsWith("2G") || n.equals("E") || n.equals("G") || n.equals("1X"))
            return darkBar ? new int[]{0xFFFF6B60, 0xFFFF9A8F} : new int[]{0xFFC62828, 0xFFAD1457};
        return null;
    }

    private static boolean shineOn() {
        return Cfg.b(Cfg.ANIM_SHIMMER, Cfg.D_ANIM_SHIMMER);
    }

    /** 0..1 while the shine is crossing, -1 otherwise. Both SIMs share the beat, SIM 2 a bit later. */
    private static float shinePhase(int sd) {
        long t = (now() + (sd == 1 ? SHINE_EVERY - 400 : 0)) % SHINE_EVERY;
        if (SysState.testing()) t = (now() + sd * 300) % 1200;          // test: shine all the time
        long len = SysState.testing() ? 900 : SHINE_MS;
        return t < len ? t / (float) len : -1f;
    }

    /** Draws the network label: gradient colours, then a moving white shine on top. */
    private void drawNet(Canvas c, String net, float x, float y, float nw, int sd) {
        int[] cols = Cfg.b(Cfg.NET_COLOR, Cfg.D_NET_COLOR) ? netColors(net) : null;
        float th = bigText();
        if (cols != null) {
            text.setShader(new android.graphics.LinearGradient(x, y - th, x + nw, y, cols[0], cols[1],
                    android.graphics.Shader.TileMode.CLAMP));
        }
        c.drawText(net, x, y, text);
        float ph = shineOn() ? shinePhase(sd) : -1f;
        if (ph >= 0f) {
            float band = Math.max(th * 0.9f, nw * 0.45f);
            float sx = x - band + (nw + band * 2) * ph;
            text.setShader(new android.graphics.LinearGradient(sx - band / 2f, y - th, sx + band / 2f, y,
                    new int[]{0x00FFFFFF, darkBar ? 0xE6FFFFFF : 0xB3FFFFFF, 0x00FFFFFF}, null,
                    android.graphics.Shader.TileMode.CLAMP));
            c.drawText(net, x, y, text);
        }
        text.setShader(null);
    }

    private static long now() {
        return android.os.SystemClock.uptimeMillis();
    }

    private boolean animArrows() {
        return Cfg.b(Cfg.ANIM_ARROWS, Cfg.D_ANIM_ARROWS);
    }

    private static boolean pingWanted() {
        return false;      // the ping now shows as text in the stats column
    }

    /** Bright colour on a dark bar, deeper shade on a light one. */
    private int pick(int bright, int deep) {
        return darkBar ? bright : deep;
    }

    /** Wave colour for what this SIM is doing now. */
    private int activityColor(Side s) {
        if (s.inCall) return pick(0xFFFF9F0A, 0xFFC46A00);                 // call: orange
        if (s.data && s.in && s.out) return pick(0xFF64D2FF, 0xFF0086A8);  // both ways: cyan
        if (s.data && s.in) return pick(0xFF34C759, 0xFF1E8E3E);           // download: green
        if (s.data && s.out) return pick(0xFF0A84FF, 0xFF0A60C0);          // upload: blue
        if (s.data) return color;                                          // data SIM, idle
        return pick(0xFF8EA4C8, 0xFF5A6E90);                               // standby: grey-blue
    }

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    SignalTowerView(Context ctx) {
        super(ctx);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTypeface(Typeface.DEFAULT_BOLD);
    }

    /** Returns true when something changed (and a redraw / relayout was requested). */
    boolean update(Side s1, Side s2, int color, float textPx, Typeface tf, float scale) {
        Side[] in = {s1, s2};
        boolean sw = Cfg.b(Cfg.ANIM_SWITCH, Cfg.D_ANIM_SWITCH);
        for (int i = 0; i < 2; i++) {
            if (sw && sides[i].present && in[i].present && sides[i].net != null && in[i].net != null
                    && !sides[i].net.equals(in[i].net)) popAt[i] = now();
        }
        boolean ps = pingWanted();
        boolean pingLayout = ps != pingShown;
        pingShown = ps;
        if (pingLayout) {
            requestLayout();
            invalidate();
        }
        if (popAt[0] > 0 || popAt[1] > 0 || SysState.testing() || SysState.pingAt != seenPingAt) invalidate();
        boolean layout = textPx != this.textPx || scale != sizeScale || (tf != null && tf != text.getTypeface())
                || !eqStr(s1.net, sides[0].net) || !eqStr(s2.net, sides[1].net)
                || !eqStr(s1.band, sides[0].band) || !eqStr(s2.band, sides[1].band)
                || !eqStr(s1.voice, sides[0].voice) || !eqStr(s2.voice, sides[1].voice)
                || !eqStr(s1.sinr, sides[0].sinr) || !eqStr(s2.sinr, sides[1].sinr)
                || !eqStr(s1.dist, sides[0].dist) || !eqStr(s2.dist, sides[1].dist)
                || s1.cells != sides[0].cells || s2.cells != sides[1].cells || !eqStr(s1.tag, sides[0].tag) || !eqStr(s2.tag, sides[1].tag) || !eqStr(s1.opLetter, sides[0].opLetter) || !eqStr(s2.opLetter, sides[1].opLetter)
                || !eqStr(s1.dbm, sides[0].dbm) || !eqStr(s2.dbm, sides[1].dbm)
                || s1.data != sides[0].data || s2.data != sides[1].data
                || s1.present != sides[0].present || s2.present != sides[1].present;
        boolean changed = layout || color != this.color || !s1.same(sides[0]) || !s2.same(sides[1]);
        if (!changed) return false;
        sides[0] = s1.copy();
        sides[1] = s2.copy();
        this.color = color;
        this.textPx = textPx;
        this.sizeScale = scale;
        if (tf != null) text.setTypeface(Typeface.create(tf, Typeface.BOLD));
        // Green voice label on a dark bar, darker green on a light one.
        float lum = (0.299f * ((color >> 16) & 0xFF) + 0.587f * ((color >> 8) & 0xFF) + 0.114f * (color & 0xFF)) / 255f;
        darkBar = lum > 0.5f;
        voColor = lum > 0.5f ? 0xFF34C759 : 0xFF1E8E3E;
        bandColor = lum > 0.5f ? 0xFF78BEFF : 0xFF0A5BC4;
        if (layout) requestLayout();
        invalidate();
        return true;
    }

    /** For idle dimming: what this view shows, without the moving parts. */
    int dimSig() {
        int h = 0;
        for (Side s : sides) {
            h = h * 31 + (s.present ? 1 : 0) + (s.inService ? 2 : 0) + s.level * 4 + (s.data ? 64 : 0);
            h = h * 31 + (s.net == null ? 0 : s.net.hashCode());
            h = h * 31 + (s.dbm == null ? 0 : s.dbm.hashCode());
            h = h * 31 + (s.voice == null ? 0 : s.voice.hashCode());
        }
        return h;
    }

    private static boolean eqStr(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    // ------------------------------------------------------------ geometry

    private float h() {
        return textPx * 2.05f * sizeScale;          // as tall as two text lines
    }

    private float bigText() {
        return textPx * 0.95f * sizeScale;
    }

    private float smallText() {
        return textPx * 0.70f * sizeScale;
    }

    /** Half-width of the tower + waves block. */
    private float coreHalf() {
        float h = h();
        float half = h * 0.56f;
        // room for the dBm under each fan, centred 0.40 h from the mast
        text.setTextSize(dbmText());
        for (Side s : sides) {
            if (s.present && (s.dbm != null || s.sinr != null)) half = Math.max(half, h * LEG_CLEAR + underWidth(s) + 2 * h * PAD + 1);
        }
        return half;
    }

    /** Distance from the mast the under-fan numbers keep, so they never touch the legs. */
    private static final float LEG_CLEAR = 0.285f;
    private static final float PAD = 0.05f;

    private float sinrText() {
        return dbmText();
    }

    /** dBm colour: strong green, fair yellow, weak orange, very weak red. */
    private int dbmColor(String dbm) {
        try {
            int v = Integer.parseInt(dbm.trim());
            if (v >= -85) return pick(0xFF34C759, 0xFF1E8E3E);
            if (v >= -100) return pick(0xFFFFD60A, 0xFF9A7B00);
            if (v >= -110) return pick(0xFFFF9F0A, 0xFFC46A00);
            return pick(0xFFFF453A, 0xFFC4281C);
        } catch (Exception e) {
            return color;
        }
    }

    private float dbmText() {
        return textPx * 0.66f * sizeScale;
    }

    private float labelWidth(int i) {
        Side s = sides[i];
        if (!s.present) return 0;
        text.setTextSize(bigText());
        float w = s.net != null ? text.measureText(s.net) : text.measureText("?");
        text.setTextSize(smallText());
        w += (s.opLetter != null ? badgeR() * 2 + 2 : text.measureText("1")) + 1 + tagWidth(s);
        float w2 = line2Width(s);
        float w3 = (s.voice != null ? text.measureText(s.voice) : 0)
                + (s.data ? arrowsWidth() + pingWidth() + (s.voice != null ? smallText() * 0.3f : 0) : 0) + cellsWidth(s);
        return Math.max(w, Math.max(w2, w3));
    }

    /** Band line: "n78", then (with a distance) a dotted line + pin and "1.0km". */
    private static final String SEP = " \u2502 ";

    private float line2Width(Side s) {
        text.setTextSize(smallText());
        float w = s.band != null ? text.measureText(s.band) : 0;
        if (s.dist != null) w += (w > 0 ? text.measureText(SEP) : 0) + text.measureText(s.dist);
        return w;
    }

    /** Width of the stacked "-99" over "13dB" under a side. */
    private float underWidth(Side s) {
        text.setTextSize(dbmText());
        float w = 0;
        if (s.dbm != null) w = text.measureText(s.dbm);
        if (s.sinr != null) {
            text.setTextSize(sinrText());
            float w2 = text.measureText(s.sinr);
            text.setTextSize(sinrText() * 0.85f);
            w2 += text.measureText("dB");
            w = Math.max(w, w2);
        }
        return w;
    }

    private float cellsWidth(Side s) {
        if (s.cells < 0) return 0;
        text.setTextSize(smallText());
        return smallText() * 0.95f + 1 + text.measureText(String.valueOf(s.cells)) + smallText() * 0.35f;
    }

    private float tagWidth(Side s) {
        if (s.tag == null) return 0;
        text.setTextSize(smallText());
        return text.measureText(s.tag) + 2;
    }

    private float badgeR() {
        return smallText() * 0.62f;
    }

    /** Operator disc: brand-ish colour with its initial (a plain letter, not the operator's logo). */
    static Object[] operatorBadge(String name, String mccMnc) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        if (n.contains("jio")) return new Object[]{"J", 0xFF0A3D91};
        if (n.contains("airtel")) return new Object[]{"A", 0xFFE40000};
        if (n.contains("vodafone") || n.contains("idea") || n.equals("vi") || n.startsWith("vi ")) return new Object[]{"V", 0xFFE60000};
        if (n.contains("bsnl")) return new Object[]{"B", 0xFFF57C00};
        if (n.contains("mtnl")) return new Object[]{"M", 0xFF1565C0};
        if (mccMnc != null && mccMnc.startsWith("405-8")) return new Object[]{"J", 0xFF0A3D91};
        if (n.isEmpty()) return null;
        return new Object[]{n.substring(0, 1).toUpperCase(java.util.Locale.US), 0xFF607D8B};
    }

    private float pingWidth() {
        return pingShown ? smallText() * 0.75f : 0;
    }

    private float arrowsWidth() {
        return smallText() * 1.3f;
    }

    private String levelText(Side s) {
        return s.level >= 0 ? s.level + "/4" : "?/4";
    }

    private float gap() {
        return textPx * 0.18f;
    }

    /**
     * Width this view would take showing s1 / s2 at this text size (nothing shown changes).
     * Lets the status bar drop optional parts before the tower pushes the battery off the edge.
     */
    float widthFor(Side s1, Side s2, float textPx, float scale) {
        Side a = sides[0], b = sides[1];
        float tp = this.textPx, sc = this.sizeScale;
        sides[0] = s1;
        sides[1] = s2;
        this.textPx = textPx;
        this.sizeScale = scale;
        try {
            return (float) Math.ceil(labelWidth(0) + gap() + 2 * coreHalf() + gap() + labelWidth(1)) + 2;
        } finally {
            sides[0] = a;
            sides[1] = b;
            this.textPx = tp;
            this.sizeScale = sc;
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        float w = labelWidth(0) + gap() + 2 * coreHalf() + gap() + labelWidth(1);
        int hh = (int) Math.ceil(h());
        setMeasuredDimension((int) Math.ceil(w) + 2, resolveSize(hh, hSpec));
    }

    // ------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        long tAt = SysState.animTestAt;
        if (SysState.testing() && tAt != seenTestAt) {
            seenTestAt = tAt;
            if (Cfg.b(Cfg.ANIM_SWITCH, Cfg.D_ANIM_SWITCH)) popAt[0] = popAt[1] = now();
            pingTickAt = now();
        }
        if (SysState.pingAt != seenPingAt) {
            seenPingAt = SysState.pingAt;
            pingTickAt = now();
        }
        float h = h();
        float top = (getHeight() - h) / 2f;
        float lw0 = labelWidth(0);
        float cx = lw0 + gap() + coreHalf();
        int dim = (color & 0x00FFFFFF) | 0x4D000000;

        // --- lattice mast: red / white painted legs, X bracing, two waves each side (inner white, outer red)
        float sw = Math.max(1.2f, h * 0.045f);
        int red = pick(0xFFFF3B30, 0xFFC62828);
        int light = color;                       // white on a dark bar, dark grey on a light one
        int brace = pick(0xFF969EAF, 0xFF6B7384);
        float bottom = top + h - sw / 2;
        float ptop = top + h * 0.18f;
        float wt = h * 0.03f, wb = h * 0.25f;
        float leg = Math.max(1.6f, h * 0.034f);
        float hubY = top + h * 0.222f;             // antenna hub: the waves are centred here
        // whip + beacon
        stroke.setColor(light);
        stroke.setStrokeWidth(Math.max(1f, sw * 0.6f));
        c.drawLine(cx, top + sw * 1.5f, cx, ptop, stroke);
        fill.setColor(red);
        c.drawCircle(cx, top + sw * 1.2f, sw * 1.2f, fill);
        int nb = 6;
        float braceW = Math.max(1f, h * 0.016f);
        for (int i = 0; i < nb; i++) {
            float y0 = ptop + (bottom - ptop) * i / nb, y1 = ptop + (bottom - ptop) * (i + 1) / nb;
            float w0 = wt + (wb - wt) * i / nb, w1 = wt + (wb - wt) * (i + 1) / nb;
            int band = i % 2 == 0 ? red : 0xFFFFFFFF;       // always red / white, like a real mast
            stroke.setColor(brace);
            stroke.setStrokeWidth(braceW);
            c.drawLine(cx - w0 + leg / 2, y0, cx + w1 - leg / 2, y1, stroke);
            c.drawLine(cx + w0 - leg / 2, y0, cx - w1 + leg / 2, y1, stroke);
            stroke.setColor(band);
            stroke.setStrokeWidth(braceW * 1.3f);
            c.drawLine(cx - w1, y1, cx + w1, y1, stroke);
            for (int sdn = -1; sdn <= 1; sdn += 2) {
                path.reset();
                path.moveTo(cx + sdn * w0, y0);
                path.lineTo(cx + sdn * (w0 - leg), y0);
                path.lineTo(cx + sdn * (w1 - leg), y1);
                path.lineTo(cx + sdn * w1, y1);
                path.close();
                fill.setColor(band);
                c.drawPath(path, fill);
                if (!darkBar) {                              // white legs vanish on a light bar
                    stroke.setColor(color);
                    stroke.setStrokeWidth(Math.max(0.8f, h * 0.01f));
                    c.drawPath(path, stroke);
                }
            }
        }
        // platform ring
        stroke.setColor(red);
        stroke.setStrokeWidth(Math.max(1f, h * 0.025f));
        rect.set(cx - h * 0.16f, ptop + h * 0.04f, cx + h * 0.16f, ptop + h * 0.125f);
        c.drawOval(rect, stroke);
        // two waves each side: inner white, outer red
        float ww = Math.max(1.6f, h * 0.042f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        for (int k = 0; k < 2; k++) {
            float r = h * (k == 0 ? 0.139f : 0.236f);
            rect.set(cx - r, hubY - r, cx + r, hubY + r);
            if (k == 0 && !darkBar) {
                stroke.setColor(color);
                stroke.setStrokeWidth(ww + Math.max(1f, h * 0.025f));
                c.drawArc(rect, -48, 96, false, stroke);
                c.drawArc(rect, 132, 96, false, stroke);
            }
            stroke.setColor(k == 0 ? 0xFFFFFFFF : red);
            stroke.setStrokeWidth(ww);
            c.drawArc(rect, -48, 96, false, stroke);
            c.drawArc(rect, 132, 96, false, stroke);
        }
        stroke.setStrokeCap(Paint.Cap.BUTT);
        // antenna panels beside the hub: left = SIM 1, right = SIM 2, coloured by what that SIM is doing
        float pw = Math.max(2.2f, h * 0.05f), ph = h * 0.13f;
        for (int sd = 0; sd < 2; sd++) {
            Side s = sides[sd];
            int pc = s.present && s.inService ? activityColor(s) : dim;
            fill.setColor(pc);
            float ox = sd == 0 ? cx - h * 0.082f : cx + h * 0.082f;
            rect.set(ox - pw / 2, hubY - ph / 2, ox + pw / 2, hubY + ph / 2);
            c.drawRoundRect(rect, pw / 2, pw / 2, fill);
            // no service: small cross by the waves
            if (s.present && !s.inService) {
                stroke.setColor(pick(0xFFFF453A, 0xFFC4281C));
                stroke.setStrokeWidth(Math.max(1.2f, h * 0.05f));
                float k = h * 0.05f, xx = sd == 0 ? cx - h * 0.34f : cx + h * 0.34f, yy = top + h * 0.20f;
                c.drawLine(xx - k, yy - k, xx + k, yy + k, stroke);
                c.drawLine(xx - k, yy + k, xx + k, yy - k, stroke);
            }
        }
        fill.setColor(light);
        c.drawCircle(cx, hubY, h * 0.04f, fill);

        // --- dBm above SINR in a pill beside the mast (SIM 1 left, SIM 2 right)
        float pad = h * PAD;
        for (int sd = 0; sd < 2; sd++) {
            Side s = sides[sd];
            if (!s.present || (s.dbm == null && s.sinr == null)) continue;
            float tw = underWidth(s);
            float pwid = tw + 2 * pad;
            float x0 = sd == 0 ? cx - h * LEG_CLEAR - pwid : cx + h * LEG_CLEAR;
            rect.set(x0, top + h * 0.36f, x0 + pwid, top + h * 0.995f);
            fill.setColor((color & 0x00FFFFFF) | 0x26000000);
            c.drawRoundRect(rect, h * 0.07f, h * 0.07f, fill);
            stroke.setColor((color & 0x00FFFFFF) | 0x59000000);
            stroke.setStrokeWidth(Math.max(1f, h * 0.012f));
            c.drawRoundRect(rect, h * 0.07f, h * 0.07f, stroke);
            if (s.dbm != null) {
                text.setTextSize(dbmText());
                text.setColor(dbmColor(s.dbm));
                c.drawText(s.dbm, x0 + (pwid - text.measureText(s.dbm)) / 2f, top + h * 0.66f, text);
            }
            if (s.sinr != null) {
                float y = top + h * 0.955f;
                text.setTextSize(sinrText());
                float sw1 = text.measureText(s.sinr);
                text.setTextSize(sinrText() * 0.85f);
                float dw = text.measureText("dB");
                float x = x0 + (pwid - sw1 - dw) / 2f;
                text.setTextSize(sinrText());
                text.setColor(s.sinrColor);
                c.drawText(s.sinr, x, y, text);
                text.setTextSize(sinrText() * 0.85f);
                text.setColor((color & 0x00FFFFFF) | 0xCC000000);
                c.drawText("dB", x + sw1, y, text);
            }
        }

        // --- labels
        for (int sd = 0; sd < 2; sd++) {
            Side s = sides[sd];
            if (!s.present) continue;
            boolean left = sd == 0;
            float edge = left ? lw0 : cx + coreHalf() + gap();
            float y1 = top + bigText() * 0.82f;
            // line 1: network + small SIM number
            text.setColor(color);
            text.setTextSize(bigText());
            String net = s.net != null ? s.net : "?";
            float nw = text.measureText(net);
            text.setTextSize(smallText());
            float sw1 = s.opLetter != null ? badgeR() * 2 + 2 : text.measureText(String.valueOf(sd + 1));
            sw1 += tagWidth(s);
            float x = left ? edge - nw - sw1 - 1 : edge;
            text.setTextSize(bigText());
            float pk = popScale(sd);
            if (pk != 1f) {
                c.save();
                c.scale(pk, pk, x + nw / 2f, y1 - bigText() * 0.35f);
                drawNet(c, net, x, y1, nw, sd);
                c.restore();
            } else {
                drawNet(c, net, x, y1, nw, sd);
            }
            if (s.opLetter != null) {
                float br = badgeR(), bx = x + nw + 2 + br, by = y1 - bigText() * 0.36f;
                fill.setColor(s.opColor);
                c.drawCircle(bx, by, br, fill);
                text.setTextSize(br * 1.45f);
                text.setColor(0xFFFFFFFF);
                float lw = text.measureText(s.opLetter);
                c.drawText(s.opLetter, bx - lw / 2f, by + br * 0.52f, text);
                text.setColor(color);
                if (s.tag != null) {
                    text.setTextSize(smallText());
                    text.setColor(s.tagColor);
                    c.drawText(s.tag, bx + br + 2, by + br * 0.52f, text);
                    text.setColor(color);
                }
            } else {
                text.setTextSize(smallText());
                c.drawText(String.valueOf(sd + 1), x + nw + 1, y1 + smallText() * 0.35f, text);
            }
            // line 2: band (blue)
            float y2 = y1 + smallText() * 1.08f;
            if (s.band != null || s.dist != null) {
                text.setTextSize(smallText());
                float lw2 = line2Width(s);
                float bx = left ? edge - lw2 : edge;
                text.setColor(bandColor);
                if (s.band != null) {
                    c.drawText(s.band, bx, y2, text);
                    bx += text.measureText(s.band);
                }
                if (s.dist != null) {
                    if (s.band != null) {
                        text.setColor((color & 0x00FFFFFF) | 0x80000000);
                        c.drawText(SEP, bx, y2, text);
                        bx += text.measureText(SEP);
                    }
                    text.setColor(bandColor);
                    c.drawText(s.dist, bx, y2, text);
                }
            }
            // line 3: voice type, then ↓↑ on the data SIM
            float y3 = y2 + smallText() * 1.0f;
            float vw = s.voice != null ? text.measureText(s.voice) : 0;
            float aw = s.data ? arrowsWidth() + pingWidth() : 0;
            float g = s.voice != null && s.data ? smallText() * 0.3f : 0;
            float cwid = cellsWidth(s);
            float base = left ? edge - (vw + g + aw + cwid) : edge;
            float x0 = base + cwid;
            if (s.cells >= 0) {
                float cx0 = base;
                NodesDrawable nodes = new NodesDrawable(s.sinrColor != 0 ? s.sinrColor : color, Math.round(smallText() * 0.95f));
                c.save();
                c.translate(cx0, y3 - smallText() * 0.85f);
                nodes.draw(c);
                c.restore();
                text.setTextSize(smallText());
                text.setColor(color);
                c.drawText(String.valueOf(s.cells), cx0 + smallText() * 0.95f + 1, y3, text);
            }
            if (s.voice != null) {
                text.setColor(voColor);
                c.drawText(s.voice, x0, y3, text);
            }
            if (s.data) {
                float ax = x0 + vw + g, ah = smallText() * 0.85f, ay = y3 - smallText() * 0.8f;
                drawArrows(c, ax, ay, arrowsWidth(), ah, s.in ? color : dim, s.out ? color : dim,
                        animArrows() && s.in, animArrows() && s.out);
                if (pingShown) {
                    int ms = SysState.pingMs;
                    int pc = ms < 0 ? pick(0xFFFF453A, 0xFFC4281C) : ms < 100 ? pick(0xFF34C759, 0xFF1E8E3E)
                            : ms < 300 ? pick(0xFFFFB020, 0xFFB86E00) : pick(0xFFFF453A, 0xFFC4281C);
                    float r = smallText() * 0.19f;
                    long te = now() - pingTickAt;
                    if (te >= 0 && te < TICK_MS) {
                        float f = te / (float) TICK_MS;
                        r *= 1f + 0.9f * (float) Math.sin(Math.PI * f);
                        // a ring going out
                        stroke.setColor((pc & 0x00FFFFFF) | (Math.round(200 * (1 - f)) << 24));
                        stroke.setStrokeWidth(Math.max(1f, r * 0.35f));
                        c.drawCircle(ax + arrowsWidth() + smallText() * 0.4f, ay + ah / 2f, r + smallText() * 0.35f * f, stroke);
                    }
                    fill.setColor(pc);
                    c.drawCircle(ax + arrowsWidth() + smallText() * 0.4f, ay + ah / 2f, r, fill);
                }
            }
        }
    }

    @Override
    public void draw(Canvas c) {
        super.draw(c);
        if (!isShown()) return;
        long n = now();
        boolean fast = false;
        for (int i = 0; i < 2; i++) {
            if (popAt[i] > 0) {
                if (n - popAt[i] < POP_MS) fast = true;
                else popAt[i] = 0;
            }
        }
        if (n - pingTickAt < TICK_MS) fast = true;
        boolean moving = false;
        for (Side s : sides) {
            if (s.inService && s.data && (s.in || s.out) && !s.inCall) moving = true;
        }
        if (shineOn()) {
            boolean any = false;
            long wait = Long.MAX_VALUE;
            for (int i = 0; i < 2; i++) {
                if (!sides[i].present || sides[i].net == null) continue;
                if (shinePhase(i) >= 0f) any = true;
                else {
                    long t = (n + (i == 1 ? SHINE_EVERY - 400 : 0)) % SHINE_EVERY;
                    wait = Math.min(wait, SHINE_EVERY - t);
                }
            }
            if (any || SysState.testing()) fast = true;
            else if (wait != Long.MAX_VALUE && !moving(n)) {
                postInvalidateDelayed(Math.max(16, pingShown ? Math.min(500, wait) : wait));
                return;
            }
        }
        if (fast) postInvalidateOnAnimation();
        else if (moving) postInvalidateDelayed(animArrows() ? 40 : 110);
        else if (pingShown || SysState.testing()) postInvalidateDelayed(500);   // catch the next ping tick
    }

    private boolean moving(long n) {
        if (n - pingTickAt < TICK_MS) return true;
        for (int i = 0; i < 2; i++) if (popAt[i] > 0) return true;
        for (Side s : sides) if (s.inService && s.data && (s.in || s.out) && !s.inCall) return true;
        return false;
    }

    /** Network type pop: grows quickly, settles back with a small bounce. */
    private float popScale(int sd) {
        if (popAt[sd] == 0) return 1f;
        float f = (now() - popAt[sd]) / (float) POP_MS;
        if (f >= 1f) return 1f;
        if (f < 0.3f) return 1f + 0.6f * (f / 0.3f);
        float g = (f - 0.3f) / 0.7f;
        return 1f + 0.6f * (float) (Math.cos(g * Math.PI * 1.5) * (1 - g));
    }

    /** ↓ then ↑, side by side, in a w×h box. */
    private void drawArrows(Canvas c, float x, float y, float w, float h, int downColor, int upColor,
                            boolean moveDown, boolean moveUp) {
        float ph = (now() % 800) / 800f;
        float slide = (ph - 0.5f) * h * 0.45f;
        int fa = Math.round(255 * (0.45f + 0.55f * (float) Math.sin(Math.PI * ph)));
        if (moveDown) {
            downColor = (downColor & 0x00FFFFFF) | (Math.round(((downColor >>> 24) & 0xFF) * fa / 255f) << 24);
            c.save();
            c.clipRect(x - 1, y - h * 0.3f, x + w / 2f, y + h * 1.3f);
            c.translate(0, slide);
        }
        drawDown(c, x, y, w, h, downColor);
        if (moveDown) c.restore();
        if (moveUp) {
            upColor = (upColor & 0x00FFFFFF) | (Math.round(((upColor >>> 24) & 0xFF) * fa / 255f) << 24);
            c.save();
            c.clipRect(x + w / 2f, y - h * 0.3f, x + w + 1, y + h * 1.3f);
            c.translate(0, -slide);
        }
        drawUp(c, x, y, w, h, upColor);
        if (moveUp) c.restore();
    }

    private void drawDown(Canvas c, float x, float y, float w, float h, int downColor) {
        float aw = w * 0.45f;
        // down arrow
        fill.setColor(downColor);
        path.reset();
        path.moveTo(x, y + h * 0.45f);
        path.lineTo(x + aw, y + h * 0.45f);
        path.lineTo(x + aw / 2, y + h);
        path.close();
        c.drawPath(path, fill);
        stroke.setColor(downColor);
        stroke.setStrokeWidth(Math.max(1f, aw * 0.28f));
        c.drawLine(x + aw / 2, y, x + aw / 2, y + h * 0.5f, stroke);
    }

    private void drawUp(Canvas c, float x, float y, float w, float h, int upColor) {
        float aw = w * 0.45f;
        // up arrow
        float ux = x + w - aw;
        fill.setColor(upColor);
        path.reset();
        path.moveTo(ux, y + h * 0.55f);
        path.lineTo(ux + aw, y + h * 0.55f);
        path.lineTo(ux + aw / 2, y);
        path.close();
        c.drawPath(path, fill);
        stroke.setColor(upColor);
        stroke.setStrokeWidth(Math.max(1f, aw * 0.28f));
        c.drawLine(ux + aw / 2, y + h * 0.5f, ux + aw / 2, y + h, stroke);
    }
}
