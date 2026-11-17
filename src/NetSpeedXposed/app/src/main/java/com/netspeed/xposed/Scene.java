package com.netspeed.xposed;

import android.content.Context;

import java.util.Locale;

/**
 * What kind of place you are in, for the skyline along the bottom of the status bar: city / town / village from
 * OpenStreetMap's place type (tower count as a fallback), hills from the terrain around you (Open-Meteo elevation),
 * coast from the distance to the sea. Worker thread; the status bar only reads the result.
 */
final class Scene {
    private Scene() {}

    static final int CITY = 0, TOWN = 1, VILLAGE = 2;
    /** Urban class of this place (CITY / TOWN / VILLAGE), -1 unknown. */
    static volatile int urban = -1;
    static volatile boolean hills, coast;
    static volatile float elev = Float.NaN, relief = Float.NaN;
    static volatile String why = "not checked yet";

    private static String cell = "";
    private static long at, tried;

    static void refresh(Context ctx) {
        try {
            android.location.Location me = TowerDb.myLocation(ctx);
            if (me == null) return;
            double lat = me.getLatitude(), lon = me.getLongitude();
            long now = System.currentTimeMillis();
            String c = String.format(Locale.US, "%.2f,%.2f", lat, lon);
            // sea distance is local and cheap: always fresh
            try { coast = Tide.seaKm(lat, lon) <= 4; } catch (Throwable ignored) { }
            if (c.equals(cell) && now - at < 6 * 3600_000L) return;
            if (now - tried < 10 * 60_000L) return;     // gentle on the free services
            tried = now;
            StringBuilder w = new StringBuilder();
            // place type
            int u = -1;
            try {
                String t = osmClass(lat, lon);
                w.append("place type ").append(t);
                if (t != null) {
                    switch (t) {
                        case "city": case "suburb": case "city_district": case "borough": case "quarter": case "neighbourhood": u = CITY; break;
                        case "town": u = TOWN; break;
                        case "village": case "hamlet": case "isolated_dwelling": case "farm": u = VILLAGE; break;
                        default: break;
                    }
                }
            } catch (Throwable e) { w.append("place type failed"); }
            if (u < 0) {
                // fallback: how many cells the phone hears
                TowerMap.Snap sn = TowerMap.SNAP;
                int n = sn == null ? -1 : sn.cells;
                u = n >= 10 ? CITY : n >= 5 ? TOWN : n >= 0 ? VILLAGE : -1;
                w.append(", towers ").append(n);
            }
            urban = u;
            // terrain
            try {
                double d = 0.04;   // about 4.5 km
                String url = String.format(Locale.US, "https://api.open-meteo.com/v1/elevation?latitude=%.5f,%.5f,%.5f,%.5f,%.5f&longitude=%.5f,%.5f,%.5f,%.5f,%.5f",
                        lat, lat + d, lat - d, lat, lat, lon, lon, lon, lon + d, lon - d);
                org.json.JSONArray a = new org.json.JSONObject(get(url)).getJSONArray("elevation");
                double mn = 1e9, mx = -1e9;
                for (int i = 0; i < a.length(); i++) { mn = Math.min(mn, a.getDouble(i)); mx = Math.max(mx, a.getDouble(i)); }
                elev = (float) a.getDouble(0);
                relief = (float) (mx - mn);
                hills = relief >= 120 || elev >= 700;
                w.append(String.format(Locale.US, ", elevation %.0f m, relief %.0f m", elev, relief));
            } catch (Throwable e) { w.append(", terrain failed"); }
            cell = c;
            at = now;
            why = w.toString();
        } catch (Throwable ignored) { }
    }

    private static String osmClass(double lat, double lon) throws Exception {
        String url = String.format(Locale.US, "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.5f&lon=%.5f&zoom=12&addressdetails=1&accept-language=en", lat, lon);
        org.json.JSONObject o = new org.json.JSONObject(get(url));
        String t = o.optString("addresstype", "");
        if (t.isEmpty()) {
            org.json.JSONObject a = o.optJSONObject("address");
            if (a != null) {
                for (String k : new String[]{"city", "town", "village", "hamlet", "suburb"}) if (a.has(k)) return k;
            }
        }
        return t.isEmpty() ? null : t;
    }

    static String get(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(8_000);
        c.setReadTimeout(8_000);
        c.setRequestProperty("User-Agent", "NetSpeedXposed/1.0 (status bar module; github.com/dmk6091/NetSpeedXposed)");
        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
