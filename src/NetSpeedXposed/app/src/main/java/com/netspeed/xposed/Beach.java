package com.netspeed.xposed;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Named beaches near a place, from OpenStreetMap (live, cached a week per 0.1 degree cell). */
final class Beach {
    private Beach() {}

    static final class Hit { String name; double km, lat, lon; String dir; }

    private static final String[] DIRS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    static double km(double a1, double o1, double a2, double o2) {
        double p = Math.toRadians(a2 - a1), q = Math.toRadians(o2 - o1);
        double h = Math.sin(p / 2) * Math.sin(p / 2) + Math.cos(Math.toRadians(a1)) * Math.cos(Math.toRadians(a2)) * Math.sin(q / 2) * Math.sin(q / 2);
        return 6371 * 2 * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    static String dir(double a1, double o1, double a2, double o2) {
        double y = Math.sin(Math.toRadians(o2 - o1)) * Math.cos(Math.toRadians(a2));
        double x = Math.cos(Math.toRadians(a1)) * Math.sin(Math.toRadians(a2)) - Math.sin(Math.toRadians(a1)) * Math.cos(Math.toRadians(a2)) * Math.cos(Math.toRadians(o2 - o1));
        double b = (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
        return DIRS[(int) Math.round(b / 45.0) % 8];
    }

    /** The n nearest named beaches within about 150 km (one entry per name); throws when the server can't be reached. */
    static List<Hit> nearest(android.content.Context c, double lat, double lon, int n) throws Exception {
        android.content.SharedPreferences sp = c.getSharedPreferences("beach", android.content.Context.MODE_PRIVATE);
        String key = String.format(Locale.US, "%.1f,%.1f", lat, lon);
        String json = sp.getString(key, null);
        if (json == null || System.currentTimeMillis() - sp.getLong(key + "@", 0) > 7 * 86400_000L) {
            try {
                json = Geo.overpass(bboxQuery(lat, lon), 35_000);
                if (!json.contains("\"natural\"")) json = Geo.overpass(bboxQuery(lat, lon, 2.0), 45_000);   // nothing in the near box: look further
            } catch (Exception e) {
                if (json == null) return BeachList.nearest(lat, lon, n);   // no internet answer: the built-in list
            }
            if (json != null && json.contains("elements")) sp.edit().putString(key, json).putLong(key + "@", System.currentTimeMillis()).apply();
        }
        return parse(json, lat, lon, n);
    }

    /** Named beaches in a box about 150 km each way (a box is much cheaper for Overpass than a radius). */
    static String bboxQuery(double lat, double lon) { return bboxQuery(lat, lon, 1.0); }

    /** half = degrees each way (1.0 is about 105 km); nodes and ways only (beaches are rarely relations), which is much lighter. */
    static String bboxQuery(double lat, double lon, double half) {
        return String.format(Locale.US, "[out:json][timeout:30];nw[\"natural\"=\"beach\"][\"name\"](%.3f,%.3f,%.3f,%.3f);out center tags;", lat - half, lon - half, lat + half, lon + half);
    }

    /** Beaches near a point: a box of about 105 km each way first, then about 210 km if that found none. */
    static List<Hit> fetch(double lat, double lon, int n) throws Exception {
        List<Hit> hs = parse(Geo.overpass(bboxQuery(lat, lon, 1.0), 35_000), lat, lon, n);
        if (hs.isEmpty()) hs = parse(Geo.overpass(bboxQuery(lat, lon, 2.0), 45_000), lat, lon, n);
        return hs;
    }

    static List<Hit> parse(String json, double lat, double lon, int n) throws Exception {
        org.json.JSONArray el = new org.json.JSONObject(json).getJSONArray("elements");
        java.util.Map<String, Hit> best = new java.util.HashMap<>();
        for (int i = 0; i < el.length(); i++) {
            org.json.JSONObject e = el.getJSONObject(i);
            double la = e.has("lat") ? e.getDouble("lat") : e.has("center") ? e.getJSONObject("center").getDouble("lat") : Double.NaN;
            double lo = e.has("lon") ? e.getDouble("lon") : e.has("center") ? e.getJSONObject("center").getDouble("lon") : Double.NaN;
            if (Double.isNaN(la)) continue;
            String nm = e.getJSONObject("tags").optString("name:en", e.getJSONObject("tags").optString("name", "")).trim();
            if (nm.isEmpty()) continue;
            double d = km(lat, lon, la, lo);
            Hit h = best.get(nm.toLowerCase(Locale.ROOT));
            if (h == null || d < h.km) {
                h = new Hit(); h.name = nm; h.km = d; h.lat = la; h.lon = lo; h.dir = dir(lat, lon, la, lo);
                best.put(nm.toLowerCase(Locale.ROOT), h);
            }
        }
        List<Hit> out = new ArrayList<>(best.values());
        java.util.Collections.sort(out, (a, b) -> Double.compare(a.km, b.km));
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }
}
