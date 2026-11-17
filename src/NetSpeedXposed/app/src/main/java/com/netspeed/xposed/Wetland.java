package com.netspeed.xposed;

import java.util.Locale;

/**
 * Nearest wetland / water body (OpenStreetMap, natural=wetland and named natural=water within 25 km) and the nearest
 * Ramsar site of India (matched by name against the official list; position from OpenStreetMap, within 220 km).
 * Only what mappers have drawn, and a Ramsar site only when OSM has it under a matching name.
 */
final class Wetland {
    private Wetland() {}

    static final class Hit {
        String name, type, dir;
        double km;
        boolean ramsar, unnamed, approx;
        String text() { return name + (unnamed ? "" : " · " + type) + " · " + (approx ? "~" : "") + (km < 10 ? String.format(Locale.US, "%.1f", km) : String.valueOf(Math.round(km))) + " km " + dir; }
    }

    static volatile Hit wetland, marsh, ramsar;
    static volatile double[] at = null;          // where they were looked up
    static volatile long okAt = 0, triedAt = 0;
    static volatile boolean running = false;
    static volatile String why = "not looked up yet";

    /** Well-known Ramsar sites of India with approximate centres (about 10 km), {name, lat, lon}. Not the full list of 101: positions I am sure of. */
    private static final Object[][] RAMSAR = {
            {"Lonar Lake", 19.98, 76.51}, {"Nandur Madhameshwar", 20.01, 74.12}, {"Thane Creek", 19.07, 72.97}, {"Sambhar Lake", 26.97, 75.00},
            {"Keoladeo (Bharatpur)", 27.16, 77.52}, {"Chilika Lake", 19.72, 85.32}, {"Bhitarkanika", 20.65, 86.90}, {"Hirakud Reservoir", 21.55, 83.85},
            {"Sundarban", 21.95, 89.00}, {"East Kolkata Wetlands", 22.55, 88.42}, {"Loktak Lake", 24.52, 93.80}, {"Deepor Beel", 26.13, 91.65},
            {"Wular Lake", 34.35, 74.55}, {"Tsomoriri", 32.90, 78.30}, {"Harike", 31.17, 75.20}, {"Pong Dam Lake", 32.02, 76.00}, {"Bhoj Wetland (Bhopal)", 23.24, 77.37},
            {"Nalsarovar", 22.80, 72.00}, {"Vembanad-Kol", 9.60, 76.40}, {"Ashtamudi", 8.95, 76.58}, {"Sasthamkotta", 9.04, 76.62}, {"Kolleru Lake", 16.65, 81.20},
            {"Point Calimere", 10.30, 79.85}, {"Pallikaranai Marsh", 12.94, 80.22}, {"Ranganathittu", 12.42, 76.65}, {"Chandra Taal", 32.48, 77.60},
            {"Renuka Lake", 30.60, 77.45}, {"Pichavaram", 11.43, 79.78}, {"Gulf of Mannar", 9.00, 79.20}, {"Aghanashini Estuary", 14.55, 74.37}};

    /** Well-known lakes, reservoirs and bird wetlands near Pune and Mumbai, approximate {name, type, lat, lon}; used only when OpenStreetMap can't be reached. */
    private static final Object[][] LOCAL = {
            {"Pavana Lake", "reservoir", 18.68, 73.50}, {"Mulshi Lake", "reservoir", 18.50, 73.50}, {"Khadakwasla Lake", "reservoir", 18.44, 73.76},
            {"Pashan Lake", "lake", 18.535, 73.79}, {"Katraj Lake", "lake", 18.45, 73.85}, {"Tungarli Lake", "lake", 18.73, 73.39},
            {"Lonavala Lake", "lake", 18.75, 73.41}, {"Bhushi Dam", "reservoir", 18.73, 73.39}, {"Bhigwan backwaters", "wetland", 18.31, 74.77},
            {"Ujani Reservoir", "reservoir", 18.07, 75.12}, {"Karnala Bird Sanctuary", "wetland", 18.88, 73.12}, {"Panje Wetland (Uran)", "wetland", 18.88, 72.93},
            {"Sewri Mudflats", "tidal flat", 19.00, 72.86}, {"Powai Lake", "lake", 19.13, 72.90}, {"Tansa Lake", "reservoir", 19.55, 73.40},
            {"Gangapur Dam", "reservoir", 20.01, 73.70}, {"Koyna Reservoir", "reservoir", 17.40, 73.75}, {"Dimbhe Reservoir", "reservoir", 19.07, 73.75}};

    /** No answer from OpenStreetMap: the built-in tables (Ramsar sites anywhere; lakes and wetlands within 60 km of Pune and Mumbai). */
    static void fallback(double lat, double lon, long now, String err) {
        Hit nw = null, nr = null;
        for (Object[] r : LOCAL) {
            Hit h = new Hit();
            h.name = (String) r[0]; h.type = (String) r[1]; h.approx = true;
            h.km = Beach.km(lat, lon, (Double) r[2], (Double) r[3]);
            h.dir = Beach.dir(lat, lon, (Double) r[2], (Double) r[3]);
            if (h.km <= 60 && (nw == null || h.km < nw.km)) nw = h;
        }
        for (Object[] r : RAMSAR) {
            Hit h = new Hit();
            h.name = (String) r[0]; h.type = "Ramsar site"; h.ramsar = true; h.unnamed = true; h.approx = true;
            h.km = Beach.km(lat, lon, (Double) r[1], (Double) r[2]);
            h.dir = Beach.dir(lat, lon, (Double) r[1], (Double) r[2]);
            if (nr == null || h.km < nr.km) nr = h;
        }
        wetland = nw; marsh = null; ramsar = nr;
        at = new double[]{lat, lon};
        okAt = now - 11 * 3600_000L - 1800_000L;   // try the live lookup again in about 30 minutes
        why = "built-in list (live lookup failed: " + err + "): " + (nw == null ? "no listed lake within 60 km" : nw.text()) + (nr == null ? "" : "; " + nr.text());
    }

