package com.netspeed.xposed;

import android.view.View;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hides the battery percentage in the quick settings header (the "67%" next to the battery icon).
 * The status bar battery is a different instance of the same view and is left alone.
 * Switch: Cfg.QS_BATT_HIDE.
 */
final class QsBatt {
    private static final String TAG = "NetSpeedXposed: [qsbatt] ";
    private static boolean logged;

    private QsBatt() {}

    static void install(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.android.systemui.battery.BatteryMeterView", cl);
            XC_MethodHook h = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    apply((View) p.thisObject);
                }
            };
            int n = 0;
            for (String m : new String[]{"updateShowPercent", "updatePercentText", "setPercentShowMode", "onAttachedToWindow"}) {
                try { n += XposedBridge.hookAllMethods(c, m, h).size(); } catch (Throwable ignored) { }
            }
            XposedBridge.log(TAG + "hooks: " + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "install failed: " + t);
        }
    }

    private static void apply(View v) {
        try {
            String id = "";
            try { id = v.getResources().getResourceEntryName(v.getId()); } catch (Throwable ignored) { }
            // the shade header's battery is "batteryRemainingIcon"; the status bar one is "battery"
            if (!id.toLowerCase(java.util.Locale.US).contains("remaining")) return;
            if (!Cfg.b(Cfg.QS_BATT_HIDE, Cfg.D_QS_BATT_HIDE)) return;
            Object tv = XposedHelpers.getObjectField(v, "mBatteryPercentView");
            if (tv instanceof TextView) {
                ((TextView) tv).setVisibility(View.GONE);
                if (!logged) {
                    logged = true;
                    XposedBridge.log(TAG + "percent hidden on " + id);
                }
            }
        } catch (Throwable ignored) { }
    }
}
