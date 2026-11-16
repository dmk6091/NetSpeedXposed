package com.netspeed.xposed;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Wake the screen when a charger is plugged in. Android's power manager refuses to wake from the
 * doze dream on plug-in (it expects an always-on display to show the charging indicator), but on
 * Lunaris the doze screen stays dark, so nothing wakes and no charging animation shows. We turn
 * that refusal into a wake when the ROM's own "wake on plug" is on (and theater mode is off).
 */
final class WakePlug {
    private static final String TAG = "NetSpeedXposed: [wakeplug] ";

    private WakePlug() {}

    private static boolean bool(Object o, String f) throws Exception {
        java.lang.reflect.Field fl = XposedHelpers.findField(o.getClass(), f);
        fl.setAccessible(true);
        return fl.getBoolean(o);
    }

    private static int num(Object o, String f) throws Exception {
        java.lang.reflect.Field fl = XposedHelpers.findField(o.getClass(), f);
        fl.setAccessible(true);
        return fl.getInt(o);
    }

    static void install(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> c = XposedHelpers.findClass("com.android.server.power.PowerManagerService", cl);
            n += XposedBridge.hookAllMethods(c, "shouldWakeUpWhenPluggedOrUnpluggedLocked", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (Boolean.TRUE.equals(p.getResult())) return;
                            if (p.args.length < 1 || !(p.args[0] instanceof Boolean)) return;
                            boolean was = (Boolean) p.args[0];
                            boolean now = bool(p.thisObject, "mIsPowered");
                            if (was || !now) return;                       // plug-in only
                            if (!bool(p.thisObject, "mWakeUpWhenPluggedOrUnpluggedConfig")) return;
                            if (bool(p.thisObject, "mTheaterModeEnabled")) return;
                            int plug = num(p.thisObject, "mPlugType");
                            if (plug == 4) return;                         // wireless: leave as is
                            p.setResult(true);
                            XposedBridge.log(TAG + "plug-in: wake forced");
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + "hook error " + t);
                        }
                    }
                }).size();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "install failed: " + t);
        }
        XposedBridge.log(TAG + "installed (" + n + " hook)");
    }
}