    /** Serialises the status bar's own Overpass lookups (beach, wetland) so they never hog the server together. */
    static final Object LOCK = new Object();

    /** Wetlands and named water in a box halfKm each way. */
    static String q1(double lat, double lon, double halfKm) {
        double d = halfKm / 111.0, e = halfKm / (111.0 * Math.cos(Math.toRadians(lat)));
        String b = String.format(Locale.US, "(%.4f,%.4f,%.4f,%.4f)", lat - d, lon - e, lat + d, lon + e);
        return "[out:json][timeout:" + (halfKm <= 3 ? 20 : halfKm <= 10 ? 30 : 50) + "];(nwr[\"natural\"=\"wetland\"]" + b + ";nwr[\"natural\"=\"water\"][\"name\"]" + b + ";);out center tags 600;";
    }

    private static String typeOf(org.json.JSONObject tg) {
        String n = tg.optString("natural", "");
        if (n.equals("wetland")) {
            String w = tg.optString("wetland", "");
            switch (w) {
                case "marsh": return "marsh"; case "swamp": return "swamp"; case "mangrove": return "mangrove"; case "tidalflat": return "tidal flat";
                case "reedbed": return "reedbed"; case "wet_meadow": return "wet meadow"; case "bog": return "bog"; case "fen": return "fen";
                case "saltmarsh": return "salt marsh"; default: return "wetland";
            }
        }
        String w = tg.optString("water", "");
        switch (w) {
            case "lake": return "lake"; case "pond": return "pond"; case "reservoir": return "reservoir"; case "lagoon": return "lagoon"; case "river": return "river";
            case "canal": return "canal"; default: return "water";
        }
    }

    private static double[] center(org.json.JSONObject e) {
        if (e.has("lat")) return new double[]{e.optDouble("lat"), e.optDouble("lon")};
        org.json.JSONObject c = e.optJSONObject("center");
        return c == null ? null : new double[]{c.optDouble("lat"), c.optDouble("lon")};
    }

    /** Parses both answers (json2 may be null) into wetland / marsh / ramsar for the point. */
    static void parse(String json1, double lat, double lon, long now) throws Exception {
        Hit nw = null, nm = null, nr = null;
        org.json.JSONArray el = new org.json.JSONObject(json1).getJSONArray("elements");
        for (int i = 0; i < el.length(); i++) {
            org.json.JSONObject e = el.getJSONObject(i);
            org.json.JSONObject tg = e.optJSONObject("tags");
            double[] c = center(e);
            if (tg == null || c == null || Double.isNaN(c[0])) continue;
            Hit h = new Hit();
            String nmv = tg.optString("name:en", tg.optString("name", "")).trim();
            h.type = typeOf(tg);
            h.name = nmv.isEmpty() ? "Unnamed " + h.type : nmv;
            h.unnamed = nmv.isEmpty();
            h.km = Beach.km(lat, lon, c[0], c[1]);
            h.dir = Beach.dir(lat, lon, c[0], c[1]);
            if (nw == null || h.km < nw.km) nw = h;
            boolean wet = "wetland".equals(tg.optString("natural", ""));
            if (wet && (nm == null || h.km < nm.km)) nm = h;
        }
        for (Object[] r : RAMSAR) {
            Hit h = new Hit();
            h.name = (String) r[0]; h.type = "Ramsar site"; h.ramsar = true; h.unnamed = true; h.approx = true;
            h.km = Beach.km(lat, lon, (Double) r[1], (Double) r[2]);
            h.dir = Beach.dir(lat, lon, (Double) r[1], (Double) r[2]);
            if (nr == null || h.km < nr.km) nr = h;
        }
        wetland = nw; marsh = nm; ramsar = nr;
        at = new double[]{lat, lon};
        okAt = now;
        why = "ok: " + (nw == null ? "no wetland within 25 km" : nw.text()) + (nr == null ? "; no Ramsar site matched" : "; " + nr.text());
    }

    /** Wetlands within 25 km (one Overpass request; the Ramsar site comes from the built-in table). */
    static void lookup(double lat, double lon, long now) throws Exception {
        // small boxes first: a 3 km box is answered quickly and finds the pond next door (Ganesh Talav) even when the 25 km box would time out
        final double[] halfKm = {3, 10, 25};
        boolean got = false;
        for (double hk : halfKm) {
            String j1;
            try {
                synchronized (LOCK) { j1 = Geo.overpass(q1(lat, lon, hk), 60_000); }   // public Overpass servers can queue even a tiny request for 40 s or more
            } catch (Exception e) {
                if (!got || wetland == null) fallback(lat, lon, now, (hk <= 3 ? "3 km box" : hk <= 10 ? "10 km box" : "25 km box") + " - " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                return;   // keep what a smaller box already found
            }
            parse(j1, lat, lon, now);
            got = true;
            Hit w = wetland;
            if (w != null && w.km <= hk) return;   // the nearest one is inside this box, so a bigger box can't beat it
        }
    }

    static boolean due(double lat, double lon, long now) {
        if (now - triedAt < 15 * 60_000L) return false;
        double[] a = at;
        return a == null || now - okAt > 12 * 3600_000L || Beach.km(lat, lon, a[0], a[1]) > 5;
    }
}
