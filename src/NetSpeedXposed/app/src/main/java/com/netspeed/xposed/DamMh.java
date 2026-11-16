package com.netspeed.xposed;

import java.util.Locale;

/**
 * Pune dams that CWC does not list (Kasarsai, Jadhavwadi, ...): a fixed list of dams whose pages exist on
 * mumbailakewaterlevel.in (an independent site that republishes Maharashtra WRD's daily live storage:
 * /maharashtra-dams/pune/&lt;dam&gt;). Each dam's position is looked up once by name (OpenStreetMap's place search, the
 * same service the district lookup uses) and kept on the phone. Used by {@link Dam#refresh}; a failure leaves the CWC dam as it was.
 */
final class DamMh {
    private DamMh() {}

    static final class Hit {
        Dam.Res res;
        Dam.Reading rd;
        double km;
    }

    /** {site slug, place-search name, shown name}; every slug was checked against the site on 2026-11-15. */
    private static final String[][] LIST = {
            {"kasarsai", "Kasarsai Dam", "Kasarsai"},
            {"jadhavwadi", "Jadhavwadi Dam", "Jadhavwadi"},
            {"khadakwasla", "Khadakwasla Dam", "Khadakwasla"},
            {"panshet", "Panshet Dam", "Panshet"},
            {"temghar", "Temghar Dam", "Temghar"},
            {"pawana", "Pawana Dam", "Pavana"},
            {"chaskaman", "Chaskaman Dam", "Chaskaman"},
            {"bhama-askhed", "Bhama Askhed Dam", "Bhama Askhed"},
            {"kundali-tata", "Kundali Dam", "Kundali"},
            {"gunjawani", "Gunjawani Dam", "Gunjawani"},
            {"bhatghar", "Bhatghar Dam", "Bhatghar"},
            {"dimbhe", "Dimbhe Dam", "Dimbhe"},
    };

    /** Rough positions (about 5 km), used only when the place search does not know the dam. */
    private static final java.util.Map<String, double[]> APPROX = new java.util.HashMap<>();
    static {
        APPROX.put("pawana", new double[]{18.69, 73.50});
        APPROX.put("bhama-askhed", new double[]{18.84, 73.76});
        APPROX.put("kundali-tata", new double[]{18.76, 73.47});
        APPROX.put("gunjawani", new double[]{18.29, 73.60});
        APPROX.put("bhatghar", new double[]{18.17, 73.80});
    }

    static volatile String why = "not tried yet";
    private static final double[][] pos = new double[LIST.length][];   // {lat, lon} once looked up
    private static boolean posLoaded;
    private static String key = "";
    private static long at;
    private static Hit last;

    private static android.app.Application app() {
        try { return (android.app.Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null); }
        catch (Throwable t) { return null; }
    }

