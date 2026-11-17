package com.netspeed.xposed;

import java.util.Locale;

/**
 * Tide at the nearest coast: hourly sea level (tide + surge, Open-Meteo Marine model) for the closest point in a
 * built-in list of coastal places, interpolated on the phone into rising / falling, height now and the next high or
 * low water. Model data, good to about ±20 min / ±0.2 m: fine for the status bar, not for boating.
 */
final class Tide {
    private Tide() {}

    /** name, lat, lon: points just offshore so the marine model has sea there. */
    private static final Object[][] COAST = {
            {"Mumbai", 18.90, 72.78}, {"Alibag", 18.64, 72.83}, {"Murud", 18.32, 72.93}, {"Harihareshwar", 17.99, 72.98},
            {"Dahanu", 19.97, 72.68}, {"Daman", 20.41, 72.79}, {"Surat", 21.05, 72.58}, {"Diu", 20.69, 70.97},
            {"Veraval", 20.88, 70.33}, {"Dwarka", 22.24, 68.92}, {"Kandla", 22.92, 70.10}, {"Ratnagiri", 16.99, 73.24},
            {"Malvan", 16.05, 73.44}, {"Goa", 15.47, 73.76}, {"Karwar", 14.80, 74.06}, {"Malpe", 13.35, 74.67},
            {"Mangaluru", 12.87, 74.79}, {"Kannur", 11.85, 75.33}, {"Kozhikode", 11.25, 75.73}, {"Kochi", 9.96, 76.19},
            {"Kovalam", 8.38, 76.95}, {"Kanyakumari", 8.04, 77.55}, {"Tuticorin", 8.78, 78.23},
            {"Rameswaram", 9.27, 79.34}, {"Nagapattinam", 10.77, 79.88}, {"Puducherry", 11.93, 79.86},
            {"Chennai", 13.08, 80.32}, {"Machilipatnam", 16.15, 81.21}, {"Kakinada", 16.95, 82.31},
            {"Vizag", 17.67, 83.33}, {"Puri", 19.78, 85.86}, {"Paradip", 20.25, 86.71}, {"Sagar", 21.60, 88.10},
            {"Port Blair", 11.67, 92.77}, {"Thane Creek", 19.04, 72.96}, {"Vasai", 19.33, 72.78}, {"Colombo", 6.95, 79.83}, {"Karachi", 24.80, 66.95}, {"Dubai", 25.28, 55.25},
    };

    static volatile String place = null;
    static volatile long[] times = null;      // epoch ms, hourly
    static volatile float[] heights = null;   // metres above mean sea level
    static volatile float[] sst = null;      // sea surface °C, same hours (NaN = missing)
    static volatile long fetchedAt = 0, triedAt = 0;
    static volatile double[] fetchedFor = null;
    /** Nearest named beach in a straight line {lat, lon, fromLat, fromLon}; set by DualRow from OpenStreetMap. */
    static volatile double[] beach = null;
    static volatile String beachName = null;
    static volatile long beachAt = 0, beachTriedAt = 0;
    static volatile boolean beachRunning = false;
    static volatile String beachWhy = "not looked up yet";

    /** The nearest beach if it was found for a place within 10 km of this one, else null. */
    static double[] beachPt(double lat, double lon) {
        double[] b = beach;
        return b != null && km(lat, lon, b[2], b[3]) < 10 ? b : null;
    }

    /** Places offered in settings: Indian coast only (the last three entries are abroad). */
    static final int INDIA = COAST.length - 3;

    static String[] choices() {
        String[] r = new String[INDIA + 1];
        r[0] = "Nearest coast (automatic)";
        for (int i = 0; i < INDIA; i++) r[i + 1] = (String) COAST[i][0];
        return r;
    }

    /** Settings choice: 0 = nearest, n = COAST[n - 1]. */
    private static int fixed() {
        int v = Cfg.i(Cfg.TIDE_PLACE, Cfg.D_TIDE_PLACE);
        return v >= 1 && v <= INDIA ? v - 1 : -1;
    }

