package com.netspeed.xposed;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Smart carrier name on the System UI side (quick settings, shade header, lock screen): System UI reads
 * each SIM's SubscriptionInfo.getCarrierName(); this answers with
 *   - the network's own fuller name at home ("Jio True5G" instead of "Jio"),
 *   - "SIM name · network name" on another state's network ("Jio · IND airtel").
 * Names come from the status bar's SIM reading (Bands), so no phone-service hook is needed.
 */
final class SmartCarrier {
    private SmartCarrier() {}

    /** subscription id -> name to show (absent = leave Android's). */
    static final Map<Integer, String> BY_SUB = new ConcurrentHashMap<>();

    /** Who listens for subscription changes in this process (System UI's lock screen / shade carrier text), with their executors. */
    private static final java.util.Map<Object, java.util.concurrent.Executor> LISTENERS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static long lastNudge = 0;

    /**
     * System UI reads getCarrierName() only when the subscription changes (what airplane mode causes). When the
     * name we answer with changes, fire those same listeners so the carrier text is read again.
     */
    private static void nudge(String why) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastNudge < 3000) return;
        lastNudge = now;
        final java.util.List<java.util.Map.Entry<Object, java.util.concurrent.Executor>> all;
        synchronized (LISTENERS) { all = new java.util.ArrayList<>(LISTENERS.entrySet()); }
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        int n = 0;
        for (final java.util.Map.Entry<Object, java.util.concurrent.Executor> e : all) {
            final Object l = e.getKey();
            Runnable r = () -> { try { XposedHelpers.callMethod(l, "onSubscriptionsChanged"); } catch (Throwable ignored) { } };
            try { if (e.getValue() != null) e.getValue().execute(r); else main.post(r); } catch (Throwable t) { main.post(r); }
            n++;
        }
        XposedBridge.log("NetSpeedXposed: [carrier] name changed (" + why + ") -> asked " + n + " listener(s) to re-read it");
    }

    static void install(ClassLoader cl) {
        try {
            XC_MethodHook add = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object l = p.args[p.args.length - 1];
                        java.util.concurrent.Executor ex = p.args.length > 1 && p.args[0] instanceof java.util.concurrent.Executor
                                ? (java.util.concurrent.Executor) p.args[0] : null;
                        if (l != null) LISTENERS.put(l, ex);
                    } catch (Throwable ignored) { }
                }
            };
            try { XposedHelpers.findAndHookMethod(android.telephony.SubscriptionManager.class, "addOnSubscriptionsChangedListener",
                    java.util.concurrent.Executor.class, android.telephony.SubscriptionManager.OnSubscriptionsChangedListener.class, add); }
            catch (Throwable ignored) { }
            try { XposedHelpers.findAndHookMethod(android.telephony.SubscriptionManager.class, "addOnSubscriptionsChangedListener",
                    android.telephony.SubscriptionManager.OnSubscriptionsChangedListener.class, add); }
            catch (Throwable ignored) { }
        } catch (Throwable ignored) { }
        try {
            XC_MethodHook h = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!Cfg.b(Cfg.SMART_CARRIER, Cfg.D_SMART_CARRIER)) return;
                        int sub = (Integer) XposedHelpers.callMethod(p.thisObject, "getSubscriptionId");
                        String v = BY_SUB.get(sub);
                        if (v != null) p.setResult(v);
                    } catch (Throwable ignored) { }
                }
            };
            XposedHelpers.findAndHookMethod("android.telephony.SubscriptionInfo", cl, "getCarrierName", h);
            XposedBridge.log("NetSpeedXposed: smart carrier name ready (System UI)");
        } catch (Throwable t) {
            XposedBridge.log("NetSpeedXposed: smart carrier name not hooked: " + t);
        }
    }

    private static String norm(String v) {
        return v == null ? "" : v.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }

    /** Worker thread, with each fresh SIM reading. */
    static void update(java.util.List<Bands.Sim> sims) {
        boolean changed = false;
        for (Bands.Sim s : sims) {
            if (s.sub < 0) continue;
            String custom = Cfg.s(s.slot == 1 ? Cfg.OP_NAME1 : s.slot == 2 ? Cfg.OP_NAME2 : "");
            String name = custom != null && !custom.isEmpty() ? null : pick(s);
            String old = BY_SUB.get(s.sub);
            if (name == null) BY_SUB.remove(s.sub); else BY_SUB.put(s.sub, name);
            if (!java.util.Objects.equals(old, name)) changed = true;
        }
        if (changed && Cfg.b(Cfg.SMART_CARRIER, Cfg.D_SMART_CARRIER)) nudge("new reading");
    }

    static String pick(Bands.Sim s) {
        String home = s.simName == null ? "" : s.simName.trim(), net = s.netName == null ? "" : s.netName.trim();
        if (home.isEmpty() || net.isEmpty()) return null;
        boolean otherCircle = Circles.roamCircle(s.simNumeric, s.netNumeric) != null;
        String a = norm(home), b = norm(net).replaceFirst("^ind", "");
        boolean related = !a.isEmpty() && !b.isEmpty() && (b.contains(a) || a.contains(b));
        if (otherCircle && related) {                                                      // same operator, other circle: "Jio(Jio Mumbai)"
            String[] nc = Circles.of(s.netNumeric);
            String lg0 = Circles.legacy(s.netNumeric);                                      // Vi only: "Vi India(Vi Mumbai · Vodafone)"
            if (nc != null) return home + "(" + nc[0] + " " + nc[2] + (lg0 != null ? " · " + lg0 : "") + ")";
        }
        if (s.roaming || otherCircle || !related) return home + "(" + net + ")";     // another state's network: "Airtel(IND airtel)"
        if (net.length() > home.length() && !norm(net).equals(a)) return net + " | " + home;   // "Jio True5G | Jio"
        String lg = Circles.legacy(s.netNumeric);
        if (lg != null) return net + " (" + lg + ")";                                     // "Vi India (Idea)"
        return null;
    }
}
