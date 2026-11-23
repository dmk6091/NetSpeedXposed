package com.netspeed.xposed;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * "Ravet · Haveli taluka · Pune district · Maharashtra" and what the ground and plants there are like.
 * The names come from OpenStreetMap's reverse lookup (cached per 5 km cell, so it repeats only when you travel); the soil and
 * plants come from a built-in guide that covers all of India, by state and, for the big states, by zone.
 */
final class PlaceInfo {
    private PlaceInfo() {}

    static final class Info {
        String name = "", sub = "", district = "", state = "";
        String soil = "", plants = "";
        /** One line for the lock screen. */
        String where() {
            StringBuilder sb = new StringBuilder(name);
            for (String s : new String[]{sub, district, state}) if (!s.isEmpty() && !sb.toString().contains(s)) { if (sb.length() > 0) sb.append(" · "); sb.append(s); }
            return sb.toString();
        }
        String ground() {
            if (soil.isEmpty() && plants.isEmpty()) return "";
            return soil + (soil.isEmpty() || plants.isEmpty() ? "" : " · ") + plants;
        }
    }

    /** Worker thread. Never null; fields are empty when nothing is known. */
    static Info lookup(Context c, SharedPreferences sp, double lat, double lon) {
        Info in = new Info();
        String key = String.format(Locale.US, "%.2f,%.2f", Math.round(lat * 20) / 20.0, Math.round(lon * 20) / 20.0);
        try {
            String cached = sp.getString("plKey", "");
            if (key.equals(cached) && System.currentTimeMillis() - sp.getLong("plAt", 0) < 30L * 86400_000L) {
                in.name = sp.getString("plName", ""); in.sub = sp.getString("plSub", ""); in.district = sp.getString("plDist", ""); in.state = sp.getString("plState", "");
            } else {
                fetch(in, lat, lon);
                if (!in.state.isEmpty())
                    sp.edit().putString("plKey", key).putLong("plAt", System.currentTimeMillis()).putString("plName", in.name).putString("plSub", in.sub)
                            .putString("plDist", in.district).putString("plState", in.state).apply();
                else {   // offline: last known place if it is close, else guess the state box
                    in.name = sp.getString("plName", ""); in.sub = sp.getString("plSub", ""); in.district = sp.getString("plDist", ""); in.state = sp.getString("plState", "");
                }
            }
        } catch (Throwable ignored) { }
        if (in.state.isEmpty() && lat > 15.6 && lat < 22.1 && lon > 72.6 && lon < 80.9) in.state = "Maharashtra";
        String[] g = guide(in.state, lat, lon);
        in.soil = g[0]; in.plants = g[1];
        return in;
    }

    private static void fetch(Info in, double lat, double lon) {
        try {
            java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(String.format(Locale.US,
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=14&addressdetails=1&accept-language=en&lat=%.5f&lon=%.5f", lat, lon)).openConnection();
            h.setConnectTimeout(8000); h.setReadTimeout(10000);
            h.setRequestProperty("User-Agent", "FieldAtlas/1.0 (personal wallpaper)");
            if (h.getResponseCode() != 200) return;
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(h.getInputStream(), "UTF-8"))) {
                String ln; while ((ln = r.readLine()) != null) sb.append(ln);
            }
            org.json.JSONObject a = new org.json.JSONObject(sb.toString()).getJSONObject("address");
            String name = "";
            for (String k : new String[]{"suburb", "neighbourhood", "quarter", "village", "hamlet", "town", "city_district", "city"})
                if (a.has(k)) { name = a.getString(k); break; }
            String city = a.optString("city", a.optString("town", ""));
            in.name = name.isEmpty() ? city : name;
            if (!city.isEmpty() && !city.equals(in.name) && in.name.length() > 0) in.name = in.name + ", " + city;
            in.state = a.optString("state", "");
            String d = a.optString("state_district", ""), sub = a.optString("county", "");
            if (d.isEmpty() && !sub.isEmpty()) { d = sub; sub = ""; }
            in.district = tidy(d, "district");
            in.sub = sub.isEmpty() ? "" : subWord(tidy(sub, "taluka", "tehsil", "tahsil", "mandal", "block", "sub-district", "subdistrict"), in.state);
            if (!in.district.isEmpty()) in.district = in.district + " district";
        } catch (Throwable ignored) { }
    }

    private static String tidy(String s, String... words) {
        String o = s.trim();
        for (String w : words) { String l = o.toLowerCase(Locale.ROOT); if (l.endsWith(" " + w)) o = o.substring(0, o.length() - w.length() - 1).trim(); }
        return o;
    }

    /** The local word for a sub-district. */
    static String subWord(String n, String state) {
        if (n.isEmpty()) return "";
        String s = state.toLowerCase(Locale.ROOT), w;
        if (has(s, "maharashtra", "gujarat", "karnataka", "goa", "dadra", "daman")) w = "taluka";
        else if (has(s, "andhra", "telangana")) w = "mandal";
        else if (has(s, "bihar", "jharkhand", "odisha", "west bengal", "assam", "tripura", "meghalaya", "mizoram", "nagaland", "manipur", "arunachal", "sikkim", "chhattisgarh", "kerala")) w = "block";
        else if (has(s, "tamil nadu", "puducherry")) w = "taluk";
        else w = "tehsil";
        return n + " " + w;
    }

    private static boolean has(String s, String... k) { for (String x : k) if (s.contains(x)) return true; return false; }

    // ------------------------------------------------------------------ soil and plants guide, all of India

