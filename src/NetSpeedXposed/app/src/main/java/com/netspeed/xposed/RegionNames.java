package com.netspeed.xposed;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;

/** Big towns and cities written on the wide (Region / Far) wallpaper maps: a dot and the name, only where there is room. */
final class RegionNames {
    private RegionNames() { }

    /** The label boxes already taken by the last draw(), so the India layer can keep clear of them. */
    static List<RectF> lastUsed = null;

    private static final Object[][] CITIES = {
            {"Mumbai", 19.076, 72.878}, {"Thane", 19.218, 72.978}, {"Panvel", 18.989, 73.112}, {"Pune", 18.520, 73.857},
            {"Nashik", 19.998, 73.790}, {"Satara", 17.680, 74.018}, {"Kolhapur", 16.705, 74.243}, {"Sangli", 16.855, 74.564},
            {"Solapur", 17.660, 75.906}, {"Ahmednagar", 19.095, 74.748}, {"Sambhajinagar", 19.876, 75.343}, {"Alibag", 18.641, 72.872},
            {"Ratnagiri", 16.990, 73.312}, {"Lonavala", 18.750, 73.409}, {"Mahabaleshwar", 17.923, 73.658}, {"Karad", 17.289, 74.185},
            {"Chiplun", 17.530, 73.516}, {"Junnar", 19.209, 73.877}, {"Baramati", 18.151, 74.577}, {"Dhule", 20.902, 74.775},
            {"Jalgaon", 21.007, 75.563}, {"Surat", 21.170, 72.831}, {"Belgaum", 15.849, 74.498}, {"Panaji", 15.499, 73.828},
            {"Vasai", 19.391, 72.839}, {"Shirdi", 19.766, 74.477}, {"Latur", 18.408, 76.560}, {"Nanded", 19.138, 77.321},
            {"Mangaon", 18.234, 73.275}, {"Khopoli", 18.785, 73.346}, {"Bhor", 18.150, 73.844}, {"Wai", 17.951, 73.890},
            {"Daman", 20.414, 72.832}, {"Nagpur", 21.146, 79.088}, {"Hyderabad", 17.385, 78.487}, {"Bengaluru", 12.972, 77.594},
            {"Goa (Margao)", 15.270, 73.958}, {"Ahmedabad", 23.023, 72.572}, {"Indore", 22.720, 75.858}, {"Bhopal", 23.260, 77.413},
    };

    static String dir8(double northKm, double eastKm) {
        int di = (int) Math.round(Math.toDegrees(Math.atan2(eastKm, northKm)) / 45.0);
        di = ((di % 8) + 8) % 8;
        return new String[]{"N", "NE", "E", "SE", "S", "SW", "W", "NW"}[di];
    }

    static final Object[][] PEAKS = {
            {"Kalsubai", 19.601, 73.711, 1646}, {"Harishchandragad", 19.384, 73.774, 1424}, {"Bhimashankar", 19.072, 73.535, 1034},
            {"Salher", 20.742, 73.964, 1567}, {"Brahmagiri", 19.930, 73.530, 1295}, {"Torna", 18.275, 73.623, 1403}, {"Raigad", 18.236, 73.445, 820},
            {"Sinhagad", 18.366, 73.756, 1312}, {"Rajgad", 18.246, 73.683, 1376}, {"Mahabaleshwar", 17.924, 73.657, 1438},
            {"Lohgad", 18.710, 73.477, 1033}, {"Tikona", 18.632, 73.512, 1076}, {"Purandar", 18.281, 73.975, 1374},
            {"Pratapgad", 17.935, 73.577, 1080}, {"Matheran", 18.986, 73.268, 803}, {"Rajmachi", 18.820, 73.400, 826},
    };
    // {text, lat, lon, angle in degrees}: big, faint, letter-spaced names of the landscape
    private static final Object[][] REGIONS = {
            {"SAHYADRI", 18.55, 73.45, 72.0}, {"KONKAN", 17.15, 73.25, 72.0}, {"DECCAN PLATEAU", 18.0, 75.1, 8.0},
            {"ARABIAN SEA", 17.4, 71.9, 72.0}, {"KONKAN", 19.9, 72.95, 72.0},
    };

