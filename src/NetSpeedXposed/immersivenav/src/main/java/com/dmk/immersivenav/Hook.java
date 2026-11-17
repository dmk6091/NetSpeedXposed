package com.dmk.immersivenav;

import android.graphics.Insets;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Immersive navigation — gesture nav: apps draw to the bottom edge and the pill floats
 * over them (removes the reserved navigationBars strip). Gesture detection is untouched.
 *
 * The navigationBars inset is provided by whichever window owns the nav bar. That window
 * can live in SystemUI (NavigationBar) or, on CLO/AOSP gesture nav, in the launcher
 * (Taskbar, com.android.launcher3). So in both of those processes we:
 *   1. force any navigationBars InsetsFrameProvider to Insets.NONE when its size is set;
 *   2. zero nav providers on windows added/updated via WindowManagerGlobal;
 *   3. sweep already-attached windows (the nav bar is created before we load) and push an
 *      updateViewLayout so the window manager recomputes the inset to 0.
 * Only the navigationBars type is touched — systemGestures / mandatorySystemGestures stay,
 * so swipe gestures keep working. In the keyboard process, the IME nav bar is dropped.
 * Everything is wrapped so a miss is a no-op, never a crash.
 */
public class Hook implements IXposedHookLoadPackage {
    private static final String TAG = "ImmersiveNav: ";
    private static boolean installed;
    private static Object wmgInstance;

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        String pkg = lp.packageName;
        boolean navOwner = "com.android.systemui".equals(pkg) || "com.android.launcher3".equals(pkg)
                || "com.android.launcher".equals(pkg);   // OxygenOS / ColorOS launcher
        if (!navOwner) {
            if (pkg.equals(lp.processName)) hideImeNavBar(pkg);
            return;
        }
        synchronized (Hook.class) {
            if (installed) return;
            installed = true;
        }
        final int navType = WindowInsets.Type.navigationBars();

        // 1) force navigationBars providers to NONE as their size is set
        try {
            Class<?> ifp = XposedHelpers.findClass("android.view.InsetsFrameProvider", null);
            XposedBridge.hookAllMethods(ifp, "setInsetsSize", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!(p.args.length > 0 && p.args[0] instanceof Insets)) return;
                    if (Insets.NONE.equals(p.args[0])) return;
                    if ((typeOf(p.thisObject) & navType) != 0) p.args[0] = Insets.NONE;
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "setInsetsSize hook failed: " + t);
        }

        // 2) zero nav providers on windows added/updated later
        try {
            Class<?> wmg = XposedHelpers.findClass("android.view.WindowManagerGlobal", null);
            XC_MethodHook fix = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (wmgInstance == null) wmgInstance = p.thisObject;
                    for (Object a : p.args) {
                        if (a instanceof WindowManager.LayoutParams) {
                            zeroNav((WindowManager.LayoutParams) a, navType);
                        }
                    }
                }
            };
            XposedBridge.hookAllMethods(wmg, "addView", fix);
            XposedBridge.hookAllMethods(wmg, "updateViewLayout", fix);
            XposedBridge.log(TAG + "active in " + pkg);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "window hook failed: " + t);
        }

        // 3) sweep windows that already exist (the nav bar, created before we loaded)
        final Handler h = new Handler(Looper.getMainLooper());
        for (int delay : new int[]{1500, 4000, 9000, 18000}) {
            h.postDelayed(new Runnable() { public void run() { sweep(navType); } }, delay);
        }
    }

    private static void sweep(int navType) {
        if (wmgInstance == null) return;
        List<View> views = new ArrayList<>();
        try {
            Object mViews = XposedHelpers.getObjectField(wmgInstance, "mViews");
            if (mViews instanceof List) {
                for (Object o : (List<?>) mViews) if (o instanceof View) views.add((View) o);
            } else if (mViews != null && mViews.getClass().isArray()) {
                for (int i = 0; i < Array.getLength(mViews); i++) {
                    Object o = Array.get(mViews, i);
                    if (o instanceof View) views.add((View) o);
                }
            }
        } catch (Throwable t) {
            return;
        }
        for (View v : views) {
            ViewGroup.LayoutParams p;
            try { p = v.getLayoutParams(); } catch (Throwable t) { continue; }
            if (!(p instanceof WindowManager.LayoutParams)) continue;
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) p;
            if (zeroNav(lp, navType)) {
                try { XposedHelpers.callMethod(wmgInstance, "updateViewLayout", v, lp); }
                catch (Throwable ignored) {}
            }
        }
    }

    private static boolean zeroNav(WindowManager.LayoutParams lp, int navType) {
        Object[] provs = providers(lp);
        if (provs == null) return false;
        boolean changed = false;
        for (Object prov : provs) {
            if (prov == null || (typeOf(prov) & navType) == 0) continue;
            Object size = null;
            try { size = XposedHelpers.callMethod(prov, "getInsetsSize"); } catch (Throwable ignored) {}
            if (Insets.NONE.equals(size)) continue;
            try {
                XposedHelpers.callMethod(prov, "setInsetsSize", Insets.NONE);
                changed = true;
            } catch (Throwable ignored) {}
        }
        return changed;
    }

    private static Object[] providers(WindowManager.LayoutParams lp) {
        try {
            Object v = XposedHelpers.getObjectField(lp, "providedInsets");
            if (v instanceof Object[]) return (Object[]) v;
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean imeHooked;

    private static void hideImeNavBar(String pkg) {
        synchronized (Hook.class) {
            if (imeHooked) return;
            imeHooked = true;
        }
        XC_MethodHook clear = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length > 0 && p.args[0] instanceof Integer) {
                    p.args[0] = ((Integer) p.args[0]) & ~1;
                }
            }
        };
        for (String c : new String[]{"android.inputmethodservice.NavigationBarController",
                "android.inputmethodservice.NavigationBarController$Impl"}) {
            Class<?> k = XposedHelpers.findClassIfExists(c, null);
            if (k != null) XposedBridge.hookAllMethods(k, "onNavButtonFlagsChanged", clear);
        }
    }

    private static int typeOf(Object provider) {
        try {
            return (int) XposedHelpers.callMethod(provider, "getType");
        } catch (Throwable t) {
            return -1;
        }
    }
}
