package com.netspeed.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.SystemClock;
import android.telephony.TelephonyManager;

import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * Weak-5G fallback with a 4G quota guard, for plans where 5G is unlimited and 4G has a daily cap.
 *
 * System UI side (every band reading, ~5 s, screen on only):
 *   - data SIM on 5G, weak for 20 s  -> ask the phone service to leave 5G out (4G)
 *   - after 3 min on the fallback (6, 12 ... up to 24 if 5G keeps failing) -> allow 5G again to retry
 *   - 4G data used today (counted whenever the data SIM is on 4G) reaches the limit -> no more fallback, 5G stays
 * Phone service side: a receiver (sender must hold MODIFY_PHONE_STATE) that changes the allowed network types.
 */
final class NrFallback {
    private NrFallback() {}

    static final String ACTION = "com.netspeed.xposed.SET_NR";
    private static final String TAG = "NetSpeedXposed: [nrfallback] ";

    // ---- System UI side
    static volatile boolean active;          // 5G currently left out by us
    static volatile long used4g;             // bytes on 4G today
    static volatile long used5g;             // bytes on 5G today
    static volatile long m4g, m5g;           // bytes on 4G / 5G this calendar month
    private static int monthKey = -1;
    static volatile long nrMs, allMs;        // today: time the data SIM was on 5G / in service (screen on)
    private static long lastTick;
    private static int activeSub = -1;
    private static long weakSince, fellAt, retryMs = 3 * 60_000L, lastSwitch;
    private static long lastBytes = -1;
    private static int day = -1;
    private static boolean loaded, restored;
    private static int forcedMode, forcedSub = -1;       // manual 5G / 4G choice we have applied (0 = automatic)
    private static int noNet;                            // internet checks in a row that failed on 5G
    private static long lastNetCheck, lastRx = -1;
    private static String autoKey = "";                  // subs + on/off the auto data switch was last sent for
    private static long savedAt;

    private static java.io.File file(Context c) { return new java.io.File(c.getCacheDir(), "nr_fallback.txt"); }

    private static void load(Context c) {
        if (loaded) return;
        loaded = true;
        try {
            String[] p = new String(java.nio.file.Files.readAllBytes(file(c).toPath()), "UTF-8").trim().split(",");
            if (Integer.parseInt(p[0]) == today()) {
                day = today();
                used4g = Long.parseLong(p[1]);
                if (p.length > 3) { nrMs = Long.parseLong(p[2]); allMs = Long.parseLong(p[3]); }
                if (p.length > 4) used5g = Long.parseLong(p[4]);
            }
            if (p.length > 7 && Integer.parseInt(p[5]) == month()) {
                monthKey = month();
                m4g = Long.parseLong(p[6]);
                m5g = Long.parseLong(p[7]);
            }
        } catch (Throwable ignored) { }
    }

    private static void save(Context c) {
        try { java.nio.file.Files.write(file(c).toPath(), (day + "," + used4g + "," + nrMs + "," + allMs + "," + used5g + "," + month() + "," + m4g + "," + m5g).getBytes("UTF-8")); } catch (Throwable ignored) { }
    }

    private static int month() {
        java.util.Calendar k = java.util.Calendar.getInstance();
        return k.get(java.util.Calendar.YEAR) * 100 + k.get(java.util.Calendar.MONTH) + 1;
    }

    private static int today() {
        java.util.Calendar k = java.util.Calendar.getInstance();
        return k.get(java.util.Calendar.YEAR) * 1000 + k.get(java.util.Calendar.DAY_OF_YEAR);
    }

    /** Share of today's in-service time on 5G, 0-100, or -1 before 10 minutes of data. */
    static int nrShare() {
        return allMs < 10 * 60_000L ? -1 : (int) Math.round(nrMs * 100.0 / allMs);
    }

    static long limitBytes() {
        return Math.max(100, Cfg.i(Cfg.NR_QUOTA_MB, Cfg.D_NR_QUOTA_MB)) * 1_000_000L;
    }

