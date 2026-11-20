package com.netspeed.xposed;

import java.io.File;
import java.util.Locale;

/**
 * Nearest dam among the ~178 CWC reservoirs: the list (name, place, capacity, FRL, hydro MW) comes once from CWC's public
 * map layer and is cached on the phone, the daily % full from the dashboard's own storage call (no login). Used by both
 * System UI (the chip) and the app (daily check, notifications, info screen). "Danger" here is only how full the
 * reservoir is, not a flood warning.
 */
final class Dam {
    private Dam() {}

    static final String WFS = "https://rsms.cwc.gov.in/geoserver/CWC/ows?service=WFS&version=1.0.0&request=GetFeature"
            + "&typeName=CWC:reservoir&outputFormat=application/json";
    static final String STORAGE = "https://rsms.cwc.gov.in/admin/api/getReservoir-details";

    static final class Res {
        String uid, name, river = "";
        double lat, lon, liveCap, frl, mw;
        boolean approx;          // the dam's position is a rough one
        String district = "";
    }

    static final class Reading {
        double storage, liveCap, pct;
        double ly = Double.NaN;  // percent full on the same date last year, when the source gives it
        String date = "", time = "";
    }

    static final String[] STATE_NAMES = {"Danger low", "Low", "Normal", "High", "Danger high"};
    static final int[] STATE_COLORS = {0xFFD93A2B, 0xFFE07B1A, 0xFF2E9E4F, 0xFF2F7FD1, 0xFF8E3FB5};

    // in-memory state for System UI (the chip)
    static volatile Res[] list = null;
    static volatile long listAt = 0;
    static volatile Res cur = null;
    static volatile Reading curRead = null;
    static volatile double curKm = 0;
    static volatile long fetchedAt = 0, triedAt = 0;
    static volatile String lastError = "";
    static volatile String lastHttp = "";
    static volatile String riverKeys = "";
    /** Extra facts for a dam read from the national snapshot ("ALL:" ids): source line and flows, empty otherwise. */
    static volatile String curSrc = "", curFlow = "";

    static int state(double pct) {
        return pct < 20 ? 0 : pct < 40 ? 1 : pct <= 90 ? 2 : pct <= 95 ? 3 : 4;
    }

