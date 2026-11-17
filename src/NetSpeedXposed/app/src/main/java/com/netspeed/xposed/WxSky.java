package com.netspeed.xposed;

import java.util.Locale;

/**
 * The sky in words, from the weather fetch (Open-Meteo): cloud type, cloud base, which way each cloud layer drifts, and the
 * kind of wind. All estimates from the model, not observed. Read by the bar's row-4 strip, which rotates through frames.
 */
final class WxSky {
    private WxSky() {}

    static volatile long at = 0;
    static volatile String why = "not fetched yet";
    /** Frame 1: kind ("Cumulus", "Clear sky"...), base ("1.2 km", "" when none), cover ("2%", "" when none). */
    static volatile String kind = "", base = "", cover = "";
    /** Frame 2: drift of each layer with cloud, e.g. {"low", "↗NE"}; empty when there is no cloud to follow. */
    static volatile String[][] layers = new String[0][];
    /** Frame 3: Beaufort word, arrow + speed, gust ("" when none). */
    static volatile String windWord = "", windSpeed = "", windGust = "";
    static volatile String kindLine = "", dirLine = "", windLine = "";

    private static final String[] DIR8 = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final String[] ARR = {"↓", "↙", "←", "↖", "↑", "↗", "→", "↘"};

    private static int oct(double deg) { return (int) Math.round(((deg % 360) + 360) % 360 / 45.0) % 8; }

    private static double num(org.json.JSONObject o, String k) {
        return o != null && o.has(k) && !o.isNull(k) ? o.optDouble(k, Double.NaN) : Double.NaN;
    }

    private static int hourIdx = 0;

    /** The hour of the forecast that matches the current time (the reply starts an hour earlier). */
    private static void pickHour(org.json.JSONObject cur, org.json.JSONObject h) {
        hourIdx = 0;
        try {
            String ct = cur.optString("time", "");
            org.json.JSONArray tm = h.optJSONArray("time");
            if (tm != null && ct.length() >= 13) for (int i = 0; i < tm.length(); i++) if (tm.optString(i, "").startsWith(ct.substring(0, 13))) { hourIdx = i; return; }
        } catch (Throwable ignored) { }
    }

    private static double first(org.json.JSONObject h, String k) {
        try {
            org.json.JSONArray a = h == null ? null : h.optJSONArray(k);
            if (a != null && a.length() > hourIdx && !a.isNull(hourIdx)) return a.getDouble(hourIdx);
        } catch (Throwable ignored) { }
        return Double.NaN;
    }

    static String beaufort(double kmh) {
        return kmh < 1 ? "Calm" : kmh < 6 ? "Light air" : kmh < 12 ? "Light breeze" : kmh < 20 ? "Gentle breeze" : kmh < 29 ? "Moderate breeze"
                : kmh < 39 ? "Fresh breeze" : kmh < 50 ? "Strong breeze" : kmh < 62 ? "Near gale" : kmh < 75 ? "Gale" : "Storm";
    }

