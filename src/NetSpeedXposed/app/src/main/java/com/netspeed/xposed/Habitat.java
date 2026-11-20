package com.netspeed.xposed;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What grows around you: the ecoregion (UNEP-WCMC "Resolve" layer, one point query), the plants people have logged within
 * 10 km (iNaturalist research-grade records), what the map shows within about 5 km (OpenStreetMap tile colours: built-up,
 * farmland, forest, grassland, scrub, water) and the nearest forest patch that a mapper tagged deciduous or evergreen.
 * Every part is best effort and cached on the phone for 7 days per 5 km cell. Used by the status bar (System UI), the app and the wallpaper.
 */
final class Habitat {
    private Habitat() {}

    static final String[] COVER = {"Built-up", "Farmland", "Forest", "Grassland", "Scrub", "Water", "Open / unmapped"};
    private static final long TTL = 7L * 86400_000L;

    static final class Data {
        double lat, lon;
        long at;
        String eco = "", biome = "", leaf = "";
        double leafKm = Double.NaN;
        String leafDir = "";
        List<String> names = new ArrayList<>(), sci = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        int species;
        float[] cover = new float[7];
        boolean coverOk;

        boolean any() { return !eco.isEmpty() || !names.isEmpty() || coverOk; }

        /** "Built-up 42% · Farmland 24% · Forest 14% · Grassland 11%": the biggest shares, at least 3%. */
        String coverText(int max) {
            Integer[] ix = {0, 1, 2, 3, 4, 5, 6};
            java.util.Arrays.sort(ix, (a, b) -> Float.compare(cover[b], cover[a]));
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (int i : ix) {
                if (cover[i] < 3 || n >= max) break;
                if (sb.length() > 0) sb.append(" · ");
                sb.append(COVER[i]).append(' ').append(Math.round(cover[i])).append('%');
                n++;
            }
            return sb.toString();
        }

        /** The commonest plants, the way the mapped people name them: "Indian beech", "Neem". */
        List<String> top(int n) {
            List<String> o = new ArrayList<>();
            for (int i = 0; i < names.size() && o.size() < n; i++) o.add(names.get(i));
            return o;
        }
    }

    static volatile Data cur;

    private static File file(Context c) { return new File(c.getCacheDir(), "habitat.txt"); }

    private static String key(double lat, double lon) { return String.format(Locale.US, "%.2f,%.2f", Math.round(lat * 20) / 20.0, Math.round(lon * 20) / 20.0); }