    private static String districtText() {
        String d = EarthLine.district == null ? "" : EarthLine.district;
        if (!d.isEmpty()) return d;
        try {
            android.app.Application ap = app();
            String g = ap == null ? null : android.provider.Settings.Global.getString(ap.getContentResolver(), "netspeed_district");
            return g == null ? "" : g;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String districtSlug() {
        String d = districtText();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([A-Za-z .'-]+?)\\s+district").matcher(d);
        String s = "";
        while (m.find()) s = m.group(1);
        s = s.replaceAll("^.*,\\s*", "").trim().toLowerCase(Locale.ROOT);
        return s.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private static String distKey = "", distVal = "", distWhy = "";

    /** The district's slug: from EarthLine when it has one, else a reverse lookup of its own (cached per 0.1 degree). "" outside Maharashtra. */
    private static String districtFor(double lat, double lon) {
        String d = districtSlug();
        if (!d.isEmpty()) { distWhy = ""; return d; }
        String k = String.format(Locale.US, "%.1f,%.1f", lat, lon);
        if (k.equals(distKey) && (!distVal.isEmpty() || !distWhy.startsWith("district lookup failed"))) return distVal;
        try {
            org.json.JSONObject a = new org.json.JSONObject(get(String.format(Locale.US,
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=10&accept-language=en&lat=%.4f&lon=%.4f", lat, lon), 15_000)).optJSONObject("address");
            String st = a == null ? "" : a.optString("state", "");
            String di = a == null ? "" : a.optString("state_district", "").replaceAll("(?i)\\s+district$", "").trim();
            distKey = k;
            if (!st.equalsIgnoreCase("Maharashtra")) { distVal = ""; distWhy = "not in Maharashtra (" + (st.isEmpty() ? "unknown" : st) + ")"; return ""; }
            distVal = di.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            distWhy = distVal.isEmpty() ? "district not given by OpenStreetMap" : "";
            return distVal;
        } catch (Throwable t) {
            distKey = k; distVal = "";
            distWhy = "district lookup failed: " + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : " " + t.getMessage());
            return "";
        }
    }

    private static String get(String url, int timeout) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(8_000);
        c.setReadTimeout(timeout);
        c.setRequestProperty("User-Agent", "NetSpeedXposed/1.0 (status bar module; github.com/dmk6091/NetSpeedXposed)");
        int rc = c.getResponseCode();
        if (rc != 200) throw new java.io.IOException("HTTP " + rc);
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String ln;
            while ((ln = r.readLine()) != null) sb.append(ln).append('\n');
        }
        return sb.toString();
    }

    private static double num(String s) {
        return Double.parseDouble(s.replace(",", ""));
    }

    /**
     * Percent, stored ML and capacity ML from a dam page. null when it is not that dam's page: the site answers an unknown
     * name with its Mumbai home page, which has no "today's storage" and never carries the dam's own name.
     */
    static Dam.Reading parse(String html, String slug) {
        String first = slug.split("-")[0];
        if (!html.toLowerCase(Locale.ROOT).contains(first)) return null;
        String pct = null, date = "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?is)<title[^>]*>[^<]*?([0-9]+(?:\\.[0-9]+)?)\\s*%\\s*\\(([^)]{6,20})\\)").matcher(html);
        if (m.find()) { pct = m.group(1); date = m.group(2); }
        String txt = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ").replaceAll("(?s)<[^>]+>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ");
        if (pct == null) {
            m = java.util.regex.Pattern.compile("(?i)today'?s storage\\s*([0-9]+(?:\\.[0-9]+)?)\\s*%").matcher(txt);
            if (m.find()) pct = m.group(1);
        }
        if (pct == null) return null;
        double p = num(pct);
        double stored = Double.NaN, cap = Double.NaN;
        m = java.util.regex.Pattern.compile("(?i)stored\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*ML").matcher(txt);
        if (m.find()) stored = num(m.group(1));
        if (Double.isNaN(stored)) {
            m = java.util.regex.Pattern.compile("(?i)([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*ML\\s*stored").matcher(txt);
            if (m.find()) stored = num(m.group(1));
        }
        m = java.util.regex.Pattern.compile("(?i)capacity\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*ML").matcher(txt);
        if (m.find()) cap = num(m.group(1));
        if (Double.isNaN(cap) && !Double.isNaN(stored) && p > 0) cap = stored / p * 100;
        if (Double.isNaN(stored) && !Double.isNaN(cap)) stored = cap * p / 100;
        if (Double.isNaN(stored) || Double.isNaN(cap) || cap <= 0) return null;
        Dam.Reading g = new Dam.Reading();
        g.storage = stored / 1e6;     // ML -> BCM
        g.liveCap = cap / 1e6;
        g.pct = p;
        try { g.date = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.text.SimpleDateFormat("d MMMM yyyy", Locale.US).parse(date.trim())); }
        catch (Throwable t) { g.date = date; }
        return g;
    }

    private static java.io.File posFile() {
        android.app.Application ap = app();
        return ap == null ? null : new java.io.File(ap.getCacheDir(), "dam_mh_pos.json");
    }

    private static void loadPos() {
        if (posLoaded) return;
        posLoaded = true;
        try {
            java.io.File f = posFile();
            if (f == null || !f.exists()) return;
            org.json.JSONObject o = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
            for (int i = 0; i < LIST.length; i++) {
                org.json.JSONArray a = o.optJSONArray(LIST[i][0]);
                if (a != null && a.length() == 2) pos[i] = new double[]{a.getDouble(0), a.getDouble(1)};
            }
        } catch (Throwable ignored) { }
    }

    private static void savePos() {
        try {
            java.io.File f = posFile();
            if (f == null) return;
            org.json.JSONObject o = new org.json.JSONObject();
            for (int i = 0; i < LIST.length; i++) if (pos[i] != null) o.put(LIST[i][0], new org.json.JSONArray().put(pos[i][0]).put(pos[i][1]));
            java.nio.file.Files.write(f.toPath(), o.toString().getBytes("UTF-8"));
        } catch (Throwable ignored) { }
    }

    /** Looks up the dams that have no position yet (one request a second, as the place search asks). Returns how many are still missing. */
    private static int fillPos(StringBuilder log) {
        loadPos();
        int missing = 0, fails = 0;
        boolean changed = false;
        for (int i = 0; i < LIST.length; i++) {
            if (pos[i] != null) continue;
            if (fails >= 3) { missing++; continue; }
            try {
                Thread.sleep(1100);
                org.json.JSONArray a = new org.json.JSONArray(get("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=3&countrycodes=in&q="
                        + java.net.URLEncoder.encode(LIST[i][1] + ", Pune, Maharashtra", "UTF-8"), 15_000));
                double[] best = null;
                for (int j = 0; j < a.length() && best == null; j++) {
                    org.json.JSONObject o = a.getJSONObject(j);
                    double la = Double.parseDouble(o.getString("lat")), lo = Double.parseDouble(o.getString("lon"));
                    if (Dam.km(18.52, 73.85, la, lo) < 150) best = new double[]{la, lo};   // somewhere around Pune
                }
                if (best == null && APPROX.containsKey(LIST[i][0])) { best = APPROX.get(LIST[i][0]); }
                if (best == null) { missing++; log.append(LIST[i][2]).append(": not found; "); }
                else { pos[i] = best; changed = true; }
            } catch (Throwable t) {
                fails++; missing++;
                log.append(LIST[i][2]).append(": ").append(t.getClass().getSimpleName()).append("; ");
            }
        }
        if (changed) savePos();
        return missing;
    }

    /** Nearest listed dam within 40 km of here, or null. Cached 3 h per spot (30 min after a miss). */
    static Hit find(double lat, double lon, long now) throws Exception {
        Hit w = null, s = null;
        try { w = DamWrd.find(lat, lon, now); } catch (Throwable t) { DamWrd.why = "error: " + t; }
        try { s = findSmall(lat, lon, now); } catch (Throwable t) { why = "error: " + t; }
        String sw = why;
        why = "major dams (WRD report): " + DamWrd.why + " | small Pune dams: " + sw;
        return s != null && (w == null || s.km < w.km - 0.5) ? s : w;
    }

    private static synchronized Hit findSmall(double lat, double lon, long now) throws Exception {
        String dist = districtFor(lat, lon);
        String k = String.format(Locale.US, "%.2f,%.2f,%s", lat, lon, dist);
        if (k.equals(key) && now - at < (last != null ? 3 * 3600_000L : 30 * 60_000L)) return last;
        key = k; at = now; last = null;
        if (dist.isEmpty()) { why = distWhy.isEmpty() ? "no district known yet" : distWhy; return null; }
        if (!dist.equals("pune")) { why = "the fixed dam list covers Pune district only (you are in " + dist + ")"; return null; }
        StringBuilder log = new StringBuilder();
        int missing = fillPos(log);
        java.util.List<Object[]> cand = new java.util.ArrayList<>();   // {index, km}
        for (int i = 0; i < LIST.length; i++) if (pos[i] != null) cand.add(new Object[]{i, Dam.km(lat, lon, pos[i][0], pos[i][1])});
        java.util.Collections.sort(cand, (a, b) -> Double.compare((Double) a[1], (Double) b[1]));
        int tried = 0;
        for (Object[] c : cand) {
            int i = (Integer) c[0];
            double km = (Double) c[1];
            if (km > 40 || ++tried > 3) break;
            try {
                Dam.Reading g = parse(get("https://mumbailakewaterlevel.in/maharashtra-dams/pune/" + LIST[i][0], 15_000), LIST[i][0]);
                if (g == null) { log.append(LIST[i][2]).append(": page has no figure; "); continue; }
                Hit h = new Hit();
                Dam.Res r = new Dam.Res();
                r.uid = "MH:pune/" + LIST[i][0];
                r.name = LIST[i][2];
                r.lat = pos[i][0]; r.lon = pos[i][1];
                r.liveCap = g.liveCap;
                h.res = r; h.rd = g; h.km = km;
                last = h;
                why = r.name + " " + Math.round(g.pct) + "%, " + Math.round(km) + " km, from mumbailakewaterlevel.in/pune/" + LIST[i][0] + " (" + g.date + ")"
                        + (missing > 0 ? "; " + missing + " dams still without a position" : "");
                return h;
            } catch (Throwable t) {
                log.append(LIST[i][2]).append(": ").append(t.getClass().getSimpleName()).append(' ').append(t.getMessage() == null ? "" : t.getMessage()).append("; ");
            }
        }
        why = cand.isEmpty() ? "no dam positions yet: " + log : "no listed dam within 40 km" + (log.length() > 0 ? " (" + log + ")" : "") + (missing > 0 ? "; " + missing + " dams without a position" : "");
        return null;
    }
}