    /** Distance to the real coastline in km (Coast data); falls back to the nearest tide point if that can't be read. */
    static double seaKm(double lat, double lon) {
        if (Cfg.i(Cfg.COAST_MODE, Cfg.D_COAST_MODE) == 1) {
            double[] bp = beachPt(lat, lon);
            if (bp != null) return km(lat, lon, bp[0], bp[1]);   // straight line to the nearest beach
        }
        double d = Coast.distanceKm(lat, lon);
        d = d >= 0 ? d : nearest(lat, lon)[1];
        if (Cfg.i(Cfg.COAST_MODE, Cfg.D_COAST_MODE) != 1) return d;
        // open sea only: the real coastline includes creeks and river mouths far inland (Ulhas creek is 3 km from
        // Dombivli, the open sea about 30 km). When the nearest tide point (offshore, creeks left out) is far,
        // measure to that instead; near the open coast the exact coastline distance stands.
        double ref = 1e9;
        for (int i = 0; i < INDIA; i++) {
            if (((String) COAST[i][0]).contains("Creek")) continue;
            ref = Math.min(ref, km(lat, lon, (Double) COAST[i][1], (Double) COAST[i][2]));
        }
        ref -= 2;                                   // the points sit about 2 km offshore
        return ref < 8 ? d : Math.max(d, ref);
    }

    /** Coastal point to use: the one picked in settings, else the nearest. {index, km}. */
    static double[] nearest(double lat, double lon) {
        int f = fixed();
        if (f >= 0) return new double[]{f, km(lat, lon, (Double) COAST[f][1], (Double) COAST[f][2])};
        if (Cfg.i(Cfg.TIDE_BY, Cfg.D_TIDE_BY) == 1) {   // nearest beach: use the tide point closest to that beach
            double[] bp = beachPt(lat, lon);
            if (bp != null) {
                int bi = -1;
                double bk = 1e9;
                for (int i = 0; i < INDIA; i++) {
                    if (((String) COAST[i][0]).contains("Creek")) continue;
                    double d = km(bp[0], bp[1], (Double) COAST[i][1], (Double) COAST[i][2]);
                    if (d < bk) { bk = d; bi = i; }
                }
                if (bi >= 0 && bk < 60) return new double[]{bi, km(lat, lon, bp[0], bp[1])};
            }
        }
        // plain straight-line nearest tide point (creeks left out in open-sea mode)
        final boolean creeks = Cfg.i(Cfg.COAST_MODE, Cfg.D_COAST_MODE) != 1;
        int best = -1;
        double bd = 1e9;
        for (int i = 0; i < COAST.length; i++) {
            if (!creeks && ((String) COAST[i][0]).contains("Creek")) continue;
            double d = km(lat, lon, (Double) COAST[i][1], (Double) COAST[i][2]);
            if (d < bd) { bd = d; best = i; }
        }
        return new double[]{best, bd};
    }

