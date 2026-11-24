package com.netspeed.xposed;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The built-in India layer of the wallpaper: thin river lines with names, major dams with a second line of facts, national parks,
 * wildlife sanctuaries and biosphere reserves, faint names of ranges and plateaus, and well known peaks. All from IndiaData, so
 * nothing here needs a map server. Positions are approximate (rivers about 2 km, the rest to a few km).
 */
final class IndiaMap {
    private IndiaMap() {}

    /** True while the built-in dams are drawn: the other dam labels then leave out any dam this layer already names. */
    static volatile boolean dedupe = false;
    /** Rows of the colour key (4 when it only has the land cover entries). */
    static volatile int legendRows = 4;

    static float legendBottom(int W, int H) {
        float u = W / 1080f;
        return Math.max(H * 0.265f, H * 0.185f + legendRows * 40f * u + 14f * u);
    }

    /** True when the wallpaper shows the numbered markers and the "near you" list panel (maps up to 150 km half-width). */
    static volatile boolean panelOn = false;
    static final float PANEL_H = 282f;

    /** Keeps other labels off the colour key and, when it is on, the list panel under it. */
    static void reserve(List<RectF> used, int W, int H) {
        float u = W / 1080f, lb = legendBottom(W, H);
        used.add(new RectF(W * 0.02f, H * 0.165f, W * 0.66f, lb));
        if (panelOn) used.add(new RectF(W * 0.03f, lb + 6 * u, W * 0.97f, lb + (PANEL_H + 14) * u));
    }

    /** A dam from the built-in list within 2.5 km of this spot. */
    static boolean majorNear(double lat, double lon) {
        for (Object[] d : IndiaData.DAMS) {
            if (Math.abs((Double) d[2] - lat) < 0.03 && Math.abs((Double) d[3] - lon) < 0.03) return true;
        }
        return false;
    }

    // ---- rivers, parsed once
    private static List<float[]> rPts;       // lon, lat, lon, lat ...
    private static List<String> rName;

    private static synchronized void load() {
        if (rPts != null) return;
        List<float[]> p = new ArrayList<>();
        List<String> n = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (String s : IndiaData.RIVERS) all.add(s);
        all.add(IndiaData.LOCAL_RIVERS + "\n");
        for (String chunk : all) for (String ln : chunk.split("\n")) {
            int bar = ln.indexOf('|');
            if (bar < 0) continue;
            String[] pts = ln.substring(bar + 1).split(";");
            float[] a = new float[pts.length * 2];
            int k = 0;
            for (String q : pts) {
                int c = q.indexOf(',');
                if (c < 0) continue;
                try { a[k++] = Integer.parseInt(q.substring(0, c)) / 100f; a[k++] = Integer.parseInt(q.substring(c + 1)) / 100f; } catch (Throwable ignored) { }
            }
            if (k < 4) continue;
            p.add(k == a.length ? a : java.util.Arrays.copyOf(a, k));
            n.add(ln.substring(0, bar));
        }
        rName = n;
        rPts = p;
    }

