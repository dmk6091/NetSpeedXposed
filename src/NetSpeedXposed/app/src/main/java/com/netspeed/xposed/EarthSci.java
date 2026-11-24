package com.netspeed.xposed;

import java.util.Locale;

/**
 * Earth-science facts for the bar: the tectonic plate under you, the nearest plate boundary (type, distance, direction, closing / opening rate),
 * the El Nino state, and the name of the ocean current at the tide place. Plates and boundaries are built in (EarthData, PB2002); El Nino comes from
 * NOAA CPC (monthly). Everything is approximate and labelled so in the Info page.
 */
final class EarthSci {
    private EarthSci() {}

    // ------------------------------------------------------------------ decoding
    private static byte[] b64(String[] parts) {
        StringBuilder sb = new StringBuilder();
        for (String s : parts) sb.append(s);
        return android.util.Base64.decode(sb.toString(), android.util.Base64.DEFAULT);
    }

    private static final class Rd {
        final byte[] b;
        int p = 0;
        Rd(byte[] b) { this.b = b; }
        int next() {
            int v = 0, sh = 0;
            while (p < b.length) {
                int c = b[p++] & 0xff;
                v |= (c & 0x7f) << sh;
                if ((c & 0x80) == 0) break;
                sh += 7;
            }
            return (v & 1) != 0 ? -((v + 1) >> 1) : (v >> 1);
        }
    }

    private static boolean ready = false;
    private static int[] plOf;            // plate index of each polygon
    private static float[][] px, py;      // polygon rings, degrees
    private static float[] sx, sy, ex, ey;
    private static byte[] scls;
    private static short[] spair, svel;

    private static synchronized void load() {
        if (ready) return;
        try {
            Rd r = new Rd(b64(EarthData.PLATES));
            int n = r.next();
            plOf = new int[n]; px = new float[n][]; py = new float[n][];
            for (int i = 0; i < n; i++) {
                plOf[i] = r.next();
                int m = r.next();
                px[i] = new float[m]; py[i] = new float[m];
                int x = 0, y = 0;
                for (int k = 0; k < m; k++) { x += r.next(); y += r.next(); px[i][k] = x / 100f; py[i][k] = y / 100f; }
            }
            r = new Rd(b64(EarthData.STEPS));
            n = r.next();
            sx = new float[n]; sy = new float[n]; ex = new float[n]; ey = new float[n];
            scls = new byte[n]; spair = new short[n]; svel = new short[n];
            int x = 0, y = 0;
            for (int i = 0; i < n; i++) {
                x += r.next(); y += r.next();
                sx[i] = x / 100f; sy[i] = y / 100f;
                ex[i] = sx[i] + r.next() / 100f; ey[i] = sy[i] + r.next() / 100f;
                scls[i] = (byte) r.next(); spair[i] = (short) r.next(); svel[i] = (short) r.next();
            }
            ready = true;
        } catch (Throwable t) { why = "plate data failed: " + t; }
    }

    // ------------------------------------------------------------------ plate under you
    static volatile String why = "";

