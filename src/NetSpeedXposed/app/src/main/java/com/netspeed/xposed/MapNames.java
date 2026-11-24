package com.netspeed.xposed;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Names written on the terrain wallpaper in a paper topo-map style: peaks with height, dams with level, lakes, wetlands,
 * passes, ridges, valleys, waterfalls, springs, caves, protected forests, rivers with flow arrows, streams and faults.
 */
final class MapNames {
    private MapNames() { }

    /** True while the "Retry names" button runs: wait much longer for the map servers. */
    static volatile boolean patient;

    private static final int DAM = 0, PEAK = 1, WET = 2, FAULT = 3, WATER = 4, RIVER = 5, PASS = 6, FEAT = 7, RESERVE = 8, STREAM = 9, KINDS = 10;

    private static final class L {
        int kind;
        String text;
        double lat, lon, rank;
        float x, y;
        int sub;            // feature kind: 1 cave, 2 spring, 3 waterfall
        boolean fixed;      // x, y already on screen (peaks found in the elevation data)
        double[] flow;      // downstream direction as {dLat, dLon} for rivers and streams
    }

    static void draw(Context c, Canvas cv, int W, int H, double lat, double lon, int z, double ox, double oy, float S, boolean day, double halfKm) {
        List<L> all = new ArrayList<>();
        try { dams(all, lat, lon, halfKm); } catch (Throwable t) { WpStatus.note("dam names skipped: " + t.getClass().getSimpleName()); }
        try { faults(all, lat, lon, halfKm); } catch (Throwable ignored) { }
        for (Object[] g : GHATS) {   // mountain passes (ghats) from the built-in list
            L l = new L();
            l.kind = PASS; l.lat = (Double) g[1]; l.lon = (Double) g[2]; l.rank = 0; l.text = (String) g[0];
            all.add(l);
        }
        if (halfKm <= 60) try { osm(c, all, lat, lon, halfKm, z); } catch (Throwable t) {
            WpStatus.note("peaks, lakes, wetland, river and forest names skipped: " + (String.valueOf(t.getMessage()).contains("busy") ? "OpenStreetMap servers busy" : t.getClass().getSimpleName()));
        }
        for (L l : all) {
            if (l.fixed) continue;
            l.x = (float) ((WallpaperMaker.worldX(l.lon, z) - ox) * S);
            l.y = (float) ((WallpaperMaker.worldY(l.lat, z) - oy) * S);
        }
        // hills found in the elevation data: heights without names, left out where a named peak is already close
        List<float[]> dp = demPeaks;
        List<Object[]> late = new ArrayList<>();
        lateHills = late;
        if (dp != null) {
            double mppD = 156543.03 * Math.cos(Math.toRadians(lat)) / (1 << z);
            float near = (float) (1500.0 / mppD * S);
            float near3 = (float) (3000.0 / mppD * S);
            // spots already named by the built-in lists (RegionNames and IndiaData draw those themselves)
            List<float[]> known = new ArrayList<>();
            for (Object[] k : RegionNames.PEAKS) known.add(new float[]{(float) ((WallpaperMaker.worldX((Double) k[2], z) - ox) * S), (float) ((WallpaperMaker.worldY((Double) k[1], z) - oy) * S)});
            for (Object[] k : IndiaData.PEAKS) known.add(new float[]{(float) ((WallpaperMaker.worldX((Double) k[2], z) - ox) * S), (float) ((WallpaperMaker.worldY((Double) k[1], z) - oy) * S)});
            String[] nameOf = new String[dp.size()];
            for (Object[] h : HILLS) {
                float hx = (float) ((WallpaperMaker.worldX((Double) h[2], z) - ox) * S), hy = (float) ((WallpaperMaker.worldY((Double) h[1], z) - oy) * S);
                int bi = -1; double bdist = near3;
                for (int i = 0; i < dp.size(); i++) {
                    double d = Math.hypot(dp.get(i)[0] - hx, dp.get(i)[1] - hy);
                    if (d < bdist && nameOf[i] == null) { bdist = d; bi = i; }
                }
                if (bi >= 0) nameOf[bi] = (String) h[0];
            }
            for (int i = 0; i < dp.size(); i++) {
                float[] q = dp.get(i);
                boolean dup = false;
                for (L o : all) if (o.kind == PEAK && Math.hypot(o.x - q[0], o.y - q[1]) < near) { dup = true; break; }
                for (float[] k : known) if (Math.hypot(k[0] - q[0], k[1] - q[1]) < near3) { dup = true; break; }
                if (dup) continue;
                if (nameOf[i] == null) { late.add(new Object[]{q[0], q[1], Math.round(q[2]) + " m"}); continue; }   // unnamed hills are drawn last, after the dam and river tags
                L l = new L();
                l.kind = PEAK; l.fixed = true; l.x = q[0]; l.y = q[1]; l.lat = lat; l.lon = lon;
                l.rank = nameOf[i] != null ? q[2] : q[2] - 5000;   // named hills first, then the unnamed ones
                l.text = (nameOf[i] != null ? nameOf[i] + " " : "") + Math.round(q[2]) + " m";
                all.add(l);
            }
            Collections.sort(all, (a, b) -> a.kind != b.kind ? Integer.compare(prio(a.kind), prio(b.kind)) : Double.compare(b.rank, a.rank));
        }
        Collections.sort(all, (a, b) -> a.kind != b.kind ? Integer.compare(prio(a.kind), prio(b.kind)) : Double.compare(b.rank, a.rank));

        final float u = W / 1080f;
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTextSize(17 * u);
        Paint halo = new Paint(t);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(3.2f * u);
        halo.setStrokeJoin(Paint.Join.ROUND);
        halo.setColor(day ? 0xD2FFFFFF : 0xDC000000);
        Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG);

