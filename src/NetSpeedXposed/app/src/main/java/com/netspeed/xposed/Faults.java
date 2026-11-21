package com.netspeed.xposed;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Nearest mapped faults. India: AFDI (GSI Seismotectonic Atlas of India, 2000); South and Central Asia beyond
 * it: GEM Global Active Faults (CC BY-SA 4.0). Mapped faults only: "none nearby" does not mean no fault, and
 * a listed fault is not a hazard warning.
 */
final class Faults {
    private Faults() {}

    static final class Hit {
        String name, type, src;
        double km, rate = Double.NaN;
        String dir;
        boolean named() { return !name.isEmpty(); }
        String label() { return named() ? name : "unnamed"; }
    }

    private static final class Line {
        String src, type, name;
        double rate = Double.NaN;
        float[] lon, lat;
    }

    private static volatile List<Line> lines;

    private static synchronized List<Line> load() {
        if (lines != null) return lines;
        List<Line> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (String c : FaultData.CHUNKS) sb.append(c);
        for (String ln : sb.toString().split("\n")) {
            String[] f = ln.split("~", -1);
            if (f.length < 5) continue;
            try {
                Line l = new Line();
                l.src = f[0];
                l.type = f[1];
                l.name = f[2];
                if (!f[3].isEmpty()) l.rate = Double.parseDouble(f[3]);
                String[] v = f[4].split(",");
                int n = v.length / 2;
                l.lon = new float[n];
                l.lat = new float[n];
                long x = 0, y = 0;
                for (int i = 0; i < n; i++) {
                    x += Long.parseLong(v[2 * i]);
                    y += Long.parseLong(v[2 * i + 1]);
                    l.lon[i] = x / 1000f;
                    l.lat[i] = y / 1000f;
                }
                out.add(l);
            } catch (Throwable ignored) { }
        }
        lines = out;
        return out;
    }

