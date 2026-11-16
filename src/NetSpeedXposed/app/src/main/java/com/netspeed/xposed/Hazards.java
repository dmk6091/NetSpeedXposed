package com.netspeed.xposed;

import java.util.Locale;

/**
 * Disasters and space weather for row 4:
 *  - GDACS (UN / EU Joint Research Centre): current orange and red alerts for cyclones, quakes, floods, volcanoes,
 *    droughts and wildfires. The worst one (red before orange), nearest first, replaces the quake line.
 *  - NOAA SWPC planetary Kp: shown only during a geomagnetic storm (Kp 5+ = G1..G5).
 * Both free, no key. Fetched from the weather worker thread.
 */
final class Hazards {
    private Hazards() {}

    // ---- GDACS
    static volatile String evType = null;     // TC EQ FL VO DR WF
    static volatile String evName = null;     // "MONTHA", "Etna", may be empty
    static volatile String evPlace = null;    // "Bay of Bengal" / country
    static volatile boolean evRed = false;
    static volatile int evKm = -1;
    static volatile String evDir = "";        // compass from you to the event
    static volatile long gdacsAt = 0, gdacsTried = 0;

    /** One current alert. */
    static final class Ev {
        String type, name, place, dir;
        double lat = Double.NaN, lon = Double.NaN;   // where it is (for the status bar's world map)
        boolean red;
        int km;
        int lvl = 1;          // 2 red, 1 orange, 0 yellow (no severity given)
        boolean india;        // official Indian alert (SACHET)
        String text = "";     // SACHET: headline + description + area, for matching storm names
    }
    private static volatile java.util.List<Ev> gdacsL = java.util.Collections.emptyList(),
            eonetL = java.util.Collections.emptyList(), quakeL = java.util.Collections.emptyList(),
            reliefL = java.util.Collections.emptyList(), volcL = java.util.Collections.emptyList(),
            sachetL = java.util.Collections.emptyList();
    static volatile long quakeAt, quakeTried, reliefAt, reliefTried;
    static volatile long eonetAt, eonetTried, volcAt, volcTried, sachetAt, sachetTried;
    static volatile String status = "";