        List<RectF> used = new ArrayList<>();
        used.add(new RectF(0, 0, W, H * 0.22f));                           // clock and status bar
        used.add(new RectF(0, H * 0.955f, W, H));                          // credit line only: names may sit under icons and dock
        IndiaMap.reserve(used, W, H); // legend
        // the big faint region names (SAHYADRI ...) are written afterwards: keep their room free
        for (Object[] rg : RegionNames.REGIONS) {
            float rx = (float) ((WallpaperMaker.worldX((Double) rg[2], z) - ox) * S), ry = (float) ((WallpaperMaker.worldY((Double) rg[1], z) - oy) * S);
            double th = Math.toRadians((Double) rg[3]);
            float hw = ((String) rg[0]).length() * 30 * u * 0.55f, hh = 20 * u;
            float ex = (float) (Math.abs(Math.cos(th)) * hw + Math.abs(Math.sin(th)) * hh), ey = (float) (Math.abs(Math.sin(th)) * hw + Math.abs(Math.cos(th)) * hh);
            if (rx + ex < 0 || rx - ex > W || ry + ey < 0 || ry - ey > H) continue;
            used.add(new RectF(rx - ex, ry - ey, rx + ex, ry + ey));
        }
        sharedUsed = used;
        used.add(new RectF(W / 2f - 100 * u, H * 0.5f - 100 * u, W / 2f + 100 * u, H * 0.5f + 100 * u));   // you
        int[] cap = {5, 8, 4, halfKm > 60 ? 6 : 3, 3, 2, 4, 5, 2, 3};
        int[][] got = new int[3][KINDS];   // each third of the screen gets its own share of names
        int[] total = new int[3];
        for (L l : all) {
            int bd = l.y < H * 0.42f ? 0 : l.y < H * 0.68f ? 1 : 2;
            if (total[bd] >= 18 || got[bd][l.kind] >= cap[l.kind] || l.text == null || l.text.isEmpty()) continue;
            boolean italic = l.kind == WATER || l.kind == RIVER || l.kind == STREAM || l.kind == RESERVE || l.kind == FAULT;
            t.setTypeface(Typeface.create(Typeface.DEFAULT, italic ? Typeface.ITALIC : Typeface.NORMAL));
            halo.setTypeface(t.getTypeface());
            float tw = t.measureText(l.text);
            boolean sym = l.kind == DAM || l.kind == PEAK || l.kind == WET || l.kind == PASS || l.kind == FEAT || l.kind == WATER;
            float ic = sym ? 13 * u : 0;
            // the real spot is off the picture (more than a screen away): pin it to the edge with an arrow, its distance and direction
            boolean off = l.x < 14 * u || l.x > W - 14 * u, rightPin = false;
            String text = l.text;
            if (off) {
                if (!sym) continue;   // rivers, faults, forests: only shown where they really are
                rightPin = l.x > W / 2f;
                double dyk = (l.lat - lat) * 110.57, dxk = (l.lon - lon) * 111.32 * Math.cos(Math.toRadians(lat));
                int di = (int) Math.round(Math.toDegrees(Math.atan2(dxk, dyk)) / 45.0); di = ((di % 8) + 8) % 8;
                text = l.text + " \u00b7 " + Math.round(Math.hypot(dxk, dyk)) + " km " + new String[]{"N", "NE", "E", "SE", "S", "SW", "W", "NW"}[di];
                tw = t.measureText(text);
                l.x = rightPin ? W - 34 * u : 34 * u;
            }
            float left = sym ? (rightPin ? l.x - ic - tw : l.x + ic) : l.x - tw / 2, right = left + tw;
            float top = l.y - 12 * u, bottom = l.y + 8 * u;
            if (!off) {
                if (right > W - 20 * u) { float s = right - (W - 20 * u); left -= s; right -= s; }
                if (left < 20 * u) { float s = 20 * u - left; left += s; right += s; }
            }
            RectF r = new RectF(left - (sym && !rightPin ? ic + 4 * u : 4 * u), top - 12 * u * (l.flow != null ? 1 : 0) - 4 * u, right + (rightPin ? ic + 4 * u : 4 * u), bottom + 4 * u);
            boolean ok = r.top > 0 && r.bottom < H;
            for (RectF o : used) if (ok && RectF.intersects(o, r)) ok = false;
            if (!ok) continue;
            used.add(r);
            got[bd][l.kind]++;
            total[bd]++;
            float by = l.y + 6 * u;
            int ink;
            switch (l.kind) {
                case DAM: {   // a wall holding blue water behind it
                    ip.setStyle(Paint.Style.FILL); ip.setColor(0xFF4A8FD6);
                    cv.drawRect(l.x - 9 * u, l.y - 6 * u, l.x + 1 * u, l.y + 5 * u, ip);
                    ip.setColor(0xFFB9B9B9);
                    cv.drawRect(l.x + 1 * u, l.y - 8 * u, l.x + 6.5f * u, l.y + 6 * u, ip);
                    ip.setStyle(Paint.Style.STROKE); ip.setStrokeWidth(1.3f * u); ip.setColor(0xFF3C3C3C);
                    cv.drawRect(l.x + 1 * u, l.y - 8 * u, l.x + 6.5f * u, l.y + 6 * u, ip);
                    ink = day ? 0xFF32230A : 0xFFFFEB8C;
                    break;
                }
                case PEAK: {
                    Path p = new Path();
                    p.moveTo(l.x, l.y - 7 * u); p.lineTo(l.x + 6.5f * u, l.y + 5 * u); p.lineTo(l.x - 6.5f * u, l.y + 5 * u); p.close();
                    ip.setStyle(Paint.Style.FILL); ip.setColor(day ? 0xFF46280F : 0xFFFFFFFF);
                    cv.drawPath(p, ip);
                    ink = day ? 0xFF46280F : 0xFFFFFFFF;
                    break;
                }
                case WET:
                    ip.setStyle(Paint.Style.FILL); ip.setColor(0xFF14968A);
                    cv.drawCircle(l.x, l.y, 5 * u, ip);
                    ink = day ? 0xFF0A5A50 : 0xFF96FFEB;
                    break;
                case PASS: {   // saddle: two slopes meeting in a dip
                    Path p = new Path();
                    p.moveTo(l.x - 7 * u, l.y - 4 * u); p.lineTo(l.x, l.y + 3 * u); p.lineTo(l.x + 7 * u, l.y - 4 * u);
                    ip.setStyle(Paint.Style.STROKE); ip.setStrokeWidth(2f * u); ip.setColor(day ? 0xFF46280F : 0xFFFFFFFF);
                    cv.drawPath(p, ip);
                    ink = day ? 0xFF46280F : 0xFFFFFFFF;
                    break;
                }
                case FEAT: {
                    ink = day ? 0xFF143C96 : 0xFFBEE1FF;
                    ip.setStyle(Paint.Style.FILL);
                    if (l.sub == 1) {   // cave: solid arch with the dark opening
                        ip.setColor(day ? 0xFF46280F : 0xFFFFFFFF);
                        cv.drawArc(new RectF(l.x - 8 * u, l.y - 9 * u, l.x + 8 * u, l.y + 7 * u), 180, 180, true, ip);
                        cv.drawRect(l.x - 8 * u, l.y - 1 * u, l.x + 8 * u, l.y + 6 * u, ip);
                        ip.setColor(0xFF141414);
                        cv.drawArc(new RectF(l.x - 3.5f * u, l.y - 4 * u, l.x + 3.5f * u, l.y + 4 * u), 180, 180, true, ip);
                        cv.drawRect(l.x - 3.5f * u, l.y, l.x + 3.5f * u, l.y + 6 * u, ip);
                        ink = day ? 0xFF46280F : 0xFFFFFFFF;
                    } else if (l.sub == 3) {   // waterfall: cliff, white water falling, pool below
                        ip.setColor(0xFF7A6450);
                        cv.drawRect(l.x - 8 * u, l.y - 8 * u, l.x - 3 * u, l.y + 4 * u, ip);
                        cv.drawRect(l.x + 3 * u, l.y - 8 * u, l.x + 8 * u, l.y + 4 * u, ip);
                        ip.setColor(0xFFE6F4FF);
                        cv.drawRect(l.x - 3 * u, l.y - 8 * u, l.x + 3 * u, l.y + 3 * u, ip);
                        ip.setColor(0xFF4A8FD6);
                        cv.drawOval(new RectF(l.x - 8 * u, l.y + 2 * u, l.x + 8 * u, l.y + 8 * u), ip);
                    } else {   // spring: water welling out of a rock, with a ripple
                        ip.setColor(0xFF7A6450);
                        cv.drawArc(new RectF(l.x - 8 * u, l.y - 4 * u, l.x + 2 * u, l.y + 8 * u), 180, 180, true, ip);
                        ip.setColor(0xFF4A8FD6);
                        cv.drawOval(new RectF(l.x - 1 * u, l.y + 0 * u, l.x + 9 * u, l.y + 6 * u), ip);
                        ip.setStyle(Paint.Style.STROKE); ip.setStrokeWidth(1.2f * u); ip.setColor(0xFF4A8FD6);
                        cv.drawCircle(l.x - 4 * u, l.y - 7 * u, 1.6f * u, ip);
                        cv.drawCircle(l.x - 1 * u, l.y - 10 * u, 1.2f * u, ip);
                    }
                    break;
                }
                case WATER: {   // lake / pond: blue water shape with a wavy line
                    ip.setStyle(Paint.Style.FILL); ip.setColor(0xFFAAD2F5);
                    cv.drawOval(new RectF(l.x - 9 * u, l.y - 6 * u, l.x + 9 * u, l.y + 6 * u), ip);
                    ip.setStyle(Paint.Style.STROKE); ip.setStrokeWidth(1.4f * u); ip.setColor(0xFF2870BE);
                    cv.drawOval(new RectF(l.x - 9 * u, l.y - 6 * u, l.x + 9 * u, l.y + 6 * u), ip);
                    Path wv = new Path();
                    wv.moveTo(l.x - 5 * u, l.y); wv.quadTo(l.x - 3 * u, l.y - 2.5f * u, l.x - 1 * u, l.y); wv.quadTo(l.x + 1 * u, l.y + 2.5f * u, l.x + 3 * u, l.y); wv.quadTo(l.x + 4.2f * u, l.y - 1.5f * u, l.x + 5.5f * u, l.y);
                    cv.drawPath(wv, ip);
                    ink = day ? 0xFF143C96 : 0xFFBEE1FF;
                    break;
                }
                case FAULT: ink = day ? 0xFF961414 : 0xFFFFAAA0; break;
                case RESERVE: ink = day ? 0xFF1E5A1E : 0xFFBEF5BE; break;
                default: ink = day ? 0xFF143C96 : 0xFFBEE1FF;
            }
            if (l.flow != null) {   // arrow showing which way the water runs
                double ang = Math.atan2(-l.flow[0] * 110.57, l.flow[1] * 111.32 * Math.cos(Math.toRadians(l.lat)));   // screen y grows southwards
                cv.save();
                cv.translate(l.x, l.y - 11 * u);
                cv.rotate((float) Math.toDegrees(ang));
                Path ar = new Path();
                ar.moveTo(-13 * u, 0); ar.lineTo(8 * u, 0);
                ip.setStyle(Paint.Style.STROKE); ip.setStrokeWidth(2f * u); ip.setColor(ink);
                cv.drawPath(ar, ip);
                Path hd = new Path();
                hd.moveTo(11 * u, 0); hd.lineTo(5 * u, -4 * u); hd.lineTo(5 * u, 4 * u); hd.close();
                ip.setStyle(Paint.Style.FILL);
                cv.drawPath(hd, ip);
                cv.restore();
            }
            if (off) {   // yellow arrow at the edge: the place lies further this way
                Path ar = new Path();
                float ex = rightPin ? W - 4 * u : 4 * u, dir = rightPin ? -1 : 1;
                ar.moveTo(ex, l.y); ar.lineTo(ex + dir * 11 * u, l.y - 7 * u); ar.lineTo(ex + dir * 11 * u, l.y + 7 * u); ar.close();
                ip.setStyle(Paint.Style.FILL); ip.setColor(0xFFFFE070);
                cv.drawPath(ar, ip);
            }
            t.setColor(ink);
            cv.drawText(text, left, by, halo);
            cv.drawText(text, left, by, t);
        }
    }

    private static int prio(int k) {
        switch (k) { case DAM: return 0; case PEAK: return 1; case FAULT: return 2; case WET: return 3; case FEAT: return 4; case PASS: return 5;
            case WATER: return 6; case RESERVE: return 7; case RIVER: return 8; default: return 9; }
    }

    /** The collision list of this class, handed on so the other label sources keep clear of what is already written. */
    static volatile List<RectF> sharedUsed;

    // well known hills and forts of the Pune - Raigad - Satara - Nashik region: {name, lat, lon}; used to name the hills found in the elevation data
    private static final Object[][] HILLS = {
            {"Visapur", 18.7131, 73.4887}, {"Korigad", 18.6975, 73.3850}, {"Sudhagad", 18.5400, 73.3000}, {"Mahabaleshwar Plateau", 17.9237, 73.6586},
            {"Chandan-Vandan", 17.9500, 73.8900}, {"Pandavgad", 17.9361, 73.7822}, {"Malhargad", 18.2840, 74.0020}, {"Karnala", 18.8890, 73.1280},
            {"Prabalgad", 18.9970, 73.2090}, {"Naneghat", 19.2900, 73.7400}, {"Ratangad", 19.3780, 73.7130}, {"Sajjangad", 17.6580, 73.9000},
            {"Vishalgad", 16.8960, 73.7120}, {"Vetal Tekdi", 18.5160, 73.8350}, {"Parvati", 18.4950, 73.8450}, {"Baner Hill", 18.5700, 73.7800},
            {"Kamalgad", 17.9833, 73.7300}, {"Raireshwar", 18.0800, 73.6000}, {"Vasota", 17.5700, 73.7700}, {"Ajinkyatara", 17.6800, 73.9900},
            {"Tung", 18.6650, 73.5300}, {"Tailbaila", 18.7300, 73.4600}, {"Ghangad", 18.6260, 73.4000}, {"Dhak Bahiri", 18.7500, 73.4300},
            {"Kurdugad", 18.5500, 73.6000}, {"Kenjalgad", 17.9970, 73.6200}, {"Chandragad", 17.9900, 73.5600}, {"Makrandgad", 17.9600, 73.5700},
            {"Mangalgad", 18.0300, 73.3900}, {"Birwadi", 18.2300, 73.3300}, {"Songad", 18.1800, 73.4300}, {"Rohida", 18.1000, 73.7900},
            {"Rajmachi", 18.8200, 73.3950}, {"Peth Fort", 18.8120, 73.4310}, {"Sarasgad", 18.5400, 73.3200}, {"Tamhini Peak", 18.4300, 73.4400},
            {"Kataldhar", 18.3600, 73.5700}, {"Chandar", 18.1900, 73.5200}, {"Singhgad Ridge", 18.3300, 73.7400}, {"Kondhana", 18.3664, 73.7558},
            {"Sinhagad West", 18.3600, 73.7300}, {"Gunjavane", 18.3300, 73.6700}, {"Rajgad East", 18.2500, 73.7200}, {"Torna South", 18.2500, 73.6200},
            {"Kolhapur Hill", 18.2000, 73.9000}, {"Bhairavgad", 18.4200, 73.3800}, {"Jivdhan", 19.2600, 73.7900}, {"Dhodap", 20.3300, 74.0900},
    };

    static volatile List<Object[]> lateHills;   // {x, y, text}

    /** Hills with a height only: written after everything else, in the room that is left, at most 8 per third of the screen. */
    static void drawLate(Canvas cv, int W, int H, boolean day, List<RectF> used) {
        List<Object[]> hl = lateHills;
        if (hl == null || used == null) return;
        float u = W / 1080f;
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTextSize(17 * u); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); t.setColor(day ? 0xFF46280F : 0xFFFFFFFF);
        Paint h = new Paint(t);
        h.setStyle(Paint.Style.STROKE); h.setStrokeWidth(3.2f * u); h.setStrokeJoin(Paint.Join.ROUND); h.setColor(day ? 0xD2FFFFFF : 0xDC000000);
        Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG); ip.setColor(t.getColor());
        int[] n = new int[3];
        for (Object[] q : hl) {
            float x = (Float) q[0], y = (Float) q[1];
            String s = (String) q[2];
            int bd = y < H * 0.42f ? 0 : y < H * 0.68f ? 1 : 2;
            if (n[bd] >= 8) continue;
            float tw = t.measureText(s);
            RectF r = new RectF(x - 9 * u, y - 14 * u, x + 16 * u + tw, y + 8 * u);
            if (r.left < 8 * u || r.right > W - 8 * u || r.top < 0 || r.bottom > H) continue;
            boolean ok = true;
            for (RectF o : used) if (RectF.intersects(o, r)) { ok = false; break; }
            if (!ok) continue;
            used.add(r);
            n[bd]++;
            Path p = new Path();
            p.moveTo(x, y - 7 * u); p.lineTo(x + 6.5f * u, y + 5 * u); p.lineTo(x - 6.5f * u, y + 5 * u); p.close();
            cv.drawPath(p, ip);
            cv.drawText(s, x + 13 * u, y + 6 * u, h); cv.drawText(s, x + 13 * u, y + 6 * u, t);
        }
    }

    private static final Object[][] GHATS = {
            {"Bor Ghat", 18.7550, 73.3700}, {"Malshej Ghat", 19.3400, 73.7800}, {"Kasara Ghat", 19.6500, 73.4700}, {"Tamhini Ghat", 18.4800, 73.4300},
            {"Varandha Ghat", 18.0900, 73.4700}, {"Katraj Ghat", 18.4500, 73.8600}, {"Dive Ghat", 18.3900, 73.9900}, {"Khambatki Ghat", 17.9500, 73.9800},
            {"Kumbharli Ghat", 17.5700, 73.7500}, {"Amba Ghat", 16.9500, 73.7700}, {"Mahabaleshwar Ghat", 17.9500, 73.5500}, {"Pasarni Ghat", 17.9300, 73.7200},
    };

    static volatile List<float[]> demPeaks;   // screen x, y and height

    /** Hills from the elevation grid: the highest cell of a neighbourhood that stands at least 90 m above the low ground around it. */
    static void findPeaks(float[] el, float[] e2, int gw, int gh, float S, double offX, double offY, double mpp) {
        demPeaks = null;
        final int B = 8, bw = gw / B, bh = gh / B;
        if (bw < 6 || bh < 6) return;
        float[] mx = new float[bw * bh], mn = new float[bw * bh];
        int[] mi = new int[bw * bh];
        for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
            float hi = -1e9f, lo = 1e9f; int hiI = 0;
            for (int y = by * B; y < by * B + B; y++) for (int x = bx * B; x < bx * B + B; x++) {
                float v = e2[y * gw + x];
                if (v > hi) { hi = v; hiI = y * gw + x; }
                if (v < lo) lo = v;
            }
            mx[by * bw + bx] = hi; mn[by * bw + bx] = lo; mi[by * bw + bx] = hiI;
        }
        double cellKm = B * mpp / 1000.0;
        int r1 = (int) Math.max(2, Math.round(1.6 / cellKm)), r2 = (int) Math.max(r1 + 2, Math.round(4.0 / cellKm));
        List<float[]> out = new ArrayList<>();
        for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
            int c = by * bw + bx;
            float v = mx[c];
            boolean top = true;
            for (int y = Math.max(0, by - r1); y <= Math.min(bh - 1, by + r1) && top; y++) for (int x = Math.max(0, bx - r1); x <= Math.min(bw - 1, bx + r1); x++) {
                int o = y * bw + x;
                if (o != c && (mx[o] > v || (mx[o] == v && o < c))) { top = false; break; }
            }
            if (!top) continue;
            float low = 1e9f;
            for (int y = Math.max(0, by - r2); y <= Math.min(bh - 1, by + r2); y++) for (int x = Math.max(0, bx - r2); x <= Math.min(bw - 1, bx + r2); x++) low = Math.min(low, mn[y * bw + x]);
            if (v - low < 45f) continue;
            int gi = mi[c], gx = gi % gw, gy = gi / gw;
            float h = v;
            for (int y = Math.max(0, gy - 4); y <= Math.min(gh - 1, gy + 4); y++) for (int x = Math.max(0, gx - 4); x <= Math.min(gw - 1, gx + 4); x++) h = Math.max(h, el[y * gw + x]);
            out.add(new float[]{(float) (offX + (gx + 0.5f) * S), (float) (offY + (gy + 0.5f) * S), h});
        }
        Collections.sort(out, (a, b) -> Float.compare(b[2], a[2]));
        demPeaks = out.size() > 90 ? new ArrayList<>(out.subList(0, 90)) : out;
    }

    private static String clip(String s) { return s.length() > 26 ? s.substring(0, 25) + "…" : s; }

    private static void dams(List<L> out, double lat, double lon, double halfKm) {
        for (DamAll.Hit h : DamAll.nearest(lat, lon, 30, halfKm * 1.2, false)) {
            if (IndiaMap.dedupe && IndiaMap.majorNear(h.lat, h.lon)) continue;   // the India layer names the big ones with their facts
            L l = new L();
            l.kind = DAM; l.lat = h.lat; l.lon = h.lon; l.rank = -h.km;
            String n = h.name.replaceAll("(?i)\\s+(dam|barrage|reservoir)$", "").trim();
            l.text = clip(n + " Dam") + (h.fresh() ? " " + Math.round(h.pct) + "%" : "");
            out.add(l);
        }
    }

    private static void faults(List<L> out, double lat, double lon, double halfKm) {
        for (Object[] f : Faults.namesIn(lat, lon, halfKm)) {
            L l = new L();
            l.kind = FAULT; l.text = clip(f[0] + " fault"); l.lat = (Double) f[1]; l.lon = (Double) f[2]; l.rank = 1;
            out.add(l);
        }
    }

    private static void osm(Context c, List<L> out, double lat, double lon, double halfKm, int z) throws Exception {
        double dLat = halfKm / 110.57, dLon = halfKm / (111.32 * Math.cos(Math.toRadians(lat)));
        String bb = String.format(Locale.US, "(%.4f,%.4f,%.4f,%.4f)", lat - dLat, lon - dLon, lat + dLat, lon + dLon);
        String key = String.format(Locale.US, "v3_%.2f_%.2f_%d", lat, lon, z);
        File f = new File(c.getCacheDir(), "mapnames_" + key + ".json");
        String js = null;
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 24 * 3600_000L) {
            try { js = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"); } catch (Throwable ignored) { }
        }
        if (js == null) {
            String q = "[out:json][timeout:40];(node[\"natural\"=\"peak\"][\"name\"]" + bb
                    + ";nwr[\"natural\"=\"wetland\"][\"name\"]" + bb
                    + ";nwr[\"natural\"=\"water\"][\"name\"]" + bb
                    + ";nwr[\"waterway\"=\"dam\"][\"name\"]" + bb
                    + ";node[\"natural\"=\"saddle\"][\"name\"]" + bb
                    + ";node[\"mountain_pass\"=\"yes\"][\"name\"]" + bb
                    + ";way[\"natural\"~\"^(ridge|valley)$\"][\"name\"]" + bb
                    + ";nwr[\"waterway\"=\"waterfall\"][\"name\"]" + bb
                    + ";nwr[\"natural\"~\"^(spring|hot_spring|cave_entrance)$\"][\"name\"]" + bb
                    + ";nwr[\"boundary\"=\"protected_area\"][\"name\"]" + bb
                    + ";nwr[\"leisure\"=\"nature_reserve\"][\"name\"]" + bb
                    + ";);out center tags 220;"
                    + "way[\"waterway\"~\"^(river|stream)$\"][\"name\"]" + bb + ";out geom tags 40;";
            // normally the wallpaper must not wait for a busy public server (two tries of 11 s); the "Retry names" button waits much longer
            String enc = java.net.URLEncoder.encode(q, "UTF-8");
            String[] eps = patient ? new String[]{"https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter",
                    "https://overpass.kumi.systems/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter"}
                    : new String[]{"https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter"};
            for (int round = 0; round < (patient ? 3 : 1) && js == null; round++) {
                if (round > 0) { try { Thread.sleep(8_000); } catch (InterruptedException ie) { break; } }
                for (String ep : eps) {
                    try {
                        String r = Geo.get(ep + "?data=" + enc, patient ? 30_000 : 11_000);
                        if (r.contains("\"elements\"")) { js = r; break; }
                    } catch (Throwable ignored) { }
                }
            }
            if (js == null) throw new java.io.IOException("OpenStreetMap busy");
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) { fo.write(js.getBytes("UTF-8")); } catch (Throwable ignored) { }
        }
        org.json.JSONArray els = new org.json.JSONObject(js).getJSONArray("elements");
        List<L> dams = new ArrayList<>();
        for (L l : out) if (l.kind == DAM) dams.add(l);
        for (int i = 0; i < els.length(); i++) {
            org.json.JSONObject e = els.getJSONObject(i), tg = e.optJSONObject("tags");
            if (tg == null) continue;
            String nm = tg.optString("name:en", ""); if (nm.isEmpty()) nm = tg.optString("name", "");
            if (nm.isEmpty()) continue;
            org.json.JSONObject ce = e.optJSONObject("center");
            double la = ce != null ? ce.optDouble("lat", Double.NaN) : e.optDouble("lat", Double.NaN), lo = ce != null ? ce.optDouble("lon", Double.NaN) : e.optDouble("lon", Double.NaN);
            org.json.JSONArray geo = e.optJSONArray("geometry");
            L l = new L();
            String nat = tg.optString("natural", ""), ww = tg.optString("waterway", "");
            if (geo != null && geo.length() >= 2) {   // river or stream: label at its middle, arrow along its drawn direction (OSM draws waterways downstream)
                org.json.JSONObject m = geo.getJSONObject(geo.length() / 2), a = geo.getJSONObject(0), b = geo.getJSONObject(geo.length() - 1);
                la = m.optDouble("lat", Double.NaN); lo = m.optDouble("lon", Double.NaN);
                l.flow = new double[]{b.optDouble("lat") - a.optDouble("lat"), b.optDouble("lon") - a.optDouble("lon")};
                if (Math.abs(l.flow[0]) + Math.abs(l.flow[1]) < 1e-6) l.flow = null;
            }
            if (Double.isNaN(la) || Double.isNaN(lo)) continue;
            l.lat = la; l.lon = lo;
            if ("peak".equals(nat)) {
                l.kind = PEAK;
                String ele = tg.optString("ele", "").replaceAll("[^0-9.]", "");
                double ev = 0; try { ev = Double.parseDouble(ele); } catch (Throwable ignored) { }
                l.rank = ev;
                l.text = clip(nm) + (ev > 0 ? " " + Math.round(ev) : "");
            } else if ("wetland".equals(nat)) {
                l.kind = WET; l.rank = 1; l.text = clip(nm);
            } else if ("dam".equals(ww)) {
                boolean dup = false;
                for (L d : dams) if (EarthLine.km(d.lat, d.lon, la, lo) < 3) dup = true;
                if (dup) continue;
                l.kind = DAM; l.rank = -EarthLine.km(lat, lon, la, lo);
                l.text = clip(nm.replaceAll("(?i)\\s+(dam|barrage)$", "") + " Dam");
            } else if ("river".equals(ww)) {
                l.kind = RIVER; l.rank = geo != null ? geo.length() : 1; l.text = clip(nm.replaceAll("(?i)\\s+(river|nadi)$", "") + " river");
            } else if ("stream".equals(ww)) {
                l.kind = STREAM; l.rank = geo != null ? geo.length() : 1; l.text = clip(nm);
            } else if ("saddle".equals(nat) || "yes".equals(tg.optString("mountain_pass", ""))) {
                l.kind = PASS; l.rank = 1; l.text = clip(nm);
            } else if ("ridge".equals(nat) || "valley".equals(nat)) {
                l.kind = PASS; l.rank = 0.5; l.text = clip(nm);
            } else if ("waterfall".equals(ww) || "spring".equals(nat) || "hot_spring".equals(nat) || "cave_entrance".equals(nat)) {
                l.kind = FEAT; l.rank = 1; l.text = clip(nm);
                l.sub = "cave_entrance".equals(nat) ? 1 : "waterfall".equals(ww) ? 3 : 2;
            } else if ("protected_area".equals(tg.optString("boundary", "")) || "nature_reserve".equals(tg.optString("leisure", ""))) {
                l.kind = RESERVE; l.rank = 1; l.text = clip(nm);
            } else {
                boolean dup = false;
                for (L d : dams) if (EarthLine.km(d.lat, d.lon, la, lo) < 2) dup = true;
                if (dup) continue;
                l.kind = WATER; l.rank = 1; l.text = clip(nm);
            }
            out.add(l);
        }
    }
}
