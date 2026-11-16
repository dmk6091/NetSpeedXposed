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
    private static String lastKey = "";
    private static long lastAt, tried;
    /** Last outcome, shown in Live diagnostics. */
    static volatile String why = "not run yet";

    static void refresh(Context ctx) {
        try {
            TowerMap.Snap sn = TowerMap.SNAP;
            if (sn == null) { why = "no tower map yet"; return; }
            java.util.List<TowerMap.Pt> ts = new java.util.ArrayList<>();
            for (TowerMap.Pt t : sn.pts) if (t.kind >= 1 && !t.rough && Math.abs(t.lat) > 0.001) ts.add(t);
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
            for (int i = 0; i < ts.size(); i++) DELTA.put(ts.get(i).slot, (int) Math.round(a.getDouble(i + 1) - me));
            lastKey = key.toString();
            lastAt = now;
            why = "ok " + DELTA;
        } catch (Throwable t) { why = "failed: " + t; }
    }
}
