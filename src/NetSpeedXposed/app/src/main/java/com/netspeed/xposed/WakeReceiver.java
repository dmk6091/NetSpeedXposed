package com.netspeed.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Reads the kernel wakelock held longest (needs root, which this app has and System UI does not) and
 * leaves the result in Settings.Global "netspeed_wake" for the status bar to read. System UI asks for
 * a refresh every few minutes with the broadcast com.netspeed.xposed.WAKE_READ.
 */
public final class WakeReceiver extends BroadcastReceiver {
    static final String KEY = "netspeed_wake";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String best = read();
                    String v = best.replaceAll("[^A-Za-z0-9_.:-]", "");
                    su("settings put global " + KEY + " '" + v + "'");
                } catch (Throwable ignored) {
                } finally {
                    pr.finish();
                }
            }
        }, "netspeed-wake").start();
    }

    private static String read() throws Exception {
        Process p = new ProcessBuilder("su", "-c", "dumpsys batterystats | grep -A14 'All kernel wake locks'")
                .redirectErrorStream(true).start();
        String best = "";
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                int k = line.indexOf("Kernel Wake lock ");
                if (k < 0) continue;
                String rest = line.substring(k + 17);
                int c = rest.indexOf(": ");
                if (c <= 0) continue;
                String name = rest.substring(0, c);
                if (name.startsWith("PowerManager") || name.trim().isEmpty()) continue;
                String dur = rest.substring(c + 2);
                int paren = dur.indexOf(" (");
                if (paren > 0) dur = dur.substring(0, paren);
                String[] t = dur.trim().split(" ");
                String d = t.length >= 2 && t[0].endsWith("h") ? t[0] + t[1] : t[0];
                best = (name.length() > 6 ? name.substring(0, 6) : name) + d;
                break;
            }
        } finally {
            p.destroy();
        }
        return best;
    }

    private static void su(String cmd) throws Exception {
        Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
        p.waitFor();
    }
}
