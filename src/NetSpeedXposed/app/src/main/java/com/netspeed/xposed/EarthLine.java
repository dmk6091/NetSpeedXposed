package com.netspeed.xposed;

import java.util.Locale;

/**
 * Extra earth-science facts for the rotating place line: rain and soil, slope and nearest peak, nearest dam,
 * last quake, ground temperature and district. Worker thread; every lookup is cached and failures leave the old text.
 * The status bar reads the strings and skips any that are empty.
 */
final class EarthLine {
    private EarthLine() {}

    static volatile String rain = "", peak = "", slope = "", dam = "", quake = "", ground = "", district = "";
    static volatile float dew = Float.NaN;
    static volatile String powerLine = "", powerSub = "";
    static volatile String soilType = "", soilSplit = "", soilMoist = "";
    static volatile String rockType = "", rockAge = "", rockUnit = "";
    private static String rockKey = "";
    private static long rockAt = 0;
    private static String soilKey = "";
    private static long soilAt, soilFailAt;
    static volatile String why = "not fetched yet";
    private static String key = "";
    private static long okAt, triedAt;
    private static String quakeKey = "";
    private static long quakeAt;

    private static String peakKey = "", damKey = "", powerKey = "";
    private static long peakAt, damAt, powerAt;
    private static final java.util.Set<String> RUNNING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Runs a slow lookup off the weather thread, one run per id at a time. */
    static void background(String id, Runnable r) {
        if (!RUNNING.add(id)) return;
        Thread t = new Thread(() -> { try { r.run(); } catch (Throwable ignored) { } finally { RUNNING.remove(id); } }, "nsx-" + id);
        t.setDaemon(true);
        t.start();
    }

    private static boolean fresh(String cell, String key, long at, long now) { return cell.equals(key) && now - at < 3 * 24 * 3600_000L; }

    static final String[] DIRS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    static String dir(double fromLat, double fromLon, double toLat, double toLon) {
        double y = Math.sin(Math.toRadians(toLon - fromLon)) * Math.cos(Math.toRadians(toLat));
        double x = Math.cos(Math.toRadians(fromLat)) * Math.sin(Math.toRadians(toLat))
                - Math.sin(Math.toRadians(fromLat)) * Math.cos(Math.toRadians(toLat)) * Math.cos(Math.toRadians(toLon - fromLon));
        double b = (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
        return DIRS[(int) Math.round(b / 45) % 8];
    }

    static double km(double la1, double lo1, double la2, double lo2) {
        double dx = (lo2 - lo1) * 111.32 * Math.cos(Math.toRadians((la1 + la2) / 2)), dy = (la2 - la1) * 110.54;
        return Math.sqrt(dx * dx + dy * dy);
    }

    static String dist(double km) {
        return km < 1 ? (Math.round(km * 100) * 10) + "m" : km < 10 ? String.format(Locale.US, "%.1fkm", km) : Math.round(km) + "km";
    }

    /** Sun's height above the horizon in degrees (negative = below), simple solar-position formula. */
    static double sunElevation(double lat, double lon, long ms) {
        double d = ms / 86400000.0 + 2440587.5 - 2451545.0;
        double g = Math.toRadians((357.529 + 0.98560028 * d) % 360);
        double q = (280.459 + 0.98564736 * d) % 360;
        double L = Math.toRadians((q + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g)) % 360);
        double e = Math.toRadians(23.439 - 0.00000036 * d);
        double ra = Math.atan2(Math.cos(e) * Math.sin(L), Math.cos(L));
        double dec = Math.asin(Math.sin(e) * Math.sin(L));
        double gmst = (280.46061837 + 360.98564736629 * d) % 360;
        double ha = Math.toRadians(gmst + lon) - ra;
        double la = Math.toRadians(lat);
        return Math.toDegrees(Math.asin(Math.sin(la) * Math.sin(dec) + Math.cos(la) * Math.cos(dec) * Math.cos(ha)));
    }

