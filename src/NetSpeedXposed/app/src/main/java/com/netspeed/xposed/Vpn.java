package com.netspeed.xposed;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;

/**
 * VPN state for the status bar: is a VPN up, for how long, and the traffic through its tunnel.
 * Android's total traffic counter adds the tunnel and the real network, so with a VPN every byte is
 * counted twice; the speed shown while connected uses the tunnel alone.
 */
final class Vpn {
    private Vpn() {}

    static boolean active;
    static long since;                  // elapsedRealtime when we first saw it up
    static String iface;                // tunnel interface (tun0, wg0 ...)
    private static long checkedAt;
    private static boolean warned;

    /** Refresh at most every 2 s. */
    static void update(Context ctx) {
        long now = SystemClock.elapsedRealtime();
        if (now - checkedAt < 2000) return;
        checkedAt = now;
        boolean up = false;
        String name = null;
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    up = true;
                    LinkProperties lp = cm.getLinkProperties(n);
                    if (lp != null) name = lp.getInterfaceName();
                    break;
                }
            }
        } catch (Throwable ignored) { }
        if (up && !active) since = now;
        active = up;
        iface = up ? name : null;
    }

    /** Bytes through the tunnel, or -1 if unreadable. dir = "rx" or "tx". */
    static long bytes(String dir) {
        if (iface == null) return -1;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/sys/class/net/" + iface + "/statistics/" + dir + "_bytes"))) {
            return Long.parseLong(r.readLine().trim());
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                de.robv.android.xposed.XposedBridge.log("NetSpeedXposed: [vpn] cannot read " + iface + " counters: " + t);
            }
            return -1;
        }
    }

    /** "14m", "2h14m", "<1m". */
    static String age() {
        long m = (SystemClock.elapsedRealtime() - since) / 60000;
        if (m < 1) return "<1m";
        return m < 60 ? m + "m" : (m / 60) + "h" + (m % 60 < 10 ? "0" : "") + (m % 60) + "m";
    }
}
