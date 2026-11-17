package com.netspeed.xposed;

import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hides the big "R" that the status bar shows beside the signal bars while roaming
 * (view "mobile_roaming_updated"). The small raised R on top of the bars stays.
 * Switch: Cfg.HIDE_SIDE_R.
 */
final class HideSideRoam {

    private static final String TAG = "NetSpeedXposed: [roam] ";
    private static final String VIEW_NAME = "mobile_roaming_updated";

    private HideSideRoam() {}

    static void install(ClassLoader cl) {
        try {
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        View v = (View) p.thisObject;
                        if (v.getId() != View.NO_ID && p.method.getName().equals("setVisibility")
                                && ((Integer) p.args[0]) == View.VISIBLE
                                && "mobile_roaming".equals(v.getResources().getResourceEntryName(v.getId())))
                            logSize(v, "(small one) wanted visible");
                        if (!Cfg.b(Cfg.HIDE_SIDE_R, Cfg.D_HIDE_SIDE_R)) return;
                        if (v.getId() == View.NO_ID || !isTarget(v)) return;
                        if (p.method.getName().equals("setVisibility")) {
                            if (((Integer) p.args[0]) == View.VISIBLE) logSize(v, "wanted visible");
                            p.args[0] = View.GONE;
                        }
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        boolean on = Cfg.b(Cfg.HIDE_SIDE_R, Cfg.D_HIDE_SIDE_R);
                        View v = (View) p.thisObject;
                        if (v.getId() == View.NO_ID) return;
                        if (p.method.getName().equals("onAttachedToWindow")) {
                            String nm = v.getResources().getResourceEntryName(v.getId());
                            if ("mobile_roaming".equals(nm) || "mobile_roaming_updated".equals(nm)
                                    || "mobile_signal".equals(nm) || "mobile_type".equals(nm)) logSize(v, "attached");
                        }
                        boolean tgt = isTarget(v);
                        if (tgt && SEEN.add("state:" + on))
                            XposedBridge.log(TAG + "hide switch is " + (on ? "ON" : "OFF"));
                        if (!on || !tgt) return;
                        if (v.getVisibility() != View.GONE) {
                            XposedBridge.log(TAG + "hiding " + v.getResources().getResourceEntryName(v.getId())
                                    + " (was " + v.getVisibility() + ")");
                            v.setVisibility(View.GONE);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, hook);
            XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", hook);
            // second line of defence: even when something shows the view by another route, don't draw it
            XposedHelpers.findAndHookMethod(android.widget.ImageView.class, "onDraw", android.graphics.Canvas.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                android.widget.ImageView iv = (android.widget.ImageView) p.thisObject;
                                int id = iv.getId();
                                if (id == View.NO_ID) return;
                                String n = NAMES.get(id);
                                if (n == null) {
                                    try { n = iv.getResources().getResourceEntryName(id); } catch (Throwable t) { n = ""; }
                                    NAMES.put(id, n);
                                }
                                if (!n.contains("roam")) return;
                                boolean side = VIEW_NAME.equals(n) || "mobile_roaming_large".equals(n);
                                boolean on = Cfg.b(Cfg.HIDE_SIDE_R, Cfg.D_HIDE_SIDE_R);
                                if (SEEN.add("draw:" + n + (side && on))) XposedBridge.log(TAG + n + " is being DRAWN ("
                                        + iv.getWidth() + "x" + iv.getHeight() + ", visibility " + iv.getVisibility()
                                        + ")" + (side && on ? " - skipping it" : ""));
                                if (side && on) p.setResult(null);
                            } catch (Throwable ignored) { }
                        }
                    });
            // the hidden views still take their space in the row: measure them (and the spacer next to them) as 0 x 0
            XC_MethodHook measure = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        View v = (View) p.thisObject;
                        int id = v.getId();
                        if (id == View.NO_ID || !Cfg.b(Cfg.HIDE_SIDE_R, Cfg.D_HIDE_SIDE_R)) return;
                        String n = NAMES.get(id);
                        if (n == null) {
                            try { n = v.getResources().getResourceEntryName(id); } catch (Throwable t) { n = ""; }
                            NAMES.put(id, n);
                        }
                        if (VIEW_NAME.equals(n) || "mobile_roaming_large".equals(n) || "mobile_roaming_space".equals(n)) {
                            XposedHelpers.callMethod(v, "setMeasuredDimension", 0, 0);
                            if (SEEN.add("measure:" + n)) XposedBridge.log(TAG + n + " measured as 0x0 (no gap left)");
                        }
                    } catch (Throwable ignored) { }
                }
            };
            XposedHelpers.findAndHookMethod(android.widget.ImageView.class, "onMeasure", int.class, int.class, measure);
            try { XposedHelpers.findAndHookMethod(android.widget.Space.class, "onMeasure", int.class, int.class, measure); }
            catch (Throwable ignored) { }
            XposedBridge.log(TAG + "installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "install failed: " + t);
        }
    }

    /** For finding which roaming view is the big one: name, size and image size, once per name. */
    private static void logSize(final View v, final String what) {
        try {
            final String n = v.getResources().getResourceEntryName(v.getId());
            int k = 0;
            while (k < 4 && !SEEN.add("size:" + n + k)) k++;
            if (k >= 4) return;
            v.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        int dw = -1, dh = -1;
                        if (v instanceof android.widget.ImageView && ((android.widget.ImageView) v).getDrawable() != null) {
                            dw = ((android.widget.ImageView) v).getDrawable().getIntrinsicWidth();
                            dh = ((android.widget.ImageView) v).getDrawable().getIntrinsicHeight();
                        }
                        XposedBridge.log(TAG + n + " " + what + ": view " + v.getWidth() + "x" + v.getHeight()
                                + ", image " + dw + "x" + dh + ", visibility " + v.getVisibility()
                                + ", alpha " + v.getAlpha());
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable ignored) { }
    }

    private static final java.util.Map<Integer, String> NAMES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> SEEN = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static boolean isTarget(View v) {
        try {
            String n = v.getResources().getResourceEntryName(v.getId());
            if (n != null && n.contains("roam") && SEEN.add(n))
                XposedBridge.log(TAG + "roaming view seen: " + n + " (" + v.getClass().getSimpleName() + ")");
            // the big side R: "mobile_roaming_updated" on this ROM, "mobile_roaming_large" in stock Android;
            // the small raised R on the bars ("mobile_roaming") is left alone
            return VIEW_NAME.equals(n) || "mobile_roaming_large".equals(n);
        } catch (Throwable t) {
            return false;
        }
    }
}