    /** Soil texture and other slow public services: a longer wait than the shared 8 s, one retry. */
    static String slowGet(String url) throws Exception {
        Exception last = null;
        for (int tries = 0; tries < 2; tries++) {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(10_000);
                c.setReadTimeout(30_000);
                c.setRequestProperty("User-Agent", "NetSpeedXposed/1.0 (status bar module; github.com/dmk6091/NetSpeedXposed)");
                if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String ln;
                    while ((ln = r.readLine()) != null) sb.append(ln);
                }
                return sb.toString();
            } catch (Exception e) {
                last = e;
                try { Thread.sleep(2_000); } catch (InterruptedException ie) { break; }
            }
        }
        throw last;
    }

    static String overpass(String q) {
        synchronized (Geo.OVP_LOCK) { return overpassRaw(q); }   // one Overpass request at a time (see Geo.overpass)
    }

    private static String overpassRaw(String q) {
        for (int round = 0; round < 2; round++) {   // a busy public server answers 429/504: the second round tries again after a pause
        if (round == 1) { try { Thread.sleep(4_000); } catch (InterruptedException ie) { break; } }
        for (String ep : new String[]{"https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter",
                "https://overpass.kumi.systems/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter"}) {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(ep + "?data="
                        + java.net.URLEncoder.encode(q, "UTF-8")).openConnection();
                c.setConnectTimeout(8_000);
                c.setReadTimeout(60_000);   // the public Overpass servers can queue a request for 40 s or more
                c.setRequestProperty("User-Agent", "NetSpeedXposed/1.0 (status bar module; github.com/dmk6091/NetSpeedXposed)");
                if (c.getResponseCode() != 200) continue;
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String ln;
                    while ((ln = r.readLine()) != null) sb.append(ln);
                }
                if (sb.length() > 0) return sb.toString();
            } catch (Throwable ignored) { }
        }
        }
        return "";
    }

    private static String num(double v) { return String.format(Locale.US, "%,d", Math.round(v)); }

    static void tick(Hazards.Http http, double lat, double lon, boolean wantRain, boolean wantLand, boolean wantQuake,
                     boolean wantEarth, boolean wantDam, boolean wantSoil, boolean wantPower) {
        long now = System.currentTimeMillis();
        String k = String.format(Locale.US, "%.2f,%.2f", lat, lon);
        // dams, peaks and power lines hardly ever change: one lookup per ~2 km cell, reused for 3 days
        final String cell = Math.round(lat / 0.02) + "," + Math.round(lon / 0.02);
        boolean same = k.equals(key);
        if (same && now - okAt < 30 * 60_000L) return;
        if (now - triedAt < 5 * 60_000L) return;
        triedAt = now;
        String w = "";
        // rain, soil, ground temperature, dew point: one Open-Meteo call
        if (wantRain || wantEarth) try {
            org.json.JSONObject h = new org.json.JSONObject(http.get(String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&hourly=precipitation,soil_moisture_0_to_7cm,soil_temperature_0cm,dew_point_2m&past_hours=24&forecast_hours=25&timezone=auto",
                    lat, lon))).getJSONObject("hourly");
            org.json.JSONArray pr = h.getJSONArray("precipitation");
            int n = pr.length(), nowI = Math.min(24, n - 1);
            double past = 0, next = 0;
            for (int i = 0; i < n; i++) if (!pr.isNull(i)) { if (i <= nowI) past += pr.getDouble(i); else next += pr.getDouble(i); }
            org.json.JSONArray sm = h.optJSONArray("soil_moisture_0_to_7cm");
            String soil = sm != null && nowI < sm.length() && !sm.isNull(nowI) ? " · soil " + Math.round(sm.getDouble(nowI) * 100) + "%" : "";
            rain = "🌧 24h " + fmtMm(past) + " · next 24h " + fmtMm(next) + soil;
            soilMoist = sm != null && nowI < sm.length() && !sm.isNull(nowI) ? "moist " + Math.round(sm.getDouble(nowI) * 100) + "%" : "";
            org.json.JSONArray st = h.optJSONArray("soil_temperature_0cm");
            ground = st != null && nowI < st.length() && !st.isNull(nowI) ? "ground " + Math.round(st.getDouble(nowI)) + "°" : "";
            org.json.JSONArray dw = h.optJSONArray("dew_point_2m");
            dew = dw != null && nowI < dw.length() && !dw.isNull(nowI) ? (float) dw.getDouble(nowI) : Float.NaN;
            w += "rain ok; ";
        } catch (Throwable t) { w += "rain " + t.getClass().getSimpleName() + "; "; }
        // slope from three ground heights 200 m apart, nearest named peak within 30 km
        // last quake within 300 km, 30 days (USGS)
        if (wantQuake && (!k.equals(quakeKey) || now - quakeAt > 30 * 60_000L)) try {
            String from = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date(now - 30L * 86400_000L));
            org.json.JSONArray fs = new org.json.JSONObject(http.get(String.format(Locale.US,
                    "https://earthquake.usgs.gov/fdsnws/event/1/query?format=geojson&latitude=%.3f&longitude=%.3f&maxradiuskm=300"
                            + "&minmagnitude=2.5&limit=1&orderby=time&starttime=%s", lat, lon, from))).getJSONArray("features");
            if (fs.length() == 0) quake = "no quake M2.5+ within 300km in 30 days";
            else {
                org.json.JSONObject f = fs.getJSONObject(0);
                org.json.JSONArray co = f.getJSONObject("geometry").getJSONArray("coordinates");
                double la = co.getDouble(1), lo = co.getDouble(0);
                long age = (now - f.getJSONObject("properties").getLong("time")) / 3600_000L;
                quake = String.format(Locale.US, "last quake M%.1f %s %s · %s ago", f.getJSONObject("properties").optDouble("mag", 0),
                        dist(km(lat, lon, la, lo)), dir(lat, lon, la, lo), age < 48 ? age + "h" : (age / 24) + "d");
            }
            quakeKey = k;
            quakeAt = now;
            w += "quake ok; ";
        } catch (Throwable t) { w += "quake " + t.getClass().getSimpleName() + "; "; }
        // full place: taluka, district, state
        if (wantEarth) try {
            org.json.JSONObject a = new org.json.JSONObject(http.get(String.format(Locale.US,
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=10&accept-language=en&lat=%.4f&lon=%.4f", lat, lon))).optJSONObject("address");
            StringBuilder sb = new StringBuilder();
            if (a != null) {
                String co = a.optString("county", "").replaceAll("(?i)\\s+(sub-?district|taluka|tehsil|tahsil|block|mandal)$", "").trim();
                String di = a.optString("state_district", "").replaceAll("(?i)\\s+district$", "").trim(), st = a.optString("state", "");
                if (!co.isEmpty()) sb.append(co).append(" taluka");
                if (!di.isEmpty() && !di.equalsIgnoreCase(co)) sb.append(sb.length() > 0 ? ", " : "").append(di).append(" district");
                if (!st.isEmpty()) sb.append(sb.length() > 0 ? ", " : "").append(st);
            }
            district = sb.toString();
            w += "place " + (district.isEmpty() ? "none" : district) + "; ";
        } catch (Throwable t) { w += "place " + t.getClass().getSimpleName() + "; "; }
        // soil texture from ISRIC SoilGrids (top 5 cm), cached a day per ~2 km
        String sk = String.format(Locale.US, "%.2f,%.2f", lat, lon);
        if (wantSoil && (!sk.equals(soilKey) || now - soilAt > 24 * 3600_000L) && now - soilFailAt > 10 * 60_000L) try {
            // the point itself; if SoilGrids answers "no data" there (built-up land), the next 3 points 2 km away, one at a time
            // (SoilGrids allows only about 5 requests a minute, and an error or timeout on the first point means: wait 10 minutes)
            double kx = 111.0 * Math.cos(Math.toRadians(lat));
            final double[][] pts = {{lat, lon}, {lat + 2 / 111.0, lon}, {lat, lon + 2 / kx}, {lat - 2 / 111.0, lon}};
            double clay = Double.NaN, sand = Double.NaN, silt = Double.NaN;
            try {
                for (int i = 0; i < pts.length && Double.isNaN(clay); i++) {
                    double[] r = Geo.soilAtSlow(pts[i][0], pts[i][1]);
                    if (r != null) { sand = r[0]; silt = r[1]; clay = r[2]; }
                }
            } catch (Throwable t) { soilFailAt = now; throw t; }
            if (Double.isNaN(clay) || Double.isNaN(sand) || Double.isNaN(silt)) { soilType = ""; soilSplit = ""; w += "soil no data; "; }
            else {
                soilType = texture(sand, silt, clay);
                soilSplit = "clay " + Math.round(clay) + "% \u00b7 silt " + Math.round(silt) + "% \u00b7 sand " + Math.round(sand) + "%";
                w += "soil " + soilType + "; ";
            }
            soilKey = sk;
            soilAt = now;
        } catch (Throwable t) { w += "soil " + t.getClass().getSimpleName() + "; "; }
        // bedrock type from Macrostrat's world geology map (coarse: a regional rock type, not a local survey), cached a week per ~5 km
        String rk = String.format(Locale.US, "%.2f,%.2f", Math.round(lat / 0.05) * 0.05, Math.round(lon / 0.05) * 0.05);
        if (Cfg.b(Cfg.EL_ROCK, Cfg.D_EL_ROCK) && (!rk.equals(rockKey) || now - rockAt > 7 * 86400_000L)) try {
            org.json.JSONArray da = new org.json.JSONObject(slowGet(String.format(Locale.US,
                    "https://macrostrat.org/api/v2/geologic_units/map?lat=%.4f&lng=%.4f&response=long", lat, lon))).getJSONObject("success").getJSONArray("data");
            if (da.length() == 0) { rockType = ""; rockAge = ""; rockUnit = ""; w += "rock no data; "; }
            else {
                org.json.JSONObject u = da.getJSONObject(0);
                String lith = u.optString("lith", "").trim();
                String first = lith.split(";")[0].trim().replaceAll("\\(s\\)", "").replaceAll("\\s+", " ");
                if (first.isEmpty()) first = u.optString("name", "").trim();
                rockType = first.isEmpty() ? "" : Character.toUpperCase(first.charAt(0)) + first.substring(1);
                rockUnit = u.optString("name", "").trim();
                double ta = u.optDouble("t_age", Double.NaN), ba = u.optDouble("b_age", Double.NaN);
                rockAge = Double.isNaN(ta) || Double.isNaN(ba) ? "" : (Math.round(ta) == Math.round(ba) ? Math.round(ta) + " Ma" : Math.round(ta) + "\u2013" + Math.round(ba) + " Ma");
                w += "rock " + rockType + "; ";
            }
            rockKey = rk;
            rockAt = now;
        } catch (Throwable t) { w += "rock " + t.getClass().getSimpleName() + "; "; }
        if (wantSoil && soilType.isEmpty() && rockType.toLowerCase(Locale.ROOT).contains("basalt")) {   // no measurement: the usual soil on Deccan basalt
            soilType = "clay (est.)";
            soilSplit = "estimated from basalt rock, not measured";
        }
        if (wantLand) {
            final int pkKm = Math.max(10, Math.min(60, Cfg.i(Cfg.EL_PEAK_KM, Cfg.D_EL_PEAK_KM)));
            final String pkCell = cell + "|" + pkKm;
            if (!fresh(pkCell, peakKey, peakAt, now)) try {
                String r = overpass(String.format(Locale.US, "[out:json][timeout:60];node(around:%d,%.5f,%.5f)[\"natural\"=\"peak\"][\"name\"];out 40;", pkKm * 1000, lat, lon));
                org.json.JSONArray els = r.isEmpty() ? null : new org.json.JSONObject(r).optJSONArray("elements");
                String best = "";
                double bd = 1e9;
                for (int i = 0; els != null && i < els.length(); i++) {
                    org.json.JSONObject e = els.getJSONObject(i);
                    double d = km(lat, lon, e.getDouble("lat"), e.getDouble("lon"));
                    if (d < bd) {
                        bd = d;
                        org.json.JSONObject tg = e.optJSONObject("tags");
                        String nm = tg == null ? "" : tg.optString("name:en", tg.optString("name", ""));
                        String ele = tg == null ? "" : tg.optString("ele", "");
                        double ev = 0;
                        try { ev = Double.parseDouble(ele.replaceAll("[^0-9.]", "")); } catch (Throwable ignored) { }
                        best = nm.isEmpty() || nm.length() > 24 ? "" : "⛰ " + nm + " " + dist(d) + " " + dir(lat, lon, e.getDouble("lat"), e.getDouble("lon"))
                                + (ev > 0 ? " " + num(ev) + "m" : "");
                    }
                }
                if (els != null) { peak = best; peakKey = pkCell; peakAt = now; }
                w += (els == null ? "peak lookup failed; " : els.length() + " peaks; ");
            } catch (Throwable t) { w += "peak " + t.getClass().getSimpleName() + "; "; }
        }
        // nearest dam within 10 km
        final int dmKm = Math.max(3, Math.min(20, Cfg.i(Cfg.EL_DAM_KM, Cfg.D_EL_DAM_KM)));
        final String dmCell = cell + "|" + dmKm;
        if (wantDam && !fresh(dmCell, damKey, damAt, now)) try {
            double d0 = dmKm / 111.0, d1 = d0 / Math.cos(Math.toRadians(lat));
            String r = overpass(String.format(Locale.US, "[out:json][timeout:60];(nwr[\"waterway\"=\"dam\"](%1$.4f,%2$.4f,%3$.4f,%4$.4f);"
                    + "nwr[\"man_made\"=\"dam\"](%1$.4f,%2$.4f,%3$.4f,%4$.4f););out center tags 30;", lat - d0, lon - d1, lat + d0, lon + d1));
            org.json.JSONArray els = r.isEmpty() ? null : new org.json.JSONObject(r).optJSONArray("elements");
            String best = "";
            double bd = 1e9, osmKm = 1e9;
            for (int i = 0; els != null && i < els.length(); i++) {
                org.json.JSONObject e = els.getJSONObject(i);
                org.json.JSONObject ce = e.optJSONObject("center");
                double la = ce != null ? ce.getDouble("lat") : e.optDouble("lat", Double.NaN), lo = ce != null ? ce.getDouble("lon") : e.optDouble("lon", Double.NaN);
                if (Double.isNaN(la)) continue;
                double d = km(lat, lon, la, lo);
                org.json.JSONObject tg = e.optJSONObject("tags");
                String nm = tg == null ? "" : tg.optString("name:en", tg.optString("name", ""));
                if (nm.length() > 26) nm = "";
                double sc = d - (nm.isEmpty() ? 0 : 2);
                if (sc < bd) {
                    bd = sc;
                    nm = nm.replaceAll("(?i)\\s+(dam|barrage)$", "").trim();
                    best = "🏞 " + (nm.isEmpty() ? "Dam" : nm + " Dam") + " " + dist(d) + " " + dir(lat, lon, la, lo);
                    osmKm = d;
                }
            }
            // Maharashtra's national dam register (names and positions only): used when it has a dam clearly nearer than the map's
            String regNote = "";
            try {
                java.util.List<DamReg.Hit> rh = DamReg.nearest(lat, lon, 1, dmKm);
                if (!rh.isEmpty() && (best.isEmpty() || rh.get(0).km + 0.5 < osmKm)) {
                    DamReg.Hit h = rh.get(0);
                    best = "🏞 " + h.name.replaceAll("(?i)\\s+(dam|barrage)$", "") + " Dam " + dist(h.km) + " " + h.dir;
                    regNote = " (national dam register)";
                }
            } catch (Throwable ignored) { }
            if (els != null || !best.isEmpty()) { dam = best; damKey = dmCell; damAt = now; }
            w += (els == null ? "dam lookup failed; " : els.length() + " dams found, nearest: " + (best.isEmpty() ? "none" : best.replace("\uD83C\uDFDE ", "") + regNote) + "; ");
        } catch (Throwable t) { w += "dam " + t.getClass().getSimpleName() + "; "; }
        // nearest power line and substation within about 5 km (OpenStreetMap)
        final int pwKm = Math.max(2, Math.min(10, Cfg.i(Cfg.EL_POWER_KM, Cfg.D_EL_POWER_KM)));
        final String pwCell = cell + "|" + pwKm;
        if (wantPower && !fresh(pwCell, powerKey, powerAt, now)) try {
            double d0 = pwKm / 111.0, d1 = d0 / Math.cos(Math.toRadians(lat));
            String bb = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", lat - d0, lon - d1, lat + d0, lon + d1);
            String r = overpass("[out:json][timeout:60];(way[\"power\"=\"line\"](" + bb + ");nwr[\"power\"=\"substation\"](" + bb + "););out tags geom(" + bb + ") 60;");
            org.json.JSONArray els = r.isEmpty() ? null : new org.json.JSONObject(r).optJSONArray("elements");
            double ky = 110540, kx = 111320 * Math.cos(Math.toRadians(lat));
            double bl = 1e18, bsub = 1e18;
            String lineTxt = "", subTxt = "";
            for (int i = 0; els != null && i < els.length(); i++) {
                org.json.JSONObject e = els.getJSONObject(i);
                org.json.JSONObject tg = e.optJSONObject("tags");
                if (tg == null) continue;
                java.util.List<double[]> pts = new java.util.ArrayList<>();
                org.json.JSONArray g = e.optJSONArray("geometry");
                for (int j = 0; g != null && j < g.length(); j++) { org.json.JSONObject q = g.optJSONObject(j); if (q != null) pts.add(new double[]{q.getDouble("lat"), q.getDouble("lon")}); }
                if (pts.isEmpty() && e.has("lat")) pts.add(new double[]{e.getDouble("lat"), e.getDouble("lon")});
                double best = 1e18, bla = 0, blo = 0;
                for (int j = 0; j < pts.size(); j++) {
                    double ax = (pts.get(j)[1] - lon) * kx, ay = (pts.get(j)[0] - lat) * ky;
                    double bx = ax, by = ay;
                    if (j + 1 < pts.size()) { bx = (pts.get(j + 1)[1] - lon) * kx; by = (pts.get(j + 1)[0] - lat) * ky; }
                    double dx = bx - ax, dy = by - ay, L2 = dx * dx + dy * dy;
                    double t = L2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / L2));
                    double px = ax + t * dx, py = ay + t * dy, d = Math.sqrt(px * px + py * py);
                    if (d < best) { best = d; bla = lat + py / ky; blo = lon + px / kx; }
                }
                if (best > 1e17) continue;
                String kind = tg.optString("power", "");
                double km = best / 1000.0;
                if ("line".equals(kind) && best < bl) {
                    bl = best;
                    double kv = 0;
                    for (String v : tg.optString("voltage", "").split("[;,]")) { try { kv = Math.max(kv, Double.parseDouble(v.trim()) / 1000.0); } catch (Throwable ignored) { } }
                    String op = tg.optString("operator", "");
                    if (op.length() > 12) op = tg.optString("operator:short", "");
                    if (op.length() > 12) op = "";
                    lineTxt = "\u26A1 " + (kv > 0 ? Math.round(kv) + "kV " : "line ") + (op.isEmpty() ? "" : op + " ") + dist(km) + " " + dir(lat, lon, bla, blo);
                } else if ("substation".equals(kind) && best < bsub) {
                    bsub = best;
                    String nm = tg.optString("name:en", tg.optString("name", ""));
                    if (nm.length() > 22) nm = "";
                    subTxt = (nm.isEmpty() ? "Substation" : nm.replaceAll("(?i)\\s*(substation|s/s)$", "") + " S/S") + " " + dist(km) + " " + dir(lat, lon, bla, blo);
                }
            }
            if (els != null) { powerLine = lineTxt; powerSub = subTxt; powerKey = pwCell; powerAt = now; }
            w += (els == null ? "power lookup failed; " : "power " + els.length() + " items; ");
        } catch (Throwable t) { w += "power " + t.getClass().getSimpleName() + "; "; }
        key = k;
        okAt = now;
        why = w;
    }

    /** USDA soil texture class from percentages of sand, silt and clay. */
    static String texture(double sa, double si, double cl) {
        if (si + 1.5 * cl < 15) return "sand";
        if (si + 1.5 * cl >= 15 && si + 2 * cl < 30) return "loamy sand";
        if ((cl >= 7 && cl < 20 && sa > 52 && si + 2 * cl >= 30) || (cl < 7 && si < 50 && si + 2 * cl >= 30)) return "sandy loam";
        if (cl >= 7 && cl < 27 && si >= 28 && si < 50 && sa <= 52) return "loam";
        if ((si >= 50 && cl >= 12 && cl < 27) || (si >= 50 && si < 80 && cl < 12)) return "silt loam";
        if (si >= 80 && cl < 12) return "silt";
        if (cl >= 20 && cl < 35 && si < 28 && sa > 45) return "sandy clay loam";
        if (cl >= 27 && cl < 40 && sa > 20 && sa <= 45) return "clay loam";
        if (cl >= 27 && cl < 40 && sa <= 20) return "silty clay loam";
        if (cl >= 35 && sa > 45) return "sandy clay";
        if (cl >= 40 && si >= 40) return "silty clay";
        if (cl >= 40) return "clay";
        return "loam";
    }

    private static String fmtMm(double mm) { return mm < 0.1 ? "0mm" : mm < 10 ? String.format(Locale.US, "%.1fmm", mm) : Math.round(mm) + "mm"; }
}