    private static double distKm(Line l, double lat0, double lon0, double[] at) {
        double kx = 111.32 * Math.cos(Math.toRadians(lat0)), ky = 110.57;
        double best = 1e9;
        for (int i = 0; i + 1 < l.lat.length; i++) {
            double x0 = (l.lon[i] - lon0) * kx, y0 = (l.lat[i] - lat0) * ky;
            double x1 = (l.lon[i + 1] - lon0) * kx, y1 = (l.lat[i + 1] - lat0) * ky;
            double dx = x1 - x0, dy = y1 - y0, len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(x0 * dx + y0 * dy) / len2));
            double px = x0 + t * dx, py = y0 + t * dy;
            double d = Math.hypot(px, py);
            if (d < best) { best = d; at[0] = px; at[1] = py; }
        }
        return best;
    }

    private static String dir(double dx, double dy) {
        double b = (Math.toDegrees(Math.atan2(dx, dy)) + 360) % 360;
        return new String[]{"N", "NE", "E", "SE", "S", "SW", "W", "NW"}[(int) ((b + 22.5) / 45) % 8];
    }

    static String typeText(String src, String t) {
        if (src.equals("G")) return t.isEmpty() ? "active fault" : t.toLowerCase(Locale.ROOT);
        switch (t) {
            case "NEO": return "neotectonic";
            case "THRUST": return "thrust";
            case "SUBSUR_FAULT": return "subsurface fault";
            case "NEO-GRAVITY": return "neotectonic, gravity-inferred";
            case "GRAVITY": return "gravity-inferred";
            case "SHEAR": return "shear zone";
            case "NEO-THRUST": return "neotectonic thrust";
            case "NEO-STRIKESLIP": return "neotectonic strike-slip";
            case "STRIKESLIP": return "strike-slip";
            case "RB-BURIED": return "buried rift boundary";
            default: return "unclassified";
        }
    }

    private static volatile long cacheKey = Long.MIN_VALUE;
    private static volatile List<Hit> cache = new ArrayList<>();

    /** The n nearest faults (one entry per named fault, unnamed ones as they come), nearest first. */
    static synchronized List<Hit> nearest(double lat, double lon, int n) {
        long key = Math.round(lat * 100) * 100000L + Math.round(lon * 100);
        if (key == cacheKey && cache.size() >= n) return cache.subList(0, n);
        List<Hit> all = new ArrayList<>();
        double[] at = new double[2];
        for (Line l : load()) {
            double d = distKm(l, lat, lon, at);
            if (d > 1500) continue;
            Hit h = new Hit();
            h.name = l.name;
            h.type = typeText(l.src, l.type);
            h.src = l.src;
            h.km = d;
            h.rate = l.rate;
            h.dir = dir(at[0], at[1]);
            all.add(h);
        }
        all.sort((a, b) -> Double.compare(a.km, b.km));
        List<Hit> out = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (Hit h : all) {
            if (h.named() && !seen.add(h.name.toLowerCase(Locale.ROOT))) continue;   // a named fault comes in several pieces
            out.add(h);
            if (out.size() >= 8) break;
        }
        cache = out;
        cacheKey = key;
        return out.subList(0, Math.min(n, out.size()));
    }

    /** Fault lines with a point inside {lat0 +- halfKm, lon0 +- halfKm}, as {lons, lats} pairs (for the map and the wallpaper). */
    static synchronized List<float[][]> linesIn(double lat0, double lon0, double halfKm) {
        List<float[][]> out = new ArrayList<>();
        double dLat = halfKm / 110.57, dLon = halfKm / (111.32 * Math.cos(Math.toRadians(lat0)));
        for (Line l : load()) {
            boolean hit = false;
            for (int i = 0; i < l.lat.length && !hit; i++)
                hit = Math.abs(l.lat[i] - lat0) <= dLat && Math.abs(l.lon[i] - lon0) <= dLon;
            if (hit) out.add(new float[][]{l.lon, l.lat});
        }
        return out;
    }

    /** Named faults with a point inside the box: {name, lat, lon} where lat/lon is the point of the fault nearest the centre. */
    static synchronized List<Object[]> namesIn(double lat0, double lon0, double halfKm) {
        List<Object[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        double dLat = halfKm / 110.57, dLon = halfKm / (111.32 * Math.cos(Math.toRadians(lat0)));
        for (Line l : load()) {
            if (l.name == null || l.name.trim().isEmpty() || !seen.add(l.name.toLowerCase(Locale.ROOT))) continue;
            int bi = -1; double bd = 1e18;
            for (int i = 0; i < l.lat.length; i++) {
                if (Math.abs(l.lat[i] - lat0) > dLat * 0.8 || Math.abs(l.lon[i] - lon0) > dLon * 0.8) continue;
                double d = (l.lat[i] - lat0) * (l.lat[i] - lat0) + (l.lon[i] - lon0) * (l.lon[i] - lon0);
                if (d < bd) { bd = d; bi = i; }
            }
            if (bi >= 0) out.add(new Object[]{l.name.replaceAll("(?i)\\s+fault$", "").trim(), (double) l.lat[bi], (double) l.lon[bi]});
        }
        return out;
    }

    /** One status-bar piece: "Fault: Chiplun Fault 64 km SW". */
    static String text(Hit h) {
        return "Fault: " + h.label() + " " + Math.round(h.km) + " km " + h.dir;
    }

    static String detail(Hit h) {
        String r = Double.isNaN(h.rate) ? "" : String.format(Locale.US, " %.1f mm/yr", h.rate);
        return h.type + r;
    }

    static String credit() {
        return "Sources: AFDI (from the GSI Seismotectonic Atlas of India, Dasgupta et al. 2000) in India; "
                + "GEM Global Active Faults (CC BY-SA 4.0) elsewhere in South and Central Asia.\n"
                + "Mapped faults only. A fault listed is not a hazard warning, and \"neotectonic\" means it moved in the "
                + "recent geological past, not that it is active now. Nothing listed nearby does not mean no fault.";
    }
}
