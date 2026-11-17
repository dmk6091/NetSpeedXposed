package com.dmk.ostweaks;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.provider.Settings;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Status bar side / top padding. System UI builds its content area from a few dimens
 * (rounded corner padding, status bar start/end padding, OPlus start/end padding, top padding).
 * Those lookups are answered with our values so the clock and icons sit at the screen edges.
 *
 * On OxygenOS the real left/right padding comes from StatusBarPaddingProvider (computed from the
 * screen's corner radius), so its result Rect is overridden as well.
 *
 * Config: Settings.System "ostweaks_sb_padding" = "left,right[,top[,bottom]]" in dp (default "2,2";
 * top is left to the ROM unless given).
 * Changing it needs a System UI restart.
 */
final class StatusBarPadding {
    private static final String TAG = Hook.TAG + "padding: ";
    private static final String PKG = "com.android.systemui";
    private static final int LEFT = 0, RIGHT = 1, TOP = 2, BOTH = 3;
    private static final String[][] DIMENS = {
            {"rounded_corner_content_padding", "3"},
            {"status_bar_padding_start", "0"},
            {"status_bar_padding_end", "1"},
            {"oplus_status_bar_padding_start", "0"},
            {"oplus_status_bar_padding_end", "1"},
            {"status_bar_padding_top", "2"},
    };

    /** resId -> which side */
    private static final Map<Integer, Integer> ids = new HashMap<>();
    private static volatile boolean resolved;
    private static float[] dp = {2f, 2f, -1f, -1f};
    private static boolean configRead;

    static void install(ClassLoader cl) {
        try {
            Class<?> prov = Class.forName("com.oplus.systemui.statusbar.phone.StatusBarPaddingProvider", false, cl);
            int k = de.robv.android.xposed.XposedBridge.hookAllMethods(prov, "getHomeStatusBarLeftRightPadding", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object r = p.getResult();
                    if (!(r instanceof android.graphics.Rect)) return;
                    try {
                        Resources res = ((Context) de.robv.android.xposed.XposedHelpers.getObjectField(p.thisObject, "context")).getResources();
                        android.graphics.Rect rect = (android.graphics.Rect) r;
                        rect.left = Math.round(px(res, LEFT));
                        rect.right = Math.round(px(res, RIGHT));
                        if (dp[3] >= 0) {
                            // Explicit top + bottom: bottom space lifts the vertically centred icon row.
                            if (dp[TOP] >= 0) rect.top = Math.round(px(res, TOP));
                            rect.bottom = Math.round(dp[3] * res.getDisplayMetrics().density);
                        } else if (dp[TOP] >= 0) {
                            // Shift the content up: keep its height, move the spare space below it.
                            int sum = rect.top + rect.bottom;
                            int top = Math.min(sum, Math.round(px(res, TOP)));
                            rect.top = top;
                            rect.bottom = sum - top;
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "provider: " + t);
                    }
                }
            }).size();
            XposedBridge.log(TAG + "OPlus padding provider hooked (" + k + ")");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "no OPlus padding provider: " + t);
        }
        installDimens();
    }

    static void installDimens() {
        XC_MethodHook h = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Integer side = lookup((Resources) p.thisObject, (Integer) p.args[0]);
                if (side == null) return;
                if (side == TOP) { readConfig(); if (dp[TOP] < 0) return; }
                float px = px((Resources) p.thisObject, side);
                String m = p.method.getName();
                if ("getDimension".equals(m)) p.setResult(px);
                else p.setResult(Math.round(px));
            }
        };
        int n = 0;
        for (String m : new String[]{"getDimensionPixelSize", "getDimensionPixelOffset", "getDimension"}) {
            try {
                de.robv.android.xposed.XposedHelpers.findAndHookMethod(Resources.class, m, int.class, h);
                n++;
            } catch (Throwable t) {
                XposedBridge.log(TAG + m + ": " + t);
            }
        }
        XposedBridge.log(TAG + "status bar padding hooks (" + n + ")");
    }

    private static Integer lookup(Resources res, int id) {
        if (!resolved) resolve(res);
        return ids.get(id);
    }

    private static synchronized void resolve(Resources res) {
        if (resolved) return;
        try {
            if (res.getIdentifier("status_bar_padding_start", "dimen", PKG) == 0) return; // not System UI resources yet
            for (String[] d : DIMENS) {
                int id = res.getIdentifier(d[0], "dimen", PKG);
                if (id != 0) ids.put(id, Integer.parseInt(d[1]));
            }
            resolved = true;
            XposedBridge.log(TAG + "dimens found: " + ids.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + "resolve: " + t);
        }
    }

    private static float px(Resources res, int side) {
        readConfig();
        float d = res.getDisplayMetrics().density;
        if (side == BOTH) return Math.min(dp[LEFT], dp[RIGHT]) * d;
        return Math.max(0f, dp[side]) * d;
    }

    private static void readConfig() {
        if (configRead) return;
        configRead = true;
        try {
            Context ctx = currentContext();
            if (ctx == null) { configRead = false; return; }
            ContentResolver cr = ctx.getContentResolver();
            String v = Settings.System.getString(cr, "ostweaks_sb_padding");
            if (v == null || v.trim().isEmpty()) return;
            String[] parts = v.split(",");
            for (int i = 0; i < 4 && i < parts.length; i++) dp[i] = Float.parseFloat(parts[i].trim());
            XposedBridge.log(TAG + "padding dp left/right/top/bottom = " + dp[0] + "/" + dp[1] + "/" + dp[2] + "/" + dp[3]);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "config: " + t + " (using defaults)");
        }
    }

    private static Context currentContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return (Context) app;
        } catch (Throwable t) {
            return null;
        }
    }
}