    /** All sources together: Indian alerts first, then red, orange, yellow; nearest first; same event once. */
    private static void merge() {
        java.util.List<Ev> all = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        // storms that really exist right now (GDACS / EONET names): only these count as storm names
        java.util.List<String> storms = new java.util.ArrayList<>();
        for (java.util.List<Ev> l : java.util.Arrays.asList(gdacsL, eonetL))
            for (Ev e : l) if ("TC".equals(e.type) && e.name != null && e.name.length() >= 3) storms.add(e.name.toLowerCase(Locale.US));
        // Indian cyclone alerts: one per storm (the most severe, with a place), named after the storm
        java.util.Map<String, Ev> inStorm = new java.util.LinkedHashMap<>();
        java.util.List<Ev> sach = new java.util.ArrayList<>();
        for (Ev e : sachetL) {
            if (!"TC".equals(e.type)) { sach.add(e); continue; }
            String st = null;
            for (String n : storms) if (e.text.contains(n)) { st = n; break; }
            if (st == null && storms.isEmpty()) { sach.add(e); continue; }
            if (st == null) {                                   // "Cyclone" without a name: the nearest active storm
                Ev near = null;
                for (Ev g : gdacsL) if ("TC".equals(g.type) && (near == null || (g.km >= 0 && g.km < near.km))) near = g;
                if (near == null || near.km < 0 || near.km > 2500) { sach.add(e); continue; }
                st = near.name.toLowerCase(Locale.US);
            }
            Ev c = new Ev();
            c.type = "TC"; c.india = true; c.lvl = e.lvl; c.km = e.km; c.dir = e.dir; c.text = e.text;
            c.name = st.substring(0, 1).toUpperCase(Locale.US) + st.substring(1);
            String pl = e.place;
            if (pl != null && (pl.toLowerCase(Locale.US).contains(st)
                    || pl.matches("(?i).*(landfall|alert|warning|cyclone|storm|bulletin|depression).*"))) pl = null;
            c.place = pl;
            Ev old = inStorm.get(st);
            if (old == null || c.lvl > old.lvl || (c.lvl == old.lvl && old.place == null && c.place != null)) inStorm.put(st, c);
        }
        sach.addAll(inStorm.values());
        for (java.util.List<Ev> l : java.util.Arrays.asList(sach, gdacsL, quakeL, reliefL, volcL, eonetL)) {
            for (Ev e : l) {
                // a storm already shown as an Indian alert: drop its global copy
                if (!e.india && "TC".equals(e.type) && e.name != null && inStorm.containsKey(e.name.toLowerCase(Locale.US))) continue;
                if (e.india) {
                    // Indian alerts: one per event + area (several districts can share "Cyclone")
                    String ik = "in|" + e.name + "|" + e.place;
                    if (seen.add(ik)) all.add(e);
                    continue;
                }
                String key = (e.name == null ? "" : e.name.toLowerCase(Locale.US).replaceAll("[^a-z]", ""));
                if (!key.isEmpty() && key.length() >= 4) {
                    boolean dup = false;
                    for (String k : seen) if (k.contains(key) || key.contains(k)) { dup = true; break; }
                    if (dup) continue;
                    seen.add(key);
                }
                all.add(e);
            }
        }
        // events inside India that only say "India" (fires, floods...): show the state instead
        for (Ev e : all) {
            if (e.place == null || !e.place.equalsIgnoreCase("India") || Double.isNaN(e.lat)) continue;
            String k = String.format(java.util.Locale.US, "%.1f,%.1f", e.lat, e.lon);
            String st = STATE.get(k);
            if (st == null) askState(k, e.lat, e.lon);
            else if (!st.isEmpty()) e.place = st;
        }
        java.util.Collections.sort(all, (x, y) -> x.india != y.india ? (x.india ? -1 : 1)
                : x.lvl != y.lvl ? Integer.compare(y.lvl, x.lvl)
                : Integer.compare(x.km < 0 ? 99999 : x.km, y.km < 0 ? 99999 : y.km));
        // mix the kinds: the weekly volcano report alone lists 20-30 volcanoes and would crowd out everything
        // else, so at most 3 of any one kind (Indian alerts exempt), then take turns between kinds
        java.util.Map<String, java.util.List<Ev>> byType = new java.util.LinkedHashMap<>();
        java.util.List<Ev> out = new java.util.ArrayList<>();
        for (Ev e : all) {
            if (e.india) { out.add(e); continue; }
            java.util.List<Ev> l = byType.get(e.type);
            if (l == null) byType.put(e.type, l = new java.util.ArrayList<>());
            if (l.size() < 3) l.add(e);
        }
        for (int round = 0; round < 3; round++)
            for (java.util.List<Ev> l : byType.values()) if (round < l.size()) out.add(l.get(round));
        events = out.size() > 15 ? new java.util.ArrayList<>(out.subList(0, 15)) : out;
    }
    /** Sea or ocean a storm is over, from its position (rough boxes, most specific first); null for non-storms or unknown. */
    static String seaOf(Ev e) {
        if (e == null || !"TC".equals(e.type) || Double.isNaN(e.lat) || Double.isNaN(e.lon)) return null;
        double la = e.lat, lo = e.lon;
        double[][] box = {   // latMin, latMax, lonMin, lonMax
            {24, 30.5, 48, 56.5}, {22, 26.5, 56.5, 61.5}, {12, 28, 32, 43.5}, {10.5, 15, 43, 52},
            {-26, -10, 32, 45}, {6, 19, 92, 99}, {5, 25, 52, 77}, {5, 23, 77, 100},
            {0, 23, 99, 121}, {5, 35, 121, 145}, {-25, -8, 142, 165}, {-47, -30, 148, 175},
            {9, 22, -88, -60}, {18, 31, -98, -81}, {30, 46, -6, 36}};
        String[] nm = {"Persian Gulf", "Gulf of Oman", "Red Sea", "Gulf of Aden",
            "Mozambique Channel", "Andaman Sea", "Arabian Sea", "Bay of Bengal",
            "South China Sea", "Philippine Sea", "Coral Sea", "Tasman Sea",
            "Caribbean Sea", "Gulf of Mexico", "Mediterranean Sea"};
        for (int i = 0; i < box.length; i++)
            if (la >= box[i][0] && la <= box[i][1] && lo >= box[i][2] && lo <= box[i][3]) return nm[i];
        if (la < -60) return "Southern Ocean";
        if (la >= 0 && lo >= -80 && lo <= -5) return "North Atlantic";
        if (la < 0 && lo >= -70 && lo <= 20) return "South Atlantic";
        if (la >= 0 && (lo >= 100 || lo <= -100)) return "North Pacific";
        if (la < 0 && (lo >= 140 || lo <= -70)) return "South Pacific";
        if (lo >= 20 && lo <= 120) return "Indian Ocean";
        return null;
    }

    /** "lat,lon" (0.1 degree) -> Indian state name, "" when none. */
    private static final java.util.Map<String, String> STATE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> STATE_ASKED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static long stateCallAt;

