package com.netspeed.xposed;

import android.content.res.Resources;
import android.graphics.Insets;
import android.view.WindowInsets;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Keyboard without the blank strip under its keys. With gesture navigation the keyboard (Gboard) pads its
 * bottom by the gesture pill's height, which it reads itself, so zeroing the system's navigation bar inset
 * doesn't remove it. Inside the keyboard's own process only, those heights read as 0; the pill, its window
 * and the swipe area everywhere else are untouched. Follows immersive navigation (same property).
 */
final class KbSpace {
    private static final String TAG = "NetSpeedXposed: [kb] ";

    private KbSpace() {}

    private static void log(String m) {
        XposedBridge.log(m);
        try { android.util.Log.e("KbSpace", m); } catch (Throwable ignored) { }
    }

    static void install(final String pkg) {
        log(TAG + pkg + ": loaded");
        if (!NavImmersive.enabled()) {
            log(TAG + pkg + ": immersive navigation is off, keyboard left alone");
            return;
        }
        Resources sys = Resources.getSystem();
        final Map<Integer, String> ids = new HashMap<>();
        for (String n : new String[]{"navigation_bar_height", "navigation_bar_height_landscape", "navigation_bar_frame_height",
                "navigation_bar_frame_height_landscape", "navigation_bar_gesture_height", "navigation_bar_gesture_larger_height",
                "navigation_bar_width"}) {
            int id = sys.getIdentifier(n, "dimen", "android");
            if (id != 0) ids.put(id, n);
        }
        final Set<Integer> seen = new HashSet<>();
        XC_MethodHook zero = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!(p.args[0] instanceof Integer)) return;
                String n = ids.get(p.args[0]);
                if (n == null) return;
                Object r = p.getResult();
                if (seen.add((Integer) p.args[0])) log(TAG + pkg + " reads " + n + " = " + r + " -> 0");
                if (r instanceof Integer) p.setResult(0);
                else if (r instanceof Float) p.setResult(0f);
            }
        };
        int k = 0;
        for (String m : new String[]{"getDimensionPixelSize", "getDimensionPixelOffset", "getDimension"}) {
            try { XposedHelpers.findAndHookMethod(Resources.class, m, int.class, zero); k++; } catch (Throwable t) {
                log(TAG + "hook " + m + " failed: " + t);
            }
        }
        // the same heights as the keyboard window gets them: gesture / navigation insets at the bottom
        final int ime = WindowInsets.Type.ime();
        final boolean[] said = {false};
        XC_MethodHook noBottom = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length == 1 && p.args[0] instanceof Integer && (((Integer) p.args[0]) & ime) != 0) return;
                Object r = p.getResult();
                if (!(r instanceof Insets)) return;
                Insets i = (Insets) r;
                if (i.bottom <= 0) return;
                if (!said[0]) { said[0] = true; log(TAG + pkg + " " + p.method.getName() + " bottom " + i.bottom + " -> 0"); }
                p.setResult(Insets.of(i.left, i.top, i.right, 0));
            }
        };
        try { XposedHelpers.findAndHookMethod(WindowInsets.class, "getInsets", int.class, noBottom); k++; } catch (Throwable ignored) { }
        try { XposedHelpers.findAndHookMethod(WindowInsets.class, "getInsetsIgnoringVisibility", int.class, noBottom); k++; } catch (Throwable ignored) { }
        for (String m : new String[]{"getSystemGestureInsets", "getMandatorySystemGestureInsets", "getTappableElementInsets", "getSystemWindowInsets", "getStableInsets"}) {
            try { XposedHelpers.findAndHookMethod(WindowInsets.class, m, noBottom); k++; } catch (Throwable ignored) { }
        }
        // Gboard adds its own gap when the system says "gesture navigation": tell it buttons (the system reserves nothing for them here)
        final int modeId = sys.getIdentifier("config_navBarInteractionMode", "integer", "android");
        final boolean[] saidM = {false, false};
        try {
            XposedHelpers.findAndHookMethod(Resources.class, "getInteger", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (modeId == 0 || !Integer.valueOf(modeId).equals(p.args[0])) return;
                    if (!saidM[0]) { saidM[0] = true; log(TAG + pkg + " reads config_navBarInteractionMode = " + p.getResult() + " -> 0"); }
                    p.setResult(0);
                }
            });
            k++;
        } catch (Throwable t) { log(TAG + "hook getInteger failed: " + t); }
        XC_MethodHook navMode = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length < 2 || !"navigation_mode".equals(p.args[1])) return;
                if (!saidM[1]) { saidM[1] = true; log(TAG + pkg + " reads setting navigation_mode = " + p.getResult() + " -> 0"); }
                Object r = p.getResult();
                if (r instanceof Integer) p.setResult(0);
                else if (r instanceof String) p.setResult("0");
            }
        };
        for (String m : new String[]{"getInt", "getIntForUser", "getString", "getStringForUser"}) {
            try { k += XposedBridge.hookAllMethods(android.provider.Settings.Secure.class, m, navMode).size(); } catch (Throwable ignored) { }
        }
        log(TAG + pkg + ": keyboard space hooks ready (" + k + ")");
    }
}