    /** Worker thread with each fresh SIM reading. */
    static void tick(Context ctx, List<Bands.Sim> sims, int dataSlot) {
        load(ctx);
        long now = SystemClock.elapsedRealtime();
        if (day != today()) { day = today(); used4g = 0; used5g = 0; nrMs = 0; allMs = 0; save(ctx); }
        if (monthKey != month()) { monthKey = month(); m4g = 0; m5g = 0; save(ctx); }
        Bands.Sim d = null;
        for (Bands.Sim s : sims) if (s.slot == dataSlot) d = s;

        // count 4G usage on the data SIM
        long b = TrafficStats.getMobileRxBytes() + TrafficStats.getMobileTxBytes();
        boolean on4g = d != null && d.band != null && d.band.startsWith("B");
        if (lastTick > 0 && now - lastTick < 30_000 && d != null && d.inService) {
            allMs += now - lastTick;
            if (d.band != null && d.band.startsWith("n")) nrMs += now - lastTick;
        }
        lastTick = now;
        if (lastBytes >= 0 && b >= lastBytes && on4g) { used4g += b - lastBytes; m4g += b - lastBytes; }
        if (lastBytes >= 0 && b >= lastBytes && d != null && d.band != null && d.band.startsWith("n")) { used5g += b - lastBytes; m5g += b - lastBytes; }
        lastBytes = b;
        if (now - savedAt > 60_000) { savedAt = now; save(ctx); }

        // Android's own "switch mobile data automatically" for every SIM we can see (setting kept in the phone service)
        try {
            boolean wantAuto = Cfg.b(Cfg.AUTO_SIM, Cfg.D_AUTO_SIM);
            StringBuilder subs = new StringBuilder(wantAuto ? "1:" : "0:");
            for (Bands.Sim s : sims) if (s.sub >= 0) subs.append(s.sub).append(',');
            String key = subs.toString();
            if (!key.equals(autoKey)) {
                boolean first = autoKey.isEmpty();
                autoKey = key;
                if (wantAuto || !first) for (Bands.Sim s : sims) if (s.sub >= 0) sendAuto(ctx, s.sub, wantAuto);
            }
        } catch (Throwable t) { XposedBridge.log(TAG + "auto data switch: " + t); }

        boolean enabled = Cfg.b(Cfg.NR_FALLBACK, Cfg.D_NR_FALLBACK);
        boolean overQuota = used4g >= limitBytes();
        if (d == null || d.sub < 0) return;
        // after a System UI restart we don't know what we left switched: always allow 5G once
        if (!restored) { restored = true; send(ctx, d.sub, true); }

        // manual choice: lock 5G allowed / 4G only and leave the automatic part alone until it is set back
        int mode = Cfg.i(Cfg.NR_MODE, Cfg.D_NR_MODE);
        if (mode != 0) {
            if (forcedMode != mode || forcedSub != d.sub) {
                send(ctx, d.sub, mode == 1);
                forcedMode = mode;
                forcedSub = d.sub;
            }
            active = mode == 2;
            activeSub = d.sub;
            weakSince = 0;
            noNet = 0;
            return;
        }
        if (forcedMode != 0) {
            send(ctx, forcedSub >= 0 ? forcedSub : d.sub, true);
            forcedMode = 0;
            forcedSub = -1;
            active = false;
            lastSwitch = now;
            weakSince = 0;
        }

        // pause rules: Wi-Fi, airplane mode or mobile data off means there is nothing to manage
        boolean paused = Cfg.b(Cfg.NR_NETCHECK, Cfg.D_NR_NETCHECK) && pausedNow(ctx, d.sub);
        if (paused) { enabled = false; noNet = 0; }

        if (active) {
            boolean give = !enabled || overQuota || d.sub != activeSub || now - fellAt >= retryMs;
            if (give) {
                send(ctx, activeSub, true);
                active = false;
                lastSwitch = now;
                weakSince = 0;
                if (enabled && !overQuota && d.sub == activeSub) retryMs = Math.min(24 * 60_000L, retryMs * 2);   // 5G failed again soon after: back off
            }
            return;
        }
        if (!enabled || overQuota) { weakSince = 0; return; }
        boolean onNr = d.band != null && d.band.startsWith("n");
        boolean weak = onNr && d.inService && d.dbm != Bands.NO_DBM
                && ((d.dbm < -112 && !Float.isNaN(d.snr) && d.snr < 0) || d.dbm < -118 || (!Float.isNaN(d.snr) && d.snr < -3));
        // a strong-looking 5G that carries no data is bad too: when nothing is coming in, ask the internet (at most once a minute)
        if (onNr && d.inService && Cfg.b(Cfg.NR_NETCHECK, Cfg.D_NR_NETCHECK)) {
            long rx = TrafficStats.getMobileRxBytes();
            long drx = lastRx < 0 ? 0 : Math.max(0, rx - lastRx);
            lastRx = rx;
            if (drx >= 4096) noNet = 0;
            else if (now - lastNetCheck >= 60_000L) {
                lastNetCheck = now;
                if (netOk(ctx)) noNet = 0; else noNet++;
            }
            if (noNet >= 3) weak = true;
        } else {
            noNet = 0;
        }
        if (onNr && !weak && now - lastSwitch > 5 * 60_000L) retryMs = 3 * 60_000L;   // 5G held up: reset back-off
        if (!weak) { weakSince = 0; return; }
        if (weakSince == 0) { weakSince = now; return; }
        if (now - weakSince < 20_000 || now - lastSwitch < 60_000) return;
        send(ctx, d.sub, false);
        active = true;
        activeSub = d.sub;
        fellAt = lastSwitch = now;
        weakSince = 0;
    }

