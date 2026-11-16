package com.netspeed.xposed;

import android.content.Context;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Custom operator name per SIM, system-wide on this phone. Runs inside the phone service
 * (com.android.phone), where Android builds the names it hands to everyone else:
 *
 *  - network name ("gsm.operator.alpha", what apps get from getNetworkOperatorName),
 *  - SIM operator name ("gsm.sim.operator.alpha", getSimOperatorName),
 *  - the SIM's display / carrier name (SubscriptionInfo: settings, SIM picker, dialer),
 *  - the operator name inside ServiceState (status bar carrier text, lock screen).
 *
 * Only the text changes. Operator codes (MCC-MNC), the network, calls, SMS and data stay real.
 */
final class OperatorName {
    private static final String TAG = "NetSpeedXposed: [opname] ";

    private static final ThreadLocal<Object[]> SAVED = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> ROAM_SET = new ThreadLocal<>();

    private OperatorName() {}

    /** Custom name for SIM slot index 0/1, or null to keep the real one. */
    static String custom(int slotIndex) {
        Cfg.retryIfNeeded(); // the phone service starts very early; settings may not be readable yet
        String v = Cfg.s(slotIndex == 0 ? Cfg.OP_NAME1 : slotIndex == 1 ? Cfg.OP_NAME2 : "");
        return v == null || v.isEmpty() ? null : v;
    }

