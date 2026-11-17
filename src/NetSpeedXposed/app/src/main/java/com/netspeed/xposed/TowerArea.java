package com.netspeed.xposed;

import android.content.Context;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The area name (locality) of each SIM's tower, for the line under the SIM boxes.
 * Tower position: tower database cache first, then the self-learned estimate. The name comes from
 * Android's Geocoder (needs internet once per place); results are kept in memory and on disk.
 * Runs on the worker thread; the status bar only reads the finished names.
 */
final class TowerArea {
    private TowerArea() {}

    /** SIM slot -> area name ("" = unknown). */
    static final Map<Integer, String> BY_SLOT = new ConcurrentHashMap<>();
    /** SIM slot -> direction from you to the tower ("↗NE"), only when the tower's position is known or learned. */
    static final Map<Integer, String> BEARING = new ConcurrentHashMap<>();
    /** Last real name per slot and when it was seen: kept for 3 min while the SIM hops to a tower without one. */
    private static final Map<Integer, String> LAST = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> LAST_AT = new ConcurrentHashMap<>();

    private static void put(int slot, String name) {
        long now = System.currentTimeMillis();
        if (name != null && !name.isEmpty()) {
            LAST.put(slot, name);
            LAST_AT.put(slot, now);
            BY_SLOT.put(slot, name);
            return;
        }
        Long at = LAST_AT.get(slot);
        BY_SLOT.put(slot, at != null && now - at < 3 * 60_000L ? LAST.get(slot) : "");
    }
    /** Rounded position "18.635,73.744" -> name ("" = looked up, nothing found). */
    private static final Map<String, String> NAMES = new ConcurrentHashMap<>();
    private static final Map<String, Long> TRIED = new ConcurrentHashMap<>();
    private static boolean loaded;
    private static long lastCall;

    /** Worker thread, with each fresh SIM list. */
    /** For the NetSpeed app: one line per SIM. */
    static volatile String report = "";

    private static String healthWhy(Context ctx) {
        try {
            String v = android.provider.Settings.Global.getString(ctx.getContentResolver(), "netspeed_battery_why");
            return v == null || v.isEmpty() ? "not asked yet / no answer from the app (needs root)" : v;
        } catch (Throwable t) { return "unreadable: " + t; }
    }