    /** A short text with a pale rim, placed by a point on the map, kept off the clock band and the dock. */
    static void tag(Canvas cv, String text, float x, float y, int W, int H, float u, int col, boolean day) {
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTextSize(20 * u); t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
        Paint h = new Paint(t); h.setStyle(Paint.Style.STROKE); h.setStrokeWidth(3.2f * u); h.setStrokeJoin(Paint.Join.ROUND); h.setColor(day ? 0xD2FFFFFF : 0xDC000000);
        t.setColor(col);
        float tw = t.measureText(text);
        float tx = Math.max(16 * u, Math.min(W - 16 * u - tw, x + 14 * u)), ty = Math.max(H * 0.24f, Math.min(H * 0.64f, y - 12 * u));
        cv.drawText(text, tx, ty, h); cv.drawText(text, tx, ty, t);
    }

    private static void rivers(Canvas cv, int W, int H, double lat, double lon, int z, double ox, double oy, float S, boolean day, double halfKm, List<RectF> used, float u) {
        double dLat = halfKm / 110.57, dLon = halfKm / (111.32 * Math.cos(Math.toRadians(lat)));
        String q = String.format(java.util.Locale.US, "[out:json][timeout:25];way[\"waterway\"=\"river\"][\"name\"](%.4f,%.4f,%.4f,%.4f);out tags center 150;",
                lat - dLat, lon - dLon, lat + dLat, lon + dLon);
        String js = TerrainMaker.overpass(q);
        if (js == null) { WpStatus.note("river names skipped: OpenStreetMap servers busy"); return; }
        org.json.JSONArray els;
        try { els = new org.json.JSONObject(js).getJSONArray("elements"); } catch (Throwable t) { return; }
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTextSize(20 * u); t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
        Paint halo = new Paint(t); halo.setStyle(Paint.Style.STROKE); halo.setStrokeWidth(3.2f * u); halo.setStrokeJoin(Paint.Join.ROUND); halo.setColor(day ? 0xD2FFFFFF : 0xDC000000);
        t.setColor(day ? 0xFF143C96 : 0xFFBEE1FF);
        int n = 0;
        for (int i = 0; i < els.length() && n < 6; i++) {
            try {
                org.json.JSONObject e = els.getJSONObject(i), tg = e.optJSONObject("tags"), ce = e.optJSONObject("center");
                if (tg == null || ce == null) continue;
                String nm = tg.optString("name:en", ""); if (nm.isEmpty()) nm = tg.optString("name", "");
                nm = nm.replaceAll("(?i)\\s+(river|nadi)$", "").trim();
                if (nm.isEmpty() || nm.length() > 18 || !seen.add(nm)) continue;
                float x = (float) ((WallpaperMaker.worldX(ce.getDouble("lon"), z) - ox) * S), y = (float) ((WallpaperMaker.worldY(ce.getDouble("lat"), z) - oy) * S);
                float tw = t.measureText(nm);
                RectF r = new RectF(x - tw / 2 - 4 * u, y - 18 * u, x + tw / 2 + 4 * u, y + 6 * u);
                if (r.left < 8 * u || r.right > W - 8 * u || r.top < 0 || r.bottom > H) continue;
                boolean ok = true;
                for (RectF o : used) if (RectF.intersects(o, r)) { ok = false; break; }
                if (!ok) continue;
                used.add(r);
                cv.drawText(nm, x - tw / 2, y, halo); cv.drawText(nm, x - tw / 2, y, t);
                n++;
            } catch (Throwable ignored) { }
        }
    }

