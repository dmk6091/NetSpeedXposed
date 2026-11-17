package com.netspeed.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * Band locking per SIM: "B3 B40 n78" in settings -> the modem may only use those LTE (B) / 5G NR (n) bands.
 * Uses the standard radio call TelephonyManager.setSystemSelectionChannels (Android 11+), run inside the phone
 * service, which holds MODIFY_PHONE_STATE. Nothing in the modem is written: an empty field, a reboot or a radio
 * restart puts every band back; System UI re-sends the choice every 10 minutes and whenever it changes.
 */
final class BandLock {
    private static final String TAG = "NetSpeedXposed: [bands] ";
    static final String ACTION = "com.netspeed.xposed.SET_BANDS";
    private static String lastSent = null;
    private static long lastAt = 0;

    private BandLock() {}

    // ---- System UI side: nudge the phone service when the choice changes (and every 10 min)
    static void tick(Context ctx) {
        String want = Cfg.s(Cfg.BANDS1) + "|" + Cfg.s(Cfg.BANDS2);
        long now = System.currentTimeMillis();
        boolean any = !want.equals("|");
        if (want.equals(lastSent) && (!any || now - lastAt < 10 * 60_000L)) return;
        lastSent = want;
        lastAt = now;
        try {
            ctx.sendBroadcast(new Intent(ACTION).setPackage("com.android.phone")
                    .putExtra("b1", Cfg.s(Cfg.BANDS1)).putExtra("b2", Cfg.s(Cfg.BANDS2)));
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
                    StringBuilder rep = new StringBuilder();
                    for (int slot = 0; slot < 2; slot++) {
                        String spec = in.getStringExtra(slot == 0 ? "b1" : "b2");
                        try { rep.append(apply(c, slot, spec == null ? "" : spec)).append('\n'); }
                        catch (Throwable t) { rep.append("SIM").append(slot + 1).append(": failed ").append(t).append('\n'); }
                    }
                    try { android.provider.Settings.Global.putString(c.getContentResolver(), "netspeed_band_status", rep.toString()); }
                    catch (Throwable ignored) { }
                    XposedBridge.log(TAG + rep.toString().replace('\n', ';'));
                }
            };
            ctx.registerReceiver(r, new IntentFilter(ACTION), "android.permission.MODIFY_PHONE_STATE", null,
                    Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "receiver failed: " + t);
        }
    }

    /** "B3 B40 n78" -> {lte bands}, {nr bands}. */
    static int[][] parse(String spec) {
        List<Integer> lte = new ArrayList<>(), nr = new ArrayList<>();
        for (String t : spec.toLowerCase(Locale.US).split("[\\s,;+/]+")) {
            if (t.isEmpty()) continue;
            try {
                if (t.startsWith("n")) nr.add(Integer.parseInt(t.substring(1)));
                else if (t.startsWith("b")) lte.add(Integer.parseInt(t.substring(1)));
                else lte.add(Integer.parseInt(t));
            } catch (NumberFormatException ignored) { }
        }
        int[] a = new int[lte.size()], b = new int[nr.size()];
        for (int i = 0; i < a.length; i++) a[i] = lte.get(i);
        for (int i = 0; i < b.length; i++) b[i] = nr.get(i);
        return new int[][]{a, b};
    }

    private static String apply(Context c, int slot, String spec) throws Exception {
        android.telephony.SubscriptionManager sm = c.getSystemService(android.telephony.SubscriptionManager.class);
        android.telephony.SubscriptionInfo si = sm.getActiveSubscriptionInfoForSimSlotIndex(slot);
        if (si == null) return "SIM" + (slot + 1) + ": no SIM";
        android.telephony.TelephonyManager tm = c.getSystemService(android.telephony.TelephonyManager.class)
                .createForSubscriptionId(si.getSubscriptionId());
        int[][] p = parse(spec);
        List<android.telephony.RadioAccessSpecifier> list = new ArrayList<>();
        if (p[0].length > 0 || p[1].length > 0) {
            // a RAN listed with no bands = all bands of that RAN; so "n78" alone leaves 4G free, "B3" alone leaves 5G free
            list.add(new android.telephony.RadioAccessSpecifier(android.telephony.AccessNetworkConstants.AccessNetworkType.EUTRAN, p[0], null));
            list.add(new android.telephony.RadioAccessSpecifier(android.telephony.AccessNetworkConstants.AccessNetworkType.NGRAN, p[1], null));
            list.add(new android.telephony.RadioAccessSpecifier(android.telephony.AccessNetworkConstants.AccessNetworkType.UTRAN, new int[0], null));
            list.add(new android.telephony.RadioAccessSpecifier(android.telephony.AccessNetworkConstants.AccessNetworkType.GERAN, new int[0], null));
        }
        final String[] res = {"sent"};
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.function.Consumer<Boolean> cb = ok -> { res[0] = ok ? "ok" : "modem refused"; done.countDown(); };
        java.lang.reflect.Method m = tm.getClass().getMethod("setSystemSelectionChannels", List.class,
                java.util.concurrent.Executor.class, java.util.function.Consumer.class);
        m.invoke(tm, list, (java.util.concurrent.Executor) Runnable::run, cb);
        done.await(15, java.util.concurrent.TimeUnit.SECONDS);
        // read back what the modem now uses (Android 12+), so "sent" without a reply can still be confirmed
        String back = "";
        try {
            Object r = tm.getClass().getMethod("getSystemSelectionChannels").invoke(tm);
            if (r instanceof List) {
                StringBuilder b = new StringBuilder();
                for (Object o : (List<?>) r) {
                    android.telephony.RadioAccessSpecifier ras = (android.telephony.RadioAccessSpecifier) o;
                    int[] bands = ras.getBands();
                    if (bands == null || bands.length == 0) continue;
                    String pre = ras.getRadioAccessNetwork() == android.telephony.AccessNetworkConstants.AccessNetworkType.NGRAN ? "n" : "B";
                    for (int x : bands) b.append(pre).append(x).append(' ');
                }
                back = ((List<?>) r).isEmpty() ? " · modem: all bands" : b.length() > 0 ? " · modem: " + b.toString().trim() : " · modem: all bands";
            }
        } catch (Throwable t) {
            back = " · read-back n/a";
        }
        return "SIM" + (slot + 1) + ": " + (list.isEmpty() ? "all bands" : spec.trim()) + " -> " + res[0] + back;
    }
}