    private static boolean pausedNow(Context ctx, int sub) {
        try {
            if (android.provider.Settings.Global.getInt(ctx.getContentResolver(), "airplane_mode_on", 0) == 1) return true;
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class).createForSubscriptionId(sub);
            if (!tm.isDataEnabled()) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    /** True when a small request over the mobile network gets any answer (a captive page counts: it is reachable). */
    private static boolean netOk(Context ctx) {
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            Network cell = null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) { cell = n; break; }
            }
            if (cell == null) return true;      // nothing to test on: no evidence
            java.net.HttpURLConnection h = (java.net.HttpURLConnection) cell.openConnection(
                    new java.net.URL("http://connectivitycheck.gstatic.com/generate_204"));
            h.setConnectTimeout(3000);
            h.setReadTimeout(3000);
            h.setInstanceFollowRedirects(false);
            h.setUseCaches(false);
            h.getResponseCode();
            h.disconnect();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void sendAuto(Context ctx, int sub, boolean on) {
        try {
            ctx.sendBroadcast(new Intent(ACTION).setPackage("com.android.phone").putExtra("sub", sub).putExtra("autosw", on ? 1 : 0));
            XposedBridge.log(TAG + "auto data switch " + (on ? "on" : "off") + " (sub " + sub + ")");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "auto switch send failed: " + t);
        }
    }

    private static void send(Context ctx, int sub, boolean allowNr) {
        try {
            Intent i = new Intent(ACTION).setPackage("com.android.phone").putExtra("sub", sub).putExtra("nr", allowNr);
            ctx.sendBroadcast(i);
            XposedBridge.log(TAG + (allowNr ? "5G allowed again" : "weak 5G: 4G for now") + " (sub " + sub + ")");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "send failed: " + t);
        }
    }

    // ---- Phone service side
    private static boolean registered;

    static void registerPhone(Context ctx) {
        if (registered) return;
        registered = true;
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent in) {
                    try {
                        int sub = in.getIntExtra("sub", -1);
                        TelephonyManager tm = c.getSystemService(TelephonyManager.class).createForSubscriptionId(sub);
                        if (in.hasExtra("autosw")) {
                            // Android's "switch mobile data automatically" (MOBILE_DATA_POLICY_AUTO_DATA_SWITCH = 3):
                            // remember it was off so that turning our option off puts it back
                            boolean on = in.getIntExtra("autosw", 0) == 1;
                            java.io.File mark = new java.io.File(c.getFilesDir(), "netspeed_autosw_" + sub);
                            java.lang.reflect.Method get = TelephonyManager.class.getMethod("isMobileDataPolicyEnabled", int.class);
                            java.lang.reflect.Method set = TelephonyManager.class.getMethod("setMobileDataPolicyEnabled", int.class, boolean.class);
                            boolean cur = (Boolean) get.invoke(tm, 3);
                            if (on && !cur) { mark.createNewFile(); set.invoke(tm, 3, true); }
                            else if (!on && mark.exists()) { set.invoke(tm, 3, false); mark.delete(); }
                            XposedBridge.log(TAG + "sub " + sub + ": auto data switch " + (on ? "on" : "off") + " (was " + cur + ")");
                            return;
                        }
                        boolean nr = in.getBooleanExtra("nr", true);
                        long cur = tm.getAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER);
                        long want = nr ? cur | TelephonyManager.NETWORK_TYPE_BITMASK_NR : cur & ~TelephonyManager.NETWORK_TYPE_BITMASK_NR;
                        if (want != cur) tm.setAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER, want);
                        XposedBridge.log(TAG + "sub " + sub + (nr ? ": 5G allowed" : ": 5G left out"));
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "switch failed: " + t);
                    }
                }
            };
            // only privileged senders (System UI) can reach it
            ctx.registerReceiver(r, new IntentFilter(ACTION), "android.permission.MODIFY_PHONE_STATE", null,
                    Context.RECEIVER_EXPORTED);
            XposedBridge.log(TAG + "phone service receiver ready");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "receiver failed: " + t);
        }
    }
}