    static void draw(Canvas cv, int W, int H, double lat, double lon, int z, double ox, double oy, float S, boolean day) {
        final float u = W / 1080f;
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTextSize(20 * u);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        Paint halo = new Paint(t);
        halo.setStyle(Paint.Style.STROKE); halo.setStrokeWidth(3.4f * u); halo.setStrokeJoin(Paint.Join.ROUND);
        halo.setColor(day ? 0xD2FFFFFF : 0xDC000000);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        List<RectF> used = new ArrayList<>();
        used.add(new RectF(0, 0, W, H * 0.22f));
        used.add(new RectF(0, H * 0.68f, W, H));
        IndiaMap.reserve(used, W, H);
        used.add(new RectF(W / 2f - 60 * u, H * 0.5f - 60 * u, W / 2f + 60 * u, H * 0.5f + 60 * u));
        lastUsed = used;
        float gu = W / 1080f;
        // region names across the landscape (faint, spaced out, turned along the ranges)
        Paint rg = new Paint(Paint.ANTI_ALIAS_FLAG);
        rg.setTextSize(30 * gu); rg.setLetterSpacing(0.35f); rg.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        rg.setColor(day ? 0x883C2810 : 0x99DCE6F5);
        for (Object[] r : REGIONS) {
            float x = (float) ((WallpaperMaker.worldX((Double) r[2], z) - ox) * S), y = (float) ((WallpaperMaker.worldY((Double) r[1], z) - oy) * S);
            if (x < 40 * gu || x > W - 40 * gu || y < H * 0.25f || y > H * 0.62f) continue;
            if (Math.hypot(x - W / 2f, y - H / 2f) < 180 * gu) continue;
            cv.save(); cv.rotate(((Double) r[3]).floatValue(), x, y);
            cv.drawText((String) r[0], x - rg.measureText((String) r[0]) / 2, y, rg);
            cv.restore();
        }
        // named peaks with heights (a built-in list of the big ones)
        Paint pk = new Paint(Paint.ANTI_ALIAS_FLAG);
        pk.setTextSize(20 * gu); pk.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        Paint pkh = new Paint(pk); pkh.setStyle(Paint.Style.STROKE); pkh.setStrokeWidth(3.2f * gu); pkh.setStrokeJoin(Paint.Join.ROUND); pkh.setColor(day ? 0xD2FFFFFF : 0xDC000000);
        pk.setColor(day ? 0xFF46280F : 0xFFFFFFFF);
        used.add(new RectF(W / 2f - 80 * gu, H / 2f - 80 * gu, W / 2f + 80 * gu, H / 2f + 80 * gu));
        int np = 0;
        for (Object[] p : PEAKS) {
            float x = (float) ((WallpaperMaker.worldX((Double) p[2], z) - ox) * S), y = (float) ((WallpaperMaker.worldY((Double) p[1], z) - oy) * S);
            String label = p[0] + " " + p[3] + " m";
            float tw = pk.measureText(label);
            RectF r = new RectF(x - 9 * gu, y - 14 * gu, x + 16 * gu + tw, y + 8 * gu);
            if (r.left < 8 * gu || r.right > W - 8 * gu || r.top < 0 || r.bottom > H) continue;
            boolean ok = true;
            for (RectF o : used) if (RectF.intersects(o, r)) { ok = false; break; }
            if (!ok) continue;
            used.add(r);
            android.graphics.Path tri = new android.graphics.Path();
            tri.moveTo(x, y - 8 * gu); tri.lineTo(x + 7 * gu, y + 5 * gu); tri.lineTo(x - 7 * gu, y + 5 * gu); tri.close();
            Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG); ip.setColor(day ? 0xFF46280F : 0xFFFFFFFF);
            cv.drawPath(tri, ip);
            cv.drawText(label, x + 14 * gu, y + 6 * gu, pkh); cv.drawText(label, x + 14 * gu, y + 6 * gu, pk);
            if (++np >= 7) break;
        }
        int shown = 0;
        // bigger places first: the list is roughly in order of size for the Maharashtra region
        for (Object[] c : CITIES) {
            double la = (Double) c[1], lo = (Double) c[2];
            if (la == 0) continue;
            float x = (float) ((WallpaperMaker.worldX(lo, z) - ox) * S), y = (float) ((WallpaperMaker.worldY(la, z) - oy) * S);
            float tw = t.measureText((String) c[0]);
            RectF r = new RectF(x - 8 * u, y - 14 * u, x + 14 * u + tw, y + 8 * u);
            if (r.left < 8 * u || r.right > W - 8 * u || r.top < 0 || r.bottom > H) continue;
            boolean ok = true;
            for (RectF o : used) if (RectF.intersects(o, r)) { ok = false; break; }
            if (!ok) continue;
            used.add(r);
            dot.setStyle(Paint.Style.FILL); dot.setColor(day ? 0xFF222222 : 0xFFFFFFFF);
            cv.drawCircle(x, y, 5.5f * u, dot);
            dot.setStyle(Paint.Style.STROKE); dot.setStrokeWidth(1.6f * u); dot.setColor(day ? 0xFFFFFFFF : 0xFF000000);
            cv.drawCircle(x, y, 5.5f * u, dot);
            t.setColor(day ? 0xFF222222 : 0xFFFFFFFF);
            cv.drawText((String) c[0], x + 12 * u, y + 6 * u, halo);
            cv.drawText((String) c[0], x + 12 * u, y + 6 * u, t);
            if (++shown >= 12) break;
        }
        try { rivers(cv, W, H, lat, lon, z, ox, oy, S, day, Math.max(W, H) / S * 156543.03 * Math.cos(Math.toRadians(lat)) / (1 << z) / 2000.0, used, u); } catch (Throwable ignored) { }
    }
}