    private static double km(double a1, double o1, double a2, double o2) {
        double p = Math.toRadians(a2 - a1), q = Math.toRadians(o2 - o1);
        double h = Math.sin(p / 2) * Math.sin(p / 2)
                + Math.cos(Math.toRadians(a1)) * Math.cos(Math.toRadians(a2)) * Math.sin(q / 2) * Math.sin(q / 2);
        return 6371 * 2 * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /** Refetch every 6 h, or when you've moved so a different coast is nearest. */
    static boolean due(double lat, double lon, long now) {
        if (now - triedAt < 10 * 60_000L) return false;
        if (fetchedFor == null || now - fetchedAt > 6 * 3600_000L) return true;
        return !COAST[(int) nearest(lat, lon)[0]][0].equals(place);
    }

    static String url(double lat, double lon) {
        int i = (int) nearest(lat, lon)[0];
        return String.format(Locale.US, "https://marine-api.open-meteo.com/v1/marine?latitude=%.3f&longitude=%.3f"
                        + "&hourly=sea_level_height_msl,sea_surface_temperature,wave_height,wave_period,ocean_current_velocity,ocean_current_direction&past_days=1&forecast_days=2&timeformat=unixtime&cell_selection=sea",
                (Double) COAST[i][1], (Double) COAST[i][2]);
    }

    static void parse(String json, double lat, double lon, long now) throws Exception {
        org.json.JSONObject h = new org.json.JSONObject(json).getJSONObject("hourly");
        org.json.JSONArray t = h.getJSONArray("time"), v = h.getJSONArray("sea_level_height_msl");
        int n = Math.min(t.length(), v.length()), k = 0;
        long[] tt = new long[n];
        float[] vv = new float[n];
        for (int i = 0; i < n; i++) {
            if (v.isNull(i)) continue;
            tt[k] = t.getLong(i) * 1000L;
            vv[k++] = (float) v.getDouble(i);
        }
        if (k < 6) throw new IllegalStateException("no sea level data");
        float[] ss = new float[k];
        java.util.Arrays.fill(ss, Float.NaN);
        org.json.JSONArray st = h.optJSONArray("sea_surface_temperature");
        if (st != null) {
            for (int i = 0, j = 0; i < n && j < k; i++) {
                if (v.isNull(i)) continue;
                if (i < st.length() && !st.isNull(i)) ss[j] = (float) st.getDouble(i);
                j++;
            }
        }
        float[][] ex = new float[4][k];
        String[] nmx = {"wave_height", "wave_period", "ocean_current_velocity", "ocean_current_direction"};
        for (int q = 0; q < 4; q++) {
            java.util.Arrays.fill(ex[q], Float.NaN);
            org.json.JSONArray ar = h.optJSONArray(nmx[q]);
            if (ar == null) continue;
            for (int i = 0, j = 0; i < n && j < k; i++) {
                if (v.isNull(i)) continue;
                if (i < ar.length() && !ar.isNull(i)) ex[q][j] = (float) ar.getDouble(i);
                j++;
            }
        }
        wvH = ex[0]; wvP = ex[1]; curV = ex[2]; curD = ex[3];
        sst = ss;
        times = java.util.Arrays.copyOf(tt, k);
        heights = java.util.Arrays.copyOf(vv, k);
        place = (String) COAST[(int) nearest(lat, lon)[0]][0];
        fetchedFor = new double[]{lat, lon};
        fetchedAt = now;
    }

    /** What the bar shows. */
    static final class Now {
        boolean rising;
        float height;          // m, now
        boolean nextHigh;
        long nextAt;           // epoch ms
        float nextHeight;
        boolean spring;        // this cycle's range well above the average one
        String place;
    }

    static Now now(long ms) {
        long[] t = times;
        float[] h = heights;
        if (t == null || h == null || ms < t[0] || ms > t[t.length - 1] - 3600_000L) return null;
        Now r = new Now();
        r.place = (Cfg.i(Cfg.TIDE_BY, Cfg.D_TIDE_BY) == 1 && beachName != null && beach != null) ? beachName : place;
        r.height = at(t, h, ms);
        r.rising = at(t, h, ms + 10 * 60_000L) > r.height;
        // turning points, refined with a parabola through the three hours around each one
        float prevExt = Float.NaN, sumRange = 0;
        int ranges = 0;
        float lastBefore = Float.NaN;
        boolean found = false;
        for (int i = 1; i < t.length - 1; i++) {
            float a = h[i - 1], b = h[i], c = h[i + 1];
            boolean max = b >= a && b > c, min = b <= a && b < c;
            if (!max && !min) continue;
            float den = a - 2 * b + c;
            float off = den == 0 ? 0 : 0.5f * (a - c) / den;
            off = Math.max(-0.5f, Math.min(0.5f, off));
            long when = t[i] + Math.round(off * 3600_000.0);
            float val = b - 0.25f * (a - c) * off;
            if (!Float.isNaN(prevExt)) { sumRange += Math.abs(val - prevExt); ranges++; }
            if (!found && when > ms) {
                found = true;
                r.nextHigh = max;
                r.nextAt = when;
                r.nextHeight = val;
                lastBefore = prevExt;
            }
            prevExt = val;
        }
        if (!found) return null;
        if (!Float.isNaN(lastBefore) && ranges > 0)
            r.spring = Math.abs(r.nextHeight - lastBefore) > 1.15f * (sumRange / ranges);
        return r;
    }

    private static float at(long[] t, float[] h, long ms) {
        for (int i = 0; i < t.length - 1; i++) {
            if (ms >= t[i] && ms <= t[i + 1]) {
                float f = (ms - t[i]) / (float) (t[i + 1] - t[i]);
                return h[i] + (h[i + 1] - h[i]) * f;
            }
        }
        return h[h.length - 1];
    }

    /** {lat, lon} of the tide place the marine data is for. */
    static double[] placeXY() {
        String pl = place;
        if (pl == null) return null;
        for (Object[] c : COAST) if (c[0].equals(pl)) return new double[]{(Double) c[1], (Double) c[2]};
        return null;
    }

    static volatile float[] wvH, wvP, curV, curD;

    /** Sea now at the tide place: {wave height m, wave period s, current km/h, current direction it flows TOWARDS in degrees}; null when unknown. */
    static float[] sea(long ms) {
        long[] t = times;
        float[] h = wvH, p = wvP, cv = curV, cd = curD;
        if (t == null || h == null || h.length != t.length) return null;
        int best = -1;
        long bd = Long.MAX_VALUE;
        for (int i = 0; i < t.length; i++) {
            long d = Math.abs(t[i] - ms);
            if (d < bd) { bd = d; best = i; }
        }
        if (best < 0 || bd > 3 * 3600_000L) return null;
        float[] r = {h[best], p[best], cv[best], cd[best]};
        return Float.isNaN(r[0]) && Float.isNaN(r[2]) ? null : r;
    }

    /** Douglas-style sea state word from the significant wave height. */
    static String stateWord(float hs) {
        return hs < 0.1f ? "Glassy" : hs < 0.5f ? "Calm sea" : hs < 1.25f ? "Smooth sea" : hs < 2.5f ? "Slight sea" : hs < 4f ? "Moderate sea"
                : hs < 6f ? "Rough sea" : hs < 9f ? "Very rough" : "High sea";
    }

    /** Sea surface temperature now (°C) at the tide place, NaN when unknown. */
    static float seaTemp(long ms) {
        long[] t = times;
        float[] s = sst;
        if (t == null || s == null || s.length != t.length) return Float.NaN;
        int best = -1;
        long bd = Long.MAX_VALUE;
        for (int i = 0; i < t.length; i++) {
            if (Float.isNaN(s[i])) continue;
            long d = Math.abs(t[i] - ms);
            if (d < bd) { bd = d; best = i; }
        }
        return best < 0 || bd > 3 * 3600_000L ? Float.NaN : s[best];
    }

    /** For the details dialog: the day's high and low waters. */
    static String table(long from) {
        long[] t = times;
        float[] h = heights;
        if (t == null) return "Tide: no data yet";
        StringBuilder b = new StringBuilder("Tide at " + place + " (model, ±20 min):\n");
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("EEE HH:mm", Locale.US);
        for (int i = 1; i < t.length - 1; i++) {
            float a = h[i - 1], m = h[i], c = h[i + 1];
            boolean max = m >= a && m > c, min = m <= a && m < c;
            if (!max && !min) continue;
            float den = a - 2 * m + c, off = den == 0 ? 0 : Math.max(-0.5f, Math.min(0.5f, 0.5f * (a - c) / den));
            long when = t[i] + Math.round(off * 3600_000.0);
            if (when < from - 6 * 3600_000L || when > from + 30 * 3600_000L) continue;
            b.append(String.format(Locale.US, "  %s %s  %+.2f m\n", max ? "High" : "Low ",
                    f.format(new java.util.Date(when)), m - 0.25f * (a - c) * off));
        }
        return b.toString();
    }
}
