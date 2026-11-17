package com.netspeed.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Reads the battery's health with root (this app has it, System UI does not) and leaves it in Settings.Global
 * "netspeed_battery_soh" for the status bar, plus a short note on where it came from in "netspeed_battery_why"
 * (shown in Live diagnostics). System UI asks with the broadcast com.netspeed.xposed.BATT_HEALTH.
 */
public final class BattHealthReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                String cmd = "for f in /sys/class/qcom-battery/soh /sys/class/power_supply/*/soh /sys/class/power_supply/*/battery_soh "
                        + "/sys/class/power_supply/*/state_of_health /sys/class/oplus_chg/battery/battery_soh; do "
                        + "[ -r $f ] && echo \"SOH $f $(cat $f)\"; done; "
                        + "for d in /sys/class/power_supply/battery /sys/class/power_supply/bms /sys/class/qcom-battery; do "
                        + "echo \"FULL $d $(cat $d/charge_full 2>/dev/null) $(cat $d/charge_full_design 2>/dev/null)\"; done; "
                        + "echo FILES; ls /sys/class/qcom-battery /sys/class/power_supply/battery /sys/class/power_supply/bms 2>/dev/null | tr '\\n' ' '";
                Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
                StringBuilder out = new StringBuilder();
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                    String ln;
                    while ((ln = br.readLine()) != null) out.append(ln).append('\n');
                } finally {
                    p.destroy();
                }
                int found = -1;
                String src = "";
                String[] lines = out.toString().split("\n");
                for (String ln : lines) {
                    String[] w = ln.trim().split("\\s+");
                    if (found <= 0 && w.length >= 3 && w[0].equals("SOH")) {
                        int v = num(w[2]);
                        if (v > 0 && v <= 120) { found = v; src = w[1]; }
                    }
                }
                if (found <= 0) {
                    for (String ln : lines) {
                        String[] w = ln.trim().split("\\s+");
                        if (w.length >= 4 && w[0].equals("FULL")) {
                            int full = num(w[2]), design = num(w[3]);
                            int v = full > 0 && design > 0 ? Math.round(full * 100f / design) : -1;
                            if (v > 0 && v <= 120) { found = v; src = w[1] + " full/design"; break; }
                        }
                    }
                }
                String o = out.toString().replace('\n', '|');
                String why = found > 0 ? "root: " + found + "% from " + src
                        : "root gave no value: " + (o.length() > 300 ? o.substring(0, 300) : o);
                why = why.replaceAll("[^A-Za-z0-9_.:/|=% -]", "");
                if (found > 0) su("settings put global netspeed_battery_soh " + found);
                su("settings put global netspeed_battery_why '" + why + "'");
            } catch (Throwable t) {
                try { su("settings put global netspeed_battery_why 'root failed'"); } catch (Throwable ignored) { }
            } finally {
                pr.finish();
            }
        }, "netspeed-batt-health").start();
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return -1; }
    }

    private static void su(String cmd) throws Exception {
        Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
        p.waitFor();
    }
}
