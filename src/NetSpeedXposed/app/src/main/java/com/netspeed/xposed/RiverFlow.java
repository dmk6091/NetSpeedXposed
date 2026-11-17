package com.netspeed.xposed;

import java.util.Locale;

/**
 * River flow at your location from the Open-Meteo Flood API (GloFAS model, about 5 km grid, nearest river):
 * today's discharge in m3/s, whether it is rising or falling since yesterday, and "high" / "low" against the
 * last 30 days. Worker thread. The status bar's place line reads {@link #text()}.
 */
final class RiverFlow {
    private RiverFlow() {}

    private static volatile String text = "";
    private static volatile long okAt, triedAt;
    private static String lastKey = "";
    static volatile String why = "not fetched yet";
    static volatile String nameWhy = "name not looked up yet";
    private static String nameKey = "";
    private static String nameVal = "";
    private static long nameAt;
    private static double nameLat, nameLon;

    /** When a lookup fails, keep the last name found if it was within 3 km of here. */
    private static String stale(String key, double lat, double lon) {
        if (nameKey.equals(key)) return nameVal;
        return !nameVal.isEmpty() && EarthLine.km(lat, lon, nameLat, nameLon) < 3 ? nameVal : "";
    }

    /**
     * "River Pavana" / "Stream X" / "Canal Y": the nearest named waterway within 4 km from OpenStreetMap (Overpass),
     * a river preferred over a stream when they are close. "" when none is named. Cached a day per km. English name first, else the local (e.g. Marathi) one.
     */
    static String waterName(double lat, double lon) {
        String key = String.format(Locale.US, "%.2f,%.2f", lat, lon);
        long now = System.currentTimeMillis();
        if (key.equals(nameKey) && now - nameAt < 24 * 3600_000L) return nameVal;
        try {
            // bounding box (about 3.3 km each way) and geometry only inside it: a much lighter query than "around" + full geometry
            double bs = lat - 0.03, bw = lon - 0.03, bn = lat + 0.03, be = lon + 0.03;
            String q = String.format(Locale.US, "[out:json][timeout:60];way[\"waterway\"~\"^(river|stream|canal)$\"](%1$.4f,%2$.4f,%3$.4f,%4$.4f);out tags geom(%1$.4f,%2$.4f,%3$.4f,%4$.4f) 40;", bs, bw, bn, be);
            StringBuilder sb = new StringBuilder();
            String codes = "";
            synchronized (Geo.OVP_LOCK) {   // one Overpass request at a time (see Geo.overpass)
            for (String ep : new String[]{"https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter", "https://overpass.kumi.systems/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter"}) {
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(ep + "?data="
                            + java.net.URLEncoder.encode(q, "UTF-8")).openConnection();
                    c.setConnectTimeout(8_000);
                    c.setReadTimeout(60_000);
                    c.setRequestProperty("User-Agent", "NetSpeedXposed/1.0 (status bar module; github.com/dmk6091/NetSpeedXposed)");
                    int rc = c.getResponseCode();
                    codes += ep.substring(8, 14) + "=" + rc + " ";
                    if (rc != 200) continue;
                    StringBuilder one = new StringBuilder();
                    try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
                        String ln;
                        while ((ln = r.readLine()) != null) one.append(ln);
                    }
                    // a busy server can answer 200 with an error page instead of JSON: try the next one
                    if (!one.toString().trim().startsWith("{") || !one.toString().contains("\"elements\"")) { codes += "(not json) "; continue; }
                    sb.append(one);
                    break;
                } catch (Throwable t1) {
                    codes += ep.substring(8, 14) + "=" + t1.getClass().getSimpleName() + " ";
                }
            }
            }
            if (sb.length() == 0) { nameWhy = "OpenStreetMap lookup failed: " + codes; return stale(key, lat, lon); }
            org.json.JSONArray els = new org.json.JSONObject(sb.toString()).optJSONArray("elements");
            String best = "";
            double bestScore = 1e18;
            double kx = 111320 * Math.cos(Math.toRadians(lat)), ky = 110540;
            for (int i = 0; els != null && i < els.length(); i++) {
                org.json.JSONObject e = els.optJSONObject(i);
                if (e == null) continue;
                org.json.JSONObject tg = e.optJSONObject("tags");
                org.json.JSONArray g = e.optJSONArray("geometry");
                if (tg == null || g == null) continue;
                String nm = tg.optString("name:en", "");
                if (nm.isEmpty()) nm = tg.optString("int_name", "");
                if (nm.isEmpty()) nm = tg.optString("name", "");   // local name (Marathi, Devanagari) when there is no English one
                if (nm.length() > 30) nm = "";
                nm = nm.replaceAll("(?i)^(river|canal|stream|nadi|nala)\\s+|\\s+(river|canal|stream|nadi)$", "").trim();   // the label already says River/Canal/Stream
                double d = 1e18;
                for (int j = 0; j < g.length(); j++) {
                    org.json.JSONObject pt = g.optJSONObject(j);   // points outside the box come back null
                    if (pt == null) continue;
                    double dx = (pt.getDouble("lon") - lon) * kx, dy = (pt.getDouble("lat") - lat) * ky;
                    d = Math.min(d, Math.sqrt(dx * dx + dy * dy));
                }
                String type = tg.optString("waterway", "river");
                double score = d - ("river".equals(type) ? 1500 : "canal".equals(type) ? 500 : 0) - (nm.isEmpty() ? 0 : 300);
                if (score < bestScore) {
                    bestScore = score;
                    String dist = d < 1000 ? (Math.round(d / 10) * 10) + "m" : String.format(Locale.US, "%.1fkm", d / 1000);
                    String wd = "";
                    try { double wv = Double.parseDouble(tg.optString("width", "").replaceAll("[^0-9.]", "")); if (wv >= 1 && wv < 5000) wd = " ~" + Math.round(wv) + "m"; } catch (Throwable ignored) { }
                    best = ("river".equals(type) ? "River" : "canal".equals(type) ? "Canal" : "Stream") + (nm.isEmpty() ? "" : " " + nm) + wd + " (" + dist + ")";
                }
            }
            nameKey = key; nameVal = best; nameAt = now; nameLat = lat; nameLon = lon;
            nameWhy = (els == null ? 0 : els.length()) + " waterways within 3 km -> " + (best.isEmpty() ? "none usable" : best);
            return best;
        } catch (Throwable t) {
            nameWhy = "name lookup error: " + t;
            return stale(key, lat, lon);
        }
    }

    /** "River 340 m³/s ▲ high", or "" when there is no river here (or the data is old). */
    static String text() {
        return System.currentTimeMillis() - okAt > 8 * 3600_000L ? "" : text;
    }

    static void tick(Hazards.Http http, double lat, double lon) {
        long now = System.currentTimeMillis();
        String key = String.format(Locale.US, "%.2f,%.2f", lat, lon);
        if (key.equals(lastKey) && now - okAt < 60 * 60_000L) return;
        if (now - triedAt < 5 * 60_000L) return;
        triedAt = now;
        try {
            String u = String.format(Locale.US, "https://flood-api.open-meteo.com/v1/flood?latitude=%.4f&longitude=%.4f"
                    + "&daily=river_discharge&past_days=30&forecast_days=1", lat, lon);
            org.json.JSONArray a = new org.json.JSONObject(http.get(u)).getJSONObject("daily").getJSONArray("river_discharge");
            int n = a.length();
            if (n < 8 || a.isNull(n - 1)) { text = ""; okAt = now; lastKey = key; why = "no river data here"; return; }
            double today = a.getDouble(n - 1);
            double yest = a.isNull(n - 2) ? today : a.getDouble(n - 2);
            java.util.List<Double> past = new java.util.ArrayList<>();
            for (int i = 0; i < n - 1; i++) if (!a.isNull(i)) past.add(a.getDouble(i));
            java.util.Collections.sort(past);
            double med = past.isEmpty() ? today : past.get(past.size() / 2);
            if (today < 1.0 && med < 1.0) { text = ""; okAt = now; lastKey = key; why = "no real river nearby (flow " + today + ")"; return; }
            String arrow = today > yest * 1.1 ? "\u25B2" : today < yest * 0.9 ? "\u25BC" : "\u2192";
            double ratio = med > 0.5 ? today / med : 1;
            String lvl = ratio >= 4 ? " very high" : ratio >= 2 ? " high" : ratio <= 0.5 ? " low" : "";
            String v = today >= 1000 ? String.format(Locale.US, "%.1fk", today / 1000) : today >= 10 ? String.valueOf(Math.round(today))
                    : String.format(Locale.US, "%.1f", today);
            String wn = waterName(lat, lon);
            text = (wn.isEmpty() ? "River" : wn) + " " + v + " m\u00b3/s " + arrow + lvl;
            okAt = wn.isEmpty() ? now - 55 * 60_000L : now;   // no name yet: try again in about 5 minutes
            lastKey = key;
            why = "ok " + text + " (30-day median " + Math.round(med) + "); " + nameWhy;
        } catch (Throwable t) {
            why = "failed: " + t;
        }
    }
}