    /** One background lookup of the state at a point (OpenStreetMap, 1 a second at most); merges again when it lands. */
    private static void askState(String k, double lat, double lon) {
        if (!STATE_ASKED.add(k)) return;
        Thread t = new Thread(() -> {
            try {
                synchronized (STATE) {
                    long wait = 1200 - (System.currentTimeMillis() - stateCallAt);
                    if (wait > 0) Thread.sleep(wait);
                    stateCallAt = System.currentTimeMillis();
                }
                String url = String.format(java.util.Locale.US, "https://nominatim.openstreetmap.org/reverse?format=jsonv2"
                        + "&lat=%.4f&lon=%.4f&zoom=5&addressdetails=1&accept-language=en", lat, lon);
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(8_000);
                c.setReadTimeout(8_000);
                c.setRequestProperty("User-Agent", "NetSpeedXposed/1.0 (status bar module; github.com/dmk6091/NetSpeedXposed)");
                if (c.getResponseCode() != 200) { STATE_ASKED.remove(k); return; }
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                org.json.JSONObject a = new org.json.JSONObject(sb.toString()).optJSONObject("address");
                String st = a == null ? "" : a.optString("state", "");
                STATE.put(k, st == null ? "" : st.trim());
                merge();
            } catch (Throwable ignored) { STATE_ASKED.remove(k); }
        }, "hazard-state");
        t.setDaemon(true);
        t.start();
    }

    /** All current orange / red alerts, red first, then nearest. */
    static volatile java.util.List<Ev> events = java.util.Collections.emptyList();

    /** The alert to show now: rotates through all of them, 15 s each. */
    static Ev pick(long now) {
        java.util.List<Ev> l = events;
        if (l.isEmpty()) return null;
        return l.get((int) ((now / 15000L) % l.size()));
    }

    // ---- Kp
    static volatile float kp = Float.NaN;
    static volatile long kpAt = 0, kpTried = 0;

    interface Http { String get(String url) throws Exception; }

    static void tick(Http http, double lat, double lon, long now) {
        if (now - sachetTried > due(sachetAt, sachetTried, 15)) {
            sachetTried = now;
            try { sachet(http, lat, lon, now); } catch (Throwable t) { status = "SACHET failed: " + t.getClass().getSimpleName(); }
        }
        if (now - eonetTried > due(eonetAt, eonetTried, 30)) {
            eonetTried = now;
            try { eonet(http.get("https://eonet.gsfc.nasa.gov/api/v3/events?status=open&days=10"), lat, lon, now); }
            catch (Throwable t) { status = "EONET failed: " + t.getClass().getSimpleName(); }
        }
        if (now - quakeTried > due(quakeAt, quakeTried, 30)) {
            quakeTried = now;
            try { quakes(http.get("https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/4.5_week.geojson"), lat, lon, now); }
            catch (Throwable t) { status = "USGS quakes failed: " + t.getClass().getSimpleName(); }
        }
        String app = Cfg.s(Cfg.RELIEFWEB_APP);
        if (!app.isEmpty() && now - reliefTried > due(reliefAt, reliefTried, 120)) {
            reliefTried = now;
            try { reliefweb(http.get("https://api.reliefweb.int/v2/disasters?appname=" + java.net.URLEncoder.encode(app, "UTF-8")
                    + "&filter[field]=status&filter[value]=current&limit=60&sort[]=date.created:desc"
                    + "&fields[include][]=name&fields[include][]=primary_type.name&fields[include][]=primary_country.name"
                    + "&fields[include][]=primary_country.location"), lat, lon, now); }
            catch (Throwable t) { status = "ReliefWeb failed: " + t.getClass().getSimpleName(); }
        }
        if (now - volcTried > due(volcAt, volcTried, 360)) {
            volcTried = now;
            try { volcanoes(http.get("https://volcano.si.edu/news/WeeklyVolcanoRSS.xml"), lat, lon, now); }
            catch (Throwable t) { status = "Volcano reports failed: " + t.getClass().getSimpleName(); }
        }
        if (now - gdacsTried > due(gdacsAt, gdacsTried, 30)) {
            gdacsTried = now;
            try { gdacs(http.get(gdacsUrl(now)), lat, lon, now); } catch (Throwable ignored) { }
        }
        if (now - kpTried > due(kpAt, kpTried, 15)) {
            kpTried = now;
            try { kp(http.get("https://services.swpc.noaa.gov/json/planetary_k_index_1m.json"), now); } catch (Throwable ignored) { }
        }
    }

    /** Minutes to wait: 3 after a failed try (e.g. no network right after boot), else the source's normal interval. */
    private static long due(long at, long tried, int normalMin) {
        return (at == 0 || tried > at ? 3 : normalMin) * 60_000L;
    }

