package com.netspeed.xposed;

import android.telephony.ServiceState;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Settings > About phone > SIM status: shows "Roaming" and the circle name ("Jio Mumbai") when the SIM's circle
 * and the tower's circle differ (the same check as the R·MU tag). Display only: it runs inside the Settings app,
 * on that screen's own copy of the service state, and puts the real values back right after. The phone service
 * (calls, data, 5G) is never touched.
 */
final class SimStatus {
    private static final String TAG = "NetSpeedXposed: [simstatus] ";
    private static final String CTRL = "com.android.settings.deviceinfo.simstatus.SimStatusDialogController";

    private SimStatus() {}

    /** Original values saved while the screen draws: {roaming, longName, shortName, numeric}. */
    private static final ThreadLocal<Object[]> SAVED = new ThreadLocal<>();

    static void install(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists(CTRL, cl);
        if (c == null) {
            XposedBridge.log(TAG + "SIM status screen not found on this ROM");
            return;
        }
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    ServiceState ss = null;
                    for (Object a : p.args) if (a instanceof ServiceState) ss = (ServiceState) a;
                    if (ss == null) return;
                    String net = ss.getOperatorNumeric();
                    String sim = simNumeric(p.thisObject);
                    if (net == null || sim == null || Circles.roamCircle(sim, net) == null) return;
                    String[] nc = Circles.of(net);
                    if (nc == null) return;
                    String lg = Circles.legacy(net);
                    String name = nc[0] + " " + nc[2] + (lg != null ? " · " + lg : "");
                    SAVED.set(new Object[]{ss.getRoaming(), ss.getOperatorAlphaLong(), ss.getOperatorAlphaShort(), net, ss});
                    if ("updateRoamingStatus".equals(p.method.getName())) {
                        XposedHelpers.callMethod(ss, "setRoaming", true);
                    } else {
                        XposedHelpers.callMethod(ss, "setOperatorName", name, name, net);
                    }
                } catch (Throwable t) {
                    SAVED.remove();
                    XposedBridge.log(TAG + "skipped: " + t);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                Object[] o = SAVED.get();
                if (o == null) return;
                SAVED.remove();
                try {
                    ServiceState ss = (ServiceState) o[4];
                    XposedHelpers.callMethod(ss, "setRoaming", (Boolean) o[0]);
                    XposedHelpers.callMethod(ss, "setOperatorName", o[1], o[2], o[3]);
                } catch (Throwable ignored) { }
            }
        };
        int n = 0;
        for (String m : new String[]{"updateRoamingStatus", "updateNetworkProvider"}) {
            try {
                n += XposedBridge.hookAllMethods(c, m, h).size();
            } catch (Throwable t) {
                XposedBridge.log(TAG + m + " not hooked: " + t);
            }
        }
        XposedBridge.log(TAG + "SIM status hooks ready (" + n + ")");
    }

    /** The SIM's own operator code, from the screen's TelephonyManager (already bound to this SIM). */
    private static String simNumeric(Object ctrl) {
        try {
            Object tm = XposedHelpers.getObjectField(ctrl, "mTelephonyManager");
            String v = (String) XposedHelpers.callMethod(tm, "getSimOperator");
            if (v != null && !v.isEmpty()) return v;
        } catch (Throwable ignored) { }
        try {
            Object si = XposedHelpers.getObjectField(ctrl, "mSubscriptionInfo");
            String mcc = (String) XposedHelpers.callMethod(si, "getMccString");
            String mnc = (String) XposedHelpers.callMethod(si, "getMncString");
            if (mcc != null && mnc != null) return mcc + mnc;
        } catch (Throwable ignored) { }
        return null;
    }
}
