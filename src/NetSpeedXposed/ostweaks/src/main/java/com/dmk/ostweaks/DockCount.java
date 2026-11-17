package com.dmk.ostweaks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 5-icon dock (experimental). The OxygenOS launcher takes the visible dock slot count from
 * InvariantDeviceProfile.numShownHotseatIcons. It is raised to 5 on the phone layout, both when the
 * profile is built and when a DeviceProfile is created from it.
 */
final class DockCount {
    private DockCount() {}
    static final int N = 5;
    private static boolean done;

    static void handle(LoadPackageParam lp) {
        if (!"com.android.launcher".equals(lp.packageName) || !lp.packageName.equals(lp.processName)) return;
        synchronized (DockCount.class) { if (done) return; done = true; }
        final ClassLoader cl = lp.classLoader;
        Cfg.whenReady(new Runnable() { public void run() { install(cl); } });
    }

    private static void install(ClassLoader cl) {
        if (!Cfg.get("ostweaks_dock5", false)) { XposedBridge.log(Hook.TAG + "dock: 5 icons off"); return; }
        try {
            Class<?> idp = XposedHelpers.findClass("com.android.launcher3.InvariantDeviceProfile", cl);
            Class<?> dp = XposedHelpers.findClass("com.android.launcher3.DeviceProfile", cl);
            XC_MethodHook afterIdp = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { raise(p.thisObject); }
            };
            int n = XposedBridge.hookAllConstructors(idp, afterIdp).size();
            for (String m : new String[]{"initGrid", "updateNumShownHotseatIcons", "onConfigChanged", "reInitGrid"}) {
                try { n += XposedBridge.hookAllMethods(idp, m, afterIdp).size(); } catch (Throwable ignored) {}
            }
            try {
                Class<?> oidp = XposedHelpers.findClass("com.android.launcher3.OplusInvariantDeviceProfile", cl);
                n += XposedBridge.hookAllMethods(oidp, "updateNumShownHotseatIcons", afterIdp).size();
                n += XposedBridge.hookAllMethods(oidp, "getHotSeatMaxIconNum", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Object r = p.getResult();
                        if (r instanceof Integer && (Integer) r == 4) p.setResult(N);
                    }
                }).size();
            } catch (Throwable ignored) {}
            n += XposedBridge.hookAllConstructors(dp, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    for (Object a : p.args) if (a != null && idp.isInstance(a)) raise(a);
                }
            }).size();
            XposedBridge.log(Hook.TAG + "dock: 5 icons (" + n + " hooks)");
        } catch (Throwable t) {
            XposedBridge.log(Hook.TAG + "dock: " + t);
        }
    }

    private static String last = "";

    private static int f(Object o, String n) {
        try { return XposedHelpers.getIntField(o, n); } catch (Throwable t) { return -99; }
    }

    private static void raise(Object idp) {
        try {
            String before = f(idp, "numShownHotseatIcons") + "/" + f(idp, "numDatabaseHotseatIcons") + "/"
                    + f(idp, "numRealTimeShownHotseatIcons") + "/" + f(idp, "numShrunkenHotseatIcons");
            int cur = f(idp, "numShownHotseatIcons");
            if (cur == 4) XposedHelpers.setIntField(idp, "numShownHotseatIcons", N);
            if (f(idp, "numRealTimeShownHotseatIcons") == 4) XposedHelpers.setIntField(idp, "numRealTimeShownHotseatIcons", N);
            if (f(idp, "numDatabaseHotseatIcons") > 0 && f(idp, "numDatabaseHotseatIcons") < N)
                XposedHelpers.setIntField(idp, "numDatabaseHotseatIcons", N);
            String after = f(idp, "numShownHotseatIcons") + "/" + f(idp, "numDatabaseHotseatIcons") + "/"
                    + f(idp, "numRealTimeShownHotseatIcons") + "/" + f(idp, "numShrunkenHotseatIcons");
            String s = before + " -> " + after;
            if (!s.equals(last)) { last = s; XposedBridge.log(Hook.TAG + "dock: shown/db/realtime/shrunk " + s); }
        } catch (Throwable t) {
            XposedBridge.log(Hook.TAG + "dock raise: " + t);
        }
    }
}