    private static String gdacsUrl(long now) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return "https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH?eventlist=TC;EQ;FL;VO;DR;WF"
                + "&alertlevel=Green;Orange;Red&fromDate=" + f.format(new java.util.Date(now - 10 * 86400_000L))
                + "&toDate=" + f.format(new java.util.Date(now + 86400_000L));
    }

    static void gdacs(String json, double lat, double lon, long now) throws Exception {
        org.json.JSONArray fs = new org.json.JSONObject(json).optJSONArray("features");
        String bt = null, bn = null, bp = null, bd = "";
        boolean br = false;
        int bk = -1;
        java.util.List<Ev> all = new java.util.ArrayList<>();
        if (fs != null) {
            for (int i = 0; i < fs.length(); i++) {
                org.json.JSONObject f = fs.optJSONObject(i);
                if (f == null) continue;
                org.json.JSONObject p = f.optJSONObject("properties");
                if (p == null) continue;
                String cur = p.optString("iscurrent", "true");
                if ("false".equalsIgnoreCase(cur)) continue;
                String lvl = p.optString("alertlevel", "");
                boolean red = "red".equalsIgnoreCase(lvl);
                boolean green = "green".equalsIgnoreCase(lvl);
                if (!red && !green && !"orange".equalsIgnoreCase(lvl)) continue;
                double elat = Double.NaN, elon = Double.NaN;
                org.json.JSONObject g = f.optJSONObject("geometry");
                if (g != null && "Point".equals(g.optString("type"))) {
                    org.json.JSONArray c = g.optJSONArray("coordinates");
                    if (c != null && c.length() >= 2) { elon = c.optDouble(0); elat = c.optDouble(1); }
                }
                int km = Double.isNaN(elat) ? 99999 : (int) Math.round(dist(lat, lon, elat, elon));
                Ev ev = new Ev();
                ev.lat = elat; ev.lon = elon;
                ev.type = p.optString("eventtype", "");
                // eventname is often empty (fires, floods): fall back to name / description ("Forest fires in X")
                String en = p.optString("eventname", "");
                if (en.isEmpty()) en = p.optString("name", "");
                if (en.isEmpty()) en = p.optString("description", "");
                ev.name = clean(en);
                String ctry = p.optString("country", "");
                if (ctry.isEmpty()) {
                    org.json.JSONArray ac = p.optJSONArray("affectedcountries");
                    if (ac != null && ac.length() > 0) ctry = ac.optJSONObject(0).optString("countryname", "");
                }
                if (ctry.isEmpty()) {
                    java.util.regex.Matcher im = java.util.regex.Pattern.compile("(?i)\\bin\\s+([A-Z][^,;.]+)").matcher(en);
                    if (im.find()) ctry = im.group(1).trim();
                }
                if (!ctry.isEmpty() && ev.name.equalsIgnoreCase(ctry)) ev.name = "";
                ev.place = ctry.isEmpty() ? null : ctry.split("[,;]")[0].trim();
                ev.red = red;
                ev.km = km >= 99999 ? -1 : km;
                ev.dir = Double.isNaN(elat) ? "" : compass(lat, lon, elat, elon);
                ev.lvl = red ? 2 : green ? -1 : 1;
                if (green && "EQ".equals(ev.type)) { all.remove(ev); continue; }   // quakes come from USGS (M6+)
                all.add(ev);
                if (green) continue;
                boolean better = bt == null || (red && !br) || (red == br && km < bk);
                if (!better) continue;
                bt = p.optString("eventtype", "");
                bn = clean(p.optString("eventname", p.optString("name", "")));
                String country = p.optString("country", "");
                bp = country.isEmpty() ? null : country.split("[,;]")[0].trim();
                br = red;
                bk = km;
                bd = Double.isNaN(elat) ? "" : compass(lat, lon, elat, elon);
            }
        }
        java.util.Collections.sort(all, (x, y) -> x.red != y.red ? (x.red ? -1 : 1)
                : Integer.compare(x.km < 0 ? 99999 : x.km, y.km < 0 ? 99999 : y.km));
        gdacsL = all;
        merge();
        evType = bt; evName = bn; evPlace = bp; evRed = br; evKm = bk >= 99999 ? -1 : bk; evDir = bd;
        gdacsAt = now;
    }

    /** GDACS names are often "Tropical Cyclone MONTHA-25" or "Earthquake in X": keep the short proper name. */
    private static String clean(String n) {
        if (n == null) return "";
        n = n.replaceAll("(?i)^(tropical cyclone|tropical storm|cyclone|hurricane|typhoon|floods?|droughts?|forest fires?|"
                + "wild ?fires?|fires?|earthquakes?|volcano(es)?)\\b\\s*", "").trim();
        // "in Ethiopia, ..." left over: that's the place, not a name
        if (n.toLowerCase(Locale.US).startsWith("in ") || n.isEmpty()) return "";
        n = n.replaceAll("-\\d{2,4}$", "").trim();
        return n.length() > 16 ? n.substring(0, 15) + "…" : n;
    }

    static void kp(String json, long now) throws Exception {
        org.json.JSONArray a = new org.json.JSONArray(json);
        float v = Float.NaN;
        for (int i = a.length() - 1; i >= 0 && Float.isNaN(v); i--) {
            Object o = a.get(i);
            if (o instanceof org.json.JSONObject) {
                org.json.JSONObject j = (org.json.JSONObject) o;
                if (j.has("estimated_kp") && !j.isNull("estimated_kp")) v = (float) j.optDouble("estimated_kp");
                else if (j.has("kp_index") && !j.isNull("kp_index")) v = (float) j.optDouble("kp_index");
            } else if (o instanceof org.json.JSONArray && i > 0) {            // old format: header row + [time, Kp, ..]
                try { v = Float.parseFloat(((org.json.JSONArray) o).getString(1)); } catch (Throwable ignored) { }
            }
        }
        kp = v;
        kpAt = now;
    }

    /** Anything to show (data under 6 h old). */
    static boolean disaster(long now) {
        long last = Math.max(Math.max(gdacsAt, eonetAt), Math.max(volcAt, sachetAt));
        return !events.isEmpty() && now - last < 6 * 3600_000L;
    }

    /** For the settings dialog: what each source returned and what rotates on the bar. */
    static String report() {
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder("DISASTERS\n");
        b.append(src("SACHET", sachetAt, sachetTried, sachetL.size(), now));
        b.append(src("GDACS", gdacsAt, gdacsTried, gdacsL.size(), now));
        b.append(src("EONET", eonetAt, eonetTried, eonetL.size(), now));
        b.append(src("USGS M6+", quakeAt, quakeTried, quakeL.size(), now));
        b.append(Cfg.s(Cfg.RELIEFWEB_APP).isEmpty() ? "  ReliefWeb: off (no app name)\n" : src("ReliefWeb", reliefAt, reliefTried, reliefL.size(), now));
        b.append(src("Volcano report", volcAt, volcTried, volcL.size(), now));
        b.append(String.format(Locale.US, "  Kp %s%s\n", Float.isNaN(kp) ? "?" : String.format(Locale.US, "%.1f", kp),
                kpAt > 0 ? " (" + (now - kpAt) / 60000 + " min ago)" : ""));
        if (!status.isEmpty()) b.append("  last error: ").append(status).append('\n');
        b.append("  rotating ").append(events.size()).append(":\n");
        for (Ev e : events)
            b.append("   ").append(e.india ? "IN " : "").append(e.lvl >= 2 ? "red " : e.lvl == 1 ? "orange " : e.lvl == 0 ? "yellow " : "green ")
                    .append(e.type).append(' ').append(e.name).append(e.place != null ? " · " + e.place : "")
                    .append(e.km >= 0 ? " " + e.km + "km " + e.dir : "").append('\n');
        return b.toString();
    }

    private static String src(String n, long at, long tried, int count, long now) {
        if (at == 0) return "  " + n + ": " + (tried == 0 ? "not tried yet" : "failed") + "\n";
        return "  " + n + ": " + count + " (" + (now - at) / 60000 + " min ago" + (tried > at ? ", last try failed" : "") + ")\n";
    }

    // ---------------------------------------------------------------- USGS: M6+ in the last 7 days, worldwide

    static void quakes(String json, double lat, double lon, long now) throws Exception {
        org.json.JSONArray fs = new org.json.JSONObject(json).getJSONArray("features");
        java.util.List<Ev> l = new java.util.ArrayList<>();
        for (int i = 0; i < fs.length(); i++) {
            org.json.JSONObject f = fs.getJSONObject(i), p = f.getJSONObject("properties");
            double mag = p.optDouble("mag", 0);
            if (mag < 6) continue;
            org.json.JSONArray c = f.getJSONObject("geometry").getJSONArray("coordinates");
            double elon = c.getDouble(0), elat = c.getDouble(1);
            Ev ev = new Ev();
            ev.type = "EQ";
            ev.lat = elat; ev.lon = elon;
            ev.name = String.format(Locale.US, "M%.1f", mag);
            String pl = p.optString("place", "");
            int k = pl.lastIndexOf(',');
            ev.place = (k >= 0 ? pl.substring(k + 1) : pl.replaceAll("^.*\\bof\\s+", "")).trim();
            if (ev.place.isEmpty()) ev.place = null;
            ev.lvl = mag >= 7 ? 2 : mag >= 6.5 ? 1 : 0;
            ev.km = (int) Math.round(dist(lat, lon, elat, elon));
            ev.dir = compass(lat, lon, elat, elon);
            l.add(ev);
        }
        quakeL = l;
        quakeAt = now;
        merge();
    }

    // ---------------------------------------------------------------- ReliefWeb (UN OCHA), needs an approved app name

    static void reliefweb(String json, double lat, double lon, long now) throws Exception {
        org.json.JSONArray a = new org.json.JSONObject(json).getJSONArray("data");
        java.util.List<Ev> l = new java.util.ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            org.json.JSONObject f = a.getJSONObject(i).optJSONObject("fields");
            if (f == null) continue;
            org.json.JSONObject pt = f.optJSONObject("primary_type"), pc = f.optJSONObject("primary_country");
            String t = pt == null ? "" : pt.optString("name", "").toLowerCase(Locale.US);
            String type = t.contains("flood") ? "FL" : t.contains("cyclone") || t.contains("storm") ? "TC"
                    : t.contains("earthquake") ? "EQ" : t.contains("volcano") ? "VO" : t.contains("drought") ? "DR"
                    : t.contains("land slide") || t.contains("landslide") || t.contains("mud") ? "LS"
                    : t.contains("epidemic") ? "EP" : t.contains("fire") ? "WF" : t.contains("tsunami") ? "TS_SEA"
                    : t.contains("cold") ? "CW" : t.contains("heat") ? "HW" : "";
            if (type.isEmpty() || type.equals("CW") || type.equals("HW")) continue;     // weather left out
            Ev ev = new Ev();
            ev.type = type;
            ev.name = pt.optString("name", "");
            ev.place = pc == null ? null : pc.optString("name", null);
            ev.lvl = 0;
            org.json.JSONObject loc = pc == null ? null : pc.optJSONObject("location");
            if (loc != null) {
                double elat = loc.optDouble("lat"), elon = loc.optDouble("lon");
                ev.km = (int) Math.round(dist(lat, lon, elat, elon));
                ev.dir = compass(lat, lon, elat, elon);
            } else { ev.km = -1; ev.dir = ""; }
            l.add(ev);
        }
        reliefL = l;
        reliefAt = now;
        merge();
    }

    // ---------------------------------------------------------------- NASA EONET

    static void eonet(String json, double lat, double lon, long now) throws Exception {
        org.json.JSONArray a = new org.json.JSONObject(json).getJSONArray("events");
        java.util.List<Ev> l = new java.util.ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            org.json.JSONObject e = a.optJSONObject(i);
            if (e == null) continue;
            org.json.JSONArray cs = e.optJSONArray("categories");
            String cat = cs != null && cs.length() > 0 ? cs.optJSONObject(0).optString("id", "") : "";
            String type;
            switch (cat) {
                case "severeStorms": type = "TC"; break;
                case "volcanoes": type = "VO"; break;
                case "floods": type = "FL"; break;
                case "wildfires": type = "WF"; break;
                case "landslides": type = "LS"; break;
                case "drought": type = "DR"; break;
                default: continue;          // sea ice, snow, temperature, water colour, manmade
            }
            org.json.JSONArray gs = e.optJSONArray("geometry");
            if (gs == null || gs.length() == 0) continue;
            org.json.JSONObject g = gs.optJSONObject(gs.length() - 1);   // latest position
            org.json.JSONArray c = g == null ? null : g.optJSONArray("coordinates");
            if (c == null || c.length() < 2 || c.opt(0) instanceof org.json.JSONArray) continue;
            double elon = c.optDouble(0), elat = c.optDouble(1);
            int km = (int) Math.round(dist(lat, lon, elat, elon));
            // wildfires are counted by the hundred: only the ones near you
            if ("WF".equals(type) && km > 500) continue;
            Ev ev = new Ev();
            ev.lat = elat; ev.lon = elon;
            ev.type = type;
            ev.name = shortTitle(e.optString("title", ""));
            ev.place = null;
            ev.lvl = 0;
            ev.km = km;
            ev.dir = compass(lat, lon, elat, elon);
            l.add(ev);
        }
        eonetL = l;
        eonetAt = now;
        merge();
    }

    /** "Tropical Cyclone Murasu" -> "Murasu"; "Wildfire in X County, Y" -> "X County"; trimmed. */
    private static String shortTitle(String t) {
        t = t.replaceAll("(?i)^(tropical cyclone|tropical storm|typhoon|hurricane|cyclone|super typhoon)\\s+", "");
        t = t.replaceAll("(?i)^(wildfire|fire|flood|floods|landslide|volcano)\\s*(in|-)?\\s*", "");
        int c = t.indexOf(',');
        if (c > 3) t = t.substring(0, c);
        t = t.trim();
        return t.length() > 18 ? t.substring(0, 17) + "…" : t;
    }

    // ---------------------------------------------------------------- Smithsonian / USGS weekly volcano report

    static void volcanoes(String xml, double lat, double lon, long now) {
        java.util.List<Ev> l = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?s)<item>(.*?)</item>").matcher(xml);
        while (m.find()) {
            String it = m.group(1);
            String title = tag(it, "title");
            if (title == null) continue;
            // "Etna (Italy)" or "Etna (Italy) - Report for ..."
            java.util.regex.Matcher tm = java.util.regex.Pattern.compile("^\\s*([^(]+?)\\s*\\(([^)]+)\\)").matcher(title);
            if (!tm.find()) continue;
            Ev ev = new Ev();
            ev.type = "VO";
            ev.name = tm.group(1).trim();
            ev.place = tm.group(2).trim();
            ev.lvl = title.toLowerCase(Locale.US).contains("new activity") ? 1 : 0;
            String pt = tag(it, "georss:point");
            if (pt != null) {
                String[] ab = pt.trim().split("\\s+");
                try {
                    double elat = Double.parseDouble(ab[0]), elon = Double.parseDouble(ab[1]);
                    ev.lat = elat; ev.lon = elon;
                    ev.km = (int) Math.round(dist(lat, lon, elat, elon));
                    ev.dir = compass(lat, lon, elat, elon);
                } catch (Throwable ignored) { ev.km = -1; ev.dir = ""; }
            } else { ev.km = -1; ev.dir = ""; }
            l.add(ev);
        }
        volcL = l;
        volcAt = now;
        merge();
    }

    // ---------------------------------------------------------------- NDMA SACHET (official Indian alerts, CAP)

    /** CAP messages never change once issued: fetch each one only once. */
    private static final java.util.Map<String, String> CAP_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    static void sachet(Http http, double lat, double lon, long now) throws Exception {
        String rss = http.get("https://sachet.ndma.gov.in/cap_public_website/rss/rss_india.xml");
        java.util.List<Ev> l = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?s)<item>(.*?)</item>").matcher(rss);
        int fetched = 0;
        while (m.find() && fetched < 25) {
            String it = m.group(1);
            String link = tag(it, "link");
            if (link == null || link.isEmpty()) continue;
            String cap = CAP_CACHE.get(link);
            if (cap == null) {
                try { cap = http.get(link.trim()); fetched++; } catch (Throwable t) { continue; }
                if (CAP_CACHE.size() > 300) CAP_CACHE.clear();
                CAP_CACHE.put(link, cap);
            }
            String exp = tag(cap, "expires");
            if (exp != null && expired(exp, now)) continue;
            String sev = tag(cap, "severity");
            int lvl = "Extreme".equalsIgnoreCase(sev) ? 2 : "Severe".equalsIgnoreCase(sev) ? 1
                    : "Moderate".equalsIgnoreCase(sev) ? 0 : -1;
            if (lvl < 0) continue;
            String event = tag(cap, "event");
            String area = tag(cap, "areaDesc");
            double[] c = polyCentre(tag(cap, "polygon"));
            int km = c == null ? -1 : (int) Math.round(dist(lat, lon, c[0], c[1]));
            // only alerts in your part of India (within ~400 km) or extreme anywhere in India
            if (km > 400 && lvl < 2) continue;
            String ty = sachetType(event);
            // disasters only: rain, thunderstorm, heat / cold wave and other weather warnings are left out
            if (ty.isEmpty() || ty.equals("TS") || ty.equals("RA") || ty.equals("HW") || ty.equals("CW")) continue;
            Ev ev = new Ev();
            if (c != null) { ev.lat = c[0]; ev.lon = c[1]; }
            ev.india = true;
            ev.type = ty;
            ev.name = event == null ? "Alert" : event.length() > 22 ? event.substring(0, 21) + "…" : event;
            String txt = (tag(cap, "headline") == null ? "" : tag(cap, "headline")) + " " + (tag(cap, "description") == null ? "" : tag(cap, "description"));
            String pl = area == null ? null : area.split("[,;]")[0].trim();
            // some messages carry a file-like id ("heavy_rainfall_2026-11-02") instead of a place: drop those
            if (pl != null && (pl.contains("_") || pl.matches(".*\\d{4}-\\d{2}.*") || pl.isEmpty())) pl = null;
            if (pl == null) {
                String sn = tag(cap, "senderName");
                if (sn != null) {
                    java.util.regex.Matcher st = java.util.regex.Pattern.compile("(?i)(Andhra Pradesh|Odisha|Tamil Nadu|Kerala|Karnataka|"
                            + "Maharashtra|Gujarat|West Bengal|Goa|Puducherry|Telangana|Bihar|Assam|Uttar Pradesh|Madhya Pradesh|Rajasthan|"
                            + "Uttarakhand|Himachal|Jammu|Ladakh|Sikkim|Meghalaya|Tripura|Mizoram|Manipur|Nagaland|Arunachal|Jharkhand|"
                            + "Chhattisgarh|Punjab|Haryana|Delhi|Andaman)").matcher(sn + " " + txt);
                    if (st.find()) pl = st.group(1);
                }
            }
            ev.place = pl;
            ev.text = (txt + " " + (area == null ? "" : area) + " " + (event == null ? "" : event)).toLowerCase(Locale.US);
            ev.lvl = lvl;
            ev.km = km;
            ev.dir = c == null ? "" : compass(lat, lon, c[0], c[1]);
            l.add(ev);
        }
        sachetL = l;
        sachetAt = now;
        merge();
    }

    private static String sachetType(String e) {
        String s = e == null ? "" : e.toLowerCase(Locale.US);
        if (s.contains("cyclone") || s.contains("depression")) return "TC";
        if (s.contains("flood")) return "FL";
        if (s.contains("thunder") || s.contains("lightning")) return "TS";
        if (s.contains("rain")) return "RA";
        if (s.contains("heat")) return "HW";
        if (s.contains("cold")) return "CW";
        if (s.contains("landslide")) return "LS";
        if (s.contains("tsunami") || s.contains("wave") || s.contains("swell")) return "TS_SEA";
        if (s.contains("earthquake")) return "EQ";
        if (s.contains("fire")) return "WF";
        return "";
    }

    private static boolean expired(String iso, long now) {
        try {
            String v = iso.trim().replaceAll("([+-]\\d\\d):(\\d\\d)$", "$1$2");
            java.util.Date d = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).parse(v);
            return d != null && d.getTime() < now;
        } catch (Throwable t) {
            return false;
        }
    }

    /** CAP polygon "lat,lon lat,lon ..." -> rough centre {lat, lon}. */
    private static double[] polyCentre(String poly) {
        if (poly == null) return null;
        double a = 0, o = 0;
        int n = 0;
        for (String pr : poly.trim().split("\\s+")) {
            String[] ab = pr.split(",");
            if (ab.length < 2) continue;
            try { a += Double.parseDouble(ab[0]); o += Double.parseDouble(ab[1]); n++; } catch (Throwable ignored) { }
        }
        return n == 0 ? null : new double[]{a / n, o / n};
    }

    /** First <tag>..</tag> (any namespace prefix, CDATA stripped). */
    private static String tag(String x, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?s)<(?:\\w+:)?" + java.util.regex.Pattern.quote(
                name.contains(":") ? name.substring(name.indexOf(':') + 1) : name) + "(?:\\s[^>]*)?>(.*?)</").matcher(x);
        if (!m.find()) return null;
        String v = m.group(1).trim();
        if (v.startsWith("<![CDATA[")) v = v.substring(9, v.endsWith("]]>") ? v.length() - 3 : v.length());
        return v.trim();
    }

    /** Kp storm level 1..5 (G1..G5), 0 = no storm or stale. */
    static int storm(long now) {
        if (Float.isNaN(kp) || now - kpAt > 3 * 3600_000L || kp < 5f) return 0;
        return Math.min(5, (int) Math.floor(kp) - 4);
    }

    static String icon(String t) {
        switch (t == null ? "" : t) {
            case "TC": return "🌀";
            case "EQ": return "〰";
            case "FL": return "🌊";
            case "VO": return "🌋";
            case "DR": return "🏜";
            case "WF": return "🔥";
            case "LS": return "⛰";
            case "DU": return "🌫";
            case "TS": return "⛈";
            case "RA": return "🌧";
            case "HW": return "🌡";
            case "CW": return "❄";
            case "TS_SEA": return "🌊";
            case "EP": return "🦠";
            default: return "⚠";
        }
    }

    private static double dist(double a1, double o1, double a2, double o2) {
        double p = Math.toRadians(a2 - a1), q = Math.toRadians(o2 - o1);
        double h = Math.sin(p / 2) * Math.sin(p / 2)
                + Math.cos(Math.toRadians(a1)) * Math.cos(Math.toRadians(a2)) * Math.sin(q / 2) * Math.sin(q / 2);
        return 6371 * 2 * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    private static String compass(double a1, double o1, double a2, double o2) {
        double y = Math.sin(Math.toRadians(o2 - o1)) * Math.cos(Math.toRadians(a2));
        double x = Math.cos(Math.toRadians(a1)) * Math.sin(Math.toRadians(a2))
                - Math.sin(Math.toRadians(a1)) * Math.cos(Math.toRadians(a2)) * Math.cos(Math.toRadians(o2 - o1));
        double b = (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
        return new String[]{"N", "NE", "E", "SE", "S", "SW", "W", "NW"}[(int) Math.round(b / 45) % 8];
    }
}
