package com.netspeed.xposed;

import android.content.Context;
import android.location.GnssStatus;
import android.location.LocationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Extra frames for the satellite strip above the clock, rotating with the usual "No ISS pass" text:
 * navigation satellites in use (phone's own GNSS), people in space, stargazing score, Moon / planet pairings
 * and close asteroid passes. While the ISS (or another bright satellite) is up, the strip stays on it.
 */
final class SkyStrip {
    private SkyStrip() {}

    static volatile int cloud = -1;                       // % cloud cover from the weather fetch
    private static volatile String navText = "";
    private static volatile long navAt = 0;
    private static boolean gnssOn = false;
    private static String gnssErr = "";

    private static volatile String peopleText = "";
    private static volatile long peopleAt = 0, peopleTried = 0;
    private static volatile String astText = "";
    private static volatile long astAt = 0, astTried = 0;
    private static volatile String astRaw = "";
    private static volatile String peopleWhy = "not fetched yet", astWhy = "not fetched yet";
    private static volatile String starText = "", pairText = "";
    private static volatile String starWhy = "", pairWhy = "";
    private static boolean fetching = false;
    // Voyager: heliocentric range (km) and range rate (km/s) at a moment, from JPL Horizons
    private static final double[] vRg = new double[2], vRr = new double[2];
    private static final long[] vAt = new long[2], vTried = new long[2];
    private static final String[] vWhy = {"not fetched yet", "not fetched yet"};

    // ---------------------------------------------------------------- navigation satellites

    private static final String[] SYS = {"?", "GPS", "SBAS", "GLONASS", "QZSS", "BeiDou", "Galileo", "NavIC"};

    private static void gnss(Context ctx) {
        if (gnssOn) return;
        gnssOn = true;
        try {
            LocationManager lm = ctx.getSystemService(LocationManager.class);
            lm.registerGnssStatusCallback(ctx.getMainExecutor(), new GnssStatus.Callback() {
                @Override
                public void onSatelliteStatusChanged(GnssStatus s) {
                    try {
                        int[] n = new int[8];
                        int used = 0;
                        for (int i = 0; i < s.getSatelliteCount(); i++) {
                            if (!s.usedInFix(i)) continue;
                            int t = s.getConstellationType(i);
                            if (t < 0 || t > 7) t = 0;
                            n[t]++;
                            used++;
                        }
                        navAt = System.currentTimeMillis();
                        if (used == 0) { navText = ""; return; }
                        // biggest groups first, NavIC always named when present (it's the Indian one)
                        List<int[]> l = new ArrayList<>();
                        for (int t = 1; t < 8; t++) if (n[t] > 0) l.add(new int[]{t, n[t]});
                        l.sort((a, b) -> b[1] != a[1] ? b[1] - a[1] : a[0] - b[0]);
                        StringBuilder b = new StringBuilder();
                        for (int[] e : l) {
                            if (b.length() > 0) b.append(" · ");
                            b.append(SYS[e[0]]).append(' ').append(e[1]);
                        }
                        navText = b + " · " + used + " in use";
                    } catch (Throwable t) { gnssErr = String.valueOf(t); }
                }
            });
        } catch (Throwable t) { gnssErr = t.getClass().getSimpleName() + ": " + t.getMessage(); }
    }

    // ---------------------------------------------------------------- network pieces (daily)

    private static String get(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", "NetSpeedXposed/1.0");
        int code = c.getResponseCode();
        if (code != 200) throw new java.io.IOException("HTTP " + code);
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String ln;
            while ((ln = r.readLine()) != null) sb.append(ln);
        }
        return sb.toString();
    }

    private static void fetchAll(final long now) {
        synchronized (SkyStrip.class) { if (fetching) return; fetching = true; }
        Thread t = new Thread(() -> {
            try {
                if (Cfg.b(Cfg.SKY_PEOPLE, Cfg.D_SKY_PEOPLE) && now - peopleTried > 3600_000L && now - peopleAt > 12 * 3600_000L) {
                    peopleTried = now;
                    try {
                        int n = new org.json.JSONObject(get("https://corquaid.github.io/international-space-station-APIs/JSON/people-in-space.json")).getInt("number");
                        peopleText = n + (n == 1 ? " person in space" : " people in space");
                        peopleAt = now;
                        peopleWhy = "ok " + n;
                    } catch (Throwable e) { peopleWhy = "failed: " + e; }
                }
                if (Cfg.b(Cfg.SKY_VOY, Cfg.D_SKY_VOY)) {
                    for (int i = 0; i < 2; i++) {
                        if (now - vTried[i] < 3600_000L || now - vAt[i] < 12 * 3600_000L) continue;
                        vTried[i] = now;
                        try { voyager(i, now); } catch (Throwable e) { vWhy[i] = "failed: " + e; }
                    }
                }
                if (Cfg.b(Cfg.SKY_LAUNCH, Cfg.D_SKY_LAUNCH) && now - lchTried > 30 * 60_000L && now - lchAt > 3 * 3600_000L) {
                    lchTried = now;
                    try { fetchLaunches(now); } catch (Throwable e) { lchWhy = "failed: " + e; }
                }
                if (Cfg.b(Cfg.SKY_AST, Cfg.D_SKY_AST) && now - astTried > 3600_000L && now - astAt > 12 * 3600_000L) {
                    astTried = now;
                    try {
                        astRaw = get("https://ssd-api.jpl.nasa.gov/cad.api?dist-max=0.01&date-min=now&date-max=%2B14&sort=date&limit=10");
                        astAt = now;
                        astWhy = "ok";
                    } catch (Throwable e) { astWhy = "failed: " + e; }
                }
            } finally {
                synchronized (SkyStrip.class) { fetching = false; }
            }
        }, "skystrip-fetch");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- rocket launches (Launch Library 2)

    private static volatile long lchAt = 0, lchTried = 0;
    private static volatile String lchWhy = "not fetched yet";
    /** {net ms, rocket, mission, agency, country code, site, pad, webcast "1"/"0"} sorted by time */
    private static volatile List<String[]> lch = new ArrayList<>();

    private static void fetchLaunches(long now) throws Exception {
        org.json.JSONArray a = new org.json.JSONObject(get("https://ll.thespacedevs.com/2.2.0/launch/upcoming/?limit=20&mode=normal")).getJSONArray("results");
        List<String[]> l = new ArrayList<>();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        for (int i = 0; i < a.length(); i++) {
            org.json.JSONObject o = a.getJSONObject(i);
            String net = o.optString("net", "");
            long t;
            try { t = f.parse(net.replaceAll("\\.\\d+Z$", "Z")).getTime(); } catch (Throwable e) { continue; }
            org.json.JSONObject rk = o.optJSONObject("rocket"), cf = rk == null ? null : rk.optJSONObject("configuration");
            org.json.JSONObject ms = o.optJSONObject("mission"), pd = o.optJSONObject("pad"), pl = pd == null ? null : pd.optJSONObject("location");
            org.json.JSONObject pv = o.optJSONObject("launch_service_provider");
            String name = o.optString("name", "");
            String rocket = cf != null ? cf.optString("name", "") : name.contains("|") ? name.split("\\|")[0].trim() : name;
            String mission = ms != null && ms.optString("name", "").length() > 0 ? ms.optString("name", "") : name.contains("|") ? name.split("\\|", 2)[1].trim() : "";
            String agency = pv == null ? "" : pv.optString("abbrev", "").length() > 0 && pv.optString("abbrev", "").length() <= 8 ? pv.optString("abbrev", "") : pv == null ? "" : pv.optString("name", "");
            String cc = pd != null ? pd.optString("country_code", pl != null ? pl.optString("country_code", "") : "") : "";
            if (cc.isEmpty() && pl != null) cc = pl.optString("country_code", "");
            String site = pl == null ? "" : pl.optString("name", "").split(",")[0].trim();
            String pad = pd == null ? "" : pd.optString("name", "");
            l.add(new String[]{String.valueOf(t), rocket, mission, agency, cc, site, pad, o.optBoolean("webcast_live", false) ? "1" : "0"});
        }
        java.util.Collections.sort(l, (x, y) -> Long.compare(Long.parseLong(x[0]), Long.parseLong(y[0])));
        lch = l;
        lchAt = now;
        lchWhy = "ok " + l.size() + " launches";
    }

    private static String countryName(String cc) {
        switch (cc) {
            case "USA": return "USA"; case "IND": return "India"; case "CHN": return "China"; case "RUS": return "Russia";
            case "JPN": return "Japan"; case "GUF": return "French Guiana"; case "NZL": return "New Zealand"; case "KOR": return "S Korea";
            case "IRN": return "Iran"; case "ISR": return "Israel"; case "PRK": return "N Korea"; case "AUS": return "Australia"; case "GBR": return "UK";
            default: return cc;
        }
    }

    private static boolean regionOk(String cc, int reg) {
        switch (reg) {
            case 1: return cc.equals("IND"); case 2: return cc.equals("USA"); case 3: return cc.equals("CHN");
            case 4: return cc.equals("GUF") || cc.equals("FRA") || cc.equals("NOR"); case 5: return cc.equals("RUS"); case 6: return cc.equals("JPN");
            default: return true;
        }
    }

    /** One or two frames for the next launch, using the pieces switched on in settings. */
    private static List<String> launchFrames(long now) {
        List<String> out = new ArrayList<>();
        if (lch.isEmpty() || now - lchAt > 2 * 86400_000L) return out;
        long maxMs = Math.max(1, Math.min(30, Cfg.i(Cfg.LCH_DAYS, Cfg.D_LCH_DAYS))) * 86400_000L;
        int reg = Cfg.i(Cfg.LCH_REGION, Cfg.D_LCH_REGION);
        String[] pick = null;
        for (String[] r : lch) {
            long t = Long.parseLong(r[0]);
            if (t < now - 2 * 3600_000L || t > now + maxMs || !regionOk(r[4], reg)) continue;
            pick = r;
            break;
        }
        if (pick == null) return out;
        long t = Long.parseLong(pick[0]), dt = t - now;
        boolean utc = Cfg.b(Cfg.LCH_UTC, Cfg.D_LCH_UTC);
        String when;
        if (dt < -15 * 60_000L) when = "launched";
        else if (dt <= 0) when = "now";
        else if (Cfg.i(Cfg.LCH_TIME, Cfg.D_LCH_TIME) == 1) {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d MMM HH:mm", Locale.US);
            if (utc) f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            when = f.format(new java.util.Date(t)) + (utc ? " UTC" : "");
        } else {
            long m = dt / 60_000L, d = m / 1440, h = (m % 1440) / 60;
            when = d > 0 ? "in " + d + "d " + h + "h" : m >= 60 ? "in " + h + "h " + (m % 60) + "m" : "in " + Math.max(1, m) + "m";
        }
        if (Cfg.b(Cfg.LCH_LIVE, Cfg.D_LCH_LIVE) && pick[7].equals("1") && Math.abs(dt) < 3600_000L) when += " · live";
        List<String> a = new ArrayList<>(), b = new ArrayList<>();
        int nm = Cfg.i(Cfg.LCH_NAME, Cfg.D_LCH_NAME);
        if ((nm == 1 || nm == 2 || pick[2].isEmpty()) && !pick[1].isEmpty()) a.add(pick[1]);   // rocket (also when the mission has no name)
        if ((nm == 0 || nm == 2) && !pick[2].isEmpty()) a.add(pick[2]);
        if (Cfg.b(Cfg.LCH_AGENCY, Cfg.D_LCH_AGENCY) && !pick[3].isEmpty()) b.add(pick[3]);
        if (Cfg.b(Cfg.LCH_COUNTRY, Cfg.D_LCH_COUNTRY) && !pick[4].isEmpty()) b.add(countryName(pick[4]));
        if (Cfg.b(Cfg.LCH_SITE, Cfg.D_LCH_SITE) && !pick[5].isEmpty()) b.add(pick[5]);
        if (Cfg.b(Cfg.LCH_PAD, Cfg.D_LCH_PAD) && !pick[6].isEmpty()) b.add(pick[6]);
        String ja = String.join(" \u00b7 ", a), jb = String.join(" \u00b7 ", b);
        String one = "\uD83D\uDE80 " + String.join(" \u00b7 ", java.util.Arrays.asList(ja, jb, when).stream().filter(x -> !x.isEmpty()).collect(java.util.stream.Collectors.toList()));
        if (one.length() <= 40 || ja.isEmpty() || jb.isEmpty()) { out.add(one); return out; }
        out.add("\uD83D\uDE80 " + ja);                       // launch name
        out.add("\uD83D\uDE80 " + jb + " \u00b7 " + when);   // spaceport, country, time
        return out;
    }

    /** Next close approach within 3.9 Moon-distances in the coming 2 weeks, or "". */
    private static String asteroid(long now) {
        try {
            if (astRaw.isEmpty()) return "";
            org.json.JSONObject o = new org.json.JSONObject(astRaw);
            org.json.JSONArray f = o.getJSONArray("fields"), d = o.optJSONArray("data");
            if (d == null) return "";
            int iDes = -1, iCd = -1, iDist = -1, iH = -1;
            for (int i = 0; i < f.length(); i++) {
                String k = f.getString(i);
                if (k.equals("des")) iDes = i; else if (k.equals("cd")) iCd = i; else if (k.equals("dist")) iDist = i; else if (k.equals("h")) iH = i;
            }
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MMM-dd HH:mm", Locale.US);
            in.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.text.SimpleDateFormat out = new java.text.SimpleDateFormat("d MMM", Locale.US);
            for (int r = 0; r < d.length(); r++) {
                org.json.JSONArray row = d.getJSONArray(r);
                long t = in.parse(row.getString(iCd)).getTime();
                if (t < now - 6 * 3600_000L) continue;      // already passed
                double ld = row.getDouble(iDist) / 0.00256955;   // AU -> Moon distances
                String size = "";
                if (iH >= 0 && !row.isNull(iH)) {
                    double km = 3552.0 * Math.pow(10, -row.getDouble(iH) / 5.0);   // size guess from brightness, ~14% reflective
                    double m = km * 1000;
                    size = m < 1000 ? " · ~" + (m < 10 ? Math.round(m) : Math.round(m / 5) * 5) + " m" : " · ~" + String.format(Locale.US, "%.1f", km) + " km";
                }
                return "Asteroid " + row.getString(iDes) + " passes " + String.format(Locale.US, "%.1f", ld) + " Moons away, "
                        + out.format(new java.util.Date(t)) + size;
            }
        } catch (Throwable ignored) { }
        return "";
    }

    /** JPL Horizons: Voyager i (0 = Voyager 1, id -31; 1 = Voyager 2, id -32) relative to the Sun: range (km) and range rate (km/s). */
    private static void voyager(int i, long now) throws Exception {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String d0 = f.format(new java.util.Date(now)), d1 = f.format(new java.util.Date(now + 86400_000L));
        String u = "https://ssd.jpl.nasa.gov/api/horizons.api?format=json&COMMAND=" + enc("'" + (i == 0 ? "-31" : "-32") + "'")
                + "&OBJ_DATA=%27NO%27&MAKE_EPHEM=%27YES%27&EPHEM_TYPE=%27VECTORS%27&CENTER=" + enc("'500@10'")
                + "&START_TIME=" + enc("'" + d0 + "'") + "&STOP_TIME=" + enc("'" + d1 + "'") + "&STEP_SIZE=" + enc("'1 d'")
                + "&VEC_TABLE=%273%27&CSV_FORMAT=%27YES%27";
        String res = new org.json.JSONObject(get(u)).getString("result");
        int a = res.indexOf("$$SOE"), b = res.indexOf("$$EOE");
        if (a < 0 || b < 0) throw new Exception("no ephemeris rows: " + res.substring(0, Math.min(120, res.length())).replace('\n', ' '));
        String row = res.substring(a + 5, b).trim().split("\n")[0];
        String[] p = row.split(",");
        // JDTDB, date, X, Y, Z, VX, VY, VZ, LT, RG, RR
        double rg = Double.parseDouble(p[9].trim()), rr = Double.parseDouble(p[10].trim());
        if (rg < 1e9 || rg > 5e10) throw new Exception("odd range " + rg);
        long t0 = now;
        // the first row is the start of today (UTC): move it to now at the probe's speed
        long rowT = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US) {{ setTimeZone(java.util.TimeZone.getTimeZone("UTC")); }}.parse(d0).getTime();
        vRg[i] = rg + rr * (t0 - rowT) / 1000.0;
        vRr[i] = rr;
        vAt[i] = t0;
        vWhy[i] = "ok " + Math.round(vRg[i] / 149597870.7 * 10) / 10.0 + " AU";
    }

    private static String enc(String x) throws Exception { return java.net.URLEncoder.encode(x, "UTF-8"); }

    private static String light(double km) {
        long s = Math.round(km / 299792.458);
        if (s >= 3600) return (s / 3600) + " h " + ((s % 3600) / 60) + " m";
        return (s / 60) + " m " + (s % 60) + " s";
    }

    private static String distText(String name, double km) {
        double au = km / 149597870.7;
        String a = au >= 100 ? String.format(Locale.US, "%.1f", au) : String.format(Locale.US, "%.3f", au);
        String k = km >= 1e9 ? String.format(Locale.US, "%.1f billion km", km / 1e9) : String.format(Locale.US, "%.1f million km", km / 1e6);
        return name + " " + a + " AU \u00b7 " + k + " \u00b7 light " + light(km);
    }

    /** Earth-Sun distance in km for the date (low-precision series, about 0.01%). */
    private static double sunKm(long now) {
        double d = now / 86400000.0 + 2440587.5 - 2451545.0;
        double g = Math.toRadians((357.529 + 0.98560028 * d) % 360);
        double au = 1.00014 - 0.01671 * Math.cos(g) - 0.00014 * Math.cos(2 * g);
        return au * 149597870.7;
    }

    // ---------------------------------------------------------------- sky maths

    private static String compass(double az) {
        String[] n = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return n[(int) Math.round(az / 45.0) % 8];
    }

    private static void sky(long now, double lat, double lon) {
        starText = ""; pairText = "";
        try {
            Astro.Pos sun = Astro.sun(now, lat, lon);
            Astro.Moon moon = Astro.moon(now, lat, lon);
            // stargazing score: only when it is dark enough to look at stars
            if (sun.alt > -6) starWhy = "sun at " + Math.round(sun.alt) + "°: not dark yet";
            else if (cloud < 0) starWhy = "no cloud-cover figure yet";
            else {
                double sc = 10 - 0.9 * cloud / 10.0;
                if (moon.alt > 0) sc -= moon.illum * 4 * Math.min(1, moon.alt / 30.0);
                if (sun.alt > -18) sc -= (sun.alt + 18) / 12.0 * 3;
                int s = (int) Math.max(1, Math.min(10, Math.round(sc)));
                String sky = cloud < 20 ? "clear" : cloud < 60 ? "partly cloudy" : "cloudy";
                String mn = moon.alt > 0 ? "Moon " + Math.round(moon.illum * 100) + "% up" : "no Moon";
                starText = "Stargazing " + s + "/10 · " + sky + " · " + mn;
                starWhy = "score " + sc + " (cloud " + cloud + "%, sun " + Math.round(sun.alt) + "°, moon alt " + Math.round(moon.alt) + "°)";
            }
            // pairings: Moon with a planet within 4 degrees, or two planets within 3, one of them above the horizon in a darkish sky
            double lst = SkyObjects.lst(now, lon);
            String[] nm = new String[5];
            double[][] rd = new double[5][];
            nm[0] = "Moon"; rd[0] = new double[]{moon.ra, moon.dec};
            for (int i = 0; i < SkyObjects.PLANETS.length; i++) { nm[i + 1] = SkyObjects.PLANETS[i]; rd[i + 1] = SkyObjects.planetRaDec(i, now); }
            double best = 99;
            String bt = "";
            for (int a = 0; a < 5; a++) for (int b = a + 1; b < 5; b++) {
                double lim = a == 0 ? 4 : 3;
                double sep = angle(rd[a][0], rd[a][1], rd[b][0], rd[b][1]);
                if (sep > lim || sep >= best) continue;
                double[] aa = SkyObjects.altAz(rd[a][0], rd[a][1], lst, lat), ab = SkyObjects.altAz(rd[b][0], rd[b][1], lst, lat);
                if (aa[0] < 5 || ab[0] < 5 || sun.alt > -3) continue;
                best = sep;
                bt = nm[a] + " " + Math.round(sep) + "° from " + nm[b] + " · " + (sun.az > 180 ? "evening" : "dawn") + ", " + compass(ab[1]);
            }
            pairText = bt;
            pairWhy = bt.isEmpty() ? "no close visible pair" : bt;
        } catch (Throwable t) { starWhy = "failed: " + t; pairWhy = "failed: " + t; }
    }

    private static double angle(double ra1, double d1, double ra2, double d2) {
        double a = Math.toRadians(d1), b = Math.toRadians(d2), dr = Math.toRadians(ra1 - ra2);
        double c = Math.sin(a) * Math.sin(b) + Math.cos(a) * Math.cos(b) * Math.cos(dr);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))));
    }

    // ---------------------------------------------------------------- public

    private static volatile long skyAt = 0;

    /** Called from the bar's background tick. */
    static void tick(Context ctx, double lat, double lon) {
        long now = System.currentTimeMillis();
        try {
            if (Cfg.b(Cfg.SKY_NAV, Cfg.D_SKY_NAV)) gnss(ctx);
            fetchAll(now);
            if (now - skyAt > 60_000L) {
                skyAt = now;
                if (Cfg.b(Cfg.SKY_STAR, Cfg.D_SKY_STAR) || Cfg.b(Cfg.SKY_PAIR, Cfg.D_SKY_PAIR)) sky(now, lat, lon);
                astText = asteroid(now);
            }
        } catch (Throwable ignored) { }
    }

    /** What the strip should show now: the ISS text when something is up, else a rotation through the base text and the enabled frames. */
    static String frame(String base, boolean live, long now) {
        // pinned only for the ISS itself or a space station / telescope; rocket-body debris overhead is always around and must not freeze the strip
        String iss = Sat.issText == null ? "" : Sat.issText, nm = Sat.satName == null ? "" : Sat.satName;
        boolean pin = live && (iss.contains("over ") || iss.contains("\u2191") || nm.startsWith("CSS") || nm.startsWith("HST") || nm.startsWith("TIANGONG"));
        if (pin) return base;
        List<String> fs = new ArrayList<>();
        if (base != null && !base.isEmpty()) fs.add(base);
        try {
            if (Cfg.b(Cfg.SKY_NAV, Cfg.D_SKY_NAV) && !navText.isEmpty() && now - navAt < 90_000L) fs.add(navText);
            if (Cfg.b(Cfg.SKY_PEOPLE, Cfg.D_SKY_PEOPLE) && !peopleText.isEmpty() && now - peopleAt < 3 * 86400_000L) fs.add(peopleText);
            if (Cfg.b(Cfg.SKY_STAR, Cfg.D_SKY_STAR) && !starText.isEmpty()) fs.add(starText);
            if (Cfg.b(Cfg.SKY_PAIR, Cfg.D_SKY_PAIR) && !pairText.isEmpty()) fs.add(pairText);
            if (Cfg.b(Cfg.SKY_AST, Cfg.D_SKY_AST) && !astText.isEmpty()) fs.add(astText);
            if (Cfg.b(Cfg.SKY_LAUNCH, Cfg.D_SKY_LAUNCH)) fs.addAll(launchFrames(now));
            if (Cfg.b(Cfg.SKY_SUN, Cfg.D_SKY_SUN)) fs.add(distText("Sun", sunKm(now)));
            if (Cfg.b(Cfg.SKY_VOY, Cfg.D_SKY_VOY)) {
                for (int i = 0; i < 2; i++)
                    if (vAt[i] > 0 && now - vAt[i] < 5 * 86400_000L) fs.add(distText("Voyager " + (i + 1), vRg[i] + vRr[i] * (now - vAt[i]) / 1000.0));
            }
        } catch (Throwable ignored) { }
        if (fs.isEmpty()) return base == null ? "" : base;
        if (fs.size() == 1) return fs.get(0);
        long sec = Math.max(3, Math.min(8, Cfg.i(Cfg.SKY_SPEED, Cfg.D_SKY_SPEED)));
        return fs.get((int) ((now / (sec * 1000L)) % fs.size()));
    }

    /** For the app's Info tab: {label, value} pairs of what the strip currently knows. */
    static List<String[]> infoLines(long now) {
        List<String[]> o = new ArrayList<>();
        if (!starText.isEmpty()) o.add(new String[]{"Stargazing", starText});
        if (!pairText.isEmpty()) o.add(new String[]{"Pairings", pairText});
        if (!peopleText.isEmpty()) o.add(new String[]{"People in space", peopleText});
        try { List<String> lf = launchFrames(now); if (!lf.isEmpty()) o.add(new String[]{"Next launch", String.join(" \u00b7 ", lf).replace("\uD83D\uDE80 ", "")}); } catch (Throwable ignored) { }
        if (!astText.isEmpty()) o.add(new String[]{"Asteroid", astText});
        for (int i = 0; i < 2; i++)
            if (vAt[i] > 0 && now - vAt[i] < 5 * 86400_000L) o.add(new String[]{"Voyager " + (i + 1), distText("", vRg[i] + vRr[i] * (now - vAt[i]) / 1000.0)});
        o.add(new String[]{"Sun distance", distText("Sun", sunKm(now))});
        return o;
    }

    static String report() {
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder("SATELLITE STRIP FRAMES\n");
        b.append("  navigation satellites: ").append(navText.isEmpty() ? (gnssOn ? "no fix data yet" + (gnssErr.isEmpty() ? "" : " (" + gnssErr + ")") : "off") : navText + " (" + (now - navAt) / 1000 + " s ago)").append('\n');
        b.append("  people in space: ").append(peopleText.isEmpty() ? "-" : peopleText).append(" · ").append(peopleWhy).append('\n');
        b.append("  stargazing: ").append(starText.isEmpty() ? "-" : starText).append(" · ").append(starWhy).append('\n');
        b.append("  pairings: ").append(pairWhy.isEmpty() ? "-" : pairWhy).append('\n');
        b.append("  sun distance: ").append(distText("Sun", sunKm(now))).append('\n');
        b.append("  voyager 1: ").append(vWhy[0]).append(" \u00b7 voyager 2: ").append(vWhy[1]).append('\n');
        {
            List<String> lf = launchFrames(now);
            b.append("  launches: ").append(lf.isEmpty() ? "none in range" : String.join(" | ", lf)).append(" \u00b7 ").append(lchWhy).append('\n');
        }
        b.append("  asteroids: ").append(astText.isEmpty() ? "none close in the next 2 weeks" : astText).append(" · ").append(astWhy).append('\n');
        return b.toString();
    }
}