    static void refresh(Context ctx, List<Bands.Sim> sims) {
        load(ctx);
        StringBuilder r = new StringBuilder("TOWERS\n");
        java.util.List<double[]> mapServing = new java.util.ArrayList<>();
        java.util.List<String> mapBands = new java.util.ArrayList<>();
        for (Bands.Sim s : sims) {
            String key = Bands.lastKey(s.sub);
            double[] p = TowerLearn.confident(ctx, key) ? TowerLearn.position(ctx, key) : null;
            String src = "self-learned (well fitted)";
            if (p == null) { p = TowerDb.position(ctx, key); src = "database"; }
            if (p == null) { p = TowerLearn.position(ctx, key); src = "self-learned"; }
            boolean rough = false;
            // the same tower filed by its channel + PCI (learned also while it was only a neighbour)
            String alias = s.pci >= 0 && s.arfcn >= 0 ? (s.band != null && s.band.startsWith("n") ? "nr~" : "lte~") + s.arfcn + ":" + s.pci : null;
            if (p == null && alias != null) { p = TowerLearn.position(ctx, alias); if (p != null) src = "self-learned (as a neighbour too)"; }
            if (p == null) { p = TowerLearn.roughArea(ctx, key); src = "rough: where its signal was strongest"; rough = true; }
            if (p == null && alias != null) { p = TowerLearn.roughArea(ctx, alias); src = "rough (as a neighbour too)"; rough = true; }
            // A tower can't be 25+ km from you while you're on it. Channel + PCI repeat across towers (only 504 PCIs),
            // so a guess filed under them can belong to a different tower far away: ignore it, use the next fallback.
            if (p != null) {
                try {
                    android.location.Location me0 = TowerDb.myLocation(ctx);
                    if (me0 != null) {
                        float[] dd = new float[1];
                        android.location.Location.distanceBetween(me0.getLatitude(), me0.getLongitude(), p[0], p[1], dd);
                        if (dd[0] > 25000) {
                            r.append("  (SIM").append(s.slot).append(": ").append(src).append(" is ")
                                    .append(Math.round(dd[0] / 1000f)).append(" km away, ignored)\n");
                            p = null;
                            rough = false;
                        }
                    }
                } catch (Throwable ignored) { }
            }
            boolean near = false;
            if (p == null && key != null) {
                // last resort: the area you're in (the serving tower is normally within 1-3 km)
                try {
                    android.location.Location me = TowerDb.myLocation(ctx);
                    if (me != null) { p = new double[]{me.getLatitude(), me.getLongitude()}; src = "your area (tower within range)"; near = true; }
                } catch (Throwable ignored) { }
            }
            String[] sc = Circles.of(s.simNumeric), ncc = Circles.of(s.netNumeric);
            String rc = Circles.roamCircle(s.simNumeric, s.netNumeric);
            r.append("  SIM").append(s.slot).append(s.roaming || rc != null ? " ROAMING" : " home network")
                    .append(s.roaming ? " (network says so)" : rc != null ? " (other circle)" : "").append('\n')
                    .append("    SIM: ").append(s.simName).append(" ").append(s.simNumeric)
                    .append(sc != null ? " = " + sc[0] + " " + sc[2] : " (circle unknown)").append('\n')
                    .append("    network: ").append(s.netName).append(" ").append(s.netNumeric)
                    .append(ncc != null ? " = " + ncc[0] + " " + ncc[2] : " (circle unknown)").append('\n')
                    .append("    tower ").append(key == null ? "(no tower ID)" : key);
            if (p == null) {
                mapServing.add(new double[]{0, 0, s.slot, 1});
                mapBands.add(s.band == null ? "" : s.band);
                put(s.slot, "");
                r.append("\n    position not known yet (not in the tower database; the module learns it from\n"
                        + "    your location + signal while you move, so keep location on)\n");
                continue;
            }
            BEARING.remove(s.slot);
            if (!near && !rough) {   // a rough spot is just where you were: no real direction
                try {
                    android.location.Location me = TowerDb.myLocation(ctx);
                    if (me != null) {
                        float[] res = new float[2];
                        android.location.Location.distanceBetween(me.getLatitude(), me.getLongitude(), p[0], p[1], res);
                        if (res[0] > 60) {
                            int i8 = (int) Math.round(((res[1] + 360) % 360) / 45.0) % 8;
                            String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
                            String[] pts = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
                            BEARING.put(s.slot, arrows[i8]);
                        }
                    }
                } catch (Throwable ignored) { }
            }
            mapServing.add(new double[]{p[0], p[1], s.slot, near || rough ? 1 : 0});
            mapBands.add(s.band == null ? "" : s.band);
            String k = String.format(Locale.US, "%.3f,%.3f", p[0], p[1]);
            String n = NAMES.get(k);
            if (n == null) n = geocode(ctx, k, p);
            put(s.slot, n == null || n.isEmpty() ? "" : (near ? "≈" : rough ? "~" : "") + n);
            r.append(String.format(Locale.US, "%n    %.4f, %.4f (%s) -> %s%n", p[0], p[1], src,
                    n == null ? "looking up…" : n.isEmpty() ? "no place name found" : n));
        }
        TowerMap.build(ctx, mapServing, mapBands);
        try { Neigh.refresh(); } catch (Throwable ignored) { }
        try { TowerElev.refresh(ctx); } catch (Throwable ignored) { }
        if (!android.location.Geocoder.isPresent()) r.append("  (no place-name service on this ROM)\n");
        r.append("  learn while riding: ").append(RideGps.moving ? "moving, GPS fix each minute" : "standing still")
                .append(", GPS fixes so far ").append(RideGps.fixes).append('\n');
        try { placeName(ctx); } catch (Throwable ignored) { }
        r.append("  PLACE (your GPS, used for the SIM box place name): \"").append(PLACE).append("\"\n");
        for (Bands.Sim s : sims) r.append("    BY_SLOT[").append(s.slot).append("]: \"").append(BY_SLOT.get(s.slot)).append("\"\n");
        r.append("  river flow: ").append(RiverFlow.why).append('\n');
        r.append("  earth line: ").append(EarthLine.why).append('\n');
        r.append("  WEATHER place: ").append(WX_WHERE).append('\n');
        r.append("  battery health source: ").append(healthWhy(ctx)).append('\n');
        r.append("  tower height lookup: ").append(TowerElev.why).append('\n');
        try { Scene.refresh(ctx); } catch (Throwable ignored) { }
        r.append("  skyline: ").append(Scene.why).append(" -> ").append(Scene.hills ? "hills" : Scene.coast && Scene.urban != Scene.CITY ? "coast" : Scene.urban == 0 ? "city" : Scene.urban == 1 ? "town" : Scene.urban == 2 ? "village" : "unknown").append('\n');
        report = r.toString();
    }