    /** A symbol of the colour key and the map: 1 dam, 2 national park, 3 wildlife sanctuary, 4 biosphere reserve. */
    static void symbol(Canvas cv, int kind, float x, float y, float r, boolean day) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (kind == 1) {
            p.setStyle(Paint.Style.FILL); p.setColor(0xFF4682FF);
            cv.drawRect(x - r, y - r, x + r, y + r, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1.5f, r * 0.25f)); p.setColor(0xFFFFFFFF);
            cv.drawRect(x - r, y - r, x + r, y + r, p);
        } else if (kind == 2) {
            Path h = new Path();
            for (int i = 0; i < 6; i++) {
                double a = Math.toRadians(60 * i + 30);
                float px = (float) (x + r * 1.1 * Math.cos(a)), py = (float) (y + r * 1.1 * Math.sin(a));
                if (i == 0) h.moveTo(px, py); else h.lineTo(px, py);
            }
            h.close();
            p.setStyle(Paint.Style.FILL); p.setColor(0xFF148C3C); cv.drawPath(h, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1.5f, r * 0.22f)); p.setColor(0xFFFFFFFF); cv.drawPath(h, p);
        } else if (kind == 3) {
            Path t = new Path();
            t.moveTo(x, y - r * 1.15f); t.lineTo(x + r * 1.1f, y + r * 0.9f); t.lineTo(x - r * 1.1f, y + r * 0.9f); t.close();
            p.setStyle(Paint.Style.FILL); p.setColor(0xFFBEE646); cv.drawPath(t, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1.5f, r * 0.2f)); p.setColor(0xFF1E501E); cv.drawPath(t, p);
        } else {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2f, r * 0.32f)); p.setColor(day ? 0xFF8A3CC8 : 0xFFC878FF);
            cv.drawCircle(x, y, r, p);
            p.setStyle(Paint.Style.FILL); cv.drawCircle(x, y, r * 0.25f, p);
        }
    }

    private static Paint text(float size, boolean bold, boolean italic, int col) {
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTextSize(size);
        t.setTypeface(Typeface.create("sans-serif-medium", bold ? Typeface.BOLD : italic ? Typeface.ITALIC : Typeface.NORMAL));
        if (italic && !bold) t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
        t.setColor(col);
        return t;
    }

    private static Paint halo(Paint t, float w, boolean day) {
        Paint h = new Paint(t);
        h.setStyle(Paint.Style.STROKE); h.setStrokeWidth(w); h.setStrokeJoin(Paint.Join.ROUND);
        h.setColor(day ? 0xD2FFFFFF : 0xDC000000);
        return h;
    }

    private static boolean free(List<RectF> used, RectF r) {
        for (RectF o : used) if (RectF.intersects(o, r)) return false;
        return true;
    }

    // ---- rivers traced from the terrain: where the water of the elevation grid collects
    static volatile List<float[]> traced;      // screen x, y pairs
    static volatile List<String> tracedName;

    private static final class Heap {
        long[] a; int n;
        Heap(int cap) { a = new long[cap]; }
        void push(long k) {
            int i = n++; a[i] = k;
            while (i > 0) { int p = (i - 1) >> 1; if (a[p] <= a[i]) break; long t = a[p]; a[p] = a[i]; a[i] = t; i = p; }
        }
        long pop() {
            long r = a[0]; a[0] = a[--n];
            int i = 0;
            while (true) {
                int l = 2 * i + 1, rr = l + 1, m = i;
                if (l < n && a[l] < a[m]) m = l;
                if (rr < n && a[rr] < a[m]) m = rr;
                if (m == i) break;
                long t = a[m]; a[m] = a[i]; a[i] = t; i = m;
            }
            return r;
        }
    }

    /**
     * Fills the pits of the (smoothed) elevation grid, lets every cell drain to its lowest neighbour, adds up the area that drains
     * through each cell and keeps the cells that collect more than a few km2: those are the streams. offX/offY place grid cell 0,0 on screen.
     */
    static void trace(float[] e, int gw, int gh, float S, double mpp, double offX, double offY, int z, double ox, double oy, double halfKm, int W, int H) {
        traced = null; tracedName = null;
        final int N = gw * gh;
        float[] f = e.clone();
        int[] par = new int[N];
        java.util.Arrays.fill(par, -2);
        int[] order = new int[N];
        int oc = 0;
        Heap q = new Heap(N + 8);
        for (int y = 0; y < gh; y++) for (int x = 0; x < gw; x++) {
            if (x == 0 || y == 0 || x == gw - 1 || y == gh - 1) {
                int i = y * gw + x;
                par[i] = -1;
                q.push(((long) Float.floatToIntBits(f[i] + 2000f) << 32) | i);
            }
        }
        while (q.n > 0) {
            int i = (int) (q.pop() & 0xFFFFFFFFL);
            order[oc++] = i;
            int x = i % gw, y = i / gw;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int xx = x + dx, yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= gw || yy >= gh) continue;
                int j = yy * gw + xx;
                if (par[j] != -2) continue;
                par[j] = i;
                if (f[j] <= f[i]) f[j] = Math.nextUp(f[i]);
                q.push(((long) Float.floatToIntBits(f[j] + 2000f) << 32) | j);
            }
        }
        float[] acc = new float[N];
        java.util.Arrays.fill(acc, 1f);
        for (int k = oc - 1; k >= 0; k--) { int i = order[k]; int p = par[i]; if (p >= 0) acc[p] += acc[i]; }
        double cellKm = mpp / 1000.0;
        double viewKm2 = gw * cellKm * gh * cellKm;
        double tKm2 = Math.max(12.0, Math.min(300.0, viewKm2 * 0.0025));
        float thr = (float) (tKm2 / (cellKm * cellKm));
        boolean[] st = new boolean[N];
        int[] ch = new int[N];
        for (int i = 0; i < N; i++) st[i] = acc[i] >= thr;
        for (int i = 0; i < N; i++) if (st[i]) { int p = par[i]; if (p >= 0 && st[p]) ch[p]++; }
        boolean[] seen = new boolean[N];
        List<float[]> out = new ArrayList<>();
        for (int h = 0; h < N; h++) {
            if (!st[h] || ch[h] != 0) continue;
            int cnt = 0;
            int[] buf = new int[64];
            int cur = h;
            while (true) {
                if (cnt == buf.length) buf = java.util.Arrays.copyOf(buf, cnt * 2);
                buf[cnt++] = cur;
                if (seen[cur]) break;
                seen[cur] = true;
                int p = par[cur];
                if (p < 0 || !st[p]) break;
                cur = p;
            }
            if (cnt < 10) continue;
            float[] xs = new float[cnt], ys = new float[cnt];
            for (int k = 0; k < cnt; k++) { xs[k] = buf[k] % gw; ys[k] = buf[k] / gw; }
            float[] pts = new float[cnt * 2];
            for (int k = 0; k < cnt; k++) {
                float sx = 0, sy = 0; int m = 0;
                for (int d = -3; d <= 3; d++) { int kk = k + d; if (kk >= 0 && kk < cnt) { sx += xs[kk]; sy += ys[kk]; m++; } }
                pts[2 * k] = (float) (offX + (sx / m + 0.5f) * S);
                pts[2 * k + 1] = (float) (offY + (sy / m + 0.5f) * S);
            }
            out.add(pts);
        }
        // names: the built-in river whose line lies closest to this stream for most of its length (within about 6 km)
        load();
        List<float[]> rvx = new ArrayList<>();
        List<String> rvn = new ArrayList<>();
        for (int i = 0; i < rPts.size(); i++) {
            float[] a = rPts.get(i);
            float px = 0, py = 0;
            for (int k = 0; k < a.length / 2; k++) {
                float x = sx(a[2 * k], z, ox, S), y = sy(a[2 * k + 1], z, oy, S);
                if (k > 0) {
                    float len = (float) Math.hypot(x - px, y - py);
                    int steps = Math.max(1, (int) (len / 20f));
                    for (int t = 1; t <= steps; t++) {
                        float xx = px + (x - px) * t / steps, yy = py + (y - py) * t / steps;
                        if (xx > -150 && xx < W + 150 && yy > -150 && yy < H + 150) { rvx.add(new float[]{xx, yy}); rvn.add(rName.get(i)); }
                    }
                }
                px = x; py = y;
            }
        }
        float tol = (float) Math.max(14.0, 6000.0 / mpp * S), tol2 = tol * tol;
        List<String> names = new ArrayList<>();
        for (float[] pts : out) {
            Map<String, Integer> hits = new HashMap<>();
            int samples = 0;
            for (int k = 0; k < pts.length / 2; k += 3) {
                samples++;
                float bd = tol2; String bn = null;
                for (int r = 0; r < rvx.size(); r++) {
                    float dx = rvx.get(r)[0] - pts[2 * k], dy = rvx.get(r)[1] - pts[2 * k + 1];
                    float d = dx * dx + dy * dy;
                    if (d < bd) { bd = d; bn = rvn.get(r); }
                }
                if (bn != null && !bn.isEmpty()) { Integer c0 = hits.get(bn); hits.put(bn, c0 == null ? 1 : c0 + 1); }
            }
            String best = ""; int bc = 0;
            for (Map.Entry<String, Integer> en : hits.entrySet()) if (en.getValue() > bc) { bc = en.getValue(); best = en.getKey(); }
            names.add(bc >= Math.max(2, samples * 0.3) ? best : "");
        }
        tracedName = names;
        traced = out;
    }

    private static double km(double la1, double lo1, double la2, double lo2) {
        double dx = (lo2 - lo1) * 111.32 * Math.cos(Math.toRadians(la1)), dy = (la2 - la1) * 110.57;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static RectF tagRect(String s, float cx, float cy, float u) {
        Paint t = text(17 * u, true, false, 0xFFFFFFFF);
        float w = t.measureText(s) + 12 * u;
        return new RectF(cx - w / 2, cy - 12 * u, cx + w / 2, cy + 12 * u);
    }

    private static void tag(Canvas cv, String s, RectF r, float u, int fill) {
        Paint t = text(17 * u, true, false, 0xFFFFFFFF);
        Paint b = new Paint(Paint.ANTI_ALIAS_FLAG);
        b.setStyle(Paint.Style.FILL); b.setColor(fill);
        cv.drawRoundRect(r, 8 * u, 8 * u, b);
        b.setStyle(Paint.Style.STROKE); b.setStrokeWidth(1.6f * u); b.setColor(0xDCFFFFFF);
        cv.drawRoundRect(r, 8 * u, 8 * u, b);
        cv.drawText(s, r.left + 6 * u, r.centerY() + 6 * u, t);
    }

    static void draw(Canvas cv, int W, int H, double lat, double lon, int z, double ox, double oy, float S, boolean day, double halfKm,
                     boolean rivers, boolean dams, boolean parks, boolean phys, List<RectF> used) {
        final float u = W / 1080f;
        final boolean tags = halfKm <= 150 && (rivers || dams || parks);
        if (used == null) {
            used = new ArrayList<>();
            used.add(new RectF(0, 0, W, H * 0.22f));
            used.add(new RectF(0, H * 0.955f, W, H));
            reserve(used, W, H);
            used.add(new RectF(W / 2f - 100 * u, H * 0.5f - 100 * u, W / 2f + 100 * u, H * 0.5f + 100 * u));
        }
        List<String[]> pd = new ArrayList<>(), pp = new ArrayList<>(), pr = new ArrayList<>();
        if (phys) try { physical(cv, W, H, z, ox, oy, S, day, halfKm, used, u); } catch (Throwable ignored) { }
        if (rivers) try { riverLines(cv, W, H, z, ox, oy, S, day, halfKm, used, u, tags, pr); } catch (Throwable ignored) { }
        if (parks) try { parkMarks(cv, W, H, z, ox, oy, S, day, halfKm, used, u, lat, lon, tags, pp); } catch (Throwable ignored) { }
        if (dams) try { damMarks(cv, W, H, z, ox, oy, S, day, halfKm, used, u, lat, lon, tags, pd); } catch (Throwable ignored) { }
        if (phys && halfKm >= 150) try { peakMarks(cv, W, H, z, ox, oy, S, day, used, u); } catch (Throwable ignored) { }
        try { MapNames.drawLate(cv, W, H, day, used); } catch (Throwable ignored) { }
        if (tags) try { panel(cv, W, H, u, day, pd, pp, pr); } catch (Throwable ignored) { }
    }

    private static String fit(Paint t, String s, float w) {
        if (t.measureText(s) <= w) return s;
        while (s.length() > 1 && t.measureText(s + "\u2026") > w) s = s.substring(0, s.length() - 1);
        return s + "\u2026";
    }

    static void dt(Canvas cv, String s, float x, float y, Paint p, boolean day, float u) {
        Paint h = halo(p, 3.6f * u, day);
        cv.drawText(s, x, y, h);
        cv.drawText(s, x, y, p);
    }

    private static void panel(Canvas cv, int W, int H, float u, boolean day, List<String[]> pd, List<String[]> pp, List<String[]> pr) {
        if (pd.isEmpty() && pp.isEmpty() && pr.isEmpty()) return;
        float x0 = W * 0.04f, x1 = W * 0.96f, y0 = legendBottom(W, H) + 10 * u;
        int cMain = day ? 0xFF0A1E3C : 0xFFFFFFFF, cSub = day ? 0xFF3C4A5A : 0xFFB9C6BC;
        Paint hd = text(16 * u, true, false, cSub);
        hd.setLetterSpacing(0.15f);
        dt(cv, "NEAR YOU", x0, y0 + 24 * u, hd, day, u);
        Paint t1 = text(18 * u, true, false, cMain), t2 = text(15 * u, false, false, cSub);
        Paint id = text(18 * u, true, false, day ? 0xFF1E46A0 : 0xFF78AAFF);
        float colW = (x1 - x0) / 2f;
        int per = Math.max(1, (Math.min(pd.size(), 8) + 1) / 2);
        for (int i = 0; i < pd.size() && i < 8; i++) {
            float x = x0 + (i / per) * colW, y = y0 + 40 * u + (i % per) * 40 * u;
            String[] e = pd.get(i);
            dt(cv, e[0], x, y + 14 * u, id, day, u);
            float ix = x + id.measureText("D8") + 10 * u;
            dt(cv, fit(t1, e[1], colW - (ix - x) - 14 * u), ix, y + 14 * u, t1, day, u);
            if (e[2] != null && !e[2].isEmpty()) dt(cv, fit(t2, e[2], colW - (ix - x) - 14 * u), ix, y + 33 * u, t2, day, u);
        }
        float yy = y0 + 40 * u + 4 * 40 * u + 22 * u;
        float wmax = x1 - x0;
        if (!pp.isEmpty()) {
            Paint pid = text(17 * u, true, false, day ? 0xFF2D6E14 : 0xFFBEE646), pt = text(17 * u, false, false, cMain);
            float x = x0;
            for (String[] e : pp) {
                String body = " " + e[1] + (e.length > 2 && e[2] != null ? "  " + e[2] : "");
                float w = pid.measureText(e[0]) + pt.measureText(body) + 22 * u;
                if (x + w > x0 + wmax) break;
                dt(cv, e[0], x, yy, pid, day, u); dt(cv, body, x + pid.measureText(e[0]), yy, pt, day, u);
                x += w;
            }
            yy += 30 * u;
        }
        if (!pr.isEmpty()) {
            Paint rid = text(17 * u, true, false, day ? 0xFF1E5A8C : 0xFF8CD2FF), rt = text(17 * u, false, false, cMain);
            float x = x0;
            for (String[] e : pr) {
                String body = " " + e[1];
                float w = rid.measureText(e[0]) + rt.measureText(body) + 22 * u;
                if (x + w > x0 + wmax) break;
                dt(cv, e[0], x, yy, rid, day, u); dt(cv, body, x + rid.measureText(e[0]), yy, rt, day, u);
                x += w;
            }
        }
    }

    private static float sx(double lon, int z, double ox, float S) { return (float) ((WallpaperMaker.worldX(lon, z) - ox) * S); }
    private static float sy(double lat, int z, double oy, float S) { return (float) ((WallpaperMaker.worldY(lat, z) - oy) * S); }

    // ---- faint names of ranges, plateaus and plains
    private static void physical(Canvas cv, int W, int H, int z, double ox, double oy, float S, boolean day, double halfKm, List<RectF> used, float u) {
        Paint rg = new Paint(Paint.ANTI_ALIAS_FLAG);
        rg.setTextSize(27 * u); rg.setLetterSpacing(0.3f); rg.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        rg.setColor(day ? 0x883C2810 : 0x99DCE6F5);
        for (Object[] r : IndiaData.PHYS) {
            int size = (Integer) r[4];
            if (size == 1 && halfKm < 250) continue;
            if (size == 2 && halfKm < 120) continue;
            if (size == 3 && (halfKm < 40 || halfKm > 320)) continue;
            String s = (String) r[0];
            float x = sx((Double) r[2], z, ox, S), y = sy((Double) r[1], z, oy, S);
            float tw = rg.measureText(s);
            if (x < 30 * u || x > W - 30 * u || y < H * 0.25f || y > H * 0.93f) continue;
            if (Math.hypot(x - W / 2f, y - H / 2f) < 180 * u) continue;
            RectF b = new RectF(x - tw / 2, y - 26 * u, x + tw / 2, y + 8 * u);
            if (b.left < 8 * u || b.right > W - 8 * u || !free(used, b)) continue;
            used.add(b);
            cv.save(); cv.rotate(((Double) r[3]).floatValue(), x, y);
            cv.drawText(s, x - tw / 2, y, rg);
            cv.restore();
        }
    }

    // ---- river lines and names
    private static void riverLines(Canvas cv, int W, int H, int z, double ox, double oy, float S, boolean day, double halfKm, List<RectF> used, float u,
                                   boolean tags, List<String[]> pr) {
        load();
        Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
        ln.setStyle(Paint.Style.STROKE); ln.setStrokeWidth(2.2f * u); ln.setStrokeCap(Paint.Cap.ROUND); ln.setStrokeJoin(Paint.Join.ROUND);
        ln.setColor(day ? 0xD22B6AD0 : 0xD286C8FF);
        List<float[][]> lines = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<float[]> tr = traced;
        List<String> trn = tracedName;
        if (tags && tr != null && !tr.isEmpty() && trn != null && trn.size() == tr.size()) {
            for (int i = 0; i < tr.size(); i++) {
                float[] a = tr.get(i);
                float[] xs = new float[a.length / 2], ys = new float[a.length / 2];
                for (int k = 0; k < xs.length; k++) { xs[k] = a[2 * k]; ys[k] = a[2 * k + 1]; }
                lines.add(new float[][]{xs, ys}); names.add(trn.get(i));
            }
        } else {
            for (int i = 0; i < rPts.size(); i++) {
                float[] a = rPts.get(i);
                String nm = rName.get(i);
                boolean local = false;
                for (String q : LOCAL) if (q.equals(nm)) { local = true; break; }
                if (local && halfKm > 150) continue;
                float[] xs = new float[a.length / 2], ys = new float[a.length / 2];
                for (int k = 0; k < xs.length; k++) { xs[k] = sx(a[2 * k], z, ox, S); ys[k] = sy(a[2 * k + 1], z, oy, S); }
                lines.add(new float[][]{xs, ys}); names.add(nm);
            }
        }
        Map<String, Object[]> best = new HashMap<>();   // name -> {visible points, xs, ys} of its longest visible line
        for (int i = 0; i < lines.size(); i++) {
            float[] xs = lines.get(i)[0], ys = lines.get(i)[1];
            String nm = names.get(i);
            Path p = new Path();
            float minx = 1e9f, maxx = -1e9f, miny = 1e9f, maxy = -1e9f;
            int vis = 0;
            for (int k = 0; k < xs.length; k++) {
                if (k == 0) p.moveTo(xs[k], ys[k]); else p.lineTo(xs[k], ys[k]);
                minx = Math.min(minx, xs[k]); maxx = Math.max(maxx, xs[k]); miny = Math.min(miny, ys[k]); maxy = Math.max(maxy, ys[k]);
                if (xs[k] > 30 * u && xs[k] < W - 30 * u && ys[k] > H * 0.08f && ys[k] < H * 0.95f) vis++;
            }
            if (maxx < -50 || minx > W + 50 || maxy < -50 || miny > H + 50) continue;
            cv.drawPath(p, ln);
            if (!nm.isEmpty() && vis > 0) {
                Object[] cur = best.get(nm);
                if (cur == null || vis > (Integer) cur[0]) best.put(nm, new Object[]{vis, xs, ys});
            }
        }
        if (tags) {
            List<Map.Entry<String, Object[]>> es = new ArrayList<>(best.entrySet());
            java.util.Collections.sort(es, (a, b) -> Integer.compare((Integer) b.getValue()[0], (Integer) a.getValue()[0]));
            int n = 0;
            for (Map.Entry<String, Object[]> e : es) {
                if (n >= 8) break;
                String id = "R" + (n + 1);
                float[] xs = (float[]) e.getValue()[1], ys = (float[]) e.getValue()[2];
                List<Integer> vis = new ArrayList<>();
                for (int k = 0; k < xs.length; k++) if (xs[k] > 30 * u && xs[k] < W - 30 * u && ys[k] > H * 0.08f && ys[k] < H * 0.95f) vis.add(k);
                float[] fr = {0.5f, 0.4f, 0.6f, 0.3f, 0.7f, 0.2f, 0.8f, 0.1f, 0.9f, 0.05f, 0.95f};
                for (float f : fr) {
                    int k = vis.get(Math.min(vis.size() - 1, (int) (vis.size() * f)));
                    RectF r = tagRect(id, xs[k], ys[k], u);
                    if (r.left < 8 * u || r.right > W - 8 * u || !free(used, r)) continue;
                    used.add(r);
                    tag(cv, id, r, u, 0xEB1E5A8C);
                    pr.add(new String[]{id, e.getKey()});
                    n++;
                    break;
                }
            }
            return;
        }
        Paint t = text(19 * u, false, true, day ? 0xFF123A8C : 0xFFBEE1FF);
        Paint h = halo(t, 3f * u, day);
        for (Map.Entry<String, Object[]> e : best.entrySet()) {
            String s = e.getKey();
            float[] bxs = (float[]) e.getValue()[1], bys = (float[]) e.getValue()[2];
            int bk = 0, cnt = 0, want = (Integer) e.getValue()[0] / 2;
            for (int k = 0; k < bxs.length; k++) if (bxs[k] > 30 * u && bxs[k] < W - 30 * u && bys[k] > H * 0.08f && bys[k] < H * 0.95f) { if (cnt++ == want) { bk = k; break; } }
            float tw = t.measureText(s), x = bxs[bk], y = bys[bk];
            RectF r = new RectF(x - tw / 2 - 4 * u, y - 24 * u, x + tw / 2 + 4 * u, y + 2 * u);
            if (r.left < 8 * u || r.right > W - 8 * u || !free(used, r)) continue;
            used.add(r);
            cv.drawText(s, x - tw / 2, y - 6 * u, h); cv.drawText(s, x - tw / 2, y - 6 * u, t);
        }
    }

    private static final String[] LOCAL = {"Pavana", "Mula", "Mutha", "Mula-Mutha", "Indrayani"};

    // ---- national parks, wildlife sanctuaries, biosphere reserves
    private static void parkMarks(Canvas cv, int W, int H, int z, double ox, double oy, float S, boolean day, double halfKm, List<RectF> used, float u,
                                  double lat, double lon, boolean tags, List<String[]> pp) {
        if (tags) {
            List<Object[]> cand = new ArrayList<>();
            for (Object[] p : IndiaData.PARKS) {
                float x = sx((Double) p[3], z, ox, S), y = sy((Double) p[2], z, oy, S);
                if (x < 24 * u || x > W - 24 * u || y < 0 || y > H) continue;
                RectF m = new RectF(x - 11 * u, y - 11 * u, x + 11 * u, y + 11 * u);
                if (!free(used, m)) continue;
                used.add(m);
                cand.add(new Object[]{p, x, y, km(lat, lon, (Double) p[2], (Double) p[3])});
            }
            java.util.Collections.sort(cand, (a, b) -> Double.compare((Double) a[3], (Double) b[3]));
            int n = 0;
            for (Object[] c : cand) {
                Object[] p = (Object[]) c[0];
                float x = (Float) c[1], y = (Float) c[2];
                String k = (String) p[1];
                symbol(cv, k.equals("N") ? 2 : k.equals("W") ? 3 : 4, x, y, 8 * u, day);
                if (n >= 4) continue;
                String id = "P" + (n + 1);
                RectF r = tagRect(id, x + 14 * u + tagRect(id, 0, 0, u).width() / 2, y, u);
                if (r.right > W - 8 * u || !free(used, r)) continue;
                used.add(r);
                tag(cv, id, r, u, 0xEB3C6E14);
                pp.add(new String[]{id, p[0] + (k.equals("N") ? " NP" : k.equals("W") ? " WLS" : " BR"), Math.round((Double) c[3]) + " km"});
                n++;
            }
            return;
        }
        int maxRank = halfKm > 700 ? 1 : halfKm > 300 ? 2 : 3;
        Paint t = text(19 * u, false, true, day ? 0xFF14501E : 0xFFB4F0B4);
        Paint h = halo(t, 3f * u, day);
        for (int pass = 1; pass <= maxRank; pass++) for (Object[] p : IndiaData.PARKS) {
            if ((Integer) p[4] != pass) continue;
            float x = sx((Double) p[3], z, ox, S), y = sy((Double) p[2], z, oy, S);
            if (x < 24 * u || x > W - 24 * u || y < 0 || y > H) continue;
            RectF m = new RectF(x - 11 * u, y - 11 * u, x + 11 * u, y + 11 * u);
            if (!free(used, m)) continue;
            used.add(m);
            String k = (String) p[1];
            symbol(cv, k.equals("N") ? 2 : k.equals("W") ? 3 : 4, x, y, 8 * u, day);
            boolean label = pass == 1 ? halfKm <= 700 : pass == 2 ? halfKm <= 300 : halfKm <= 120;
            if (!label) continue;
            String s = p[0] + (k.equals("N") ? " NP" : k.equals("W") ? " WLS" : " BR");
            float tw = t.measureText(s);
            RectF r = new RectF(x + 12 * u, y - 15 * u, x + 16 * u + tw, y + 6 * u);
            if (r.right > W - 8 * u || !free(used, r)) continue;
            used.add(r);
            cv.drawText(s, x + 14 * u, y + 5 * u, h); cv.drawText(s, x + 14 * u, y + 5 * u, t);
        }
    }

    private static String damFacts(Object[] d, double la, double lo) {
        StringBuilder sb = new StringBuilder();
        if ((Double) d[4] > 0) sb.append(String.format(Locale.US, "~%s TMC", (Double) d[4] >= 10 ? String.valueOf(Math.round((Double) d[4])) : String.format(Locale.US, "%.1f", (Double) d[4])));
        if ((Integer) d[5] > 0) sb.append(sb.length() > 0 ? " \u00b7 " : "").append(d[5]).append(" m");
        if (!((String) d[6]).isEmpty()) sb.append(sb.length() > 0 ? " \u00b7 " : "").append(d[6]);
        if ((Integer) d[7] > 0) sb.append(sb.length() > 0 ? " \u00b7 " : "").append(d[7]);
        try {
            List<DamAll.Hit> hs = DamAll.nearest(la, lo, 1, 3.0, false);
            if (!hs.isEmpty() && hs.get(0).fresh()) sb.append(sb.length() > 0 ? " \u00b7 " : "").append(Math.round(hs.get(0).pct)).append("%");
        } catch (Throwable ignored) { }
        return sb.toString();
    }

    // ---- major dams: numbered markers near you (the list panel has the facts); name labels on wide maps
    private static void damMarks(Canvas cv, int W, int H, int z, double ox, double oy, float S, boolean day, double halfKm, List<RectF> used, float u,
                                 double lat, double lon, boolean tags, List<String[]> pd) {
        if (tags) {
            List<Object[]> cand = new ArrayList<>();
            for (Object[] d : IndiaData.DAMS) {
                double la = (Double) d[2], lo = (Double) d[3];
                float x = sx(lo, z, ox, S), y = sy(la, z, oy, S);
                if (x < 24 * u || x > W - 24 * u || y < 0 || y > H) continue;
                RectF m = new RectF(x - 11 * u, y - 11 * u, x + 11 * u, y + 11 * u);
                if (!free(used, m)) continue;
                used.add(m);
                cand.add(new Object[]{d, x, y, km(lat, lon, la, lo)});
            }
            java.util.Collections.sort(cand, (a, b) -> Double.compare((Double) a[3], (Double) b[3]));
            int n = 0;
            for (Object[] c : cand) {
                Object[] d = (Object[]) c[0];
                float x = (Float) c[1], y = (Float) c[2];
                symbol(cv, 1, x, y, 7 * u, day);
                if (n >= 8) continue;
                String id = "D" + (n + 1);
                RectF r = tagRect(id, x + 14 * u + tagRect(id, 0, 0, u).width() / 2, y, u);
                if (r.right > W - 8 * u || !free(used, r)) continue;
                used.add(r);
                tag(cv, id, r, u, 0xEB1E46A0);
                String nm = (String) d[0];
                String l1 = nm.contains("Sagar") || "barrage".equals(d[6]) ? nm : nm + " Dam";
                String river = (String) d[1];
                if (!river.isEmpty()) l1 = l1 + " \u00b7 " + river + (river.endsWith("R.") ? "" : " R.");
                String facts = damFacts(d, (Double) d[2], (Double) d[3]);
                pd.add(new String[]{id, l1, facts + (facts.isEmpty() ? "" : " \u00b7 ") + Math.round((Double) c[3]) + " km"});
                n++;
            }
            return;
        }
        int maxRank = halfKm > 700 ? 1 : halfKm > 250 ? 2 : 3;
        Paint t1 = text(21 * u, true, false, day ? 0xFF0A1E50 : 0xFFFFFFFF);
        Paint h1 = halo(t1, 3.4f * u, day);
        for (int pass = 1; pass <= maxRank; pass++) for (Object[] d : IndiaData.DAMS) {
            if ((Integer) d[8] != pass) continue;
            double la = (Double) d[2], lo = (Double) d[3];
            float x = sx(lo, z, ox, S), y = sy(la, z, oy, S);
            if (x < 24 * u || x > W - 24 * u || y < 0 || y > H) continue;
            String nm = (String) d[0];
            String l1 = nm.contains("Sagar") || "barrage".equals(d[6]) ? nm : nm + " Dam";
            float w = t1.measureText(l1);
            RectF r = new RectF(x - 11 * u, y - 24 * u, x + 18 * u + w, y + 8 * u);
            if (r.right > W - 8 * u || !free(used, r)) {
                r = new RectF(x - 18 * u - w, y - 24 * u, x + 11 * u, y + 8 * u);
                if (r.left < 8 * u || !free(used, r)) continue;
                used.add(r);
                symbol(cv, 1, x, y, 7 * u, day);
                float lx = x - 16 * u - w;
                cv.drawText(l1, lx, y - 6 * u, h1); cv.drawText(l1, lx, y - 6 * u, t1);
                continue;
            }
            used.add(r);
            symbol(cv, 1, x, y, 7 * u, day);
            cv.drawText(l1, x + 16 * u, y - 6 * u, h1); cv.drawText(l1, x + 16 * u, y - 6 * u, t1);
        }
    }

    // ---- well known peaks across India (wide maps only; the Maharashtra ones are drawn by RegionNames)
    private static void peakMarks(Canvas cv, int W, int H, int z, double ox, double oy, float S, boolean day, List<RectF> used, float u) {
        Paint t = text(20 * u, false, false, day ? 0xFF46280F : 0xFFFFFFFF);
        Paint h = halo(t, 3.2f * u, day);
        Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG);
        ip.setColor(day ? 0xFF46280F : 0xFFFFFFFF);
        int n = 0;
        for (Object[] p : IndiaData.PEAKS) {
            float x = sx((Double) p[2], z, ox, S), y = sy((Double) p[1], z, oy, S);
            String s = p[0] + " " + p[3] + " m";
            float tw = t.measureText(s);
            RectF r = new RectF(x - 9 * u, y - 14 * u, x + 16 * u + tw, y + 8 * u);
            if (r.left < 8 * u || r.right > W - 8 * u || r.top < 0 || r.bottom > H || !free(used, r)) continue;
            used.add(r);
            Path tri = new Path();
            tri.moveTo(x, y - 8 * u); tri.lineTo(x + 7 * u, y + 5 * u); tri.lineTo(x - 7 * u, y + 5 * u); tri.close();
            cv.drawPath(tri, ip);
            cv.drawText(s, x + 14 * u, y + 6 * u, h); cv.drawText(s, x + 14 * u, y + 6 * u, t);
            if (++n >= 8) break;
        }
    }
}
