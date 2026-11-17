package com.dmk.btbatfix;

import java.lang.reflect.Member;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Puts back the ROM's own data activity arrows under the mobile network type.
 * StatusbarTemp replaces them with MIUI 10 style half arrows and has no switch for it, so this runs
 * first (highest priority), lets the ROM set its own arrow image, and ends the call there:
 * StatusbarTemp's arrow code then never runs. Its temperature and Bluetooth pill are not touched.
 */
final class StockArrows {
    static final String VIEW = "com.android.systemui.newstatusbar.sim.SimInOutView";
    static final int PRIORITY_HIGHEST = 10000;

    static void install(ClassLoader cl) {
        Class<?> v = XposedHelpers.findClassIfExists(VIEW, cl);
        if (v == null) { XposedBridge.log(Hook.TAG + "no SimInOutView, arrows left alone"); return; }
        final Method invokeOriginal;
        try {
            invokeOriginal = XposedBridge.class.getMethod("invokeOriginalMethod", Member.class, Object.class, Object[].class);
        } catch (Throwable t) {
            XposedBridge.log(Hook.TAG + "can't call original methods: " + t);
            return;
        }
        XposedBridge.hookAllMethods(v, "setImageResource", new XC_MethodHook(PRIORITY_HIGHEST) {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    invokeOriginal.invoke(null, p.method, p.thisObject, p.args);
                    p.setResult(null);   // done: later hooks (StatusbarTemp's arrows) are skipped
                } catch (Throwable t) {
                    XposedBridge.log(Hook.TAG + "stock arrows: " + (t.getCause() != null ? t.getCause() : t));
                }
            }
        });
        XposedBridge.log(Hook.TAG + "stock data arrows restored");
    }
}
