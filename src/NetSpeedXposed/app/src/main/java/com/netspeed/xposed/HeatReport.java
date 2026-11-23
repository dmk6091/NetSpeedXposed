package com.netspeed.xposed;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heat and power report. Reads real numbers with root: battery current and voltage, the hottest sensors, the screen,
 * what each process is doing right now, and what each app has used since the last charge. The causes it lists are
 * ranked from those numbers; it does not invent watts per cause.
 */
final class HeatReport {
    private HeatReport() {}

    private static String sh(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) bo.write(b, 0, n); }
            p.waitFor();
            return bo.toString("UTF-8");
        } catch (Throwable t) { return ""; }
    }

    private static double num(String s, double def) { try { return Double.parseDouble(s.trim()); } catch (Throwable t) { return def; } }

    /** pid -> {name, ticks}, from one read of every process stat file. */
    private static HashMap<Integer, Object[]> procTicks() {
        HashMap<Integer, Object[]> m = new HashMap<>();
        String s = sh("cat /proc/[0-9]*/stat 2>/dev/null");
        for (String ln : s.split("\n")) {
            int a = ln.indexOf('('), b = ln.lastIndexOf(')');
            if (a < 0 || b < a) continue;
            try {
                int pid = Integer.parseInt(ln.substring(0, a).trim());
                String[] f = ln.substring(b + 2).split(" ");
                long t = Long.parseLong(f[11]) + Long.parseLong(f[12]);
                m.put(pid, new Object[]{ln.substring(a + 1, b), t});
            } catch (Throwable ignored) { }
        }
        return m;
    }

    static String build(Context c, String simLive) {
        StringBuilder out = new StringBuilder();
        List<String> causes = new ArrayList<>();
        // ---- power and temperature now
        double cur = num(sh("cat /sys/class/power_supply/battery/current_now"), 0), volt = num(sh("cat /sys/class/power_supply/battery/voltage_now"), 0);
        double batT = num(sh("cat /sys/class/power_supply/battery/temp"), 0) / 10.0;
        String status = sh("cat /sys/class/power_supply/battery/status").trim();
        double watts = Math.abs(cur) * volt / 1e12;
        String zones = sh("for z in /sys/class/thermal/thermal_zone*; do echo \"$(cat $z/type) $(cat $z/temp)\"; done 2>/dev/null");
        double cpuT = 0, gpuT = 0, skinT = 0, modemT = 0;
        for (String ln : zones.split("\n")) {
            String[] p = ln.trim().split(" ");
            if (p.length < 2) continue;
            double v = num(p[p.length - 1], 0); if (v > 1000) v /= 1000.0;
            String t = ln.toLowerCase(Locale.ROOT);
            if (t.startsWith("cpu") || t.startsWith("cpuss")) cpuT = Math.max(cpuT, v);
            else if (t.startsWith("gpu")) gpuT = Math.max(gpuT, v);
            else if (t.contains("skin") || t.contains("quiet") || t.contains("xo-therm")) skinT = Math.max(skinT, v);
            else if (t.contains("modem") || t.contains("mdm") || t.contains("qmi")) modemT = Math.max(modemT, v);
        }
        out.append("RIGHT NOW\n");
        out.append(String.format(Locale.US, "  Power: %.1f W (%s, %.0f mA)\n", watts, status.isEmpty() ? "?" : status, Math.abs(cur) / 1000.0));
        out.append(String.format(Locale.US, "  Battery %.1f°  CPU %.0f°  GPU %.0f°%s%s\n", batT, cpuT, gpuT, skinT > 0 ? String.format(Locale.US, "  skin %.0f°", skinT) : "", modemT > 0 ? String.format(Locale.US, "  modem %.0f°", modemT) : ""));
        boolean hot = batT >= 42 || cpuT >= 70;
        boolean heavy = watts >= 6;
        out.append(hot || heavy ? "  Verdict: " + (hot ? "running hot" : "") + (hot && heavy ? " and " : "") + (heavy ? "drawing a lot of power" : "") + "\n"
                : "  Verdict: normal at this moment\n");
        // ---- screen
        String br = sh("settings get system screen_brightness").trim(), auto = sh("settings get system screen_brightness_mode").trim();
        String maxS = sh("dumpsys power | grep -m1 -o 'mScreenBrightnessSettingMaximum=[0-9]*'").trim();
        double brMax = maxS.contains("=") ? num(maxS.substring(maxS.indexOf('=') + 1), 255) : 255;
        double brPct = brMax > 0 ? num(br, 0) / brMax * 100 : -1;
        String rate = sh("dumpsys display | grep -m1 -o 'refreshRate=[0-9.]*'").trim();
        String screenOn = sh("dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*'").trim();
        boolean on = screenOn.contains("Awake");
        out.append(String.format(Locale.US, "  Screen: %s, brightness %s%s, %s\n", on ? "on" : "off", br, brPct >= 0 ? String.format(Locale.US, " (%.0f%% of the setting range)", brPct) : "", auto.equals("1") ? "auto" : "manual"));
        if (!rate.isEmpty()) out.append("  Refresh ").append(rate.replace("refreshRate=", "")).append(" Hz\n");
        // ---- what the processes are doing now (3 s)
        HashMap<Integer, Object[]> a = procTicks();
        long t0 = System.nanoTime();
        try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
        HashMap<Integer, Object[]> b = procTicks();
        double dt = (System.nanoTime() - t0) / 1e9;
        List<Object[]> busy = new ArrayList<>();
        for (java.util.Map.Entry<Integer, Object[]> e : b.entrySet()) {
            Object[] o = a.get(e.getKey());
            if (o == null) continue;
            double pct = ((Long) e.getValue()[1] - (Long) o[1]) / 100.0 / dt * 100.0;
            if (pct >= 2) busy.add(new Object[]{pct, e.getValue()[0], e.getKey()});
        }
        busy.sort((x, y) -> Double.compare((Double) y[0], (Double) x[0]));
        out.append("\nBUSIEST PROCESSES (share of one core, last 3 s)\n");
        if (busy.isEmpty()) out.append("  nothing above 2%\n");
        for (int i = 0; i < Math.min(8, busy.size()); i++) {
            String nm = sh("cat /proc/" + busy.get(i)[2] + "/cmdline | tr '\\0' ' '").trim();
            if (nm.isEmpty()) nm = (String) busy.get(i)[1];
            out.append(String.format(Locale.US, "  %.0f%%  %s\n", (Double) busy.get(i)[0], nm));
        }
        double suiPct = 0; String top1 = "";
        for (Object[] o : busy) {
            String nm = sh("cat /proc/" + o[2] + "/cmdline | tr '\\0' ' '").trim();
            if (nm.startsWith("com.android.systemui")) suiPct = (Double) o[0];
            if (top1.isEmpty() && !nm.isEmpty() && !nm.startsWith("/") && !nm.startsWith("surfaceflinger") && !nm.contains("kworker")) top1 = nm + " " + Math.round((Double) o[0]) + "%";
        }
        // ---- since the last charge
        String bs = sh("dumpsys batterystats");
        double drain = 0, screenMah = 0, cpuMah = 0, radioMah = 0, gnssMah = 0, audioMah = 0, videoMah = 0, camMah = 0;
        String gnssDur = "", screenOnDur = "";
        Matcher m = Pattern.compile("Computed drain: ([0-9.]+)").matcher(bs);
        if (m.find()) drain = num(m.group(1), 0);
        String sect = bs.contains("Estimated power use") ? bs.substring(bs.indexOf("Estimated power use"), Math.min(bs.length(), bs.indexOf("Estimated power use") + 900)) : "";
        screenMah = grab(sect, "screen"); cpuMah = grab(sect, "cpu"); radioMah = grab(sect, "mobile_radio");
        gnssMah = grab(sect, "gnss"); audioMah = grab(sect, "audio"); videoMah = grab(sect, "video"); camMah = grab(sect, "camera");
        Matcher gd = Pattern.compile("gnss: [0-9.]+ apps: [0-9.]+ duration: ([^\\n]+)").matcher(sect);
        if (gd.find()) gnssDur = gd.group(1).trim();
        out.append("\nSINCE THE LAST CHARGE\n");
        out.append(String.format(Locale.US, "  Total used: %.0f mAh\n", drain));
        String[][] hw = {{"CPU", "" + cpuMah}, {"Screen", "" + screenMah}, {"Mobile data radio", "" + radioMah}, {"Video", "" + videoMah}, {"Audio", "" + audioMah}, {"GPS", "" + gnssMah + (gnssDur.isEmpty() ? "" : " (" + gnssDur + ")")}, {"Camera", "" + camMah}};
        for (String[] h : hw) {
            double v = num(h[1].split(" ")[0], 0);
            if (v >= 5) out.append(String.format(Locale.US, "  %-18s %.0f mAh%s\n", h[0], v, drain > 0 ? String.format(Locale.US, "  (%.0f%%)", v / drain * 100) : "")).append("");
        }
        // apps by drain
        HashMap<String, String> uidPkg = new HashMap<>();
        for (String ln : sh("pm list packages -U").split("\n")) {
            Matcher pm = Pattern.compile("package:(\\S+) uid:(\\d+)").matcher(ln);
            if (pm.find()) uidPkg.put(pm.group(2), pm.group(1));
        }
        List<Object[]> apps = new ArrayList<>();
        Matcher um = Pattern.compile("(?m)^  UID (u0a\\d+|\\d+): ([0-9.]+)( fg: ([0-9.]+) \\(([^)]*)\\))?( bg: ([0-9.]+))?( fgs: ([0-9.]+) \\(([^)]*)\\))?").matcher(bs);
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        while (um.find()) {
            if (!seen.add(um.group(1))) continue;
            String id = um.group(1);
            String uid = id.startsWith("u0a") ? String.valueOf(10000 + Integer.parseInt(id.substring(3))) : id;
            String pkg = uidPkg.containsKey(uid) ? uidPkg.get(uid) : "uid " + id;
            apps.add(new Object[]{num(um.group(2), 0), pkg, um.group(5) == null ? "" : um.group(5), um.group(10) == null ? "" : um.group(10)});
        }
        apps.sort((x, y) -> Double.compare((Double) y[0], (Double) x[0]));
        out.append("\nAPPS BY BATTERY USE\n");
        for (int i = 0; i < Math.min(8, apps.size()); i++) {
            Object[] o = apps.get(i);
            out.append(String.format(Locale.US, "  %.0f mAh%s  %s%s%s\n", (Double) o[0], drain > 0 ? String.format(Locale.US, " (%.0f%%)", (Double) o[0] / drain * 100) : "", o[1],
                    ((String) o[2]).isEmpty() ? "" : "  on screen " + o[2], ((String) o[3]).isEmpty() ? "" : "  in background service " + o[3]));
        }
        // wake locks
        out.append("\nHELD THE PHONE AWAKE (longest)\n");
        String wl = bs.contains("All partial wake locks:") ? bs.substring(bs.indexOf("All partial wake locks:")) : "";
        int cnt = 0;
        for (String ln : wl.split("\n")) {
            if (cnt >= 5) break;
            String t = ln.trim();
            if (!t.startsWith("Wake lock")) continue;
            if (t.length() > 150) t = t.substring(0, 150) + "…";
            out.append("  ").append(t).append("\n"); cnt++;
        }
        if (cnt == 0) out.append("  none recorded\n");
        // ---- the signal
        StringBuilder sig = new StringBuilder(); boolean weak = false;
        for (String ln : simLive.split("\n")) {
            if (!ln.startsWith("SIMLIVE")) continue;
            Matcher dm = Pattern.compile("dbm=(-?\\d+)").matcher(ln), rm = Pattern.compile("rat=([^;]*)").matcher(ln), sm = Pattern.compile("SIMLIVE\\|(\\d+)").matcher(ln);
            if (dm.find()) {
                int d = Integer.parseInt(dm.group(1));
                sig.append(String.format(Locale.US, "  SIM %s: %s at %d dBm%s\n", sm.find() ? sm.group(1) : "?", rm.find() ? rm.group(1) : "?", d, d <= -100 ? "  (weak, the modem works harder)" : ""));
                if (d <= -100) weak = true;
            }
        }
        if (sig.length() > 0) out.append("\nSIGNAL\n").append(sig);
        // ---- ranked causes
        if (on && brPct >= 70 && watts > 0) causes.add(String.format(Locale.US, "Screen at high brightness (%.0f%% of range%s%s). The screen used %.0f mAh since the last charge.", brPct, rate.isEmpty() ? "" : ", " + rate.replace("refreshRate=", "") + " Hz", auto.equals("1") ? ", auto brightness" : "", screenMah));
        if (weak) causes.add("Weak mobile signal on at least one SIM. The mobile data radio used " + Math.round(radioMah) + " mAh since the last charge.");
        if (!apps.isEmpty() && drain > 0 && (Double) apps.get(0)[0] / drain >= 0.25) causes.add(apps.get(0)[1] + " used " + Math.round((Double) apps.get(0)[0]) + " mAh (" + Math.round((Double) apps.get(0)[0] / drain * 100) + "% of all use since the last charge)" + (!((String) apps.get(0)[3]).isEmpty() ? ", including " + apps.get(0)[3] + " as a background service" : "") + ".");
        if (suiPct >= 12) causes.add(String.format(Locale.US, "System UI (status bar and lock screen) is using %.0f%% of a core right now.", suiPct));
        if (!top1.isEmpty() && !top1.startsWith("com.android.systemui")) { String[] pp = top1.split(" "); if (num(pp[pp.length - 1].replace("%", ""), 0) >= 25) causes.add("Busiest process right now: " + top1 + ".");}
        if (gnssMah >= 40) causes.add("GPS has been on for " + (gnssDur.isEmpty() ? "a while" : gnssDur) + " (" + Math.round(gnssMah) + " mAh).");
        if (videoMah + audioMah >= 150) causes.add("Video and audio playback used " + Math.round(videoMah + audioMah) + " mAh since the last charge.");
        if (status.equalsIgnoreCase("Charging") && on) causes.add("Charging while the screen is on adds heat.");
        if (cpuT >= 60) causes.add(String.format(Locale.US, "The chip is already at %.0f°, and a hot chip leaks more current, which makes it hotter.", cpuT));
        out.insert(0, "LIKELY CAUSES (from the readings below)\n" + (causes.isEmpty() ? "  nothing stands out right now. Run this again while the phone is hot.\n" : numbered(causes)) + "\n");
        return out.toString();
    }

    private static String numbered(List<String> l) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < l.size(); i++) s.append("  ").append(i + 1).append(". ").append(l.get(i)).append("\n");
        return s.toString();
    }

    private static double grab(String sect, String key) {
        Matcher m = Pattern.compile("(?m)^    " + key + ": ([0-9.]+)").matcher(sect);
        return m.find() ? num(m.group(1), 0) : 0;
    }
}