    /** Place name of where you are (weather location), shown on the status bar between the notification row and the weather. */
    static volatile String PLACE = "";
    /** The place the weather row is for: the name of the exact coordinates the forecast was fetched with. */
    static volatile String WX_NAME = "";
    static volatile String WX_WHERE = "weather not fetched yet";

    /** Name at these coordinates (cache, then OpenStreetMap / Geocoder). Worker thread; null when none yet. */
    static String nameAt(Context ctx, double lat, double lon) {
        String k = String.format(Locale.US, "%.3f,%.3f", lat, lon);
        String n = NAMES.get(k);
        if (n == null) n = geocode(ctx, k, new double[]{lat, lon});
        return n == null || n.isEmpty() ? null : n;
    }

    private static void placeName(Context ctx) {
        android.location.Location me = TowerDb.myLocation(ctx);
        if (me == null) return;
        double[] p = {me.getLatitude(), me.getLongitude()};
        String k = String.format(Locale.US, "%.3f,%.3f", p[0], p[1]);
        String n = NAMES.get(k);
        if (n == null) n = geocode(ctx, k, p);
        if (n != null && !n.isEmpty()) PLACE = n;
    }

    private static String geocode(Context ctx, String k, double[] p) {
        long now = System.currentTimeMillis();
        Long t = TRIED.get(k);
        if (t != null && now - t < 30 * 60_000L) return null;   // failed recently: try again later
        if (now - lastCall < 2_000L) return null;                // gentle on the service
        lastCall = now;
        TRIED.put(k, now);
        // OpenStreetMap first: neighbourhood / sector names, finer than Android's town names
        try {
            String n = osmName(p[0], p[1]);
            if (n != null && !n.isEmpty()) {
                NAMES.put(k, n);
                save(ctx);
                return n;
            }
        } catch (Throwable ignored) { }
        try {
            if (!android.location.Geocoder.isPresent()) return null;
            android.location.Geocoder g = new android.location.Geocoder(ctx, Locale.ENGLISH);
            @SuppressWarnings("deprecation")
            List<android.location.Address> a = g.getFromLocation(p[0], p[1], 1);
            String name = "";
            if (a != null && !a.isEmpty()) {
                android.location.Address ad = a.get(0);
                name = first(ad.getSubLocality(), ad.getLocality(), ad.getSubAdminArea(), ad.getFeatureName());
            }
            NAMES.put(k, name);
            save(ctx);
            return name;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Nominatim reverse lookup (OpenStreetMap); cached per ~100 m, so very few calls. */
    private static String osmName(double lat, double lon) throws Exception {
        String url = String.format(Locale.US, "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.5f&lon=%.5f"
                + "&zoom=17&addressdetails=1&accept-language=en", lat, lon);
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(8_000);
        c.setReadTimeout(8_000);
        c.setRequestProperty("User-Agent", "NetSpeedXposed/1.0 (status bar module; github.com/dmk6091/NetSpeedXposed)");
        if (c.getResponseCode() != 200) return null;
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        org.json.JSONObject a = new org.json.JSONObject(sb.toString()).optJSONObject("address");
        if (a == null) return null;
        return first(a.optString("neighbourhood"), a.optString("quarter"), a.optString("suburb"), a.optString("hamlet"),
                a.optString("village"), a.optString("residential"), a.optString("city_district"), a.optString("town"),
                a.optString("city"));
    }

    private static String first(String... v) {
        for (String s : v) if (s != null && !s.trim().isEmpty() && !s.matches("[0-9+ ,-]+")) return s.trim();
        return "";
    }

    private static java.io.File file(Context ctx) {
        return new java.io.File(ctx.getCacheDir(), "tower_areas_osm.txt");
    }

    private static void load(Context ctx) {
        if (loaded) return;
        loaded = true;
        try {
            java.io.File f = file(ctx);
            if (!f.isFile()) return;
            for (String line : new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").split("\n")) {
                int i = line.indexOf('=');
                if (i > 0) NAMES.put(line.substring(0, i), line.substring(i + 1));
            }
        } catch (Throwable ignored) { }
    }

    private static void save(Context ctx) {
        try {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, String> e : NAMES.entrySet()) b.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            java.nio.file.Files.write(file(ctx).toPath(), b.toString().getBytes("UTF-8"));
        } catch (Throwable ignored) { }
    }
}
