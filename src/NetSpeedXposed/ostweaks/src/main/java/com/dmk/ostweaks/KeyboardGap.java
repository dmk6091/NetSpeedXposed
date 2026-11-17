package com.dmk.ostweaks;

import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/** Removes the empty strip below Gboard (the navigation-bar inset padding and the IME nav-bar frame). */
final class KeyboardGap {
    private static boolean done;

    static void handle(final LoadPackageParam lp) {
        if (!"com.google.android.inputmethod.latin".equals(lp.packageName) || !lp.packageName.equals(lp.processName)) return;
        Cfg.whenReady(new Runnable() { public void run() {
            if (!Cfg.get("ostweaks_kb_gap", true)) { XposedBridge.log(Hook.TAG + "keyboard gap: off"); return; }
            synchronized (KeyboardGap.class) { if (done) return; done = true; }
            final int nav = WindowInsets.Type.navigationBars();
            XposedBridge.hookAllMethods(View.class, "dispatchApplyWindowInsets", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length == 0 || !(p.args[0] instanceof WindowInsets)) return;
                        WindowInsets in = (WindowInsets) p.args[0];
                        Insets sys = in.getSystemWindowInsets();
                        if (sys.bottom == 0 && in.getInsets(nav).bottom == 0) return;
                        p.args[0] = new WindowInsets.Builder(in)
                                .setInsets(nav, Insets.NONE)
                                .setInsetsIgnoringVisibility(nav, Insets.NONE)
                                .setSystemWindowInsets(Insets.of(sys.left, sys.top, sys.right, 0))
                                .build();
                    } catch (Throwable ignored) {}
                }
            });
            // hide the IME's own navigation-bar frame (chevron + gesture pill)
            for (String c : new String[]{"android.inputmethodservice.NavigationBarController$Impl"}) {
                try {
                    Class<?> k = XposedHelpers.findClassIfExists(c, null);
                    if (k == null) { XposedBridge.log(Hook.TAG + "keyboard gap: no " + c); continue; }
                    StringBuilder sb = new StringBuilder();
                    for (java.lang.reflect.Method m : k.getDeclaredMethods()) sb.append(m.getName()).append(' ');
                    XposedBridge.log(Hook.TAG + "keyboard gap: " + c + " methods " + sb);
                    XposedBridge.hookAllMethods(k, "onNavButtonFlagsChanged", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) { hideFrame(p.thisObject); }
                    });
                    XposedBridge.hookAllMethods(k, "updateInsets", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) { hideFrame(p.thisObject); }
                    });
                } catch (Throwable t) { XposedBridge.log(Hook.TAG + "keyboard gap hook: " + t); }
            }
            XposedBridge.log(Hook.TAG + "keyboard gap: on");
        } });
    }

    private static void hideFrame(Object impl) {
        try {
            Object f = XposedHelpers.getObjectField(impl, "mNavigationBarFrame");
            if (f instanceof View) {
                View v = (View) f;
                v.setVisibility(View.GONE);
                android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (lp != null && lp.height != 0) { lp.height = 0; v.setLayoutParams(lp); }
            }
        } catch (Throwable t) { XposedBridge.log(Hook.TAG + "keyboard gap frame: " + t); }
    }
}
