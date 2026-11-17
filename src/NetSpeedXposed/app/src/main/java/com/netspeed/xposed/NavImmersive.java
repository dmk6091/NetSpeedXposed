package com.netspeed.xposed;

import android.content.res.Resources;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Immersive gesture navigation: the bottom inset reserved for the gesture pill
 * (navigation_bar_height) becomes 0, so apps draw to the bottom edge and the pill floats
 * over them. The pill's own window (navigation_bar_frame_height) and the gesture area
 * (navigation_bar_gesture_height) are left alone, so the pill and swipes keep working.
 *
 * Switched by the system property persist.sys.netspeed.immersive (set by the settings app
 * with root), because the system server starts long before any app's settings are readable.
 * Needs a reboot to apply.
 */
final class NavImmersive {
    static final String PROP = "persist.sys.netspeed.immersive";
    private static final String TAG = "NetSpeedXposed: [nav] ";

    private NavImmersive() {}

    static boolean enabled() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (Boolean) sp.getMethod("getBoolean", String.class, boolean.class).invoke(null, PROP, false);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean sProp = false;
    private static boolean sSystemUi = false;

    /** On if the property is set (any process) or, in System UI, the app setting is on. */
    static boolean active() {
        return sProp || (sSystemUi && Cfg.b(Cfg.NAV_IMMERSIVE, Cfg.D_NAV_IMMERSIVE));
    }

    static void install(final String where) {
        sProp = enabled();
        sSystemUi = "systemui".equals(where);
        XposedBridge.log(TAG + "[" + where + "] property " + PROP + " = " + sProp
                + (sSystemUi ? ", app setting decides too" : ""));
        // The system server can't read the app's settings: there, only the property counts.
        if (!sProp && !sSystemUi) return;
        Resources sys = Resources.getSystem();
        final Set<Integer> ids = new HashSet<>();
        for (String n : new String[]{"navigation_bar_height", "navigation_bar_height_landscape"}) {
            int id = sys.getIdentifier(n, "dimen", "android");
            if (id != 0) ids.add(id);
        }
        if (ids.isEmpty()) {
            XposedBridge.log(TAG + "[" + where + "] navigation bar height not found");
            return;
        }
        XC_MethodHook zero = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!(p.args[0] instanceof Integer) || !ids.contains(p.args[0])) return;
                if (!active()) return;
                Object r = p.getResult();
                if (r instanceof Integer) p.setResult(0);
                else if (r instanceof Float) p.setResult(0f);
            }
        };
        int n = 0;
        for (String m : new String[]{"getDimensionPixelSize", "getDimensionPixelOffset", "getDimension"}) {
            try {
                XposedHelpers.findAndHookMethod(Resources.class, m, int.class, zero);
                n++;
            } catch (Throwable t) {
                XposedBridge.log(TAG + "[" + where + "] hook " + m + " failed: " + t);
            }
        }
        XposedBridge.log(TAG + "[" + where + "] immersive navigation hooks ready (" + n + ")");
    }
}
