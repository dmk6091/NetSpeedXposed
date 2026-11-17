package com.netspeed.xposed;

import java.util.Locale;

/** Soil and rock lookups for the map and the wallpaper (the app's own process; slow public servers, so patient). */
final class Geo {
    private Geo() {}

    static String get(String url, int readMs) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(readMs);
        c.setRequestProperty("User-Agent", "FieldAtlas/1.0 (github.com/dmk6091/NetSpeedXposed)");
        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String ln;
            while ((ln = r.readLine()) != null) sb.append(ln);
        }
        return sb.toString();
    }

    /** Shared by every Overpass request of this process (Geo, EarthLine, RiverFlow). */
    static final Object OVP_LOCK = new Object();

    private static final String[] OVERPASS ={"https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter"};

    /** Runs an Overpass query on the first server that answers (they are often busy); throws the last error if none does. */
    static String overpass(String query, int readMs) throws Exception {
        StringBuilder why = new StringBuilder();   // what each server said, so a failure can be understood
        String q = java.net.URLEncoder.encode(query, "UTF-8");
        synchronized (OVP_LOCK) {   // one Overpass request at a time: the public servers give each phone only 2 slots and answer the rest with an error
            for (int round = 0; round < 2; round++) {
                if (round == 1) { try { Thread.sleep(6_000); } catch (InterruptedException ie) { break; } }
                for (String ep : OVERPASS) {
                    String host = ep.replaceFirst("^https://([^/]+)/.*$", "$1").replaceFirst("^(www\\.|maps\\.)", "");
                    try {
                        String r = get(ep + "?data=" + q, readMs);
                        if (r.contains("\"elements\"")) return r;
                        why.append(host).append(": no data in answer; ");
                    } catch (Exception e) {
                        why.append(host).append(": ").append(e instanceof java.net.SocketTimeoutException ? "timeout" : String.valueOf(e.getMessage())).append("; ");
                    }
                }
            }
        }
        throw new java.io.IOException(why.toString().trim());
    }

    /** Same as soilAt but patient (30 s, one retry); for the status bar, which asks one point at a time. */
    static double[] soilAtSlow(double lat, double lon) throws Exception {
        return soilParse(EarthLine.slowGet(String.format(Locale.US,
                "https://rest.isric.org/soilgrids/v2.0/properties/query?lon=%.4f&lat=%.4f&property=clay&property=sand&property=silt&depth=0-5cm&value=mean",
                lon, lat)));
    }

    /** {sand, silt, clay} in % for the top 5 cm, or null when SoilGrids has no data at that point. */
    static double[] soilAt(double lat, double lon) throws Exception {
        return soilParse(get(String.format(Locale.US,
                "https://rest.isric.org/soilgrids/v2.0/properties/query?lon=%.4f&lat=%.4f&property=clay&property=sand&property=silt&depth=0-5cm&value=mean",
                lon, lat), 25_000));
    }

    private static double[] soilParse(String body) throws Exception {
        org.json.JSONArray ly = new org.json.JSONObject(body).getJSONObject("properties").getJSONArray("layers");
        double clay = Double.NaN, sand = Double.NaN, silt = Double.NaN;
        for (int i = 0; i < ly.length(); i++) {
            org.json.JSONObject l = ly.getJSONObject(i);
            org.json.JSONObject v = l.getJSONArray("depths").getJSONObject(0).getJSONObject("values");
            if (v.isNull("mean")) continue;
            double pc = v.getDouble("mean") / 10.0;
            String nm = l.getString("name");
            if (nm.equals("clay")) clay = pc; else if (nm.equals("sand")) sand = pc; else if (nm.equals("silt")) silt = pc;
        }
        return Double.isNaN(clay) || Double.isNaN(sand) || Double.isNaN(silt) ? null : new double[]{sand, silt, clay};
    }

    /** Soil near a point: the point itself, else the nearest land with data within 10 km. Text like "Clay loam · sand 30% · silt 34% · clay 36%"; "" if none. */
    static String soilText(double lat, double lon) {
        try {
            double kx = 111.0 * Math.cos(Math.toRadians(lat));
            double[][] pts = {{lat, lon}, {lat + 2 / 111.0, lon}, {lat, lon + 2 / kx}, {lat - 2 / 111.0, lon}, {lat, lon - 2 / kx},
                    {lat + 6 / 111.0, lon}, {lat, lon + 6 / kx}, {lat - 6 / 111.0, lon}, {lat, lon - 6 / kx}};
            for (int round = 0; round < 3; round++) {
                int a = round == 0 ? 0 : round == 1 ? 5 : 5, b = round == 0 ? 5 : 9;
                if (round == 2) break;
                final double[][] res = new double[b - a][];
                Thread[] th = new Thread[b - a];
                for (int i = a; i < b; i++) {
                    final int k = i - a, j = i;
                    th[k] = new Thread(() -> { try { res[k] = soilAt(pts[j][0], pts[j][1]); } catch (Throwable ignored) { } });
                    th[k].start();
                }
                for (Thread t : th) t.join();
                for (double[] r : res) if (r != null) {
                    String t = EarthLine.texture(r[0], r[1], r[2]);
                    return Character.toUpperCase(t.charAt(0)) + t.substring(1) + String.format(Locale.US, " · sand %.0f%% · silt %.0f%% · clay %.0f%%", r[0], r[1], r[2]);
                }
            }
        } catch (Throwable ignored) { }
        return "";
    }

    /** Bedrock from Macrostrat: "Flood basalt · 23–145 Ma · Mesozoic-Cenozoic volcanic rocks"; "" if none. */
    static String rockText(double lat, double lon) {
        try {
            org.json.JSONArray da = new org.json.JSONObject(get(String.format(Locale.US,
                    "https://macrostrat.org/api/v2/geologic_units/map?lat=%.4f&lng=%.4f&response=long", lat, lon), 25_000)).getJSONObject("success").getJSONArray("data");
            if (da.length() == 0) return "";
            org.json.JSONObject u = da.getJSONObject(0);
            String first = u.optString("lith", "").split(";")[0].trim().replaceAll("\\(s\\)", "").replaceAll("\\s+", " ");
            if (first.isEmpty()) first = u.optString("name", "").trim();
            if (first.isEmpty()) return "";
            double ta = u.optDouble("t_age", Double.NaN), ba = u.optDouble("b_age", Double.NaN);
            String age = Double.isNaN(ta) || Double.isNaN(ba) ? "" : (Math.round(ta) == Math.round(ba) ? Math.round(ta) + " Ma" : Math.round(ta) + "–" + Math.round(ba) + " Ma");
            String unit = u.optString("name", "").trim();
            return Character.toUpperCase(first.charAt(0)) + first.substring(1) + (age.isEmpty() ? "" : " · " + age) + (unit.isEmpty() ? "" : " · " + unit);
        } catch (Throwable t) {
            return "";
        }
    }
}
