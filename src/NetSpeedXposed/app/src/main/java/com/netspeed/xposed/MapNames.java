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

/** Names written on the terrain wallpaper: peaks, dams with their level, lakes, wetlands, rivers and faults. */
final class MapNames {
    private MapNames() { }

    private static final class L {
        int kind;            // 0 dam, 1 peak, 2 wetland, 3 fault, 4 water, 5 river
        String text;
        double lat, lon, rank;
        float x, y;
    }

    /** Draws up to about a dozen names that do not overlap each other, the clock, the legend or the position dot. */
    static void draw(Context c, Canvas cv, int W, int H, double lat, double lon, int z, double ox, double oy, float S, boolean day, double halfKm) {
        List<L> all = new ArrayList<>();
        try { dams(all, lat, lon, halfKm); } catch (Throwable ignored) { }
        try { faults(all, lat, lon, halfKm); } catch (Throwable ignored) { }
        try { osm(c, all, lat, lon, halfKm, z); } catch (Throwable ignored) { }
        for (L l : all) {
            l.x = (float) ((WallpaperMaker.worldX(l.lon, z) - ox) * S);
            l.y = (float) ((WallpaperMaker.worldY(l.lat, z) - oy) * S);
        }
        Collections.sort(all, (a, b) -> a.kind != b.kind ? Integer.compare(prio(a.kind), prio(b.kind)) : Double.compare(b.rank, a.rank));

        final float u = W / 1080f;
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTextSize(26 * u);
        Paint halo = new Paint(t);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(6 * u);
        halo.setStrokeJoin(Paint.Join.ROUND);
        halo.setColor(0xEB000000);
        Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG);

