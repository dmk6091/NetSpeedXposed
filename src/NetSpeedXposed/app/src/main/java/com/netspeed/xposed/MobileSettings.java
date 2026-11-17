package com.netspeed.xposed;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Settings > Network & internet > SIM > (a SIM): makes these rows visible even when the ROM hides them:
 * Data roaming, CDMA "System select" and "CDMA subscription". Only the "is this row shown" answer is changed;
 * the rows themselves are the ROM's own and keep working as before.
 */
final class MobileSettings {
    private static final String TAG = "NetSpeedXposed: [mobilesettings] ";
    private static final String PKG = "com.android.settings.network.telephony.";
    private static final String[] ROWS = {
            "cdma.CdmaSystemSelectPreferenceController",
            "cdma.CdmaSubscriptionPreferenceController",
            "CdmaSystemSelectPreferenceController",
            "CdmaSubscriptionPreferenceController",
            "RoamingPreferenceController",
    };

    private MobileSettings() {}

    private static boolean declares(Class<?> c) {
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            if ("getAvailabilityStatus".equals(m.getName()) && !java.lang.reflect.Modifier.isAbstract(m.getModifiers())) return true;
        }
        return false;
    }

    static void install(ClassLoader cl) {
        for (String n : ROWS) {
            try {
                Class<?> k = XposedHelpers.findClassIfExists(PKG + n, cl);
                if (k == null) {
                    XposedBridge.log(TAG + n + " not found on this ROM");
                    continue;
                }
                // The row may inherit getAvailabilityStatus: hook the class that really declares it,
                // but only change the answer for this row's own controller.
                Class<?> owner = k;
                while (owner != null && !declares(owner)) owner = owner.getSuperclass();
                if (owner == null) {
                    XposedBridge.log(TAG + n + " has no availability check");
                    continue;
                }
                final Class<?> row = k;
                int cnt = XposedBridge.hookAllMethods(owner, "getAvailabilityStatus", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (row.isInstance(p.thisObject)) p.setResult(0);
                    }
                }).size();
                XposedBridge.log(TAG + n + " shown (" + cnt + " hook)");
            } catch (Throwable t) {
                XposedBridge.log(TAG + n + " failed: " + t);
            }
        }
    }
}