    static String http(String url, String body) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", "NetSpeedXposed/1.0");
        if (body != null) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (java.io.OutputStream o = c.getOutputStream()) { o.write(body.getBytes("UTF-8")); }
        }
        int code = c.getResponseCode();
        lastHttp = "HTTP " + code;
        if (code != 200) throw new java.io.IOException("HTTP " + code);
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString().trim();
    }

    /** Loads the reservoir list from the cache file, or from CWC when the cache is missing or over 30 days old. */
    static synchronized boolean ensureList(File dir) {
        long now = System.currentTimeMillis();
        if (list != null && list.length > 0 && now - listAt < 30L * 86400_000L) return true;
        File f = new File(dir, "dam_list2.json");
        String json = null;
        long stamp = 0;
        try {
            if (f.exists() && now - f.lastModified() < 30L * 86400_000L) {
                json = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
                stamp = f.lastModified();
            }
        } catch (Throwable ignored) { }
        if (json == null) {
            try {
                json = http(WFS, null);
                stamp = now;
                java.nio.file.Files.write(f.toPath(), json.getBytes("UTF-8"));
            } catch (Throwable t) {
                lastError = "list: " + t;
                try {   // an old cache is better than nothing
                    if (f.exists()) { json = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"); stamp = f.lastModified(); }
                } catch (Throwable ignored) { }
            }
        }
        if (json == null) return false;
        try {
            Res[] r = parseList(json);
            if (r.length == 0) return false;
            list = r;
            listAt = stamp;
            return true;
        } catch (Throwable t) {
            lastError = "list parse: " + t;
            return false;
        }
    }

    static Res[] parseList(String json) throws Exception {
        org.json.JSONArray fs = new org.json.JSONObject(json).getJSONArray("features");
        java.util.LinkedHashMap<String, Res> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < fs.length(); i++) {
            org.json.JSONObject p = fs.getJSONObject(i).getJSONObject("properties");
            String uid = p.optString("reservoir_unique_id", "").trim();
            String name = p.optString("reservoir_name", "").trim();
            if (uid.isEmpty() || uid.equals("null") || name.isEmpty()) continue;
            String low = name.toLowerCase(Locale.ROOT);
            if (low.contains("test") || low.contains("zz")) continue;
            Res r = new Res();
            r.uid = uid;
            r.name = name;
            r.lat = p.optDouble("lattitude", 0);
            r.lon = p.optDouble("longitude", 0);
            r.liveCap = p.optDouble("live_cap_bcm", 0);
            r.frl = p.optDouble("frl_meter", 0);
            r.mw = p.optDouble("hydro_power_mw", 0);
            // the river's name: whichever column of the CWC layer is called something with "river"
            for (java.util.Iterator<String> it = p.keys(); it.hasNext(); ) {
                String k = it.next();
                if (!k.toLowerCase(Locale.ROOT).contains("river")) continue;
                String v = p.optString(k, "").trim();
                if (!v.isEmpty() && !v.equals("null") && !v.equals("-")) { r.river = v; break; }
            }
            if (r.river.isEmpty()) r.river = DamRivers.get(uid);
            if (m.isEmpty()) riverKeys = p.names() == null ? "" : p.names().toString();
            if (Double.isNaN(r.lat) || Double.isNaN(r.lon) || Math.abs(r.lat) < 1 || Math.abs(r.lon) < 1) continue;
            m.put(uid, r);
        }
        return m.values().toArray(new Res[0]);
    }

    static double km(double a1, double o1, double a2, double o2) {
        double p = Math.toRadians(a2 - a1), q = Math.toRadians(o2 - o1);
        double h = Math.sin(p / 2) * Math.sin(p / 2)
                + Math.cos(Math.toRadians(a1)) * Math.cos(Math.toRadians(a2)) * Math.sin(q / 2) * Math.sin(q / 2);
        return 6371 * 2 * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /** The n nearest reservoirs, nearest first. */
    static Res[] nearest(double lat, double lon, int n) {
        Res[] l = list;
        if (l == null) return new Res[0];
        Res[] sorted = l.clone();
        java.util.Arrays.sort(sorted, (a, b) -> Double.compare(km(lat, lon, a.lat, a.lon), km(lat, lon, b.lat, b.lon)));
        return java.util.Arrays.copyOf(sorted, Math.min(n, sorted.length));
    }

    /** Today's reading for one reservoir; null when CWC has no storage figure for it. */
    static Reading read(Res r) throws Exception {
        String body = http(STORAGE, "{\"reservoir_uid\":\"" + r.uid + "\"}");
        org.json.JSONObject o = new org.json.JSONObject(body);
        org.json.JSONArray a = o.optJSONArray("result");
        if (a == null || a.length() == 0) return null;
        org.json.JSONObject x = a.getJSONObject(0);
        String nm = x.optString("reservoir_name", "").trim();
        // the server answers with Kol Dam when it doesn't know the id
        if (nm.toUpperCase(Locale.ROOT).startsWith("KOL DAM") && !r.name.toUpperCase(Locale.ROOT).startsWith("KOL DAM")) return null;
        double st = x.optDouble("storage_bcm", Double.NaN);
        double cap = x.optDouble("live_cap_bcm", Double.NaN);
        if (Double.isNaN(cap) || cap <= 0) cap = r.liveCap;
        if (Double.isNaN(st) || cap <= 0) return null;
        Reading g = new Reading();
        g.storage = st;
        g.liveCap = cap;
        g.pct = 100.0 * st / cap;
        g.date = x.optString("entry_date", "");
        return g;
    }

    /**
     * Nearest dam that has a reading: the 3 nearest CWC reservoirs, and for Maharashtra the nearest named dam from
     * OpenStreetMap read from mumbailakewaterlevel.in ({@link DamMh}); whichever is closer wins. Sets cur / curRead / curKm.
     */
    static boolean refresh(File dir, double lat, double lon, long now) {
        triedAt = now;
        curSrc = ""; curFlow = "";
        try { DamAll.download(dir, now); } catch (Throwable ignored) { }
        Res cr = null;
        Reading cg = null;
        double ck = 0;
        if (ensureList(dir)) {
            try {
                for (Res r : nearest(lat, lon, 3)) {
                    Reading g = read(r);
                    if (g == null) continue;
                    cr = r; cg = g; ck = km(lat, lon, r.lat, r.lon);
                    break;
                }
                if (cr == null) lastError = "no reading for the 3 nearest dams";
            } catch (Throwable t) {
                lastError = "storage: " + t;
            }
        }
        DamMh.Hit h = null;
        try { h = DamMh.find(lat, lon, now); } catch (Throwable t) { DamMh.why = "error: " + t; }
        DamAll.Hit sn = null;
        try {
            java.util.List<DamAll.Hit> l = DamAll.nearest(lat, lon, 1, 100, true);
            if (!l.isEmpty()) sn = l.get(0);
        } catch (Throwable ignored) { }
        double best = Math.min(cr == null ? 1e9 : ck, h == null ? 1e9 : h.km);
        if (sn != null && sn.km < best - 2) {
            Res r = new Res();
            r.uid = "ALL:" + sn.name; r.name = sn.name; r.river = sn.river; r.lat = sn.lat; r.lon = sn.lon;
            r.district = sn.state; r.approx = true;
            Reading g = new Reading();
            g.pct = sn.pct; g.storage = sn.pct; g.liveCap = 100; g.date = sn.date;
            StringBuilder f = new StringBuilder();
            if (!Double.isNaN(sn.inflow)) f.append(String.format(Locale.US, "in %.0f ", sn.inflow));
            if (!Double.isNaN(sn.outRiver)) f.append(String.format(Locale.US, "out river %.0f ", sn.outRiver));
            if (!Double.isNaN(sn.outCanal)) f.append(String.format(Locale.US, "out canal %.0f", sn.outCanal));
            cur = r; curRead = g; curKm = sn.km; fetchedAt = now; lastError = "";
            curSrc = sn.basis + " · " + sn.source + " · " + sn.date;
            curFlow = f.toString().trim();
            return true;
        }
        if (h != null && (cr == null || h.km < ck - 0.5)) {
            cur = h.res; curRead = h.rd; curKm = h.km; fetchedAt = now; lastError = "";
            return true;
        }
        if (cr != null) {
            cur = cr; curRead = cg; curKm = ck; fetchedAt = now; lastError = "";
            return true;
        }
        return false;
    }

    /** System UI: refetch every 3 h, or when you've moved so another dam is nearest. */
    static boolean due(double lat, double lon, long now) {
        if (now - triedAt < 10 * 60_000L) return false;
        if (cur == null || curRead == null || now - fetchedAt > 3 * 3600_000L) return true;
        if (cur.uid.startsWith("MH:") || cur.uid.startsWith("ALL:")) return false;   // the OpenStreetMap dam is re-checked on the 3 h refresh
        Res[] n = nearest(lat, lon, 1);
        return n.length > 0 && !n[0].uid.equals(cur.uid);
    }

    /** Short chip text: "62%". */
    static String pctText(double pct) {
        return Math.round(pct) + "%";
    }

    static String kmText(double km) {
        return km < 10 ? String.format(Locale.US, "%.1f km", km) : Math.round(km) + " km";
    }
}
