package com.netspeed.xposed;

import android.graphics.Insets;
import android.view.WindowInsets;
import android.view.WindowManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Immersive navigation when the launcher draws the gesture pill (Android 16 "Taskbar" on
 * phones, as on Lunaris). The Taskbar window tells the system how much space to keep free
 * for navigation; we set its navigation-bar inset to zero, so apps draw to the bottom edge.
 * The gesture inset types (system / mandatory gestures) are left as they are, so swipes keep
 * working, and the pill itself is still drawn by the launcher.
 */
final class LauncherNav {
    private static final String TAG = "NetSpeedXposed: [nav] ";
    private static boolean sLogged = false;

    private LauncherNav() {}

    static void install(String pkg, ClassLoader cl) {
        XposedBridge.log(TAG + "[" + pkg + "] loaded, property " + NavImmersive.PROP + " = "
                + NavImmersive.enabled());
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                android.view.View v = null;
                WindowManager.LayoutParams lp = null;
                for (Object a : p.args) {
                    if (a instanceof android.view.View) v = (android.view.View) a;
                    if (a instanceof WindowManager.LayoutParams) lp = (WindowManager.LayoutParams) a;
                }
                if (lp == null || !isTaskbar(lp)) return;
                if (!on(v)) return;
                String sig = lp.width + "x" + lp.height + " y=" + lp.y + " g=" + lp.gravity + " fl=0x"
                        + Integer.toHexString(lp.flags) + " " + insetsSummary(lp);
                if (!sig.equals(sLastSig)) {
                    sLastSig = sig;
                    XposedBridge.log(TAG + "taskbar window now: " + sig);
                }
                strip(lp);
                if (v != null) watch(v);
            }
        };
        int n = 0;
        try {
            Class<?> g = XposedHelpers.findClass("android.view.WindowManagerGlobal", cl);
            n += XposedBridge.hookAllMethods(g, "addView", h).size();
            n += XposedBridge.hookAllMethods(g, "updateViewLayout", h).size();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[" + pkg + "] window hooks failed: " + t);
        }
        XposedBridge.log(TAG + "[" + pkg + "] taskbar hooks: " + n);
        keepPillStill(pkg, cl);
    }

    private static boolean sPillLogged = false;
    /** How far the resting pill is pushed down (dp), from the NetSpeed setting. */
    private static volatile int sPillDyDp = 9;
    private static int sCfgTick = 0;

    /** Read the pill drop from the settings app; re-seat the handle when it changed. */
    private static void refreshPillDy(android.view.View root) {
        try {
            int dp = sPillDyDp;
            try (android.database.Cursor c = root.getContext().getContentResolver().query(Cfg.URI, null, null, null, null)) {
                if (c == null) return;
                while (c.moveToNext()) {
                    if (Cfg.PILL_DY.equals(c.getString(0))) dp = Integer.parseInt(c.getString(1));
                }
            }
            if (dp != sPillDyDp) {
                sPillDyDp = Math.max(0, Math.min(12, dp));
                android.view.View h = findHandle(root);
                if (h != null) h.setTranslationY(0f);   // goes through the filter, lands on the new drop
                XposedBridge.log(TAG + "pill drop now " + sPillDyDp + " dp");
            }
        } catch (Throwable ignored) { }
    }

    private static android.view.View findHandle(android.view.View v) {
        if (v.getClass().getSimpleName().equals("StashedHandleView")) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View r = findHandle(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    /**
     * The launcher nudges the gesture pill by +-2 dp steps every minute (a burn-in shift, +-6 dp in
     * all), so it keeps drifting up and down. Steps that are exact multiples of 2 dp are turned
     * into 0; continuous translations (swipes, hints, stash animations) pass untouched.
     */
    private static void keepPillStill(String pkg, ClassLoader cl) {
        try {
            final Class<?> hc = XposedHelpers.findClass("com.android.launcher3.taskbar.StashedHandleView", cl);
            XposedBridge.hookAllMethods(android.view.View.class, "setTranslationY", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!hc.isInstance(p.thisObject)) return;
                    float v = (Float) p.args[0];
                    float dens = ((android.view.View) p.thisObject).getResources().getDisplayMetrics().density;
                    float dy = sPillDyDp * dens;
                    if (v == 0f) {                       // at rest: sit lower, towards the bezel
                        if (dy != 0f) p.args[0] = dy;
                        return;
                    }
                    if (Math.abs(v) > 20f) return;
                    float k = v / (2f * dens);
                    if (Math.abs(k - Math.round(k)) > 0.03f) return;
                    p.args[0] = dy;
                    if (!sPillLogged) {
                        sPillLogged = true;
                        StringBuilder sb = new StringBuilder("pill burn-in shift " + v + " -> 0, from:");
                        StackTraceElement[] st = new Throwable().getStackTrace();
                        for (int i = 0; i < Math.min(st.length, 14); i++) sb.append("\n    ").append(st[i]);
                        XposedBridge.log(TAG + sb);
                    }
                }
            });
            XposedBridge.log(TAG + "[" + pkg + "] pill shift filter ready");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[" + pkg + "] pill shift filter not installed: " + t);
        }
    }

    private static String sLastSig = "";
    private static java.lang.ref.WeakReference<android.view.View> sRoot;
    private static android.os.Handler sH;
    private static String sLastViews = "";

    /** Log where the pill (the Taskbar's views) really is, whenever it changes - for the dump. */
    private static void watch(android.view.View root) {
        sRoot = new java.lang.ref.WeakReference<>(root);
        if (sH != null) return;
        refreshPillDy(root);   // before the first frame, so the pill doesn't start in the wrong place
        sH = new android.os.Handler(android.os.Looper.getMainLooper());
        sH.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    android.view.View r = sRoot == null ? null : sRoot.get();
                    if (r != null && sCfgTick++ % 4 == 0) refreshPillDy(r);
                    if (r != null) {
                        StringBuilder sb = new StringBuilder();
                        int[] loc = new int[2];
                        r.getLocationOnScreen(loc);
                        sb.append("root y=").append(loc[1]).append(" h=").append(r.getHeight())
                                .append(" ty=").append(r.getTranslationY()).append(" a=").append(r.getAlpha());
                        collect(r, r, sb, 0);
                        String s = sb.toString();
                        if (!s.equals(sLastViews)) {
                            sLastViews = s;
                            XposedBridge.log(TAG + "taskbar views: " + s);
                        }
                    }
                } catch (Throwable ignored) { }
                sH.postDelayed(this, 1500);
            }
        }, 3000);
    }

    private static void collect(android.view.View root, android.view.View v, StringBuilder sb, int depth) {
        if (!(v instanceof android.view.ViewGroup) || depth > 0) {
            if (v != root && v.getVisibility() == android.view.View.VISIBLE && v.getWidth() > 0 && sb.length() < 1800) {
                int[] l = new int[2];
                v.getLocationOnScreen(l);
                sb.append(" | ").append(v.getClass().getSimpleName()).append(" y=").append(l[1])
                        .append(" h=").append(v.getHeight()).append(" ty=").append(v.getTranslationY())
                        .append(" a=").append(v.getAlpha());
            }
        }
        if (v instanceof android.view.ViewGroup && depth < 4) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(root, g.getChildAt(i), sb, depth + 1);
        }
    }

    private static String insetsSummary(WindowManager.LayoutParams lp) {
        try {
            Object arr = XposedHelpers.getObjectField(lp, "providedInsets");
            if (!(arr instanceof Object[])) return "no insets";
            StringBuilder sb = new StringBuilder();
            for (Object prov : (Object[]) arr) {
                if (prov == null) continue;
                sb.append("[t=").append(XposedHelpers.callMethod(prov, "getType"))
                        .append(" size=").append(XposedHelpers.callMethod(prov, "getInsetsSize")).append("]");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "insets? " + t;
        }
    }

    static boolean isTaskbar(WindowManager.LayoutParams lp) {
        CharSequence title = lp.getTitle();
        return title != null && title.toString().toLowerCase(java.util.Locale.US).contains("taskbar");
    }

    private static boolean sLoggedSrc = false;

    /** On if the system property is set, or the NetSpeed app's setting is on (read directly). */
    static boolean on(android.view.View v) {
        if (NavImmersive.enabled()) return true;
        boolean on = false;
        String how = "app setting";
        try {
            android.content.Context ctx = v != null ? v.getContext() : null;
            if (ctx == null) {
                how = "no context";
            } else {
                try (android.database.Cursor c = ctx.getContentResolver().query(Cfg.URI, null, null, null, null)) {
                    if (c == null) how = "settings app not reachable";
                    else while (c.moveToNext()) {
                        if (Cfg.NAV_IMMERSIVE.equals(c.getString(0))) on = Boolean.parseBoolean(c.getString(1));
                    }
                }
            }
        } catch (Throwable t) {
            how = "settings read failed: " + t;
        }
        if (!sLoggedSrc) {
            sLoggedSrc = true;
            XposedBridge.log(TAG + "taskbar window seen, immersive = " + on + " (" + how + ")");
        }
        return on;
    }

    /** Zero the navigation-bar inset a Taskbar window provides (gestures untouched). */
    static void strip(WindowManager.LayoutParams lp) {
        try {
            int changed = stripOne(lp);
            // The launcher also gives one copy of the params per screen rotation, and the
            // system uses those when present.
            Object rot = null;
            try { rot = XposedHelpers.getObjectField(lp, "paramsForRotation"); } catch (Throwable ignored) {}
            if (rot instanceof Object[]) {
                for (Object r : (Object[]) rot) {
                    if (r instanceof WindowManager.LayoutParams) changed += stripOne((WindowManager.LayoutParams) r);
                }
            }
            if (changed > 0 && !sLogged) {
                sLogged = true;
                XposedBridge.log(TAG + "taskbar navigation inset removed (" + changed + ")");
            }
        } catch (Throwable t) {
            if (!sLogged) {
                sLogged = true;
                XposedBridge.log(TAG + "couldn't change the taskbar inset: " + t);
            }
        }
    }

    private static int stripOne(WindowManager.LayoutParams lp) {
        Object arr = XposedHelpers.getObjectField(lp, "providedInsets");
        if (!(arr instanceof Object[])) return 0;
        int nav = WindowInsets.Type.navigationBars(), changed = 0;
        for (Object prov : (Object[]) arr) {
            if (prov == null) continue;
            int type = (Integer) XposedHelpers.callMethod(prov, "getType");
            if (type != nav) continue;
            XposedHelpers.callMethod(prov, "setInsetsSize", Insets.NONE);
            changed++;
        }
        return changed;
    }
}
