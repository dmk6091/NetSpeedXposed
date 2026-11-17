package com.dmk.ostweaks;

import android.content.Context;
import android.provider.Settings;

/** Feature switches written by the OxygenOS Tweaks app into Settings.System (default: on). */
final class Cfg {
    private Cfg() {}

    static boolean on(String key) { return get(key, true); }

    static boolean get(String key, boolean def) {
        try {
            Context c = ctx();
            if (c == null) {
                de.robv.android.xposed.XposedBridge.log("OSTweaks: cfg " + key + ": no context");
                return def;
            }
            int v = Settings.System.getInt(c.getContentResolver(), key, def ? 1 : 0);
            de.robv.android.xposed.XposedBridge.log("OSTweaks: cfg " + key + " = " + v + " via " + c.getPackageName());
            return v != 0;
        } catch (Throwable t) {
            de.robv.android.xposed.XposedBridge.log("OSTweaks: cfg " + key + ": " + t);
            return def;
        }
    }

    /** Runs r once the app's own Context exists (hooks can be installed from handleLoadPackage, which is too early). */
    static void whenReady(final Runnable r) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            if (at.getMethod("currentApplication").invoke(null) != null) { r.run(); return; }
        } catch (Throwable ignored) {}
        de.robv.android.xposed.XposedBridge.hookAllMethods(android.app.Application.class, "attach",
                new de.robv.android.xposed.XC_MethodHook() {
                    boolean ran;
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (ran) return;
                        ran = true;
                        if (p.thisObject instanceof Context) appCtx = (Context) p.thisObject;
                        try { r.run(); } catch (Throwable t) {
                            de.robv.android.xposed.XposedBridge.log("OSTweaks: ready: " + t);
                        }
                    }
                });
    }

    static volatile Context appCtx;

    static Context ctx() {
        if (appCtx != null) return appCtx;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) return (Context) app;
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            return (Context) at.getMethod("getSystemContext").invoke(thread);
        } catch (Throwable t) {
            return null;
        }
    }
}