    /** From the forecast reply: its "current" object and its "hourly" object (first hour = now). */
    static void update(org.json.JSONObject cur, org.json.JSONObject hourly) {
        try {
            double t = num(cur, "temperature_2m"), td = num(cur, "dew_point_2m");
            double cov = num(cur, "cloud_cover"), lo = num(cur, "cloud_cover_low"), mid = num(cur, "cloud_cover_mid"), hi = num(cur, "cloud_cover_high");
            double wind = num(cur, "wind_speed_10m"), gust = num(cur, "wind_gusts_10m"), wdir = num(cur, "wind_direction_10m");
            int wmo = cur != null && cur.has("weather_code") && !cur.isNull("weather_code") ? cur.optInt("weather_code", -1) : -1;
            pickHour(cur, hourly);
            double cape = first(hourly, "cape");

            // cloud base above the ground: the dew-point spread method, about 125 m per degree
            String baseS = "";
            if (!Double.isNaN(t) && !Double.isNaN(td)) {
                double b = Math.max(0, t - td) * 125.0;
                baseS = b >= 1000 ? String.format(Locale.US, "%.1f km", b / 1000.0) : (Math.round(b / 10) * 10) + " m";
            }
            double baseM = 0;
            try { if (!Double.isNaN(t) && !Double.isNaN(td)) baseM = Math.max(0, t - td) * 125.0; } catch (Throwable ignored) { }

            // type: from the layers, the instability and the weather code
            String k = "";
            if (!Double.isNaN(cov)) {
                if (wmo == 45 || wmo == 48) k = "Fog";
                else if (cov < 10) k = "Clear sky";
                else if (wmo >= 95 || (!Double.isNaN(cape) && cape >= 1500 && lo >= 30)) k = "Cumulonimbus";
                else if (lo >= 40 && (Double.isNaN(cape) || cape < 150) && baseM < 1000) k = "Stratus";
                else if (lo >= 50 && (Double.isNaN(cape) || cape < 400)) k = "Stratocumulus";
                else if (lo >= 20 && !Double.isNaN(cape) && cape >= 100) k = "Cumulus";
                else if (mid >= Math.max(lo, hi) && mid >= 15) k = "Altocumulus";
                else if (hi >= Math.max(lo, mid) && hi >= 10) k = "Cirrus";
                else if (lo >= 10) k = "Cumulus";
                else k = "Cloud";
            }
            boolean clear = k.equals("Clear sky") || k.equals("Fog");
            kind = k;
            base = clear ? "" : baseS;
            cover = clear || Double.isNaN(cov) ? "" : Math.round(cov) + "%";
            kindLine = k.isEmpty() ? "" : k + (base.isEmpty() ? "" : " · base " + base) + (cover.isEmpty() ? "" : " · cover " + cover);

            // direction: each layer drifts with the wind at its height (about 1.5, 3 and 5.5 km)
            String[] lay = {"low", "mid", "high"};
            double[] cv = {lo, mid, hi};
            String[] hp = {"850", "700", "500"};
            java.util.List<String[]> ls = new java.util.ArrayList<>();
            StringBuilder dl = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                double wd = first(hourly, "wind_direction_" + hp[i] + "hPa");
                if (Double.isNaN(wd) || Double.isNaN(cv[i]) || cv[i] < 10) continue;
                int o = oct(wd + 180);   // the wind blows FROM wd, so the cloud moves TOWARDS the opposite side
                ls.add(new String[]{lay[i], ARR[oct(wd)] + DIR8[o]});
                dl.append(dl.length() > 0 ? " · " : "").append(lay[i]).append(' ').append(DIR8[o]);
            }
            layers = ls.toArray(new String[0][]);
            dirLine = dl.length() > 0 ? "drifting towards: " + dl : cov < 10 ? "clear, no cloud to follow" : "";

            // wind kind
            if (Double.isNaN(wind)) { windWord = ""; windSpeed = ""; windGust = ""; windLine = ""; }
            else {
                boolean gusty = !Double.isNaN(gust) && gust - wind >= 10 && gust >= wind * 1.5;
                windWord = beaufort(wind);
                windSpeed = (Double.isNaN(wdir) ? "" : ARR[oct(wdir)]) + Math.round(wind);
                windGust = Double.isNaN(gust) || gust < 6 ? "" : String.valueOf(Math.round(gust));
                windLine = windWord + (gusty ? ", gusty" : wind >= 6 ? ", steady" : "") + " · " + (Double.isNaN(wdir) ? "" : DIR8[oct(wdir)] + " ")
                        + Math.round(wind) + " km/h" + (windGust.isEmpty() ? "" : " · gust " + windGust);
            }
            at = System.currentTimeMillis();
            why = "ok " + kindLine + " | " + dirLine + " | " + windLine + " (cape " + (Double.isNaN(cape) ? "n/a" : String.valueOf(Math.round(cape))) + ")";
        } catch (Throwable ex) { why = "failed: " + ex; }
    }
}
