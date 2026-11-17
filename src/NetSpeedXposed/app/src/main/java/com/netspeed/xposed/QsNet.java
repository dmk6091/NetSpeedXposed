package com.netspeed.xposed;

import android.graphics.drawable.Drawable;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Preferred network tile: icon shows 5G / 4G / 3G, and a tap steps 5G, 4G, 3G. */
final class QsNet {
    private static final String TAG = "NetSpeedXposed: [qsnet] ";
    private static final long G3 = bits("GSM", "GPRS", "EDGE", "UMTS", "HSDPA", "HSUPA", "HSPA", "HSPAP", "TD_SCDMA");
    private static final long G4 = G3 | bits("LTE", "LTE_CA");
    private static final long G5 = G4 | bits("NR");

    private QsNet() {}

    private static long bits(String... n) {
        long r = 0;
        for (String s : n) {
            try { r |= TelephonyManager.class.getField("NETWORK_TYPE_BITMASK_" + s).getLong(null); } catch (Throwable ignored) { }
        }
        return r;
    }

    private static TelephonyManager tm(Object tile) {
        TelephonyManager t = (TelephonyManager) XposedHelpers.getObjectField(tile, "mTelephonyManager");
        return t.createForSubscriptionId(SubscriptionManager.getDefaultDataSubscriptionId());
    }

    private static String gen(long allowed) {
        if ((allowed & bits("NR")) != 0) return "5G";
        if ((allowed & bits("LTE")) != 0) return "4G";
        return "3G";
    }

    static void install(ClassLoader cl) {
        try {
            Class<?> k = XposedHelpers.findClass("com.android.systemui.qs.tiles.PreferredNetworkTile", cl);
            XposedBridge.hookAllMethods(k, "handleUpdateState", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object st = p.args[0];
                        long a = tm(p.thisObject).getAllowedNetworkTypesForReason(0);
                        String g = gen(a);
                        Class<?> ic = XposedHelpers.findClass("com.android.systemui.qs.tileimpl.QSTileImpl$DrawableIconWithRes", cl);
                        Object icon = ic.getConstructors()[0].newInstance("5G".equals(g) ? 0x7e0f0005 : "4G".equals(g) ? 0x7e0f0004 : 0x7e0f0003, (Drawable) new ValueIcon(g, 0xFFFFFFFF));
                        XposedHelpers.setObjectField(st, "icon", icon);
                        XposedHelpers.setObjectField(st, "secondaryLabel", g);
                        XposedHelpers.setIntField(st, "state", "3G".equals(g) ? 1 : 2);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "update failed: " + t);
                    }
                }
            });
            XposedBridge.hookAllMethods(k, "handleClick", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        android.util.Log.e("QsNet", "click");
                        TelephonyManager t = tm(p.thisObject);
                        String g = gen(t.getAllowedNetworkTypesForReason(0));
                        long next = "5G".equals(g) ? G4 : "4G".equals(g) ? G3 : G5;
                        t.setAllowedNetworkTypesForReason(0, next);
                        XposedHelpers.callMethod(p.thisObject, "refreshState");
                        p.setResult(null);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "click failed: " + t); android.util.Log.e("QsNet", "click failed", t);
                    }
                }
            });
            XposedBridge.log(TAG + "installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "install failed: " + t);
        }
    }
}