    private static boolean inRing(float lon, float lat, float[] xs, float[] ys) {
        boolean c = false;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            if ((ys[i] > lat) != (ys[j] > lat) && lon < (xs[j] - xs[i]) * (lat - ys[i]) / (ys[j] - ys[i]) + xs[i]) c = !c;
        }
        return c;
    }

    /** Plate code ("IN") under the point, or null. */
    static String plateCode(double lat, double lon) {
        load();
        if (!ready) return null;
        float lo = (float) lon, la = (float) lat;
        if (lo > 180f) lo -= 360f;
        for (int i = 0; i < plOf.length; i++) if (inRing(lo, la, px[i], py[i])) return EarthData.CODES[plOf[i]];
        // between simplified polygons: the plate of the nearest boundary step
        return null;
    }

    static String plateName(String code) {
        for (int i = 0; i < EarthData.CODES.length; i++) if (EarthData.CODES[i].equals(code)) return EarthData.NAMES[i];
        return code;
    }

    /** "Indian Plate" (the model calls it "India"); "Pacific Plate". */
    static String plateTitle(String code) {
        String n = plateName(code);
        if ("India".equals(n)) return "Indian Plate";
        if ("Eurasia".equals(n)) return "Eurasian Plate";
        if ("Africa".equals(n)) return "African Plate";
        if ("Australia".equals(n)) return "Australian Plate";
        if ("Arabia".equals(n)) return "Arabian Plate";
        if ("Somalia".equals(n)) return "Somali Plate";
        if ("Pacific".equals(n)) return "Pacific Plate";
        if ("North America".equals(n)) return "North American Plate";
        if ("South America".equals(n)) return "South American Plate";
        if ("Antarctica".equals(n)) return "Antarctic Plate";
        if ("Philippine Sea".equals(n)) return "Philippine Sea Plate";
        return n.endsWith("Plate") ? n : n + " Plate";
    }

    // rough absolute motion (no-net-rotation frame, typical over the plate): code, cm/yr, azimuth degrees clockwise from north
    private static final Object[][] MOTION = {
            {"IN", 5.4, 35.0}, {"AU", 6.9, 32.0}, {"PA", 7.0, 300.0}, {"NA", 2.3, 245.0}, {"AF", 2.4, 40.0}, {"EU", 2.4, 105.0}, {"NZ", 5.5, 82.0},
    };

    /** {cm/yr, azimuth} or null when no figure is built in for this plate. */
    static double[] motion(String code) {
        for (Object[] m : MOTION) if (m[0].equals(code)) return new double[]{(Double) m[1], (Double) m[2]};
        return null;
    }

    private static final String[] DIR16 = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
    static String dir16(double az) { return DIR16[(int) Math.round(((az % 360) + 360) % 360 / 22.5) % 16]; }
    private static final String[] ARR8 = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    static String arrow(double az) { return ARR8[(int) Math.round(((az % 360) + 360) % 360 / 45.0) % 8]; }

    // ------------------------------------------------------------------ nearest boundary
    static final class Bound {
        String kind;     // "Convergent" / "Divergent" / "Transform"
        String label;    // "Collision", "Subduction", "Spreading ridge", ...
        String pair;     // "India–Eurasia"
        int km;
        double az;       // bearing from you to it
        float rate;      // cm/yr relative motion, <0 unknown
    }

    private static final String[] LABEL = {"Subduction", "Collision", "Convergent", "Spreading ridge", "Rift", "Transform", "Transform"};
    private static final String[] KIND = {"Convergent", "Convergent", "Convergent", "Divergent", "Divergent", "Transform", "Transform"};

    private static double hav(double la1, double lo1, double la2, double lo2) {
        double r = Math.PI / 180, dla = (la2 - la1) * r, dlo = (lo2 - lo1) * r;
        double a = Math.sin(dla / 2) * Math.sin(dla / 2) + Math.cos(la1 * r) * Math.cos(la2 * r) * Math.sin(dlo / 2) * Math.sin(dlo / 2);
        return 12742.0 * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    private static double bearing(double la1, double lo1, double la2, double lo2) {
        double r = Math.PI / 180, dlo = (lo2 - lo1) * r;
        double y = Math.sin(dlo) * Math.cos(la2 * r), x = Math.cos(la1 * r) * Math.sin(la2 * r) - Math.sin(la1 * r) * Math.cos(la2 * r) * Math.cos(dlo);
        return (Math.atan2(y, x) / r + 360) % 360;
    }

    private static double[] memoKey = null;
    private static Bound memo = null;

    static synchronized Bound nearest(double lat, double lon) {
        if (memoKey != null && Math.abs(memoKey[0] - lat) < 0.05 && Math.abs(memoKey[1] - lon) < 0.05) return memo;
        load();
        if (!ready) return null;
        double best = 1e18;
        int bi = -1;
        double bla = 0, blo = 0;
        for (int i = 0; i < sx.length; i++) {
            double ml = (sy[i] + ey[i]) / 2, mo = (sx[i] + ex[i]) / 2;
            double d = Math.min(hav(lat, lon, sy[i], sx[i]), Math.min(hav(lat, lon, ml, mo), hav(lat, lon, ey[i], ex[i])));
            if (d < best) {
                best = d; bi = i;
                double d0 = hav(lat, lon, sy[i], sx[i]), d1 = hav(lat, lon, ml, mo), d2 = hav(lat, lon, ey[i], ex[i]);
                if (d0 <= d1 && d0 <= d2) { bla = sy[i]; blo = sx[i]; } else if (d1 <= d2) { bla = ml; blo = mo; } else { bla = ey[i]; blo = ex[i]; }
            }
        }
        if (bi < 0) return null;
        Bound b = new Bound();
        int c = Math.max(0, Math.min(6, scls[bi]));
        b.kind = KIND[c]; b.label = LABEL[c];
        String pr = EarthData.PAIRS[spair[bi]];
        int dash = pr.indexOf('-');
        b.pair = dash > 0 ? plateShort(pr.substring(0, dash)) + "–" + plateShort(pr.substring(dash + 1)) : pr;
        b.km = (int) Math.round(best / 10.0) * 10;
        b.az = bearing(lat, lon, bla, blo);
        b.rate = svel[bi] > 0 ? svel[bi] / 100f : -1f;
        memoKey = new double[]{lat, lon};
        memo = b;
        return b;
    }

    static String plateShort(String code) {
        String n = plateName(code);
        return n.replace("North America", "N America").replace("South America", "S America").replace("Philippine Sea", "Philippine");
    }

    // ------------------------------------------------------------------ El Nino (NOAA CPC, monthly)
    static volatile long at = 0, triedAt = 0;
    static volatile double oni = Double.NaN, n34 = Double.NaN, n12 = Double.NaN, n3 = Double.NaN, n4 = Double.NaN;
    static volatile String oniSeason = "";

    static final String URL_IDX = "https://www.cpc.ncep.noaa.gov/data/indices/sstoi.indices";
    static final String URL_ONI = "https://www.cpc.ncep.noaa.gov/data/indices/oni.ascii.txt";

    /** Plain GET that keeps the line breaks (the shared one joins lines). */
    static String get(String url) throws Exception {
        try { String r = get0(url); Diag.ok(url); return r; } catch (Exception e) { Diag.fail(url, e); throw e; }
    }

    private static String get0(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        c.setRequestProperty("User-Agent", "NetSpeedXposed/1.0");
        int code = c.getResponseCode();
        if (code != 200) throw new java.io.IOException("HTTP " + code);
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    static boolean due(long now) {
        return now - triedAt > 10 * 60_000L && now - at > 12 * 3600_000L;
    }

    static void parse(String idx, String oniTxt, long now) {
        try {
            String last = null;
            for (String ln : idx.split("\n")) if (ln.trim().matches("^\\d{4}\\s+\\d+\\s+.*")) last = ln.trim();
            if (last != null) { String[] f = last.split("\\s+"); if (f.length >= 10) { n12 = Double.parseDouble(f[3]); n3 = Double.parseDouble(f[5]); n4 = Double.parseDouble(f[7]); n34 = Double.parseDouble(f[9]); } }
            last = null;
            for (String ln : oniTxt.split("\n")) if (ln.trim().matches("^[A-Z]{3}\\s+\\d{4}\\s+.*")) last = ln.trim();
            if (last != null) { String[] f = last.split("\\s+"); oni = Double.parseDouble(f[3]); oniSeason = f[0] + " " + f[1]; }
            ClimIdx.trendFrom(oniTxt);
            if (!Double.isNaN(oni)) { at = now; why = "ok"; }
            else why = "no ONI value in the reply";
        } catch (Throwable t) { why = "failed: " + t; }
    }

    /** "El Niño" / "La Niña" / "Neutral". */
    static String phase() { return Double.isNaN(oni) ? "" : oni >= 0.5 ? "El Niño" : oni <= -0.5 ? "La Niña" : "Neutral"; }

    /** Index of the Pacific box with the biggest anomaly in the direction of the phase (0 Nino1+2, 1 Nino3, 2 Nino3.4, 3 Nino4), -1 when unknown or neutral. */
    private static int hot() {
        double[] a = {n12, n3, n34, n4};
        boolean cold = oni <= -0.5;
        if (Double.isNaN(oni) || Math.abs(oni) < 0.5) return -1;
        int b = -1;
        for (int i = 0; i < 4; i++) if (!Double.isNaN(a[i]) && (b < 0 || (cold ? a[i] < a[b] : a[i] > a[b]))) b = i;
        return b;
    }

    /** Where the El Nino (or La Nina) water is strongest right now, short: "off Peru", "E Pacific", "C Pacific", "WC Pacific"; "" when unknown. */
    static String whereShort() {
        int h = hot();
        return h < 0 ? "" : new String[]{"off Peru", "E Pacific", "C Pacific", "WC Pacific"}[h];
    }

    static String whereLong() {
        int h = hot();
        if (h < 0) return "";
        double[] a = {n12, n3, n34, n4};
        return new String[]{"Ni\u00f1o 1+2 (coast of Peru)", "Ni\u00f1o 3 (eastern Pacific)", "Ni\u00f1o 3.4 (central Pacific)", "Ni\u00f1o 4 (west-central Pacific)"}[h]
                + String.format(Locale.US, " %+.1f\u00b0 \u00b7 others: 1+2 %+.1f, 3 %+.1f, 3.4 %+.1f, 4 %+.1f (latest month)", a[h], n12, n3, n34, n4);
    }

    /** "VS" very strong, "S" strong, "M" moderate, "W" weak. */
    static String strengthShort() {
        String x = strength();
        return x.equals("very strong") ? "VS" : x.equals("strong") ? "S" : x.equals("moderate") ? "M" : x.equals("weak") ? "W" : "";
    }

    static String strength() {
        double a = Math.abs(oni);
        return Double.isNaN(oni) || a < 0.5 ? "" : a >= 2.0 ? "very strong" : a >= 1.5 ? "strong" : a >= 1.0 ? "moderate" : "weak";
    }

    // ------------------------------------------------------------------ ocean current by region (tide places are all in the northern Indian Ocean)
    /** Name of the current usually found there, null when unknown. Names only; the measured direction shows what it does now. */
    static String currentName(double lat, double lon) {
        if (lat > 22.5 && lon < 62) return "Persian Gulf flow";
        if (lat > 22.5 && lon < 68) return "Arabian Sea Current";
        if (lat >= 5 && lat <= 24 && lon >= 66 && lon <= 78.5) return "West India Coastal";
        if (lat >= 7 && lat <= 22.5 && lon > 78.5 && lon <= 90) return "East India Coastal";
        if (lat >= 9 && lat <= 14 && lon > 90) return "Andaman Sea Current";
        if (lat < 7 && lat > 4 && lon > 78 && lon < 82) return "Monsoon Current";
        return null;
    }

    /** "West India Coastal" to "W India Coastal". */
    static String shortCurrent(String n) {
        if (n == null) return null;
        String[] w = n.split(" ");
        if (w.length >= 3 && w[0].length() > 1) return w[0].substring(0, 1) + " " + w[1] + " " + w[2];
        return n;
    }

    static String fmtKm(int km) { return km >= 1000 ? String.format(Locale.US, "%,d", km) : String.valueOf(km); }
}