    /** The cached answer for this place if it is under 7 days old, else null. No network. */
    static Data cached(Context c, double lat, double lon) {
        Data d = cur;
        if (d != null && key(d.lat, d.lon).equals(key(lat, lon)) && System.currentTimeMillis() - d.at < TTL) return d;
        try {
            File f = file(c);
            if (!f.exists()) return null;
            org.json.JSONObject o = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
            if (!key(lat, lon).equals(o.optString("key")) || System.currentTimeMillis() - o.optLong("at") > TTL) return null;
            Data r = new Data();
            r.lat = o.optDouble("lat"); r.lon = o.optDouble("lon"); r.at = o.optLong("at");
            r.eco = o.optString("eco"); r.biome = o.optString("biome"); r.leaf = o.optString("leaf");
            r.leafKm = o.optDouble("leafKm", Double.NaN); r.leafDir = o.optString("leafDir");
            r.species = o.optInt("species");
            org.json.JSONArray a = o.optJSONArray("plants");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                org.json.JSONArray p = a.getJSONArray(i);
                r.names.add(p.getString(0)); r.sci.add(p.getString(1)); r.counts.add(p.getInt(2));
            }
            org.json.JSONArray cv = o.optJSONArray("cover");
            if (cv != null && cv.length() == 7) { for (int i = 0; i < 7; i++) r.cover[i] = (float) cv.getDouble(i); r.coverOk = true; }
            cur = r;
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Looks everything up (blocking, up to about a minute). Call off the main thread. */
    static Data load(Context c, double lat, double lon, boolean force) {
        if (!force) { Data d = cached(c, lat, lon); if (d != null) return d; }
        Data d = new Data();
        d.lat = lat; d.lon = lon; d.at = System.currentTimeMillis();
        try {
            org.json.JSONObject j = new org.json.JSONObject(Dam.http("https://data-gis.unep-wcmc.org/server/rest/services/Bio-geographicalRegions/Resolve_Ecoregions/MapServer/0/query?where=1%3D1"
                    + "&outFields=ECO_NAME%2CBIOME_NAME&returnGeometry=false&geometry=" + String.format(Locale.US, "%.4f%%2C%.4f", lon, lat)
                    + "&geometryType=esriGeometryPoint&inSR=4326&spatialRel=esriSpatialRelIntersects&f=json", null));
            org.json.JSONArray f = j.optJSONArray("features");
            if (f != null && f.length() > 0) {
                org.json.JSONObject at = f.getJSONObject(0).getJSONObject("attributes");
                d.eco = at.optString("eco_name", "");
                d.biome = at.optString("biome_name", "");
            }
        } catch (Throwable ignored) { }
        try {
            org.json.JSONObject j = new org.json.JSONObject(Dam.http(String.format(Locale.US,
                    "https://api.inaturalist.org/v1/observations/species_counts?lat=%.4f&lng=%.4f&radius=10&iconic_taxa=Plantae&quality_grade=research&per_page=30&locale=en", lat, lon), null));
            d.species = j.optInt("total_results");
            org.json.JSONArray r = j.optJSONArray("results");
            List<int[]> rank = new ArrayList<>();      // {capped count, count, index}
            List<String[]> nm = new ArrayList<>();
            if (r != null) for (int i = 0; i < r.length(); i++) {
                org.json.JSONObject x = r.getJSONObject(i), t = x.optJSONObject("taxon");
                if (t == null) continue;
                String sc = t.optString("name", ""), cn = t.optString("preferred_common_name", "");
                if (sc.isEmpty() || sc.indexOf(' ') < 0) continue;       // species only
                int n = x.optInt("count");
                rank.add(new int[]{Math.min(n, 60), n, nm.size()});
                nm.add(new String[]{cn.isEmpty() ? sc : title(cn), sc});
            }
            // one species (or one project's records) must not run away with the list: counts above 60 count as 60
            java.util.Collections.sort(rank, (a, b) -> a[0] != b[0] ? b[0] - a[0] : b[1] - a[1]);
            for (int[] k : rank) { if (d.names.size() >= 10) break; d.names.add(nm.get(k[2])[0]); d.sci.add(nm.get(k[2])[1]); d.counts.add(k[1]); }
        } catch (Throwable ignored) { }
        try { cover(d); } catch (Throwable ignored) { }
        if (d.any()) {
            cur = d;
            try { save(c, d); } catch (Throwable ignored) { }
            // the forest-patch tag lookup (Overpass) can take minutes: it fills in later and is saved again
            final Context ac = c;
            Thread t = new Thread(() -> {
                try { leaf(d); if (!d.leaf.isEmpty()) save(ac, d); } catch (Throwable ignored) { }
            }, "nsx-leaf");
            t.setDaemon(true);
            t.start();
        }
        return d;
    }

    private static String title(String s) {
        String[] w = s.split(" ");
        StringBuilder b = new StringBuilder();
        for (String x : w) if (!x.isEmpty()) b.append(b.length() > 0 ? " " : "").append(Character.toUpperCase(x.charAt(0))).append(x.substring(1));
        return b.toString();
    }

    private static void save(Context c, Data d) throws Exception {
        org.json.JSONObject o = new org.json.JSONObject();
        o.put("key", key(d.lat, d.lon)).put("lat", d.lat).put("lon", d.lon).put("at", d.at).put("eco", d.eco).put("biome", d.biome)
                .put("leaf", d.leaf).put("leafKm", Double.isNaN(d.leafKm) ? JSONObjectNull() : d.leafKm).put("leafDir", d.leafDir).put("species", d.species);
        org.json.JSONArray a = new org.json.JSONArray();
        for (int i = 0; i < d.names.size(); i++) a.put(new org.json.JSONArray().put(d.names.get(i)).put(d.sci.get(i)).put(d.counts.get(i)));
        o.put("plants", a);
        if (d.coverOk) { org.json.JSONArray cv = new org.json.JSONArray(); for (float v : d.cover) cv.put(v); o.put("cover", cv); }
        java.nio.file.Files.write(file(c).toPath(), o.toString().getBytes("UTF-8"));
    }

    private static Object JSONObjectNull() { return org.json.JSONObject.NULL; }

    // ---- land cover from OpenStreetMap's own tile colours (the same trick the wallpaper map uses)

    private static final int[][] REF = {
            {224, 223, 223}, {217, 208, 201}, {242, 218, 217}, {235, 219, 232},   // 0 built-up: residential, building, commercial, industrial
            {238, 240, 213}, {174, 223, 163}, {245, 220, 186},                     // 1 farmland, orchard, farmyard
            {173, 209, 158},                                                       // 2 forest
            {205, 235, 176},                                                       // 3 grass / meadow / park
            {200, 215, 171},                                                       // 4 scrub
            {170, 211, 223},                                                       // 5 water
            {242, 239, 233}};                                                      // 6 open land (nothing mapped)
    private static final int[] REFCLS = {0, 0, 0, 0, 1, 1, 1, 2, 3, 4, 5, 6};

    private static void cover(Data d) throws Exception {
        final int z = 13;
        double n = Math.pow(2, z);
        double wx = (d.lon + 180) / 360 * n, wy = (1 - Math.log(Math.tan(Math.toRadians(d.lat)) + 1 / Math.cos(Math.toRadians(d.lat))) / Math.PI) / 2 * n;
        int tx0 = (int) Math.floor(wx) - 1, ty0 = (int) Math.floor(wy) - 1;
        double mpp = 156543.03 * Math.cos(Math.toRadians(d.lat)) / n;
        double r5 = 5000 / mpp;                       // the 5 km radius, in pixels
        long[] cnt = new long[7];
        long tot = 0;
        int[] pix = new int[65536];
        for (int ty = ty0; ty <= ty0 + 2; ty++) for (int tx = tx0; tx <= tx0 + 2; tx++) {
            byte[] b = TerrainMaker.get("https://tile.openstreetmap.org/" + z + "/" + (((tx % (int) n) + (int) n) % (int) n) + "/" + ty + ".png", 3);
            Bitmap bm = b == null ? null : BitmapFactory.decodeByteArray(b, 0, b.length);
            if (bm == null || bm.getWidth() != 256) continue;
            bm.getPixels(pix, 0, 256, 0, 0, 256, 256);
            bm.recycle();
            for (int v = 0; v < 256; v += 2) for (int u = 0; u < 256; u += 2) {
                double dx = (tx * 256 + u) - wx * 256, dy = (ty * 256 + v) - wy * 256;
                if (dx * dx + dy * dy > r5 * r5) continue;
                int p = pix[v * 256 + u];
                int r = (p >> 16) & 255, g = (p >> 8) & 255, bl = p & 255;
                int best = -1, bd = 40;
                for (int k = 0; k < REF.length; k++) {
                    int dd = Math.abs(r - REF[k][0]) + Math.abs(g - REF[k][1]) + Math.abs(bl - REF[k][2]);
                    if (dd < bd) { bd = dd; best = k; }
                }
                if (best < 0 || bd > 14) continue;     // roads, labels, outlines: not counted
                cnt[REFCLS[best]]++;
                tot++;
            }
        }
        if (tot < 500) return;
        for (int i = 0; i < 7; i++) d.cover[i] = 100f * cnt[i] / tot;
        d.coverOk = true;
    }

    /** The nearest forest patch within 15 km that a mapper gave a leaf type (deciduous / evergreen / mixed). */
    private static void leaf(Data d) throws Exception {
        String q = String.format(Locale.US, "[out:json][timeout:20];(way[natural=wood][leaf_type](around:15000,%.5f,%.5f);relation[natural=wood][leaf_type](around:15000,%.5f,%.5f);"
                + "way[landuse=forest][leaf_type](around:15000,%.5f,%.5f););out tags center;", d.lat, d.lon, d.lat, d.lon, d.lat, d.lon);
        String js = TerrainMaker.overpass(q);
        if (js == null) return;
        org.json.JSONArray els = new org.json.JSONObject(js).getJSONArray("elements");
        double best = 1e9;
        for (int i = 0; i < els.length(); i++) {
            org.json.JSONObject e = els.getJSONObject(i), c = e.optJSONObject("center"), t = e.optJSONObject("tags");
            if (c == null || t == null) continue;
            double km = Dam.km(d.lat, d.lon, c.getDouble("lat"), c.getDouble("lon"));
            if (km < best) {
                best = km;
                String lt = t.optString("leaf_type", ""), lc = t.optString("leaf_cycle", "");
                d.leaf = lt.equals("broadleaved") ? (lc.equals("deciduous") ? "broadleaved, deciduous" : lc.equals("evergreen") ? "broadleaved, evergreen" : "broadleaved")
                        : lt.equals("needleleaved") ? "needleleaved" : lt.equals("mixed") ? "mixed" : lt;
                if (lc.equals("deciduous") && !d.leaf.contains("deciduous")) d.leaf += ", deciduous";
                d.leafKm = km;
                d.leafDir = DamReg.dir(d.lat, d.lon, c.getDouble("lat"), c.getDouble("lon"));
            }
        }
    }

    /** Status bar: refresh when there is nothing cached for this 5 km cell. */
    static void tick(Context c, double lat, double lon) {
        if (cached(c, lat, lon) != null) return;
        load(c, lat, lon, true);
    }
}
