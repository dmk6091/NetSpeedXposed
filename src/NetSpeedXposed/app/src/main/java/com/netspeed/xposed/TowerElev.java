package com.netspeed.xposed;

import android.content.Context;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** How much higher (+) or lower (-) each SIM's serving tower stands than you, from ground elevation at both places (Open-Meteo). Worker thread. */
final class TowerElev {
    private TowerElev() {}

    /** SIM slot -> metres the tower's ground is above yours. */
    static final Map<Integer, Integer> DELTA = new ConcurrentHashMap<>();
    /** SIM slot -> metres the ground between you and the tower sticks up above the straight line to the antenna (<= 2: clear). */
    /** SIM slots whose tower position is only a rough guess: the numbers are shown as estimates. */
    static final java.util.Set<Integer> EST = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static final Map<Integer, Integer> LOS = new ConcurrentHashMap<>();
    /** SIM slot -> degrees above (+) or below (-) the horizon you look to the antenna (assumed mast). */
    static final Map<Integer, Integer> ANGLE = new ConcurrentHashMap<>();
    /** SIM slot -> % of the first Fresnel zone (about 2.5 GHz) that stays clear of the ground along the path, capped at 100. */
    static final Map<Integer, Integer> CLEAR = new ConcurrentHashMap<>();
    /** Radio horizon in km between your phone and an antenna on the assumed mast (4/3-earth). */
    static final int HORIZON_KM = (int) Math.round((Math.sqrt(2 * 8.5e6 * 30) + Math.sqrt(2 * 8.5e6 * 1.5)) / 1000.0);
    private static final double YOU_H = 1.5, MAST_H = 30, REFF = 8.5e6;   // assumed antenna heights, 4/3-earth radius
    /** SIM slot -> real mast height (m) listed in OpenStreetMap for the serving tower; absent = unknown, the 30 m guess is used. */
    static final Map<Integer, Integer> MAST = new ConcurrentHashMap<>();
    /** SIM slot -> radio horizon (km) using the real mast height; absent = use HORIZON_KM. */
    static final Map<Integer, Integer> HKM = new ConcurrentHashMap<>();
    private static final Map<String, Integer> OSM_H = new ConcurrentHashMap<>();   // tower position -> OSM height, 0 = none listed
    /** Landform around you from 8 ground heights 300 m away: "Ridge", "Slope", "Valley" or "Flat"; null until known. */
    static volatile String LAND;
    /** Direction word + arrow and height (m) above you of the highest ground 300 m away, e.g. "\u2196NW"; SHADE_M 0 = nothing worth showing. */
    static volatile String SHADE_DIR = "";
    static volatile int SHADE_M;
    private static void landform(double meLat, double meLon, double meElev) {
        try {
            final String[] nm = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
            final String[] ar = {"\u2191", "\u2197", "\u2192", "\u2198", "\u2193", "\u2199", "\u2190", "\u2196"};
            StringBuilder la = new StringBuilder(), lo = new StringBuilder();
            double dLat = 300 / 111320.0, dLon = 300 / (111320.0 * Math.cos(Math.toRadians(meLat)));
            for (int k = 0; k < 8; k++) {
                double th = Math.toRadians(k * 45);
                if (k > 0) { la.append(','); lo.append(','); }
                la.append(String.format(Locale.US, "%.5f", meLat + dLat * Math.cos(th)));
                lo.append(String.format(Locale.US, "%.5f", meLon + dLon * Math.sin(th)));
            }
            org.json.JSONArray a = new org.json.JSONObject(Scene.get("https://api.open-meteo.com/v1/elevation?latitude=" + la + "&longitude=" + lo)).getJSONArray("elevation");
            if (a.length() < 8) return;
            double mx = -1e9, mn = 1e9, sum = 0; int mi = 0;
            for (int k = 0; k < 8; k++) { double dm = a.getDouble(k) - meElev; sum += dm; if (dm > mx) { mx = dm; mi = k; } mn = Math.min(mn, dm); }
            double mean = sum / 8;
            LAND = (mx - mn) < 6 ? "Flat" : mean <= -6 ? "Valley" : mean >= 6 ? "Ridge" : "Slope";
            SHADE_M = mx >= 5 ? (int) Math.round(mx) : 0;
            SHADE_DIR = ar[mi] + nm[mi];
        } catch (Throwable ignored) { }
    }
    private static String lastKey = "";
    private static long lastAt, tried;
    /** Last outcome, shown in Live diagnostics. */
    static volatile String why = "not run yet";

