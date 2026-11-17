package com.netspeed.xposed;

import android.content.Context;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Which telecom circle you are physically in, from your position (Android's Geocoder: state name) -> circle code.
 * Used for the roaming tag when the network itself hides it (same network code in every state, no roaming flag).
 * Looked up at most every 10 min, or when you've moved more than ~10 km; cached between.
 */
final class GeoCircle {
    private GeoCircle() {}

    private static final Map<String, String> STATE = new HashMap<>();
    static {
        String[][] m = {
                {"maharashtra", "MH"}, {"goa", "MH"}, {"gujarat", "GJ"}, {"dadra and nagar haveli and daman and diu", "GJ"},
                {"karnataka", "KA"}, {"kerala", "KL"}, {"lakshadweep", "KL"}, {"tamil nadu", "TN"}, {"puducherry", "TN"},
                {"andhra pradesh", "AP"}, {"telangana", "AP"}, {"madhya pradesh", "MP"}, {"chhattisgarh", "MP"},
                {"rajasthan", "RJ"}, {"punjab", "PB"}, {"chandigarh", "PB"}, {"haryana", "HR"}, {"delhi", "DL"},
                {"national capital territory of delhi", "DL"}, {"himachal pradesh", "HP"}, {"jammu and kashmir", "JK"},
                {"ladakh", "JK"}, {"uttar pradesh", "UP"}, {"uttarakhand", "UP"}, {"bihar", "BR"}, {"jharkhand", "BR"},
                {"west bengal", "WB"}, {"sikkim", "WB"}, {"odisha", "OD"}, {"assam", "AS"},
                {"arunachal pradesh", "NE"}, {"meghalaya", "NE"}, {"manipur", "NE"}, {"mizoram", "NE"},
                {"nagaland", "NE"}, {"tripura", "NE"}, {"andaman and nicobar islands", "AN"},
        };
        for (String[] p : m) STATE.put(p[0], p[1]);
    }

    static volatile String here = null;      // circle code where you are, null = unknown / abroad
    static volatile String hereName = null;
    private static volatile long at = 0;
    private static volatile double lat0 = 999, lon0 = 999;

    /** Worker thread only (Geocoder blocks). */
    static void tick(Context ctx, double lat, double lon) {
        long now = System.currentTimeMillis();
        boolean moved = Math.abs(lat - lat0) > 0.09 || Math.abs(lon - lon0) > 0.09;
        if (!moved && now - at < 10 * 60_000L) return;
        if (now - at < 60_000L) return;
        at = now;
        try {
            if (!android.location.Geocoder.isPresent()) return;
            @SuppressWarnings("deprecation")
            java.util.List<android.location.Address> a = new android.location.Geocoder(ctx, Locale.ENGLISH).getFromLocation(lat, lon, 1);
            if (a == null || a.isEmpty()) return;
            android.location.Address ad = a.get(0);
            if (!"IN".equalsIgnoreCase(ad.getCountryCode())) { here = null; hereName = null; lat0 = lat; lon0 = lon; return; }
            String st = ad.getAdminArea();
            hereName = st;
            here = st == null ? null : STATE.get(st.toLowerCase(Locale.US).replace("&", "and").trim());
            lat0 = lat; lon0 = lon;
        } catch (Throwable ignored) { }
    }

    /** The circle you're in when it differs from the SIM's home circle, else null. Mumbai counts as Maharashtra etc. */
    static String away(String simCircle) {
        String h = here;
        if (h == null || simCircle == null) return null;
        String s = norm(simCircle);
        return s.equals(h) ? null : h;
    }

    private static String norm(String c) {
        switch (c) {
            case "MU": return "MH";
            case "CH": return "TN";
            case "KO": return "WB";
            case "UE": case "UW": return "UP";
            default: return c;
        }
    }
}