    static void install(final ClassLoader cl) {
        int n = 0;
        // Settings are read from the NetSpeed app once the phone service is up.
        try {
            XposedHelpers.findAndHookMethod(android.app.Instrumentation.class, "callApplicationOnCreate",
                    android.app.Application.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                Cfg.init((Context) p.args[0]);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "settings init failed: " + t);
                            }
                            try { NrFallback.registerPhone((Context) p.args[0]); } catch (Throwable ignored) { }
                            try { BandLock.registerPhone((Context) p.args[0]); } catch (Throwable ignored) { }
                        }
                    });
            n++;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "settings hook failed: " + t);
        }

        // 1) Network / SIM operator name properties, written per phone (= slot).
        XC_MethodHook byPhoneId = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!(p.args[0] instanceof Integer)) return;
                String c = custom((Integer) p.args[0]);
                if (c != null) p.args[1] = c;
            }
        };
        for (String m : new String[]{"setNetworkOperatorNameForPhone", "setSimOperatorNameForPhone"}) {
            try {
                XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", cl, m,
                        int.class, String.class, byPhoneId);
                n++;
            } catch (Throwable t) {
                XposedBridge.log(TAG + m + " not hooked: " + t);
            }
        }

        // 2) SIM display / carrier name, as handed out in SubscriptionInfo.
        try {
            Class<?> b = XposedHelpers.findClass("android.telephony.SubscriptionInfo$Builder", cl);
            XposedBridge.hookAllMethods(b, "build", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        int slot = XposedHelpers.getIntField(p.thisObject, "mSimSlotIndex");
                        String c = custom(slot);
                        if (c == null) return;
                        XposedHelpers.setObjectField(p.thisObject, "mDisplayName", c);
                        XposedHelpers.setObjectField(p.thisObject, "mCarrierName", c);
                    } catch (Throwable ignored) { }
                }
            });
            n++;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "SubscriptionInfo not hooked: " + t);
        }

        // 3) Operator name inside ServiceState, set just before it's sent to anyone.
        try {
            XposedHelpers.findAndHookMethod("android.telephony.ServiceState", cl, "writeToParcel",
                    android.os.Parcel.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            int slot = slotOf(p.thisObject, cl);
                            String c = slot < 0 ? null : custom(slot);
                            if (c == null) return;
                            try {
                                String numeric = (String) XposedHelpers.callMethod(p.thisObject, "getOperatorNumeric");
                                Object longName = XposedHelpers.callMethod(p.thisObject, "getOperatorAlphaLong");
                                if (longName == null && numeric == null) return; // not registered
                                Object shortName = XposedHelpers.callMethod(p.thisObject, "getOperatorAlphaShort");
                                SAVED.set(new Object[]{longName, shortName, numeric});
                                XposedHelpers.callMethod(p.thisObject, "setOperatorName", c, c, numeric);
                            } catch (Throwable ignored) { }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            // Put the real names back: only what leaves the phone service changes,
                            // so its own "did anything change?" checks keep working.
                            Object[] o = SAVED.get();
                            if (o == null) return;
                            SAVED.remove();
                            try {
                                XposedHelpers.callMethod(p.thisObject, "setOperatorName", o[0], o[1], o[2]);
                            } catch (Throwable ignored) { }
                        }
                    });
            n++;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "ServiceState not hooked: " + t);
        }
        // 3b) Roaming flag in ServiceState, only for what leaves the phone service (display and apps).
        //     The phone service's own copy stays real, so data handling, calls and the modem are untouched.
        try {
            XposedHelpers.findAndHookMethod("android.telephony.ServiceState", cl, "writeToParcel",
                    android.os.Parcel.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                Cfg.retryIfNeeded();
                                if (!Cfg.b(Cfg.CIRCLE_ROAM, Cfg.D_CIRCLE_ROAM)) return;
                                if ((Boolean) XposedHelpers.callMethod(p.thisObject, "getRoaming")) return;
                                String net = (String) XposedHelpers.callMethod(p.thisObject, "getOperatorNumeric");
                                if (net == null || net.isEmpty()) return;      // not registered
                                int slot = slotOf(p.thisObject, cl);
                                if (slot < 0) return;
                                Class<?> pf = XposedHelpers.findClass("com.android.internal.telephony.PhoneFactory", cl);
                                Object phone = pf.getMethod("getPhone", int.class).invoke(null, slot);
                                if (phone == null) return;
                                Context ctx = (Context) XposedHelpers.callMethod(phone, "getContext");
                                int sub = (Integer) XposedHelpers.callMethod(phone, "getSubId");
                                String sim = ctx.getSystemService(android.telephony.TelephonyManager.class)
                                        .createForSubscriptionId(sub).getSimOperator();
                                if (Circles.roamCircle(sim, net) == null) return;
                                XposedHelpers.callMethod(p.thisObject, "setRoaming", true);
                                ROAM_SET.set(Boolean.TRUE);
                            } catch (Throwable ignored) { }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (ROAM_SET.get() == null) return;
                            ROAM_SET.remove();
                            try { XposedHelpers.callMethod(p.thisObject, "setRoaming", false); } catch (Throwable ignored) { }
                        }
                    });
            n++;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "roaming flag not hooked: " + t);
        }
        // 3c) The display info System UI uses for its "R" icon (TelephonyDisplayInfo.isRoaming) is built from the
        //     real state, so it is replaced with a copy that says roaming, only on its way out to the registry.
        try {
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyRegistryManager", cl, "notifyDisplayInfoChanged",
                    int.class, int.class, "android.telephony.TelephonyDisplayInfo", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                Cfg.retryIfNeeded();
                                boolean on = Cfg.b(Cfg.CIRCLE_ROAM, Cfg.D_CIRCLE_ROAM);
                                Object tdi = p.args[2];
                                boolean already = tdi != null && (Boolean) XposedHelpers.callMethod(tdi, "isRoaming");
                                boolean circ = on && tdi != null && !already && circleRoaming(cl, (Integer) p.args[0]);
                                if (diLogs++ < 15)
                                    XposedBridge.log(TAG + "display-info slot=" + p.args[0] + " switchOn=" + on + " alreadyRoaming=" + already
                                            + " circlesDiffer=" + circ);
                                if (!circ) return;
                                android.os.Parcel pc = android.os.Parcel.obtain();
                                try {
                                    ((android.os.Parcelable) tdi).writeToParcel(pc, 0);
                                    pc.setDataPosition(0);
                                    Object creator = tdi.getClass().getField("CREATOR").get(null);
                                    Object copy = ((android.os.Parcelable.Creator<?>) creator).createFromParcel(pc);
                                    java.lang.reflect.Field rf = tdi.getClass().getDeclaredField("mIsRoaming");
                                    rf.setAccessible(true);
                                    rf.setBoolean(copy, true);
                                    p.args[2] = copy;
                                } finally {
                                    pc.recycle();
                                }
                            } catch (Throwable t) {
                                logOnce("display-info roaming skipped: " + t);
                            }
                        }
                    });
            n++;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "display-info roaming not hooked: " + t);
        }
        // 3d) The display info is only re-sent when its own values change, so a "no" decided at boot (before the
        //     network is known) would stick. After each service-state update, re-check and re-send when it flipped.
        try {
            XposedBridge.hookAllMethods(XposedHelpers.findClass("android.telephony.TelephonyRegistryManager", cl),
                    "notifyServiceStateChanged", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                if (p.args.length < 3 || !(p.args[0] instanceof Integer)) return;
                                Cfg.retryIfNeeded();
                                if (!Cfg.b(Cfg.CIRCLE_ROAM, Cfg.D_CIRCLE_ROAM)) return;
                                int slot = (Integer) p.args[0];
                                boolean now = circleRoaming(cl, slot);
                                Boolean prev = LAST_CR.put(slot, now);
                                if (prev == null ? !now : prev == now) return;
                                Class<?> pf = XposedHelpers.findClass("com.android.internal.telephony.PhoneFactory", cl);
                                Object phone = pf.getMethod("getPhone", int.class).invoke(null, slot);
                                // the carrier name chose its form at boot too: have the tracker rebuild it (smart() runs again)
                                try {
                                    Object sst = XposedHelpers.callMethod(phone, "getServiceStateTracker");
                                    XposedHelpers.callMethod(sst, "updateSpnDisplay");
                                } catch (Throwable t) {
                                    logOnce("carrier name refresh skipped: " + t);
                                }
                                Object dic = XposedHelpers.callMethod(phone, "getDisplayInfoController");
                                Object info = XposedHelpers.callMethod(dic, "getTelephonyDisplayInfo");
                                if (info == null) return;
                                XposedHelpers.callMethod(p.thisObject, "notifyDisplayInfoChanged", slot, (Integer) p.args[1], info);
                                XposedBridge.log(TAG + "display-info re-sent slot=" + slot + " roaming=" + now);
                            } catch (Throwable t) {
                                logOnce("display-info re-send skipped: " + t);
                            }
                        }
                    });
            n++;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "service-state follow-up not hooked: " + t);
        }
        // 4) Smart carrier name (when no custom name is set): the network's own fuller name at home
        //    ("Jio True5G"), and "SIM name · network name" on another state's network ("Jio · IND airtel").
        try {
            Class<?> sst = XposedHelpers.findClass("com.android.internal.telephony.ServiceStateTracker", cl);
            int k = XposedBridge.hookAllMethods(sst, "notifySpnDisplayUpdate", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length == 0 || p.args[0] == null) return;
                        Cfg.retryIfNeeded();
                        if (!Cfg.b(Cfg.SMART_CARRIER, Cfg.D_SMART_CARRIER)) return;
                        smart(p.thisObject, p.args[0]);
                    } catch (Throwable t) {
                        logOnce("smart name skipped: " + t);
                    }
                }
            }).size();
            if (k > 0) n++;
            else XposedBridge.log(TAG + "notifySpnDisplayUpdate not found on this ROM: smart carrier name off");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "smart carrier name not hooked: " + t);
        }
        XposedBridge.log(TAG + "phone service hooks ready (" + n + ")");
    }

    /** True when this slot's SIM circle and the registered network's circle differ (uses the phone's real state). */
    static boolean circleRoaming(ClassLoader cl, int slot) {
        try {
            Class<?> pf = XposedHelpers.findClass("com.android.internal.telephony.PhoneFactory", cl);
            Object phone = pf.getMethod("getPhone", int.class).invoke(null, slot);
            if (phone == null) return false;
            Object ss = XposedHelpers.callMethod(phone, "getServiceState");
            String net = ss == null ? null : (String) XposedHelpers.callMethod(ss, "getOperatorNumeric");
            if (net == null || net.isEmpty()) return false;
            Context ctx = (Context) XposedHelpers.callMethod(phone, "getContext");
            int sub = (Integer) XposedHelpers.callMethod(phone, "getSubId");
            String sim = ctx.getSystemService(android.telephony.TelephonyManager.class)
                    .createForSubscriptionId(sub).getSimOperator();
            boolean r = Circles.roamCircle(sim, net) != null;
            if (crLogs++ < 15) XposedBridge.log(TAG + "circle check slot=" + slot + " sim=" + sim + " net=" + net + " -> " + r);
            return r;
        } catch (Throwable t) {
            if (crLogs++ < 15) XposedBridge.log(TAG + "circle check slot=" + slot + " failed: " + t);
            return false;
        }
    }

    private static boolean logged;
    private static int diLogs, crLogs;
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Boolean> LAST_CR = new java.util.concurrent.ConcurrentHashMap<>();

    private static void logOnce(String m) {
        if (logged) return;
        logged = true;
        XposedBridge.log(TAG + m);
    }

    private static String field(Object o, String f) {
        try {
            Object v = XposedHelpers.getObjectField(o, f);
            return v == null ? "" : v.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void setBool(Object o, String name, boolean v) {
        try {
            XposedHelpers.findField(o.getClass(), name).setBoolean(o, v);
        } catch (Throwable ignored) { }
    }

    private static String norm(String v) {
        return v == null ? "" : v.toLowerCase(java.util.Locale.US).replaceAll("[^a-z0-9]", "");
    }

    /** Rewrites the carrier name Android is about to publish (CarrierDisplayNameData). */
    private static void smart(Object tracker, Object data) {
        Object phone = XposedHelpers.getObjectField(tracker, "mPhone");
        int slot = (Integer) XposedHelpers.callMethod(phone, "getPhoneId");
        if (custom(slot) != null) return;                          // a name typed in settings wins
        android.telephony.ServiceState ss = (android.telephony.ServiceState) XposedHelpers.getObjectField(tracker, "mSS");
        Context ctx = (Context) XposedHelpers.callMethod(phone, "getContext");
        int sub = (Integer) XposedHelpers.callMethod(phone, "getSubId");
        android.telephony.TelephonyManager tm = ctx.getSystemService(android.telephony.TelephonyManager.class)
                .createForSubscriptionId(sub);

        String spn = field(data, "mSpn"), plmn = field(data, "mPlmn");
        String home = !spn.isEmpty() ? spn : tm.getSimOperatorName();
        String net = ss == null ? null : ss.getOperatorAlphaLong();
        if (net == null || net.trim().isEmpty()) net = plmn;
        if (net == null || net.trim().isEmpty()) net = ss == null ? null : ss.getOperatorAlphaShort();
        if (home == null || home.trim().isEmpty() || net == null || net.trim().isEmpty()) return;
        home = home.trim();
        net = net.trim();
        if (home.contains("(") || home.contains(" | ")) return;      // already rewritten

        boolean roaming = ss != null && ss.getRoaming();
        boolean otherCircle = Circles.roamCircle(tm.getSimOperator(), ss == null ? null : ss.getOperatorNumeric()) != null;
        String a = norm(home), b = norm(net).replaceFirst("^ind", "");
        boolean related = !a.isEmpty() && !b.isEmpty() && (b.contains(a) || a.contains(b));

        String name;
        String[] nCirc = Circles.of(ss == null ? null : ss.getOperatorNumeric());
        if (otherCircle && related && nCirc != null) {
            String lg0 = Circles.legacy(ss.getOperatorNumeric());      // Vi only: "Vi India(Vi Mumbai · Vodafone)"
            name = home + "(" + nCirc[0] + " " + nCirc[2] + (lg0 != null ? " · " + lg0 : "") + ")";       // same operator, other circle: "Jio(Jio Mumbai)"
        } else if ((roaming || otherCircle) || !related) {
            name = home + "(" + net + ")";                          // another state's network: "Airtel(IND airtel)"
        } else if (net.length() > home.length() && !norm(net).equals(a)) {
            name = net + " | " + home;                              // home: "Jio True5G | Jio"
        } else {
            return;                                                 // same name: leave it
        }
        XposedHelpers.setObjectField(data, "mSpn", name);
        try { XposedHelpers.setObjectField(data, "mDataSpn", name); } catch (Throwable ignored) { }
        setBool(data, "mShowSpn", true);
        setBool(data, "mShowPlmn", false);
    }

    /**
     * Which SIM slot a ServiceState belongs to: the phone holding this very object, else the
     * only phone registered on the same network code. -1 when it can't be told.
     */
    static int slotOf(Object ss, ClassLoader cl) {
        try {
            Class<?> pf = XposedHelpers.findClass("com.android.internal.telephony.PhoneFactory", cl);
            Object[] phones = (Object[]) pf.getMethod("getPhones").invoke(null);
            if (phones == null) return -1;
            String numeric = null;
            try {
                numeric = (String) XposedHelpers.callMethod(ss, "getOperatorNumeric");
            } catch (Throwable ignored) { }
            int match = -1, matches = 0;
            for (Object ph : phones) {
                if (ph == null) continue;
                Object own = XposedHelpers.callMethod(ph, "getServiceState");
                int id = (Integer) XposedHelpers.callMethod(ph, "getPhoneId");
                if (own == ss) return id;
                if (numeric != null && own != null) {
                    Object n = XposedHelpers.callMethod(own, "getOperatorNumeric");
                    if (numeric.equals(n)) {
                        match = id;
                        matches++;
                    }
                }
            }
            return matches == 1 ? match : -1;
        } catch (Throwable t) {
            return -1;
        }
    }
}