    /** {soil, plants} */
    static String[] guide(String state, double lat, double lon) {
        String s = state == null ? "" : state.toLowerCase(Locale.ROOT);
        if (s.contains("maharashtra") || s.isEmpty()) {
            if (lon < 73.45 && lat < 20.2) return new String[]{"Laterite and coastal soil", "mango, coconut, cashew, jackfruit, mangrove"};
            if (lon < 74.1 && lat < 21) return new String[]{"Thin red-laterite hill soil", "teak, ain, karvi, bamboo, wild grass"};
            if (lon > 77.0) return new String[]{"Black and red soil", "teak, mahua, tendu, cotton, sorghum"};
            return new String[]{"Black basalt soil (regur)", "neem, babul, tamarind, teak, bamboo"};
        }
        if (has(s, "goa")) return new String[]{"Laterite and coastal sand", "cashew, coconut, mango, kokum, mangrove"};
        if (has(s, "gujarat", "diu", "daman", "dadra"))
            return lon < 71.5 && lat > 22.5 ? new String[]{"Sandy and saline soil (Kutch)", "babul, prosopis, khejri, desert scrub"}
                    : lat < 22.5 && lon < 72.0 ? new String[]{"Black cotton soil", "babul, neem, banyan, cotton, groundnut"}
                    : new String[]{"Alluvial and sandy loam", "neem, mango, teak, bamboo, cotton"};
        if (has(s, "rajasthan")) return lon < 73.5 ? new String[]{"Desert sand and dunes", "khejri, rohida, ker, babul, sewan grass"}
                : new String[]{"Semi-arid alluvial and sandy loam", "khejri, babul, neem, dhok, mustard"};
        if (has(s, "punjab", "haryana", "delhi", "chandigarh")) return new String[]{"Alluvial (Indo-Gangetic) soil", "shisham, kikar, neem, eucalyptus, wheat and paddy"};
        if (has(s, "uttar pradesh")) return lat > 27.8 && lon > 80 ? new String[]{"Terai alluvial soil", "sal, teak, sugarcane, bamboo, khair"}
                : lat < 25.5 && lon < 80.8 ? new String[]{"Red and black soil (Bundelkhand)", "dhak, khair, babul, neem, mahua"}
                : new String[]{"Gangetic alluvial soil", "mango, shisham, neem, sugarcane, wheat"};
        if (has(s, "uttarakhand")) return new String[]{"Mountain forest soil", "deodar, pine, oak, rhododendron, sal in the foothills"};
        if (has(s, "himachal")) return new String[]{"Mountain forest and alluvial soil", "deodar, pine, oak, apple, rhododendron"};
        if (has(s, "ladakh")) return new String[]{"Cold desert gravel and sand", "sea buckthorn, willow, poplar, apricot"};
        if (has(s, "jammu", "kashmir")) return new String[]{"Karewa and mountain soil", "chinar, deodar, fir, willow, apple"};
        if (has(s, "bihar")) return new String[]{"Gangetic alluvial soil", "mango, litchi, shisham, bamboo, paddy"};
        if (has(s, "jharkhand")) return new String[]{"Red and laterite soil", "sal, mahua, palash, bamboo, tendu"};
        if (has(s, "west bengal")) return lat < 22.3 ? new String[]{"Deltaic alluvium and saline soil", "sundari mangrove, coconut, palm, paddy"}
                : lat > 26 ? new String[]{"Terai and hill soil", "sal, tea, teak, bamboo, orchids"} : new String[]{"Alluvial and red laterite soil", "sal, mango, palm, bamboo, paddy"};
        if (has(s, "odisha")) return new String[]{"Red, laterite and coastal alluvium", "sal, mahua, teak, bamboo, casuarina"};
        if (has(s, "chhattisgarh")) return new String[]{"Red-yellow and laterite soil", "sal, teak, mahua, tendu, bamboo"};
        if (has(s, "madhya pradesh")) return lon > 80 ? new String[]{"Red-yellow soil", "sal, teak, mahua, tendu, bamboo"} : new String[]{"Black (regur) and alluvial soil", "teak, sagon, mahua, khejri, soybean"};
        if (has(s, "karnataka")) return lon < 75.2 && lat < 15.5 ? new String[]{"Laterite (coast and Malnad)", "teak, rosewood, areca, coffee, cashew, bamboo"}
                : lat > 15.3 ? new String[]{"Black cotton soil", "neem, tamarind, babul, cotton, jowar"} : new String[]{"Red loam", "sandalwood, teak, eucalyptus, mango, ragi"};
        if (has(s, "kerala")) return new String[]{"Laterite and coastal alluvium", "coconut, rubber, teak, pepper, areca"};
        if (has(s, "tamil nadu", "puducherry")) return lon > 79.2 && lat > 10 && lat < 11.6 ? new String[]{"Deltaic alluvium", "coconut, palmyra, banana, paddy"}
                : new String[]{"Red and black soil", "palmyra, neem, tamarind, casuarina, millets"};
        if (has(s, "andhra", "telangana")) return new String[]{"Red and black soil, deltaic alluvium on the coast", "neem, tamarind, red sanders, teak, palmyra"};
        if (has(s, "assam", "meghalaya", "arunachal", "nagaland", "manipur", "mizoram", "tripura", "sikkim"))
            return new String[]{"Alluvial plains and acidic hill soil", "bamboo, sal, tea, orchids, rhododendron"};
        if (has(s, "andaman", "nicobar", "lakshadweep")) return new String[]{"Coral sand and forest soil", "coconut, padauk, mangrove, banyan"};
        return new String[]{"", ""};
    }
}