    static void refresh(Context ctx) {
        try {
            TowerMap.Snap sn = TowerMap.SNAP;
            if (sn == null) { why = "no tower map yet"; return; }
            java.util.List<TowerMap.Pt> ts = new java.util.ArrayList<>();
            for (TowerMap.Pt t : sn.pts) if (t.kind >= 1 && Math.abs(t.lat) > 0.001) { ts.add(t); if (t.rough) EST.add(t.slot); else EST.remove(t.slot); }
            if (ts.isEmpty()) { why = "no tower with a known exact position (" + sn.pts.size() + " points, all unknown/rough)"; return; }
            StringBuilder la = new StringBuilder(), lo = new StringBuilder(), key = new StringBuilder();
            la.append(String.format(Locale.US, "%.4f", sn.meLat));
            lo.append(String.format(Locale.US, "%.4f", sn.meLon));
            key.append(String.format(Locale.US, "%.3f,%.3f", sn.meLat, sn.meLon));
            for (TowerMap.Pt t : ts) {
                la.append(',').append(String.format(Locale.US, "%.4f", t.lat));
                lo.append(',').append(String.format(Locale.US, "%.4f", t.lon));
                key.append(';').append(String.format(Locale.US, "%.4f,%.4f", t.lat, t.lon));
            }
            long now = System.currentTimeMillis();
            if (key.toString().equals(lastKey) && now - lastAt < 6 * 3600_000L) { why = "ok (cached) " + DELTA; return; }
            if (now - tried < 5 * 60_000L) return;
            tried = now;
            String url = "https://api.open-meteo.com/v1/elevation?latitude=" + la + "&longitude=" + lo;
            org.json.JSONArray a = new org.json.JSONObject(Scene.get(url)).getJSONArray("elevation");
            if (a.length() < ts.size() + 1) { why = "elevation reply too short: " + a.length(); return; }
            double me = a.getDouble(0);
            landform(sn.meLat, sn.meLon, me);
            for (int i = 0; i < ts.size(); i++) DELTA.put(ts.get(i).slot, (int) Math.round(a.getDouble(i + 1) - me));
            lastKey = key.toString();
            lastAt = now;
            why = "ok " + DELTA;
            for (TowerMap.Pt t : ts) {
                MAST.remove(t.slot); HKM.remove(t.slot);
                if (t.rough) continue;
                int h = osmHeight(t.lat, t.lon);
                if (h > 0) { MAST.put(t.slot, h); HKM.put(t.slot, (int) Math.round((Math.sqrt(2 * REFF * h) + Math.sqrt(2 * REFF * YOU_H)) / 1000.0)); }
            }
            try { los(ts, sn.meLat, sn.meLon, me, a); } catch (Throwable t) { why += " (los failed: " + t + ")"; }
        } catch (Throwable t) { why = "failed: " + t; }
    }

    /** Height (m) of a communication mast within 60 m of the point, from OpenStreetMap; 0 when none lists one or the lookup failed. */
    private static int osmHeight(double lat, double lon) {
        String k = String.format(Locale.US, "%.4f,%.4f", lat, lon);
        Integer c = OSM_H.get(k);
        if (c != null) return c;
        try {
            String at = String.format(Locale.US, "(around:60,%.6f,%.6f)", lat, lon);
            String q = "[out:json][timeout:20];(node" + at + "[\"height\"][\"man_made\"~\"mast|tower\"];node" + at + "[\"height\"][\"tower:type\"=\"communication\"];way" + at + "[\"height\"][\"man_made\"~\"mast|tower\"];);out tags;";
            org.json.JSONArray el = new org.json.JSONObject(Geo.overpass(q, 25_000)).getJSONArray("elements");
            int best = 0;
            for (int i = 0; i < el.length(); i++) {
                String hv = el.getJSONObject(i).getJSONObject("tags").optString("height", "");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)").matcher(hv);
                if (m.find()) { int v = (int) Math.round(Double.parseDouble(m.group(1))); if (v >= 5 && v <= 400) best = Math.max(best, v); }
            }
            OSM_H.put(k, best);
            return best;
        } catch (Throwable e) { return 0; }   // not cached: try again next time
    }

    /** Line of sight to each tower: sample 10 ground heights along the straight line and see what rises above it. */
    private static void los(java.util.List<TowerMap.Pt> ts, double meLat, double meLon, double meElev, org.json.JSONArray tw) throws Exception {
        final int N = 10;
        StringBuilder la = new StringBuilder(), lo = new StringBuilder();
        for (TowerMap.Pt t : ts) for (int k = 1; k <= N; k++) {
            double f = k / (N + 1.0);
            if (la.length() > 0) { la.append(','); lo.append(','); }
            la.append(String.format(Locale.US, "%.5f", meLat + (t.lat - meLat) * f));
            lo.append(String.format(Locale.US, "%.5f", meLon + (t.lon - meLon) * f));
        }
        org.json.JSONArray a = new org.json.JSONObject(Scene.get("https://api.open-meteo.com/v1/elevation?latitude=" + la + "&longitude=" + lo)).getJSONArray("elevation");
        if (a.length() < ts.size() * N) return;
        for (int i = 0; i < ts.size(); i++) {
            TowerMap.Pt t = ts.get(i);
            double dLat = Math.toRadians(t.lat - meLat), dLon = Math.toRadians(t.lon - meLon) * Math.cos(Math.toRadians(meLat));
            double dist = 6371000 * Math.sqrt(dLat * dLat + dLon * dLon);
            double a0 = meElev + YOU_H, a1 = tw.getDouble(i + 1) + (MAST.containsKey(t.slot) ? MAST.get(t.slot) : MAST_H), worst = -1e9, tightest = 1e9;
            double dKm = dist / 1000.0;
            for (int k = 1; k <= N; k++) {
                double f = k / (N + 1.0);
                double line = a0 + (a1 - a0) * f;
                double bulge = dist * dist * f * (1 - f) / (2 * REFF);   // the earth's curve lifts the ground into the path
                double over = a.getDouble(i * N + k - 1) + bulge - line;
                worst = Math.max(worst, over);
                double r1 = 17.3 * Math.sqrt(Math.max(1e-6, dKm * f * dKm * (1 - f) / (2.5 * Math.max(dKm, 1e-3))));   // first Fresnel radius (m) at 2.5 GHz
                tightest = Math.min(tightest, (-over) / Math.max(r1, 0.5));
            }
            LOS.put(t.slot, (int) Math.round(Math.max(0, worst)));
            if (dist >= 30) {
                ANGLE.put(t.slot, (int) Math.round(Math.toDegrees(Math.atan2(a1 - a0, dist))));
                CLEAR.put(t.slot, (int) Math.round(Math.max(0, Math.min(1, tightest)) * 100));
            } else { ANGLE.remove(t.slot); CLEAR.remove(t.slot); }
        }
    }
}
