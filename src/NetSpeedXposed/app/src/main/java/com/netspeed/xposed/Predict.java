package com.netspeed.xposed;

import android.content.res.Resources;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * App suggestions: Android only starts its app prediction service (which the launcher asks for
 * dock / app drawer suggestions) when the ROM names a prediction app in
 * config_defaultAppPredictionService. Some ROMs name none, others name Google's AiAi service that gives no
 * suggestions here; either way, when the switch is on we name ours (system server, at boot).
 * Switched by the property persist.sys.netspeed.predict, set by the settings app. Needs a reboot.
 */
final class Predict {
    static final String PROP = "persist.sys.netspeed.predict";
    /** Flag file (written with root by the settings app); the system server can read it at boot. */
    static final String FLAG = "/data/system/netspeed_predict";
    static final String SERVICE = "com.netspeed.xposed/.PredictionService";
    private static final String TAG = "NetSpeedXposed: [predict] ";

    private Predict() {}

    static boolean enabled() {
        try {
            if (new java.io.File(FLAG).exists()) return true;
        } catch (Throwable ignored) { }
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (Boolean) sp.getMethod("getBoolean", String.class, boolean.class).invoke(null, PROP, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Android's app freezer pauses background app processes. The suggestion service lives in
     * our app's process, so once frozen it stops updating (the dock keeps its first list).
     * Skip freezing for our process only.
     */
    static void keepAwake(ClassLoader cl) {
        int n = 0;
        for (String cn : new String[]{"com.android.server.am.CachedAppOptimizer",
                "com.android.server.am.CachedAppOptimizer$FreezeHandler"}) {
            Class<?> c;
            try {
                c = XposedHelpers.findClass(cn, cl);
            } catch (Throwable t) {
                continue;
            }
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                String name = m.getName().toLowerCase(java.util.Locale.US);
                if (!name.contains("freeze") || name.contains("unfreeze")) continue;
                int idx = -1;
                Class<?>[] pt = m.getParameterTypes();
                for (int i = 0; i < pt.length; i++) {
                    if (pt[i].getName().equals("com.android.server.am.ProcessRecord")) idx = i;
                }
                if (idx < 0) continue;
                final int argIdx = idx;
                final Class<?> ret = m.getReturnType();
                try {
                    XC_MethodHook hook = new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            Object app = p.args[argIdx];
                            if (app == null) return;
                            Object pn;
                            try {
                                pn = XposedHelpers.getObjectField(app, "processName");
                            } catch (Throwable t) {
                                return;
                            }
                            if (!(pn instanceof String) || !((String) pn).startsWith("com.netspeed.xposed")) return;
                            if (ret == void.class) p.setResult(null);
                            else if (ret == boolean.class) p.setResult(false);
                            else if (ret == int.class) p.setResult(0);
                            else if (ret == long.class) p.setResult(0L);
                            else if (!ret.isPrimitive()) p.setResult(null);
                        }
                    };
                    Object[] args = new Object[pt.length + 1];
                    System.arraycopy(pt, 0, args, 0, pt.length);
                    args[pt.length] = hook;
                    XposedHelpers.findAndHookMethod(c, m.getName(), args);
                    n++;
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "freezer hook " + m.getName() + " failed: " + t);
                }
            }
        }
        XposedBridge.log(TAG + "suggestion service kept out of the app freezer (" + n + " hooks)");
    }

    static void installSystem(ClassLoader cl) {
        boolean on = enabled();
        if (on) keepAwake(cl);
        final int id = Resources.getSystem().getIdentifier("config_defaultAppPredictionService", "string", "android");
        String current = "";
        try {
            if (id != 0) current = Resources.getSystem().getString(id);
        } catch (Throwable ignored) { }
        XposedBridge.log(TAG + "switch (" + FLAG + " or " + PROP + ") = " + on + ", ROM prediction service = \""
                + current + "\"" + (id == 0 ? " (setting not found)" : ""));
        if (!on || id == 0) return;
        if (current != null && !current.isEmpty()) {
            // The switch is on, so it wins. (This ROM names Google's AiAi service, which gives this phone no suggestions.)
            XposedBridge.log(TAG + "overriding the ROM's prediction service " + current);
        }
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length > 0 && p.args[0] instanceof Integer && (Integer) p.args[0] == id) {
                    p.setResult(SERVICE);
                }
            }
        };
        int n = 0;
        for (String m : new String[]{"getText", "getString"}) {
            try {
                XposedHelpers.findAndHookMethod(Resources.class, m, int.class, h);
                n++;
            } catch (Throwable t) {
                XposedBridge.log(TAG + "hook " + m + " failed: " + t);
            }
        }
        XposedBridge.log(TAG + "prediction service set to " + SERVICE + " (hooks " + n + ")");
    }
}
