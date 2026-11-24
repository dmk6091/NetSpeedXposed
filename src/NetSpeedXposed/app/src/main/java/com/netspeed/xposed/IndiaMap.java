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

    static void draw(Canvas cv, int W, int H, double lat, double lon, int z, double ox, double oy, float S, boolean day, double halfKm,
                     boolean rivers, boolean dams, boolean parks, boolean phys, List<RectF> used) {
        final float u = W / 1080f;
        if (used == null) {
            used = new ArrayList<>();
            used.add(new RectF(0, 0, W, H * 0.22f));
            used.add(new RectF(0, H * 0.68f, W, H));
            used.add(new RectF(W * 0.02f, H * 0.165f, W * 0.66f, legendBottom(W, H)));
            used.add(new RectF(W / 2f - 100 * u, H * 0.5f - 100 * u, W / 2f + 100 * u, H * 0.5f + 100 * u));
        }
        if (phys) try { physical(cv, W, H, z, ox, oy, S, day, halfKm, used, u); } catch (Throwable ignored) { }
        if (rivers) try { riverLines(cv, W, H, z, ox, oy, S, day, halfKm, used, u); } catch (Throwable ignored) { }
        if (parks) try { parkMarks(cv, W, H, z, ox, oy, S, day, halfKm, used, u); } catch (Throwable ignored) { }
        if (dams) try { damMarks(cv, W, H, z, ox, oy, S, day, halfKm, used, u); } catch (Throwable ignored) { }
        if (phys && halfKm >= 150) try { peakMarks(cv, W, H, z, ox, oy, S, day, used, u); } catch (Throwable ignored) { }
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
            if (x < 30 * u || x > W - 30 * u || y < H * 0.25f || y > H * 0.62f) continue;
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
    private static void riverLines(Canvas cv, int W, int H, int z, double ox, double oy, float S, boolean day, double halfKm, List<RectF> used, float u) {
        load();
        Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
        ln.setStyle(Paint.Style.STROKE); ln.setStrokeWidth(2.2f * u); ln.setStrokeCap(Paint.Cap.ROUND); ln.setStrokeJoin(Paint.Join.ROUND);
        ln.setColor(day ? 0xD22B6AD0 : 0xD286C8FF);
        Map<String, float[]> best = new HashMap<>();   // name -> {visible points, x, y}
        for (int i = 0; i < rPts.size(); i++) {
            float[] a = rPts.get(i);
            String nm = rName.get(i);
            boolean local = false;
            for (String q : LOCAL) if (q.equals(nm)) { local = true; break; }
            if (local && halfKm > 150) continue;
            Path p = new Path();
            float minx = 1e9f, maxx = -1e9f, miny = 1e9f, maxy = -1e9f;
            int vis = 0, mid = -1;
            float[] xs = new float[a.length / 2], ys = new float[a.length / 2];
            for (int k = 0; k < a.length / 2; k++) {
                float x = sx(a[2 * k], z, ox, S), y = sy(a[2 * k + 1], z, oy, S);
                xs[k] = x; ys[k] = y;
                if (k == 0) p.moveTo(x, y); else p.lineTo(x, y);
                minx = Math.min(minx, x); maxx = Math.max(maxx, x); miny = Math.min(miny, y); maxy = Math.max(maxy, y);
                if (x > 30 * u && x < W - 30 * u && y > H * 0.26f && y < H * 0.62f) { vis++; }
            }
            if (maxx < -50 || minx > W + 50 || maxy < -50 || miny > H + 50) continue;
            cv.drawPath(p, ln);
            if (!nm.isEmpty() && vis > 0) {
                int seen = 0, want = vis / 2;
                for (int k = 0; k < xs.length; k++) {
                    if (xs[k] > 30 * u && xs[k] < W - 30 * u && ys[k] > H * 0.26f && ys[k] < H * 0.62f) { if (seen++ == want) { mid = k; break; } }
                }
                float[] cur = best.get(nm);
                if (mid >= 0 && (cur == null || vis > cur[0])) best.put(nm, new float[]{vis, xs[mid], ys[mid]});
            }
        }
        Paint t = text(19 * u, false, true, day ? 0xFF123A8C : 0xFFBEE1FF);
        Paint h = halo(t, 3f * u, day);
        for (Map.Entry<String, float[]> e : best.entrySet()) {
            String s = e.getKey();
            float tw = t.measureText(s), x = e.getValue()[1], y = e.getValue()[2];
            RectF r = new RectF(x - tw / 2 - 4 * u, y - 24 * u, x + tw / 2 + 4 * u, y + 2 * u);
            if (r.left < 8 * u || r.right > W - 8 * u || !free(used, r)) continue;
            used.add(r);
            cv.drawText(s, x - tw / 2, y - 6 * u, h); cv.drawText(s, x - tw / 2, y - 6 * u, t);
        }
    }

    private static final String[] LOCAL = {"Pavana", "Mula", "Mutha", "Mula-Mutha", "Indrayani"};

    // ---- national parks, wildlife sanctuaries, biosphere reserves
    private static void parkMarks(Canvas cv, int W, int H, int z, double ox, double oy, float S, boolean day, double halfKm, List<RectF> used, float u) {
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

    // ---- major dams: name and river, then capacity, height, type, year (and the live level when the app has one)
    private static void damMarks(Canvas cv, int W, int H, int z, double ox, double oy, float S, boolean day, double halfKm, List<RectF> used, float u) {
        int maxRank = halfKm > 700 ? 1 : halfKm > 250 ? 2 : 3;
        boolean two = halfKm <= 150;
        Paint t1 = text(21 * u, true, false, day ? 0xFF0A1E50 : 0xFFFFFFFF);
        Paint h1 = halo(t1, 3.4f * u, day);
        Paint t2 = text(17 * u, false, false, day ? 0xFF1E3C78 : 0xFFBEDCFF);
        Paint h2 = halo(t2, 3f * u, day);
        for (int pass = 1; pass <= maxRank; pass++) for (Object[] d : IndiaData.DAMS) {
            if ((Integer) d[8] != pass) continue;
            double la = (Double) d[2], lo = (Double) d[3];
            float x = sx(lo, z, ox, S), y = sy(la, z, oy, S);
            if (x < 24 * u || x > W - 24 * u || y < 0 || y > H) continue;
            String nm = (String) d[0];
            String l1 = nm.contains("Sagar") || "barrage".equals(d[6]) ? nm : nm + " Dam";
            String river = (String) d[1];
            String l2 = null;
            if (two) {
                StringBuilder sb = new StringBuilder();
                if ((Double) d[4] > 0) sb.append(String.format(Locale.US, "~%s TMC", (Double) d[4] >= 10 ? String.valueOf(Math.round((Double) d[4])) : String.format(Locale.US, "%.1f", (Double) d[4])));
                if ((Integer) d[5] > 0) sb.append(sb.length() > 0 ? " · " : "").append(d[5]).append(" m");
                if (!((String) d[6]).isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(d[6]);
                if ((Integer) d[7] > 0) sb.append(sb.length() > 0 ? " · " : "").append(d[7]);
                try {
                    List<DamAll.Hit> hs = DamAll.nearest(la, lo, 1, 3.0, false);
                    if (!hs.isEmpty() && hs.get(0).fresh()) sb.append(sb.length() > 0 ? " · " : "").append(Math.round(hs.get(0).pct)).append("%");
                } catch (Throwable ignored) { }
                if (sb.length() > 0) l2 = sb.toString();
                if (!river.isEmpty()) l1 = l1 + " · " + river + (river.endsWith("R.") ? "" : " R.");
            }
            float w1 = t1.measureText(l1), w2 = l2 == null ? 0 : t2.measureText(l2);
            float w = Math.max(w1, w2);
            RectF r = new RectF(x - 11 * u, y - 24 * u, x + 18 * u + w, y + (l2 == null ? 8 : 26) * u);
            if (r.right > W - 8 * u || !free(used, r)) {
                // no room to the right: try the left
                r = new RectF(x - 18 * u - w, y - 24 * u, x + 11 * u, y + (l2 == null ? 8 : 26) * u);
                if (r.left < 8 * u || !free(used, r)) continue;
                used.add(r);
                symbol(cv, 1, x, y, 7 * u, day);
                float lx = x - 16 * u - w1;
                cv.drawText(l1, lx, y - 6 * u, h1); cv.drawText(l1, lx, y - 6 * u, t1);
                if (l2 != null) { float l2x = x - 16 * u - w2; cv.drawText(l2, l2x, y + 16 * u, h2); cv.drawText(l2, l2x, y + 16 * u, t2); }
                continue;
            }
            used.add(r);
            symbol(cv, 1, x, y, 7 * u, day);
            cv.drawText(l1, x + 16 * u, y - 6 * u, h1); cv.drawText(l1, x + 16 * u, y - 6 * u, t1);
            if (l2 != null) { cv.drawText(l2, x + 16 * u, y + 16 * u, h2); cv.drawText(l2, x + 16 * u, y + 16 * u, t2); }
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
