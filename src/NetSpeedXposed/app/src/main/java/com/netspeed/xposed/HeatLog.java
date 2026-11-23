package com.netspeed.xposed;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heat and power history. Two logs:
 *  - System UI writes one line a minute (time, watts, battery temp, CPU temp, charging) to its own folder;
 *  - this app, every 15 minutes, reads the battery statistics with root (since-last-charge totals per part and per app).
 * The graph turns the differences between two readings into "average watts by cause" for that interval.
 */
final class HeatLog {
    private HeatLog() {}

    private static final String SUI_FILE = "/data/user_de/0/com.android.systemui/files/fa_heat.csv";
    private static long lastSample;

    /** System UI side. */
    static void sample(Context ctx, float watts, float bat, float cpu, boolean charging) {
        try {
            long now = System.currentTimeMillis();
            if (now - lastSample < 60_000L) return;
            lastSample = now;
            File f = new File(ctx.getFilesDir(), "fa_heat.csv");
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(now + "," + (Float.isNaN(watts) ? "" : String.format(Locale.US, "%.2f", Math.abs(watts))) + "," + (Float.isNaN(bat) ? "" : String.format(Locale.US, "%.1f", bat))
                        + "," + (Float.isNaN(cpu) ? "" : String.format(Locale.US, "%.0f", cpu)) + "," + (charging ? 1 : 0) + "\n");
            }
            if (f.length() > 400_000L) {                         // keep the newest half
                String all = new String(java.nio.file.Files.readAllBytes(f.toPath()));
                String keep = all.substring(all.length() / 2);
                keep = keep.substring(keep.indexOf('\n') + 1);
                java.nio.file.Files.write(f.toPath(), keep.getBytes());
            }
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ app side: the battery statistics

    static void snapshot(Context c) {
        try {
            String bs = HeatReport.sh("dumpsys batterystats");
            if (bs.isEmpty()) return;
            double drain = 0;
            Matcher m = Pattern.compile("Computed drain: ([0-9.]+)").matcher(bs);
            if (m.find()) drain = HeatReport.num(m.group(1), 0);
            int at = bs.indexOf("Estimated power use");
            String sect = at < 0 ? "" : bs.substring(at, Math.min(bs.length(), at + 900));
            HashMap<String, String> uidPkg = new HashMap<>();
            for (String ln : HeatReport.sh("pm list packages -U").split("\n")) {
                Matcher pm = Pattern.compile("package:(\\S+) uid:(\\d+)").matcher(ln);
                if (pm.find()) uidPkg.put(pm.group(2), pm.group(1));
            }
            StringBuilder apps = new StringBuilder();
            Matcher um = Pattern.compile("(?m)^  UID (u0a\\d+|\\d+): ([0-9.]+)").matcher(bs);
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            int n = 0;
            while (um.find() && n < 25) {
                if (!seen.add(um.group(1))) continue;
                String id = um.group(1);
                String uid = id.startsWith("u0a") ? String.valueOf(10000 + Integer.parseInt(id.substring(3))) : id;
                String pkg = uid.equals("1000") ? "Android system" : uid.equals("0") ? "root processes" : uidPkg.containsKey(uid) ? uidPkg.get(uid) : "uid " + id;
                apps.append(apps.length() > 0 ? "," : "").append(pkg.replace(',', '_').replace('=', '_')).append('=').append(um.group(2));
                n++;
            }
            String line = System.currentTimeMillis() + ";" + drain + ";" + HeatReport.grab(sect, "screen") + ";" + HeatReport.grab(sect, "cpu") + ";" + HeatReport.grab(sect, "mobile_radio")
                    + ";" + HeatReport.grab(sect, "gnss") + ";" + HeatReport.grab(sect, "audio") + ";" + HeatReport.grab(sect, "video") + ";" + HeatReport.grab(sect, "camera") + ";" + apps + "\n";
            File f = new File(c.getFilesDir(), "heat_snap.csv");
            try (FileWriter w = new FileWriter(f, true)) { w.write(line); }
            if (f.length() > 600_000L) {
                String all = new String(java.nio.file.Files.readAllBytes(f.toPath()));
                String keep = all.substring(all.length() / 2);
                java.nio.file.Files.write(f.toPath(), keep.substring(keep.indexOf('\n') + 1).getBytes());
            }
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ the graph's data

    static final String[] CAT = {"Screen", "CPU", "Mobile radio", "GPS", "Audio and video", "Everything else"};

    static final class Data {
        long from, to;
        List<double[]> samples = new ArrayList<>();        // ts, W, battery C, cpu C, charging
        List<double[]> bars = new ArrayList<>();           // t0, t1, then average W for each of the 6 causes
        double[] catMah = new double[6];
        LinkedHashMap<String, Double> apps = new LinkedHashMap<>();
        double totalMah;
        int snaps;
    }

    private static double d(String s) { return HeatReport.num(s, 0); }

    static Data build(Context c, long windowMs) {
        long now = System.currentTimeMillis();
        Data out = new Data();
        out.from = now - windowMs; out.to = now;
        try {
            for (String ln : HeatReport.sh("cat " + SUI_FILE + " /data/user/0/com.android.systemui/files/fa_heat.csv 2>/dev/null").split("\n")) {
                String[] p = ln.split(",", -1);
                if (p.length < 5) continue;
                long ts = (long) d(p[0]);
                if (ts < out.from) continue;
                out.samples.add(new double[]{ts, p[1].isEmpty() ? Double.NaN : d(p[1]), p[2].isEmpty() ? Double.NaN : d(p[2]), p[3].isEmpty() ? Double.NaN : d(p[3]), d(p[4])});
            }
        } catch (Throwable ignored) { }
        List<Object[]> snaps = new ArrayList<>();            // ts, double[8] hw, map apps
        try {
            File f = new File(c.getFilesDir(), "heat_snap.csv");
            if (f.exists()) for (String ln : new String(java.nio.file.Files.readAllBytes(f.toPath())).split("\n")) {
                String[] p = ln.split(";", -1);
                if (p.length < 10) continue;
                double[] hw = new double[8];
                for (int k = 0; k < 8; k++) hw[k] = d(p[1 + k]);
                LinkedHashMap<String, Double> ap = new LinkedHashMap<>();
                for (String e : p[9].split(",")) { int q = e.lastIndexOf('='); if (q > 0) ap.put(e.substring(0, q), d(e.substring(q + 1))); }
                snaps.add(new Object[]{(long) d(p[0]), hw, ap});
            }
        } catch (Throwable ignored) { }
        snaps.sort((a, b) -> Long.compare((Long) a[0], (Long) b[0]));
        out.snaps = snaps.size();
        for (int i = 1; i < snaps.size(); i++) {
            Object[] a = snaps.get(i - 1), b = snaps.get(i);
            long t0 = (Long) a[0], t1 = (Long) b[0];
            if (t1 < out.from) continue;
            double[] ha = (double[]) a[1], hb = (double[]) b[1];
            boolean reset = hb[0] < ha[0] - 1;
            double[] dh = new double[8];
            for (int k = 0; k < 8; k++) dh[k] = reset ? hb[k] : Math.max(0, hb[k] - ha[k]);
            double hours = Math.max(0.05, (t1 - t0) / 3.6e6);
            double[] cat = {dh[1], dh[2], dh[3], dh[4], dh[5] + dh[6] + dh[7], 0};
            cat[5] = Math.max(0, dh[0] - cat[0] - cat[1] - cat[2] - cat[3] - cat[4]);
            double[] bar = new double[8];
            bar[0] = t0; bar[1] = t1;
            for (int k = 0; k < 6; k++) { bar[2 + k] = cat[k] / 1000.0 * 3.85 / hours; out.catMah[k] += cat[k]; }
            out.totalMah += dh[0];
            out.bars.add(bar);
            @SuppressWarnings("unchecked") LinkedHashMap<String, Double> aa = (LinkedHashMap<String, Double>) a[2], ab = (LinkedHashMap<String, Double>) b[2];
            for (java.util.Map.Entry<String, Double> e : ab.entrySet()) {
                double prev = aa.containsKey(e.getKey()) ? aa.get(e.getKey()) : 0;
                double dv = reset ? e.getValue() : Math.max(0, e.getValue() - prev);
                if (dv > 0) out.apps.merge(e.getKey(), dv, Double::sum);
            }
        }
        return out;
    }
}
