package com.netspeed.xposed;

import android.database.ContentObserver;
import android.graphics.Insets;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Smaller top inset for apps. The status bar window (System UI) tells the system how much space
 * apps must keep free at the top: by default the whole bar, so with the tall 4-row bar every app's
 * header lands well below it. Here the statusBars / tappableElement insets it provides are cut by
 * Cfg.APP_TOP_TRIM dp, so app content moves up behind the lower rows. The bar's window, its height
 * and the swipe-down gesture area (mandatorySystemGestures) stay as they are. Applies live: when
 * the setting changes, the bar's window layout is pushed again.
 */
final class AppInset {
    private static final String TAG = "NetSpeedXposed: [inset] ";
    private static final int TYPE_STATUS_BAR = WindowManager.LayoutParams.TYPE_STATUS_BAR;

    private static View sView;
    private static WindowManager.LayoutParams sLp;
    private static int sLastFull = 0;
    private static int sAppliedTrim = -1;
    private static boolean sObserving = false;

    private AppInset() {}

    static void install(ClassLoader cl) {
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                View v = null;
                WindowManager.LayoutParams lp = null;
                for (Object a : p.args) {
                    if (a instanceof View) v = (View) a;
                    if (a instanceof WindowManager.LayoutParams) lp = (WindowManager.LayoutParams) a;
                }
                if (lp == null || lp.type != TYPE_STATUS_BAR) return;
                if (v != null) {
                    sView = v;
                    sLp = lp;
                    observe(v);
                }
                apply(lp, v);
            }
        };
        int n = 0;
        try {
            Class<?> g = XposedHelpers.findClass("android.view.WindowManagerGlobal", cl);
            n += XposedBridge.hookAllMethods(g, "addView", h).size();
            n += XposedBridge.hookAllMethods(g, "updateViewLayout", h).size();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "window hooks failed: " + t);
        }
        XposedBridge.log(TAG + "status bar window hooks: " + n);
    }

    private static int trimPx(View v) {
        int dp = Math.max(0, Cfg.i(Cfg.APP_TOP_TRIM, Cfg.D_APP_TOP_TRIM));
        if (dp == 0 || v == null) return 0;
        return Math.round(dp * v.getResources().getDisplayMetrics().density);
    }

    private static void apply(WindowManager.LayoutParams lp, View v) {
        try {
            int trim = trimPx(v);
            int changed = applyOne(lp, trim);
            Object rot = null;
            try { rot = XposedHelpers.getObjectField(lp, "paramsForRotation"); } catch (Throwable ignored) {}
            if (rot instanceof Object[]) {
                for (Object r : (Object[]) rot) {
                    if (r instanceof WindowManager.LayoutParams) changed += applyOne((WindowManager.LayoutParams) r, trim);
                }
            }
            if (trim != sAppliedTrim) {
                sAppliedTrim = trim;
                XposedBridge.log(TAG + "apps' top inset trimmed by " + trim + " px (" + changed + " providers, bar "
                        + sLastFull + " px)");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "couldn't change the status bar inset: " + t);
        }
    }

    /** Sets the app-facing insets of one params object to (bar height - trim); idempotent. */
    private static int applyOne(WindowManager.LayoutParams lp, int trim) throws Throwable {
        // The bar's full height: the window height when it's at rest. While System UI stretches the
        // window (heads-up, shade animations) height is MATCH_PARENT, so use the last real one.
        int full = lp.height > 0 ? lp.height : sLastFull;
        if (lp.height > 0 && lp == sLp) sLastFull = lp.height;
        if (full <= 0) return 0;
        Object arr = XposedHelpers.getObjectField(lp, "providedInsets");
        if (!(arr instanceof Object[])) return 0;
        int sb = WindowInsets.Type.statusBars(), tap = WindowInsets.Type.tappableElement();
        int top = Math.max(0, full - trim);
        int changed = 0;
        for (Object prov : (Object[]) arr) {
            if (prov == null) continue;
            int type = (Integer) XposedHelpers.callMethod(prov, "getType");
            if (type != sb && type != tap) continue;
            XposedHelpers.callMethod(prov, "setInsetsSize", Insets.of(0, top, 0, 0));
            changed++;
        }
        return changed;
    }

    /** Re-push the bar's layout when the setting changes, so apps move without a restart. */
    private static void observe(View v) {
        if (sObserving) return;
        sObserving = true;
        try {
            Cfg.init(v.getContext());
            final Handler main = new Handler(Looper.getMainLooper());
            v.getContext().getContentResolver().registerContentObserver(Cfg.URI, true, new ContentObserver(main) {
                @Override
                public void onChange(boolean selfChange) {
                    // Cfg reloads on its own thread; give it a moment, then relayout if the trim moved.
                    main.postDelayed(AppInset::relayout, 400);
                }
            });
            // Settings may not have loaded when the bar was first added (boot): check again shortly.
            main.postDelayed(AppInset::relayout, 5000);
            main.postDelayed(AppInset::relayout, 20000);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "observer failed: " + t);
        }
    }

    private static void relayout() {
        View v = sView;
        WindowManager.LayoutParams lp = sLp;
        if (v == null || lp == null || !v.isAttachedToWindow()) return;
        Cfg.retryIfNeeded();
        if (trimPx(v) == sAppliedTrim) return;
        try {
            v.getContext().getSystemService(WindowManager.class).updateViewLayout(v, lp);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "relayout failed: " + t);
        }
    }
}
