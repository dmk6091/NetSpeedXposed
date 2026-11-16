package com.netspeed.xposed;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Real ISS and satellite positions for the strip above the clock (no made-up passes).
 *
 * Orbits come from CelesTrak's "visual" group (bright satellites, ISS included), refreshed every 12 h.
 * Each one is moved forward from its published orbit (Kepler + Earth's flattening + drag): a few km
 * of error after a day, plenty to say which city the ISS is over or whether a satellite is up in your sky.
 *
 *   ISS ground track over India      -> "ISS over Nagpur"
 *   otherwise                        -> "No ISS pass"
 *   another satellite 10°+ above you -> "· HST" added (the highest one)
 */
final class Sat {
    private Sat() {}

    /** What the strip shows; set on the worker thread. */
    static volatile String text = "";
    /** True while the ISS is over India or a satellite is up. */
    static volatile boolean live;
    /** The parts of text, so the strip can shorten the satellite name first and keep the city. */
    static volatile String issText = "", satName = "", satCity = "";

    static final class Tle {
        String name;
        int id;
        double epochJd, ndot2, inc, raan, ecc, argp, m0, n;   // radians, rad/min, rev/day² for ndot2
    }

    private static List<Tle> tles = new ArrayList<>();
    private static long fetchedAt, triedAt, calcAt;
    private static volatile String lastError;
    /** Human-readable list for the NetSpeed app ("Satellites now"), rebuilt with every calculation. */
    static volatile String report = "";
    /** Satellites at or near your horizon at the last update, for the sky background to move in real time. */
    static volatile List<Tle> overhead = new ArrayList<>();

    private static final double MU = 398600.4418, RE = 6378.137, J2 = 1.08262668e-3, F_WGS = 1 / 298.257223563;

    /** Worker thread, about every 5 s. lat / lon: where you are. */
    static void update(double lat, double lon) {
        long now = System.currentTimeMillis();
        if (tles.isEmpty() && !cacheTried) { cacheTried = true; loadCache(); }
        // no orbits: retry after 1 min for the first few tries (network may not be up right after boot), then 10 min
        long wait = tles.isEmpty() ? (tries < 4 ? 60_000L : 10 * 60_000L) : 30 * 60_000L;
        if ((tles.isEmpty() || now - fetchedAt > 12 * 3600_000L) && now - triedAt > wait) {
            triedAt = now;
            tries++;
            fetch();
        }
        if (now - calcAt < 4_000L) return;
        calcAt = now;
        compute(now, lat, lon);
    }

    static void compute(long now, double lat, double lon) {
        List<Tle> list = tles;
        java.text.SimpleDateFormat hm = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US);
        if (list.isEmpty()) {
            text = tries >= 4 ? "ISS: no orbit data (retrying)" : "ISS: loading orbits…";   // no guesses: say what's happening
            issText = text; satName = ""; satCity = "";
            live = false;
            report = "Updated " + hm.format(new java.util.Date(now)) + "\n\nSatellite orbits not downloaded yet"
                    + (triedAt > 0 ? " (last try " + hm.format(new java.util.Date(triedAt)) + ")" : "")
                    + (lastError != null ? ":\n" + lastError : "") + "\n\nThe status bar retries every 10 minutes.";
            return;
        }
        List<Object[]> up = new ArrayList<>();      // {name, elevation, azimuth, city}
        List<Tle> upT = new ArrayList<>();
        String issWhere = "?";
        String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        String iss = null;                 // set only while the ISS matters (over India or in your sky)
        Tle issT = null;
        boolean on = false;
        String best = null, bestCity = null;
        int bestId = 0;
        double bestScore = -1, bestEl = 0;
        double[] obs = ecef(lat, lon, 0.55);
        for (Tle t : list) {
            double[] g = propagate(t, now);
            if (g == null) continue;
            if (t.id == 25544) {
                issT = t;
                double[] ll = subPoint(g);
                String city = India.cityUnder(ll[0], ll[1]);
                double ie = elevation(obs, lat, lon, g);
                String d = dirs[(int) Math.round(azimuth(obs, lat, lon, g) / 45.0) % 8];
                if (ie > 0) iss = "ISS " + (city != null ? "over " + city + " " : "") + String.format(Locale.US, "↑%.0f° %s", ie, d);
                else if (city != null) iss = "ISS over " + city;
                if (iss != null) on = true;
                issWhere = String.format(Locale.US, "%.1f°, %.1f°", ll[0], ll[1]) + (city != null ? " — over " + city : " — outside India");
                if (ie > 0) up.add(new Object[]{"ISS", ie, azimuth(obs, lat, lon, g), city});
                if (ie > -5) upT.add(t);
                continue;
            }
            double el = elevation(obs, lat, lon, g);
            if (el > -5) upT.add(t);
            if (el > 0) {
                double[] ll0 = subPoint(g);
                up.add(new Object[]{shortName(t.name), el, azimuth(obs, lat, lon, g), India.cityUnder(ll0[0], ll0[1]),
                        origin(t.id, t.name)});
            }
            if (el < 10) continue;
            // priority: space station / Hubble, then anything nearly overhead, then the highest
            double score = el + (t.id == 48274 || t.id == 20580 ? 200 : 0) + (el >= 60 ? 100 : 0);
            if (score > bestScore) {
                bestScore = score;
                bestEl = el;
                best = t.name;
                bestId = t.id;
                double[] ll = subPoint(g);
                bestCity = India.cityUnder(ll[0], ll[1]);
            }
        }
        // no live ISS: countdown to its next pass over you
        if (iss == null && issT != null) {
            long[] np = nextPass(issT, now, lat, lon, obs);
            if (np != null) {
                long m = Math.max(0, (np[0] - now) / 60_000L);
                String in = m >= 60 ? (m / 60) + "h" + String.format(Locale.US, "%02d", m % 60) : m + "m";
                // one or the other, not both: a countdown when it's close, else the clock time
                iss = m < 60 ? "ISS in " + in + " " + dirs[(int) np[1]]
                        : "ISS " + new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new java.util.Date(np[0])) + " " + dirs[(int) np[1]];
            } else {
                iss = "No ISS pass today";
            }
        }
        if (iss == null) iss = "ISS: no orbit";
        // after the satellite's name: the city under it, else the country that launched it
        String suffix = "";
        if (best != null) {
            String org = origin(bestId, best);
            suffix = bestCity != null ? " over " + bestCity : org != null ? " (" + org + ")" : "";
        }
        String s = iss;
        if (best != null) {
            s += " · " + shortName(best) + suffix;
            on = true;
        }
        overhead = upT;
        issText = iss;
        satName = best == null ? "" : shortName(best);
        satCity = suffix;
        text = s;
        live = on;

        up.sort((a, b) -> Double.compare((Double) b[1], (Double) a[1]));
        StringBuilder r = new StringBuilder();
        r.append("Updated ").append(hm.format(new java.util.Date(now)))
                .append(String.format(Locale.US, " · you %.2f, %.2f", lat, lon)).append('\n')
                .append(list.size()).append(" orbits, downloaded ")
                .append(fetchedAt > 0 ? Math.max(0, (now - fetchedAt) / 60_000L) + " min ago" : "?").append("\n\n")
                .append("Status bar shows:\n  ").append(s).append("\n\n")
                .append("ISS now under: ").append(issWhere).append("\n\n")
                .append("Above your horizon (10°+ is shown in the bar):\n");
        if (up.isEmpty()) r.append("  none right now\n");
        String[] dir = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        for (int i = 0; i < Math.min(20, up.size()); i++) {
            Object[] o = up.get(i);
            double az = (Double) o[2];
            r.append(String.format(Locale.US, "  %-18s %3.0f° %-2s  %s%n", o[0], (Double) o[1],
                    dir[(int) Math.round(az / 45.0) % 8], o[3] != null ? "over " + o[3]
                            : "not over India" + (o.length > 4 && o[4] != null ? " · " + o[4] : "")));
        }
        report = r.toString();
    }

    private static long npAt, npFor;
    private static long[] npCache;

    /**
     * Next time the satellite climbs 10° above your horizon within 24 h: {time ms, compass index 0-7}.
     * Recomputed every 10 min (or once that pass starts); 1-minute steps, refined to 10 s.
     */
    static long[] nextPass(Tle t, long now, double lat, double lon, double[] obs) {
        if (npCache != null && npFor == t.id && now - npAt < 10 * 60_000L && npCache[0] > now) return npCache;
        npAt = now;
        npFor = t.id;
        npCache = null;
        double prev = -90;
        for (long ms = now; ms < now + 24 * 3600_000L; ms += 60_000L) {
            double[] g = propagate(t, ms);
            if (g == null) return null;
            double el = elevation(obs, lat, lon, g);
            if (el >= 10 && prev < 10) {
                long lo = ms - 60_000L, hi = ms;
                while (hi - lo > 10_000L) {
                    long mid = (lo + hi) / 2;
                    double[] gm = propagate(t, mid);
                    if (gm != null && elevation(obs, lat, lon, gm) >= 10) hi = mid; else lo = mid;
                }
                double[] gh = propagate(t, hi);
                int d = (int) Math.round(azimuth(obs, lat, lon, gh) / 45.0) % 8;
                npCache = new long[]{hi, d};
                return npCache;
            }
            prev = el;
        }
        return null;
    }

    /** Compass direction to the satellite, degrees from north. */
    static double azimuth(double[] obs, double lat, double lon, double[] g) {
        double dx = g[0] - obs[0], dy = g[1] - obs[1], dz = g[2] - obs[2];
        double la = Math.toRadians(lat), lo = Math.toRadians(lon);
        double e = -Math.sin(lo) * dx + Math.cos(lo) * dy;
        double n = -Math.sin(la) * Math.cos(lo) * dx - Math.sin(la) * Math.sin(lo) * dy + Math.cos(la) * dz;
        double a = Math.toDegrees(Math.atan2(e, n));
        return a < 0 ? a + 360 : a;
    }

    private static String shortName(String n) {
        n = n.trim();
        if (n.length() > 16 && n.contains(" (")) n = n.substring(0, n.indexOf(" ("));
        return n.length() > 18 ? n.substring(0, 18) : n;
    }

    // ---------------------------------------------------------------- TLE download / parse

    /** NORAD id -> owner code from CelesTrak's satellite catalogue (e.g. "PRC", "CIS", "US"). */
    private static volatile java.util.Map<Integer, String> owners = new java.util.HashMap<>();

    /** Country (or agency) that launched it: catalogue owner first, else guessed from the name. */
    static String origin(int id, String name) {
        String o = owners.get(id);
        if (o != null) {
            String n = ownerName(o);
            if (n != null) return n;
        }
        return guessOrigin(name);
    }

    static String ownerName(String c) {
        switch (c.trim().toUpperCase(Locale.US)) {
            case "US": return "USA";
            case "CIS": case "RUS": return "Russia";
            case "PRC": return "China";
            case "ESA": case "EUME": case "EUTE": return "Europe";
            case "JPN": return "Japan";
            case "IND": return "India";
            case "FR": return "France";
            case "GER": return "Germany";
            case "UK": return "UK";
            case "IT": return "Italy";
            case "CA": return "Canada";
            case "SKOR": return "S. Korea";
            case "ISS": return "ISS partners";
            case "ISRA": return "Israel";
            case "BRAZ": return "Brazil";
            case "SPN": return "Spain";
            case "NETH": return "Netherlands";
            case "TURK": return "Turkey";
            case "IRAN": return "Iran";
            case "UAE": return "UAE";
            case "GLOB": return "Globalstar";
            case "ORB": return "Orbcomm";
            case "IM": return "Inmarsat";
            case "SES": return "SES";
            case "": return null;
            default: return c.trim();
        }
    }

    /** Rocket stages and well-known families named after their country. */
    static String guessOrigin(String n) {
        String u = n.toUpperCase(Locale.US);
        if (u.startsWith("SL-") || u.startsWith("COSMOS") || u.startsWith("KOSMOS") || u.startsWith("METEOR")
                || u.startsWith("RESURS") || u.startsWith("OKEAN") || u.startsWith("INTERCOSMOS") || u.startsWith("SOYUZ")
                || u.startsWith("PROGRESS") || u.startsWith("ZARYA") || u.startsWith("NAUKA")) return "Russia";
        if (u.startsWith("CZ-") || u.startsWith("CSS") || u.startsWith("TIANHE") || u.startsWith("YAOGAN")
                || u.startsWith("SHIJIAN") || u.startsWith("FENGYUN")) return "China";
        if (u.startsWith("ARIANE") || u.startsWith("VEGA") || u.startsWith("ERS-") || u.startsWith("ENVISAT")
                || u.startsWith("SENTINEL")) return "Europe";
        if (u.startsWith("H-2") || u.startsWith("H-II") || u.startsWith("ALOS") || u.startsWith("AJISAI")
                || u.startsWith("GOSAT") || u.startsWith("MIDORI")) return "Japan";
        if (u.startsWith("PSLV") || u.startsWith("GSLV") || u.startsWith("CARTOSAT") || u.startsWith("RISAT")
                || u.startsWith("OCEANSAT") || u.startsWith("RESOURCESAT")) return "India";
        if (u.startsWith("ATLAS") || u.startsWith("DELTA") || u.startsWith("THOR") || u.startsWith("TITAN")
                || u.startsWith("FALCON") || u.startsWith("STARLINK") || u.startsWith("HST") || u.startsWith("NOAA")
                || u.startsWith("SEASAT") || u.startsWith("LANDSAT") || u.startsWith("TIROS") || u.startsWith("OAO")
                || u.startsWith("AQUA") || u.startsWith("TERRA") || u.startsWith("SCOUT") || u.startsWith("IRAS")
                || u.startsWith("TERRIERS") || u.startsWith("LACROSSE") || u.startsWith("USA ")) return "USA";
        return null;
    }

    /** Catalogue owners for the visual group, refreshed with the orbits. */
    private static void fetchOwners() {
        try {
            String body = get("https://celestrak.org/satcat/records.php?GROUP=visual&FORMAT=JSON");
            org.json.JSONArray a = new org.json.JSONArray(body);
            java.util.Map<Integer, String> m = new java.util.HashMap<>();
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                int id = o.optInt("NORAD_CAT_ID", 0);
                String ow = o.optString("OWNER", "");
                if (id > 0 && !ow.isEmpty()) m.put(id, ow);
            }
            if (!m.isEmpty()) { owners = m; save("sat_owners.json", body); }
        } catch (Throwable t) {
            try { de.robv.android.xposed.XposedBridge.log("NetSpeedXposed: satellite owners failed (name guess used): " + t); }
            catch (Throwable ignored) { }
        }
    }

    /** Where the last good download is kept, so a System UI restart doesn't need the network (set by DualRow). */
    static volatile java.io.File cacheDir;
    private static boolean cacheTried;
    private static int tries;

    private static void save(String name, String body) {
        java.io.File d = cacheDir;
        if (d == null) return;
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(d, name))) {
            o.write(body.getBytes("UTF-8"));
        } catch (Throwable ignored) { }
    }

    private static String load(String name, long maxAgeMs) {
        java.io.File d = cacheDir;
        if (d == null) return null;
        java.io.File f = new java.io.File(d, name);
        if (!f.isFile() || System.currentTimeMillis() - f.lastModified() > maxAgeMs) return null;
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /** Orbits (up to 3 days old) and owners from the last good download. */
    private static void loadCache() {
        try {
            String t = load("sat_visual.tle", 3 * 86400_000L);
            if (t != null) {
                List<Tle> out = parse(t);
                if (!out.isEmpty()) {
                    tles = out;
                    fetchedAt = new java.io.File(cacheDir, "sat_visual.tle").lastModified();
                }
            }
            String o = load("sat_owners.json", 30 * 86400_000L);
            if (o != null) {
                org.json.JSONArray a = new org.json.JSONArray(o);
                java.util.Map<Integer, String> m = new java.util.HashMap<>();
                for (int i = 0; i < a.length(); i++) {
                    org.json.JSONObject x = a.getJSONObject(i);
                    int id = x.optInt("NORAD_CAT_ID", 0);
                    String ow = x.optString("OWNER", "");
                    if (id > 0 && !ow.isEmpty()) m.put(id, ow);
                }
                if (!m.isEmpty()) owners = m;
            }
        } catch (Throwable ignored) { }
    }

    private static void fetch() {
        try {
            String body = get("https://celestrak.org/NORAD/elements/gp.php?GROUP=visual&FORMAT=tle");
            List<Tle> out = parse(body);
            boolean hasIss = false;
            for (Tle t : out) if (t.id == 25544) hasIss = true;
            if (!hasIss) {
                try { out.addAll(parse(get("https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=tle"))); }
                catch (Throwable ignored) { }
            }
            if (!out.isEmpty()) {
                tles = out;
                fetchedAt = System.currentTimeMillis();
                lastError = null;
                tries = 0;
                save("sat_visual.tle", body);
                fetchOwners();
            } else {
                lastError = "the orbit list came back empty";
            }
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            try { de.robv.android.xposed.XposedBridge.log("NetSpeedXposed: satellite orbits failed (will retry): " + t); }
            catch (Throwable ignored) { }
        }
    }

    private static String get(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", "NetSpeedXposed/1.0");
        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    static List<Tle> parse(String body) {
        List<Tle> out = new ArrayList<>();
        String[] ln = body.split("\r?\n");
        for (int i = 0; i + 2 < ln.length + 0; i++) {
            String a = ln[i], b = ln[i + 1], c = ln[i + 2];
            if (!b.startsWith("1 ") || !c.startsWith("2 ") || b.length() < 63 || c.length() < 63) continue;
            try {
                Tle t = new Tle();
                t.name = a.trim();
                t.id = Integer.parseInt(c.substring(2, 7).trim());
                int yy = Integer.parseInt(b.substring(18, 20).trim());
                double doy = Double.parseDouble(b.substring(20, 32).trim());
                int year = yy < 57 ? 2000 + yy : 1900 + yy;
                t.epochJd = jdJan1(year) + doy - 1;
                t.ndot2 = Double.parseDouble(b.substring(33, 43).trim());
                t.inc = Math.toRadians(Double.parseDouble(c.substring(8, 16).trim()));
                t.raan = Math.toRadians(Double.parseDouble(c.substring(17, 25).trim()));
                t.ecc = Double.parseDouble("0." + c.substring(26, 33).trim());
                t.argp = Math.toRadians(Double.parseDouble(c.substring(34, 42).trim()));
                t.m0 = Math.toRadians(Double.parseDouble(c.substring(43, 51).trim()));
                t.n = Double.parseDouble(c.substring(52, 63).trim());
                out.add(t);
                i += 2;
            } catch (Throwable ignored) { }
        }
        return out;
    }

    /** Julian date of 1 January, 0h UT (Meeus 7.1 with month 13 of the year before). */
    static double jdJan1(int y) {
        int yy = y - 1, a = yy / 100, b = 2 - a + a / 4;
        return Math.floor(365.25 * (yy + 4716)) + Math.floor(30.6001 * 14) + 1 + b - 1524.5;
    }

    // ---------------------------------------------------------------- orbit

    /** Earth-fixed position (km) at time ms, or null for a decayed / bad orbit. */
    static double[] propagate(Tle t, long ms) {
        double jd = Astro.jd(ms);
        double dtMin = (jd - t.epochJd) * 1440.0;
        if (Math.abs(dtMin) > 20 * 1440) return null;          // orbit too old to trust
        double n0 = t.n * 2 * Math.PI / 1440.0;                  // rad/min
        double a = Math.cbrt(MU / Math.pow(n0 / 60.0, 2));
        double e = t.ecc, i = t.inc;
        double p = a * (1 - e * e);
        double k = 1.5 * J2 * (RE / p) * (RE / p) * n0;
        double raan = t.raan - k * Math.cos(i) * dtMin;
        double argp = t.argp + k * (2 - 2.5 * Math.sin(i) * Math.sin(i)) * dtMin;
        double dtDay = dtMin / 1440.0;
        double m = t.m0 + n0 * dtMin + 2 * Math.PI * t.ndot2 * dtDay * dtDay;
        double ndays = t.n + 2 * t.ndot2 * dtDay;                // decaying orbit shrinks
        if (ndays <= 0) return null;
        a = Math.cbrt(MU / Math.pow(ndays * 2 * Math.PI / 86400.0, 2));
        if (a < RE + 100) return null;
        m = m % (2 * Math.PI);
        double E = m;
        for (int it = 0; it < 12; it++) E -= (E - e * Math.sin(E) - m) / (1 - e * Math.cos(E));
        double nu = 2 * Math.atan2(Math.sqrt(1 + e) * Math.sin(E / 2), Math.sqrt(1 - e) * Math.cos(E / 2));
        double r = a * (1 - e * Math.cos(E));
        double u = argp + nu;
        double cO = Math.cos(raan), sO = Math.sin(raan), cu = Math.cos(u), su = Math.sin(u), ci = Math.cos(i), si = Math.sin(i);
        double x = r * (cO * cu - sO * su * ci);
        double y = r * (sO * cu + cO * su * ci);
        double z = r * (su * si);
        double th = Math.toRadians(Astro.gmst(jd));
        double ct = Math.cos(th), st = Math.sin(th);
        return new double[]{ct * x + st * y, -st * x + ct * y, z};
    }

    /** {lat, lon} under the satellite (geodetic). */
    static double[] subPoint(double[] g) {
        double lon = Math.toDegrees(Math.atan2(g[1], g[0]));
        double rxy = Math.hypot(g[0], g[1]);
        double e2 = F_WGS * (2 - F_WGS);
        double lat = Math.atan2(g[2], rxy);
        for (int i = 0; i < 5; i++) {
            double s = Math.sin(lat), nn = RE / Math.sqrt(1 - e2 * s * s);
            lat = Math.atan2(g[2] + nn * e2 * s, rxy);
        }
        return new double[]{Math.toDegrees(lat), lon};
    }

    static double[] ecef(double lat, double lon, double hKm) {
        double la = Math.toRadians(lat), lo = Math.toRadians(lon), e2 = F_WGS * (2 - F_WGS);
        double nn = RE / Math.sqrt(1 - e2 * Math.sin(la) * Math.sin(la));
        return new double[]{(nn + hKm) * Math.cos(la) * Math.cos(lo), (nn + hKm) * Math.cos(la) * Math.sin(lo),
                (nn * (1 - e2) + hKm) * Math.sin(la)};
    }

    /** Degrees above your horizon. */
    static double elevation(double[] obs, double lat, double lon, double[] g) {
        double dx = g[0] - obs[0], dy = g[1] - obs[1], dz = g[2] - obs[2];
        double la = Math.toRadians(lat), lo = Math.toRadians(lon);
        double ux = Math.cos(la) * Math.cos(lo), uy = Math.cos(la) * Math.sin(lo), uz = Math.sin(la);
        double rr = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return Math.toDegrees(Math.asin((dx * ux + dy * uy + dz * uz) / rr));
    }

    // ---------------------------------------------------------------- India

    static final class India {
        /** Mainland outline (lat, lon), simplified to ~50 km; disputed north follows India's claimed boundary. */
        private static final double[][] OUTLINE = {
                {35.5, 74.0}, {36.9, 75.5}, {35.6, 77.8}, {35.2, 79.5}, {32.6, 79.4}, {31.0, 81.0}, {30.2, 81.2},
                {28.8, 80.0}, {27.0, 83.3}, {26.4, 86.0}, {26.6, 88.1}, {28.0, 88.1}, {27.6, 88.9}, {26.8, 89.8},
                {26.8, 92.1}, {27.8, 91.6}, {29.4, 94.6}, {28.2, 97.3}, {27.2, 96.9}, {25.9, 95.2}, {24.0, 94.2},
                {23.0, 93.4}, {21.9, 92.6}, {23.4, 91.4}, {24.2, 92.2}, {25.1, 89.9}, {26.0, 89.8}, {25.2, 88.5},
                {24.2, 88.7}, {22.9, 88.9}, {21.6, 89.1}, {21.5, 87.0}, {20.3, 86.8}, {19.3, 85.0}, {17.7, 83.4},
                {16.3, 81.4}, {15.7, 80.3}, {13.4, 80.3}, {11.5, 79.8}, {10.3, 79.9}, {9.2, 79.0}, {8.1, 77.5},
                {8.9, 76.5}, {10.8, 75.8}, {12.8, 74.8}, {15.5, 73.8}, {18.5, 72.8}, {20.7, 72.8}, {21.9, 72.5},
                {22.4, 69.2}, {23.6, 68.2}, {24.6, 68.8}, {24.3, 70.9}, {25.0, 70.6}, {26.6, 70.2}, {28.0, 70.6},
                {30.1, 73.4}, {32.0, 74.6}, {32.9, 74.6}, {33.7, 73.9}};

        /** Indian cities and island capitals the strip can name. */
        private static final Object[][] CITIES = {
                {"Delhi", 28.61, 77.21}, {"Mumbai", 19.08, 72.88}, {"Pune", 18.52, 73.86}, {"Bengaluru", 12.97, 77.59},
                {"Chennai", 13.08, 80.27}, {"Kolkata", 22.57, 88.36}, {"Hyderabad", 17.39, 78.49},
                {"Ahmedabad", 23.02, 72.57}, {"Jaipur", 26.91, 75.79}, {"Lucknow", 26.85, 80.95},
                {"Kanpur", 26.45, 80.33}, {"Nagpur", 21.15, 79.09}, {"Indore", 22.72, 75.86}, {"Bhopal", 23.26, 77.41},
                {"Patna", 25.59, 85.14}, {"Varanasi", 25.32, 82.97}, {"Agra", 27.18, 78.01}, {"Surat", 21.17, 72.83},
                {"Vadodara", 22.31, 73.18}, {"Rajkot", 22.30, 70.80}, {"Nashik", 20.00, 73.79},
                {"Aurangabad", 19.88, 75.34}, {"Solapur", 17.66, 75.91}, {"Kolhapur", 16.70, 74.24},
                {"Goa", 15.49, 73.83}, {"Mangaluru", 12.91, 74.86}, {"Mysuru", 12.30, 76.64},
                {"Hubballi", 15.36, 75.12}, {"Belagavi", 15.85, 74.50}, {"Kochi", 9.93, 76.27},
                {"Thiruvananthapuram", 8.52, 76.94}, {"Kozhikode", 11.26, 75.78}, {"Coimbatore", 11.02, 76.96},
                {"Madurai", 9.93, 78.12}, {"Tiruchirappalli", 10.79, 78.70}, {"Puducherry", 11.94, 79.81},
                {"Vijayawada", 16.51, 80.65}, {"Visakhapatnam", 17.69, 83.22}, {"Tirupati", 13.63, 79.42},
                {"Kurnool", 15.83, 78.04}, {"Warangal", 17.97, 79.59}, {"Bhubaneswar", 20.30, 85.82},
                {"Raipur", 21.25, 81.63}, {"Jabalpur", 23.18, 79.99}, {"Gwalior", 26.22, 78.18},
                {"Ranchi", 23.34, 85.31}, {"Jamshedpur", 22.80, 86.20}, {"Guwahati", 26.14, 91.74},
                {"Shillong", 25.58, 91.89}, {"Imphal", 24.82, 93.94}, {"Agartala", 23.83, 91.29},
                {"Aizawl", 23.73, 92.72}, {"Kohima", 25.67, 94.11}, {"Itanagar", 27.08, 93.61},
                {"Gangtok", 27.33, 88.61}, {"Siliguri", 26.73, 88.40}, {"Dehradun", 30.32, 78.03},
                {"Shimla", 31.10, 77.17}, {"Chandigarh", 30.73, 76.78}, {"Amritsar", 31.63, 74.87},
                {"Ludhiana", 30.90, 75.86}, {"Jammu", 32.73, 74.86}, {"Srinagar", 34.08, 74.80}, {"Leh", 34.15, 77.58},
                {"Jodhpur", 26.24, 73.02}, {"Bikaner", 28.02, 73.31}, {"Jaisalmer", 26.92, 70.91},
                {"Udaipur", 24.59, 73.71}, {"Kota", 25.21, 75.86}, {"Bhuj", 23.24, 69.67}, {"Gorakhpur", 26.76, 83.37},
                {"Prayagraj", 25.44, 81.85}, {"Bareilly", 28.37, 79.43}, {"Gaya", 24.80, 85.00},
                {"Port Blair", 11.62, 92.73}, {"Car Nicobar", 9.16, 92.82}, {"Campbell Bay", 7.01, 93.93},
                {"Kavaratti", 10.57, 72.64}, {"Minicoy", 8.28, 73.05}};

        /** Nearest Indian city when (lat, lon) is over India (mainland, Andaman & Nicobar, Lakshadweep), else null. */
        static String cityUnder(double lat, double lon) {
            boolean in = inside(lat, lon);
            String best = null;
            double bestKm = 1e9;
            for (Object[] c : CITIES) {
                double km = km(lat, lon, (Double) c[1], (Double) c[2]);
                if (km < bestKm) { bestKm = km; best = (String) c[0]; }
            }
            if (in) return best;
            // the islands: within 120 km of their towns
            if (bestKm < 120 && ("Port Blair".equals(best) || "Car Nicobar".equals(best) || "Campbell Bay".equals(best)
                    || "Kavaratti".equals(best) || "Minicoy".equals(best))) return best;
            return null;
        }

        static boolean inside(double lat, double lon) {
            boolean in = false;
            for (int i = 0, j = OUTLINE.length - 1; i < OUTLINE.length; j = i++) {
                double yi = OUTLINE[i][0], xi = OUTLINE[i][1], yj = OUTLINE[j][0], xj = OUTLINE[j][1];
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) in = !in;
            }
            return in;
        }

        static double km(double la1, double lo1, double la2, double lo2) {
            double r = Math.PI / 180, dla = (la2 - la1) * r, dlo = (lo2 - lo1) * r;
            double a = Math.sin(dla / 2) * Math.sin(dla / 2)
                    + Math.cos(la1 * r) * Math.cos(la2 * r) * Math.sin(dlo / 2) * Math.sin(dlo / 2);
            return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        }
    }

    /** For tests: the text for a given time without touching the network. */
    static String describe(String tleText, long ms, double lat, double lon) {
        tles = parse(tleText);
        compute(ms, lat, lon);
        return text + (live ? " (live)" : "");
    }

    static String fmt(double v) { return String.format(Locale.US, "%.2f", v); }
}
