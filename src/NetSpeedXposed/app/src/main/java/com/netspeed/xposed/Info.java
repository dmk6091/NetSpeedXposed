package com.netspeed.xposed;

import java.util.Locale;

/** Builds the feed for the app's Info tab (runs in System UI): lines "S|label|value", S = A astronomy, E earth, D disasters. */
final class Info {
    private Info() {}

    private static void add(StringBuilder b, char s, String label, String v) {
        if (v == null) return;
        v = v.replace('\n', ' ').replace('|', '/').trim();
        if (v.isEmpty() || v.equals("-")) return;
        b.append(s).append('|').append(label).append('|').append(v).append('\n');
    }

    static String feed(double lat, double lon, long now) {
        StringBuilder b = new StringBuilder();
        try { for (String[] p : SkyStrip.infoLines(now)) add(b, 'A', p[0], p[1]); } catch (Throwable ignored) { }
        try { add(b, 'A', "Overhead now", Sat.issText); } catch (Throwable ignored) { }
        try { add(b, 'E', "River flow", RiverFlow.text()); } catch (Throwable ignored) { }
        try { add(b, 'E', "Dam / weir", EarthLine.dam); } catch (Throwable ignored) { }
        try { add(b, 'E', "Rain", EarthLine.rain); } catch (Throwable ignored) { }
        try {
            String soil = EarthLine.soilType == null ? "" : EarthLine.soilType;
            if (!soil.isEmpty()) {
                String x = soil + (EarthLine.soilSplit == null || EarthLine.soilSplit.isEmpty() ? "" : " · " + EarthLine.soilSplit)
                        + (EarthLine.soilMoist == null || EarthLine.soilMoist.isEmpty() ? "" : " · " + EarthLine.soilMoist);
                add(b, 'E', "Soil", x);
            }
        } catch (Throwable ignored) { }
        try {
            Tide.Now t = Tide.now(now);
            if (t != null) {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm", Locale.US);
                add(b, 'E', "Tide " + (t.place == null ? "" : t.place), String.format(Locale.US, "%.1f m, %s; next %s %.1f m at %s", t.height,
                        t.rising ? "rising" : "falling", t.nextHigh ? "high" : "low", t.nextHeight, f.format(new java.util.Date(t.nextAt))));
            }
        } catch (Throwable ignored) { }
        try { if (!EarthLine.rockType.isEmpty()) add(b, 'E', "Rock", EarthLine.rockType + (EarthLine.rockUnit.isEmpty() ? "" : " \u00b7 " + EarthLine.rockUnit) + (EarthLine.rockAge.isEmpty() ? "" : " \u00b7 " + EarthLine.rockAge)); } catch (Throwable ignored) { }
        try { add(b, 'E', "Power", EarthLine.powerLine + (EarthLine.powerSub == null || EarthLine.powerSub.isEmpty() ? "" : " · " + EarthLine.powerSub)); } catch (Throwable ignored) { }
        try { add(b, 'E', "Terrain", EarthLine.slope); } catch (Throwable ignored) { }
        try { add(b, 'E', "Highest point nearby", EarthLine.peak); } catch (Throwable ignored) { }
        try { add(b, 'E', "Ground", EarthLine.ground); } catch (Throwable ignored) { }
        try { add(b, 'E', "District", EarthLine.district); } catch (Throwable ignored) { }
        try { add(b, 'D', "Earthquakes", EarthLine.quake); } catch (Throwable ignored) { }
        try {
            int n = 0;
            for (Hazards.Ev e : Hazards.events) {
                if (n++ >= 4) break;
                add(b, 'D', (e.red ? "🔴 " : "🟠 ") + Hazards.icon(e.type) + " " + (e.name == null ? "" : e.name),
                        (e.place == null ? "" : e.place) + (e.km >= 0 ? " · " + e.km + " km " + (e.dir == null ? "" : e.dir) : ""));
            }
        } catch (Throwable ignored) { }
        try { if (!Float.isNaN(Hazards.kp)) add(b, 'D', "Geomagnetic Kp", String.format(Locale.US, "%.0f%s", Hazards.kp, Hazards.kp >= 5 ? " (storm)" : " (quiet)")); } catch (Throwable ignored) { }
        return b.toString();
    }
}