        List<RectF> used = new ArrayList<>();
        used.add(new RectF(0, 0, W, H * 0.22f));                           // clock and status bar
        used.add(new RectF(0, H * 0.66f, W, H));                           // icons, dock, search bar
        used.add(new RectF(W * 0.02f, H * 0.165f, W * 0.66f, H * 0.265f)); // legend
        used.add(new RectF(W / 2f - 110 * u, H * 0.5f - 110 * u, W / 2f + 110 * u, H * 0.5f + 110 * u));   // you
        int[] cap = {3, 4, 2, 2, 2, 1};
        int[] got = new int[6];
        int total = 0;
        for (L l : all) {
            if (total >= 12 || got[l.kind] >= cap[l.kind] || l.text == null || l.text.isEmpty()) continue;
            boolean bold = l.kind <= 3;
            t.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.ITALIC));
            halo.setTypeface(t.getTypeface());
            float tw = t.measureText(l.text);
            float ic = 22 * u;                                        // room for the symbol
            float left = l.x + ic, top = l.y - 18 * u, right = left + tw, bottom = l.y + 12 * u;
            if (l.kind >= 4) { left = l.x - tw / 2; right = left + tw; }
            if (right > W - 24 * u) { float s = right - (W - 24 * u); left -= s; right -= s; }
            if (left < 24 * u) { float s = 24 * u - left; left += s; right += s; }
            RectF r = new RectF(left - (l.kind < 4 ? ic + 6 * u : 6 * u), top - 6 * u, right + 6 * u, bottom + 6 * u);
            boolean ok = r.top > 0 && r.bottom < H;
            for (RectF o : used) if (ok && RectF.intersects(o, r)) ok = false;
            if (!ok) continue;
            used.add(r);
            got[l.kind]++;
            total++;
            float by = l.y + 8 * u;
            switch (l.kind) {
                case 0: {   // dam wall
                    ip.setStyle(Paint.Style.FILL);
                    ip.setColor(0xFFFFD60A);
                    cv.drawRect(l.x - 11 * u, l.y - 8 * u, l.x + 11 * u, l.y + 8 * u, ip);
                    ip.setStyle(Paint.Style.STROKE); ip.setStrokeWidth(2.5f * u); ip.setColor(0xFF000000);
                    cv.drawRect(l.x - 11 * u, l.y - 8 * u, l.x + 11 * u, l.y + 8 * u, ip);
                    cv.drawLine(l.x - 11 * u, l.y, l.x + 11 * u, l.y, ip);
                    t.setColor(0xFFFFEB8C);
                    break;
                }
                case 1: {   // peak
                    Path p = new Path();
                    p.moveTo(l.x, l.y - 12 * u); p.lineTo(l.x + 11 * u, l.y + 9 * u); p.lineTo(l.x - 11 * u, l.y + 9 * u); p.close();
                    ip.setStyle(Paint.Style.FILL); ip.setColor(0xFFFFFFFF);
                    cv.drawPath(p, ip);
                    ip.setStyle(Paint.Style.STROKE); ip.setStrokeWidth(2.5f * u); ip.setColor(0xFF000000);
                    cv.drawPath(p, ip);
                    t.setColor(0xFFFFFFFF);
                    break;
                }
                case 2: {   // wetland
                    ip.setStyle(Paint.Style.FILL); ip.setColor(0xFF20B2A0);
                    cv.drawCircle(l.x, l.y, 9 * u, ip);
                    ip.setStyle(Paint.Style.STROKE); ip.setStrokeWidth(2.5f * u); ip.setColor(0xFFFFFFFF);
                    cv.drawCircle(l.x, l.y, 9 * u, ip);
                    t.setColor(0xFF96FFEB);
                    break;
                }
                case 3: t.setColor(0xFFFFAAA0); break;
                case 4: t.setColor(0xFFBEE1FF); break;
                default: t.setColor(0xFFA8D4FF);
            }
            cv.drawText(l.text, left, by, halo);
            cv.drawText(l.text, left, by, t);
        }
    }

    private static int prio(int kind) { return kind == 0 ? 0 : kind == 1 ? 1 : kind == 3 ? 2 : kind == 2 ? 3 : kind == 4 ? 4 : 5; }

    private static String clip(String s) { return s.length() > 26 ? s.substring(0, 25) + "…" : s; }

    private static void dams(List<L> out, double lat, double lon, double halfKm) {
        for (DamAll.Hit h : DamAll.nearest(lat, lon, 8, halfKm * 1.2, false)) {
            L l = new L();
            l.kind = 0; l.lat = h.lat; l.lon = h.lon; l.rank = -h.km;
            String n = h.name.replaceAll("(?i)\\s+(dam|barrage|reservoir)$", "").trim();
            l.text = clip(n + " Dam") + (h.fresh() ? "  " + Math.round(h.pct) + "%" : "");
            out.add(l);
        }
    }

    private static void faults(List<L> out, double lat, double lon, double halfKm) {
        for (Object[] f : Faults.namesIn(lat, lon, halfKm)) {
            L l = new L();
            l.kind = 3; l.text = clip(f[0] + " fault"); l.lat = (Double) f[1]; l.lon = (Double) f[2]; l.rank = 1;
            out.add(l);
        }
    }

    private static void osm(Context c, List<L> out, double lat, double lon, double halfKm, int z) throws Exception {
        double dLat = halfKm / 110.57, dLon = halfKm / (111.32 * Math.cos(Math.toRadians(lat)));
        String bb = String.format(Locale.US, "(%.4f,%.4f,%.4f,%.4f)", lat - dLat, lon - dLon, lat + dLat, lon + dLon);
        String key = String.format(Locale.US, "%.2f_%.2f_%d", lat, lon, z);
        File f = new File(c.getCacheDir(), "mapnames_" + key + ".json");
        String js = null;
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 24 * 3600_000L) {
            try { js = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"); } catch (Throwable ignored) { }
        }
        if (js == null) {
            String q = "[out:json][timeout:40];(node[\"natural\"=\"peak\"][\"name\"]" + bb + ";nwr[\"natural\"=\"wetland\"][\"name\"]" + bb
                    + ";nwr[\"natural\"=\"water\"][\"name\"]" + bb + ";nwr[\"waterway\"=\"dam\"][\"name\"]" + bb
                    + ";way[\"waterway\"=\"river\"][\"name\"]" + bb + ";);out center tags 160;";
            // the wallpaper must not wait for a busy public server: two tries of at most 11 s each, then draw without OpenStreetMap names (the cache fills next time)
            String enc = java.net.URLEncoder.encode(q, "UTF-8");
            for (String ep : new String[]{"https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter"}) {
                try {
                    String r = Geo.get(ep + "?data=" + enc, 11_000);
                    if (r.contains("\"elements\"")) { js = r; break; }
                } catch (Throwable ignored) { }
            }
            if (js == null) throw new java.io.IOException("OpenStreetMap busy");
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) { fo.write(js.getBytes("UTF-8")); } catch (Throwable ignored) { }
        }
        org.json.JSONArray els = new org.json.JSONObject(js).getJSONArray("elements");
        List<L> dams = new ArrayList<>();
        for (L l : out) if (l.kind == 0) dams.add(l);
        for (int i = 0; i < els.length(); i++) {
            org.json.JSONObject e = els.getJSONObject(i), tg = e.optJSONObject("tags");
            if (tg == null) continue;
            String nm = tg.optString("name:en", ""); if (nm.isEmpty()) nm = tg.optString("name", "");
            if (nm.isEmpty()) continue;
            org.json.JSONObject ce = e.optJSONObject("center");
            double la = ce != null ? ce.optDouble("lat", Double.NaN) : e.optDouble("lat", Double.NaN), lo = ce != null ? ce.optDouble("lon", Double.NaN) : e.optDouble("lon", Double.NaN);
            if (Double.isNaN(la) || Double.isNaN(lo)) continue;
            L l = new L();
            l.lat = la; l.lon = lo;
            String nat = tg.optString("natural", ""), ww = tg.optString("waterway", "");
            if ("peak".equals(nat)) {
                l.kind = 1;
                String ele = tg.optString("ele", "").replaceAll("[^0-9.]", "");
                double ev = 0; try { ev = Double.parseDouble(ele); } catch (Throwable ignored) { }
                l.rank = ev;
                l.text = clip(nm) + (ev > 0 ? "  " + Math.round(ev) + " m" : "");
            } else if ("wetland".equals(nat)) {
                l.kind = 2; l.rank = 1; l.text = clip(nm);
            } else if ("dam".equals(ww)) {
                boolean dup = false;
                for (L d : dams) if (EarthLine.km(d.lat, d.lon, la, lo) < 3) dup = true;
                if (dup) continue;
                l.kind = 0; l.rank = -EarthLine.km(lat, lon, la, lo);
                l.text = clip(nm.replaceAll("(?i)\\s+(dam|barrage)$", "") + " Dam");
            } else if ("river".equals(ww)) {
                l.kind = 5; l.rank = 1; l.text = clip(nm.replaceAll("(?i)\\s+(river|nadi)$", "") + " river");
            } else {
                boolean dup = false;
                for (L d : dams) if (EarthLine.km(d.lat, d.lon, la, lo) < 2) dup = true;
                if (dup) continue;
                l.kind = 4; l.rank = 1; l.text = clip(nm);
            }
            out.add(l);
        }
    }
}
