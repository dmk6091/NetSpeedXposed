package com.netspeed.xposed;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.HardwarePropertiesManager;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.Locale;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Two-row status bar.
 *
 *   Row 1 left : clock, CPU temp / battery temp, weather icon + temperature
 *   Row 1 right: net speed, VoLTE / VoNR icons, battery
 *   Row 2 left : notification icons (full width, no camera cutout in the way)
 *   Row 2 right: other system icons (Wi-Fi, signal, Bluetooth, location ...)
 *
 * The status bar is made taller by adding Cfg.i(Cfg.ROW2_H, Cfg.D_ROW2_H) to the height System UI
 * reports for its window, so apps automatically start below both rows.
 */
final class DualRow {

    private static final String TAG = "NetSpeedXposed: [dual] ";
    private static final String SYSTEMUI = "com.android.systemui";

    // ---------------------------------------------------------------- settings

    /** Master switch. false = normal one-row status bar. */
    static final boolean ENABLED = true;

    /** Height of the second row, in dp. */
    /** Gap between row 1's icons and row 2. */
    /**
     * How much taller than stock the whole status bar gets. Row 2 is tucked into the
     * stock height first (it's tall because of the camera cutout), so 0 is usually enough;
     * raise it if row 2 looks cut off at the bottom.
     */

    /** Status icons whose slot name contains one of these stay on row 1 (right). */
    static final String[] ROW1_SLOT_KEYWORDS = {"volte", "vonr", "vowifi", "ims", "mobile"};

    /** Show CPU temperature / battery temperature / weather on row 1. */
    /** true = net speed sits on row 1 after the weather; false = stays on the right. */

    /** How often temperatures are refreshed, in milliseconds. */
    static final long TEMP_INTERVAL_MS = 5_000;
    /** How often charging current / power is read. */
    static final long POWER_INTERVAL_MS = 2_000;

    /** Weather is also refreshed whenever OmniJaws updates; this is a fallback. */
    static final long WEATHER_INTERVAL_MS = 15 * 60_000;

    // ---------------------------------------------------------------- internals

    private static final Uri OMNI_WEATHER = Uri.parse("content://org.omnirom.omnijaws.provider/weather");
    private static final Uri OMNI_SETTINGS = Uri.parse("content://org.omnirom.omnijaws.provider/settings");
    private static final String OMNI_PKG = "org.omnirom.omnijaws";

    private static final String BAR_CLASS = "com.android.systemui.statusbar.phone.PhoneStatusBarView";
    private static final String BAR_UTILS = "com.android.internal.policy.SystemBarUtils";

    /** When true, the height hook returns the original (one-row) height. */
    private static volatile boolean sBypass = false;
    /** Height of the info line drawn under the clock digits (row 2 must start below it). */
    private static volatile int sClockInfoPx = 0;
    private static boolean sHeightLogged = false;
    private static final Map<String, Boolean> sLogOnce = new HashMap<>();

    /** Row-2 icon container and the icons in it that are shown on row 1 instead. */
    private static volatile ViewGroup sIconContainer;
    private static final Set<View> SKIP = Collections.newSetFromMap(new WeakHashMap<>());
    /** Readable layout: the old row 1 / row 2 keep running but are not drawn while this is on. */
    private static volatile boolean sReadableOn = false;
    private static volatile boolean sDrawHookOk = false;
    /** Locked with "show the bar on the lock screen" on: the stock lock-screen status bar (carrier name, icons) is not drawn. */
    private static volatile boolean sHideKeyguardBar = false;
    private static volatile View sHideA, sHideB;
    private static final ThreadLocal<List<View>> HIDDEN = new ThreadLocal<>();

    private DualRow() {}

    /** Skips drawing row-1 icons inside the row-2 container (they are drawn on row 1). */
    private static void hookIconDraw() {
        try {
            XposedHelpers.findAndHookMethod(ViewGroup.class, "dispatchDraw", Canvas.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (sReadableOn && (p.thisObject == sHideA || p.thisObject == sHideB)) {
                        p.setResult(null);   // the readable layout is drawn instead
                        return;
                    }
                    if (sHideKeyguardBar && p.thisObject.getClass().getName().endsWith("KeyguardStatusBarView")) {
                        p.setResult(null);
                        return;
                    }
                    if (p.thisObject != sIconContainer) return;
                    List<View> hidden = new ArrayList<>();
                    synchronized (SKIP) {
                        for (View v : SKIP) {
                            if (v.getParent() == p.thisObject && v.getVisibility() == View.VISIBLE) {
                                try {
                                    XposedHelpers.callMethod(v, "setTransitionVisibility", View.INVISIBLE);
                                    hidden.add(v);
                                } catch (Throwable ignored) { }
                            }
                        }
                    }
                    HIDDEN.set(hidden);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (p.thisObject != sIconContainer) return;
                    List<View> o = HIDDEN.get();
                    HIDDEN.set(null);
                    if (o == null) return;
                    for (View v : o) {
                        try {
                            XposedHelpers.callMethod(v, "setTransitionVisibility", View.VISIBLE);
                        } catch (Throwable ignored) { }
                    }
                }
            });
            sDrawHookOk = true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "icon draw hook failed: " + t);
        }
    }

    static void install(ClassLoader cl) {
        if (!ENABLED) return;
        hookHeight("systemui");
        hookIconDraw();
        SysState.install(cl);

        try {
            XposedHelpers.findAndHookMethod(BAR_CLASS, cl, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    final ViewGroup bar = (ViewGroup) p.thisObject;
                    // Run after every other onFinishInflate hook (net speed indicator included).
                    bar.post(() -> {
                        try {
                            build(bar);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + "build failed: " + t);
                            XposedBridge.log(t);
                        }
                    });
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "onFinishInflate hook failed: " + t);
        }

        // System UI recalculates the layout on rotation / cutout changes; re-apply ours after.
        try {
            Class<?> bar = XposedHelpers.findClass(BAR_CLASS, cl);
            int n = XposedBridge.hookAllMethods(bar, "updateLayoutForCutout", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        applyRowHeights((ViewGroup) p.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "re-layout failed: " + t);
                    }
                }
            }).size();
            XposedBridge.log(TAG + "updateLayoutForCutout hooks: " + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "updateLayoutForCutout hook failed: " + t);
        }
    }

    // ------------------------------------------------------------ status bar height

    /**
     * Makes the status bar Cfg.i(Cfg.ROW2_H, Cfg.D_ROW2_H) taller by changing the height values Android reads
     * from its resources (status_bar_height_default / _portrait / _landscape).
     * Hooking Resources works in both System UI and the system framework, unlike hooks
     * on SystemBarUtils, which Android 16 compiles inline so they never fire.
     */
    static void hookHeight(final String where) {
        if (!ENABLED) return;
        Resources sys = Resources.getSystem();
        final java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (String n : new String[]{"status_bar_height_default", "status_bar_height_portrait",
                "status_bar_height_landscape", "status_bar_height"}) {
            int id = sys.getIdentifier(n, "dimen", "android");
            if (id != 0) ids.add(id);
        }
        if (ids.isEmpty()) {
            XposedBridge.log(TAG + "[" + where + "] status bar height resources not found");
            return;
        }
        XC_MethodHook add = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (sBypass || !(p.args[0] instanceof Integer) || !ids.contains(p.args[0])) return;
                Object r = p.getResult();
                Resources res = (Resources) p.thisObject;
                int extraDp = Cfg.b(Cfg.PRO, Cfg.D_PRO) ? Cfg.i(Cfg.PRO_EXTRA, Cfg.D_PRO_EXTRA) : Cfg.i(Cfg.EXTRA, Cfg.D_EXTRA);
                if (extraDp == 0) return;
                float extra = extraDp * res.getDisplayMetrics().density;
                if (r instanceof Integer) {
                    int h = (Integer) r;
                    if (h <= 0) return;
                    int nh = Math.max(h / 2, h + Math.round(extra));
                    p.setResult(nh);
                    logHeight(where, h, nh);
                } else if (r instanceof Float) {
                    float h = (Float) r;
                    if (h <= 0) return;
                    p.setResult(Math.max(h / 2f, h + extra));
                }
            }
        };
        int n = 0;
        for (String m : new String[]{"getDimensionPixelSize", "getDimensionPixelOffset", "getDimension"}) {
            try {
                XposedHelpers.findAndHookMethod(Resources.class, m, int.class, add);
                n++;
            } catch (Throwable t) {
                XposedBridge.log(TAG + "[" + where + "] hook " + m + " failed: " + t);
            }
        }
        XposedBridge.log(TAG + "[" + where + "] height hooks: " + n + " ids: " + ids.size());
    }

    private static void logHeight(String where, int from, int to) {
        if (sHeightLogged) return;
        sHeightLogged = true;
        XposedBridge.log(TAG + "[" + where + "] status bar height " + from + " -> " + to);
    }

    /** Status bar height without our second row. */
    private static int row1Height(Context ctx) {
        sBypass = true;
        try {
            Class<?> c = XposedHelpers.findClass(BAR_UTILS, ctx.getClassLoader());
            java.lang.reflect.Method m = c.getDeclaredMethod("getStatusBarHeight", Context.class);
            m.setAccessible(true);
            return (Integer) m.invoke(null, ctx);
        } catch (Throwable t) {
            Resources sys = Resources.getSystem();
            int id = sys.getIdentifier("status_bar_height", "dimen", "android");
            return id != 0 ? sys.getDimensionPixelSize(id) : dp(sys, 28);
        } finally {
            sBypass = false;
        }
    }

    // ------------------------------------------------------------ layout

    private static View byId(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", SYSTEMUI);
        return id != 0 ? root.findViewById(id) : null;
    }

    private static void detach(View v) {
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    private static void build(ViewGroup bar) {
        if (bar.findViewWithTag("dual_row2") != null) return;
        Context ctx = bar.getContext();
        Cfg.init(ctx);
        if (!Cfg.b(Cfg.PRO, Cfg.D_PRO) || !Cfg.b(Cfg.DUAL, Cfg.D_DUAL)) {
            XposedBridge.log(TAG + "4-row bar off in settings: stock status bar");
            return;
        }

        View contents = byId(bar, "status_bar_contents");
        View notifArea = byId(bar, "notification_icon_area");
        View statusIcons = byId(bar, "statusIcons");
        View clock = byId(bar, "clock");
        View systemIcons = byId(bar, "system_icons");
        XposedBridge.log(TAG + "views: contents=" + (contents != null)
                + " notifArea=" + (notifArea != null) + " statusIcons=" + (statusIcons != null)
                + " clock=" + (clock != null) + " systemIcons=" + (systemIcons != null)
                + " bar=" + bar.getClass().getSuperclass().getSimpleName());

        if (contents == null || !(bar instanceof FrameLayout)) {
            XposedBridge.log(TAG + "unexpected status bar layout, dual row not applied");
            return;
        }

        // ---- Sky behind everything (first child = drawn first)
        if (bar.findViewWithTag("dual_sky") == null) {
            SkyView sky = new SkyView(ctx);
            sky.setTag("dual_sky");
            bar.addView(sky, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // ---- Row 2 container
        LinearLayout row2 = new LinearLayout(ctx);
        row2.setTag("dual_row2");
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(row2, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx.getResources(), Cfg.i(Cfg.ROW2_H, Cfg.D_ROW2_H)), Gravity.TOP));

        // Row 2 left: our own icons (notification count, Bluetooth, ringer, alarm, Wi-Fi, tray).
        SysViews.CapRow sysRow = new SysViews.CapRow(ctx);
        sysRow.setTag("dual_sys");
        sysRow.setOrientation(LinearLayout.HORIZONTAL);
        sysRow.setGravity(Gravity.CENTER_VERTICAL);
        int gapPx = dp(ctx.getResources(), 7);
        View[] mine = {new SysViews.NetSw(ctx), new SysViews.Notif(ctx), new SysViews.Bt(ctx), new SysViews.Ringer(ctx), new SysViews.Alarm(ctx),
                new SysViews.Tray(ctx), new SysViews.Wifi(ctx)};
        // The tray (other system icons) sits left of the camera; Wi-Fi goes right of it.
        String[] tags = {"dual_netsw", "dual_notif", "dual_bt", "dual_ringer", "dual_alarm", "dual_tray", "dual_wifi"};
        for (int i = 0; i < mine.length; i++) {
            mine[i].setTag(tags[i]);
            sysRow.addView(mine[i], SysViews.wrap(i == 0 ? 0 : gapPx));
        }
        row2.addView(sysRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View spacer = new View(ctx);
        spacer.setTag("dual_spacer");
        row2.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        // The stock notification icons and system icons stay alive (System UI and the row-1 copies
        // use them) in a host that takes no room and draws nothing.
        SysViews.HiddenHost host = new SysViews.HiddenHost(ctx);
        host.setTag("dual_hidden");
        row2.addView(host, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (notifArea != null) {
            detach(notifArea);
            host.addView(notifArea, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (statusIcons != null) {
            detach(statusIcons);
            host.addView(statusIcons, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            statusIcons.setPaddingRelative(statusIcons.getPaddingStart(), statusIcons.getPaddingTop(),
                    0, statusIcons.getPaddingBottom());
        }

        applyRowHeights(bar);
        // Row 1 items are measured, so re-place row 2 whenever the bar lays out again.
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) ->
                v.post(() -> {
                    try { applyRowHeights((ViewGroup) v); } catch (Throwable ignored) { }
                }));

        // ---- Row 1 right: mobile signal + VoLTE / VoNR copies, just before the battery.
        LinearLayout imsRow = null;
        if (systemIcons != null && systemIcons.getParent() instanceof ViewGroup) {
            ViewGroup p = (ViewGroup) systemIcons.getParent();
            imsRow = new LinearLayout(ctx);
            imsRow.setTag("dual_ims");
            imsRow.setOrientation(LinearLayout.HORIZONTAL);
            imsRow.setGravity(Gravity.CENTER_VERTICAL);
            p.addView(imsRow, p.indexOfChild(systemIcons), wrapParams(p));

            // Band per SIM (n78 with a small 1 over B3 with a small 2), before VoLTE / VoNR.
            TextView band = new TextView(ctx);
            band.setTag("dual_band");
            band.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
            band.setIncludeFontPadding(false);
            band.setLineSpacing(0, 0.85f);
            band.setMaxLines(2);
            band.setHorizontallyScrolling(true);
            band.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            band.setPaddingRelative(0, 0, dp(ctx.getResources(), 2), 0);
            band.setVisibility(View.GONE);
            p.addView(band, p.indexOfChild(imsRow), wrapParams(p));

            // Combined voice badge: a boxed "Vo" and one line per SIM (NR / LTE / WiFi + SIM no.).
            LinearLayout vo = new LinearLayout(ctx);
            vo.setTag("dual_vo");
            vo.setOrientation(LinearLayout.HORIZONTAL);
            vo.setGravity(Gravity.CENTER_VERTICAL);
            vo.setVisibility(View.GONE);
            TextView voBox = new TextView(ctx);
            voBox.setTag("dual_vo_box");
            voBox.setText("Vo");
            voBox.setIncludeFontPadding(false);
            voBox.setGravity(Gravity.CENTER);
            int bp = dp(ctx.getResources(), 1);
            voBox.setPadding(0, 0, 0, 0);
            vo.addView(voBox, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            TextView voLines = new TextView(ctx);
            voLines.setTag("dual_vo_lines");
            voLines.setIncludeFontPadding(false);
            voLines.setLineSpacing(0, 0.85f);
            voLines.setMaxLines(2);
            voLines.setHorizontallyScrolling(true);
            voLines.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp.setMarginStart(bp);
            vo.addView(voLines, vlp);
            LinearLayout.LayoutParams volp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            volp.gravity = Gravity.CENTER_VERTICAL;
            volp.setMarginEnd(dp(ctx.getResources(), 1));
            p.addView(vo, p.indexOfChild(imsRow), volp);

            // Network type (5G SA / 4G+) with data activity arrows underneath, Samsung style.
            LinearLayout nt = new LinearLayout(ctx);
            nt.setTag("dual_nettype");
            nt.setOrientation(LinearLayout.VERTICAL);
            nt.setGravity(Gravity.CENTER_HORIZONTAL);
            nt.setVisibility(View.GONE);
            ImageView ntIcon = new ImageView(ctx);
            ntIcon.setTag("dual_nettype_icon");
            ntIcon.setAdjustViewBounds(true);
            ntIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            nt.addView(ntIcon, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ImageView ntArrows = new ImageView(ctx);
            ntArrows.setTag("dual_nettype_arrows");
            android.content.res.Resources rr = ctx.getResources();
            float dn = rr.getDisplayMetrics().density;
            ntArrows.setImageDrawable(new ArrowsDrawable(Math.round(4f * dn), Math.round(5f * dn),
                    Math.round(1.5f * dn)));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.gravity = Gravity.CENTER_VERTICAL;
            // Arrows + small number of the SIM that carries mobile data, side by side.
            LinearLayout ntRow = new LinearLayout(ctx);
            ntRow.setOrientation(LinearLayout.HORIZONTAL);
            ntRow.setGravity(Gravity.CENTER_VERTICAL);
            ntRow.addView(ntArrows, alp);
            TextView ntSim = new TextView(ctx);
            ntSim.setTag("dual_nettype_sim");
            ntSim.setTextSize(TypedValue.COMPLEX_UNIT_SP, 6.5f);
            ntSim.setIncludeFontPadding(false);
            ntSim.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.setMarginStart(Math.round(1.5f * dn));
            slp.gravity = Gravity.CENTER_VERTICAL;
            ntRow.addView(ntSim, slp);
            View ntPing = new View(ctx);
            ntPing.setTag("dual_nettype_ping");
            android.graphics.drawable.GradientDrawable pingBg = new android.graphics.drawable.GradientDrawable();
            pingBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            pingBg.setColor(0xFF34C759);
            ntPing.setBackground(pingBg);
            ntPing.setVisibility(View.GONE);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Math.round(3.5f * dn), Math.round(3.5f * dn));
            plp.setMarginStart(Math.round(2f * dn));
            plp.gravity = Gravity.CENTER_VERTICAL;
            ntRow.addView(ntPing, plp);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = Math.round(1f * dn);
            nt.addView(ntRow, rlp);
            LinearLayout.LayoutParams ntlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ntlp.gravity = Gravity.CENTER_VERTICAL;
            ntlp.setMarginEnd(dp(ctx.getResources(), 1));
            p.addView(nt, p.indexOfChild(imsRow), ntlp);

            // Signal tower: both SIMs' signal as waves, replaces the stock signal / type / Vo icons.
            SignalTowerView tower = new SignalTowerView(ctx);
            tower.setTag("dual_tower");
            tower.setVisibility(View.GONE);
            LinearLayout.LayoutParams twlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            twlp.gravity = Gravity.CENTER_VERTICAL;
            twlp.setMarginStart(dp(ctx.getResources(), 2));
            twlp.setMarginEnd(dp(ctx.getResources(), 3));
            p.addView(tower, p.indexOfChild(imsRow), twlp);

        }

        // ---- Row 1 left: temperatures and weather, right after the clock.
        Extras extras = null;
        if (clock != null && clock.getParent() instanceof ViewGroup) {
            ViewGroup p = (ViewGroup) clock.getParent();
            extras = new Extras(ctx);
            p.addView(extras.root, p.indexOfChild(clock) + 1, wrapParams(p));

            // Weather goes to the right end of row 2, after the system icons.
            LinearLayout weatherBox = new LinearLayout(ctx);
            weatherBox.setTag("dual_weather");
            weatherBox.setOrientation(LinearLayout.HORIZONTAL);
            weatherBox.setGravity(Gravity.CENTER_VERTICAL);
            detach(extras.weatherIcon);
            detach(extras.weatherTemp);
            LinearLayout.LayoutParams wilp = new LinearLayout.LayoutParams(
                    dp(ctx.getResources(), 14), dp(ctx.getResources(), 14));
            weatherBox.addView(extras.weatherIcon, wilp);
            LinearLayout.LayoutParams wtlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wtlp.setMarginStart(dp(ctx.getResources(), 2));
            weatherBox.addView(extras.weatherTemp, wtlp);
            LinearLayout.LayoutParams wblp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            wblp.setMarginStart(dp(ctx.getResources(), Cfg.i(Cfg.WEATHER_GAP, Cfg.D_WEATHER_GAP)));
            wblp.setMarginEnd(dp(ctx.getResources(), 8));   // keep the temperature off the rounded corner
            row2.addView(weatherBox, wblp);

            // Charging current / power, two lines like the temperatures, after the net speed.
            TextView power = new TextView(ctx);
            power.setTag("dual_power");
            power.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
            power.setIncludeFontPadding(false);
            power.setLineSpacing(0, 0.85f);
            power.setMaxLines(2);
            power.setHorizontallyScrolling(true);
            power.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            power.setVisibility(View.GONE);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.gravity = Gravity.CENTER_VERTICAL;
            plp.setMarginStart(dp(ctx.getResources(), 3));
            extras.root.addView(power, plp);

            // Cells heard per SIM, two lines (antenna + count + small SIM number), after the amps.
            TextView cellsView = new TextView(ctx);
            cellsView.setTag("dual_cells");
            cellsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
            cellsView.setIncludeFontPadding(false);
            cellsView.setLineSpacing(0, 0.85f);
            cellsView.setMaxLines(2);
            cellsView.setHorizontallyScrolling(true);
            cellsView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            cellsView.setVisibility(View.GONE);
            LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp2.gravity = Gravity.CENTER_VERTICAL;
            clp2.setMarginStart(dp(ctx.getResources(), 3));
            extras.root.addView(cellsView, clp2);
        } else {
            XposedBridge.log(TAG + "clock not found, temperatures/weather not added");
        }

        if (statusIcons instanceof ViewGroup) sIconContainer = (ViewGroup) statusIcons;
        new Updater(bar, clock, statusIcons, imsRow, extras, notifArea).start();
        XposedBridge.log(TAG + "dual row built");
    }

    private static ViewGroup.LayoutParams wrapParams(ViewGroup parent) {
        if (parent instanceof LinearLayout) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.gravity = Gravity.CENTER_VERTICAL;
            return lp;
        }
        return new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /**
     * Row 1 keeps the original area at the top. Row 2 sits Cfg.i(Cfg.ROW_GAP, Cfg.D_ROW_GAP) below the lowest
     * row-1 item (clock / temperatures), not below the whole cutout-sized row 1.
     */
    private static void applyRowHeights(ViewGroup bar) {
        View row2 = bar.findViewWithTag("dual_row2");
        View contents = byId(bar, "status_bar_contents");
        if (row2 == null || contents == null) return;

        Resources res = bar.getResources();
        int row1 = row1Height(bar.getContext());
        int row2h = dp(res, Cfg.i(Cfg.ROW2_H, Cfg.D_ROW2_H));
        int barH = bar.getHeight() > 0 ? bar.getHeight() : row1 + dp(res, Cfg.i(Cfg.EXTRA, Cfg.D_EXTRA));

        ViewGroup.LayoutParams clp = contents.getLayoutParams();
        if (clp != null && clp.height != row1) {
            clp.height = row1;
            if (clp instanceof FrameLayout.LayoutParams) ((FrameLayout.LayoutParams) clp).gravity = Gravity.TOP;
            contents.setLayoutParams(clp);
        }

        // Bottom of the lowest row-1 item, in the bar's coordinates.
        int bottom = Math.max(textBottomIn(bar, byId(bar, "clock")) + sClockInfoPx,
                textBottomIn(bar, bar.findViewWithTag("dual_extras")));
        int top = bottom > 0 ? bottom + dp(res, Cfg.i(Cfg.ROW_GAP, Cfg.D_ROW_GAP)) : row1;
        // Keep row 2 inside the bar.
        if (top + row2h > barH) top = Math.max(0, barH - row2h);

        FrameLayout.LayoutParams rlp = (FrameLayout.LayoutParams) row2.getLayoutParams();
        // The bar itself keeps padding at its right end (rounded corners); row 2 reaches past it,
        // so its own right margin setting is measured from the screen edge.
        int endPad = bar.getPaddingEnd();
        if (bar.getClipToPadding()) bar.setClipToPadding(false);
        if (rlp.height != row2h || rlp.topMargin != top || rlp.getMarginEnd() != -endPad) {
            rlp.height = row2h;
            rlp.topMargin = top;
            rlp.setMarginEnd(-endPad);
            row2.setLayoutParams(rlp);
            logOnceStatic("row2end" + endPad, "row 2 reaches past the bar's " + endPad + "px end padding");
            // Logged per position: the first pass runs before the clock has text (bottom 0),
            // the next passes place row 2 under the real row-1 text.
            logOnceStatic("rows" + bottom + "/" + top + "/" + barH, "row 1 items end at " + bottom + "px, row 2 at " + top
                    + "px, bar " + barH + "px");
        }
        // Line up with row 1's side padding (rounded screen corners).
        // Right edge: its own margin (default almost at the bezel), not row 1's corner padding.
        row2.setPaddingRelative(contents.getPaddingStart(), 0, dp(res, Cfg.i(Cfg.ROW2_END, Cfg.D_ROW2_END)), 0);
    }

    /**
     * Bottom of the visible text inside v (or its child TextViews), in the bar's
     * coordinates. The views themselves are as tall as the bar, so their own height
     * can't be used; the text inside them sits near the top.
     */
    private static int textBottomIn(ViewGroup bar, View v) {
        if (v == null || v.getVisibility() != View.VISIBLE || v.getHeight() == 0) return 0;
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            if (tv.getLayout() == null) return 0;
            int[] a = new int[2], b = new int[2];
            tv.getLocationInWindow(a);
            bar.getLocationInWindow(b);
            return a[1] - b[1] + tv.getTotalPaddingTop() + tv.getLayout().getHeight();
        }
        int max = 0;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                max = Math.max(max, textBottomIn(bar, g.getChildAt(i)));
            }
        }
        return max;
    }

    private static void logOnceStatic(String key, String msg) {
        synchronized (sLogOnce) {
            if (sLogOnce.put(key, Boolean.TRUE) != null) return;
        }
        XposedBridge.log(TAG + msg);
    }

    // ------------------------------------------------------------ row 1 extras

    /** Two-line temperature text + weather icon and temperature. */
    private static final class Extras {
        final LinearLayout root;
        final TextView temps;
        final ImageView weatherIcon;
        final TextView weatherTemp;

        Extras(Context ctx) {
            Resources res = ctx.getResources();
            int pad = dp(res, 4);

            root = new LinearLayout(ctx);
            root.setTag("dual_extras");
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPaddingRelative(pad, 0, 0, 0);

            temps = new TextView(ctx);
            temps.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
            temps.setTypeface(Typeface.DEFAULT_BOLD);
            temps.setIncludeFontPadding(false);
            temps.setLineSpacing(0, 0.85f);
            temps.setMaxLines(2);
            temps.setHorizontallyScrolling(true);
            temps.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            temps.setVisibility(View.GONE);
            root.addView(temps, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            int icon = dp(res, 14);
            weatherIcon = new ImageView(ctx);
            weatherIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            weatherIcon.setVisibility(View.GONE);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(icon, icon);
            ilp.setMarginStart(pad);
            root.addView(weatherIcon, ilp);

            weatherTemp = new TextView(ctx);
            weatherTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            weatherTemp.setIncludeFontPadding(false);
            weatherTemp.setVisibility(View.GONE);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.setMarginStart(dp(res, 2));
            root.addView(weatherTemp, tlp);
        }
    }

    // ------------------------------------------------------------ updater

    private static final class Updater {
        final ViewGroup bar;
        final View clock;
        final View statusIcons;
        final LinearLayout imsRow;
        final Extras extras;
        final Context ctx;

        final Handler main = new Handler(Looper.getMainLooper());
        final Handler worker;

        /** Mirrored row-1 icons, by slot name. */

        // Values filled in by the worker thread.
        volatile float cpuTemp = Float.NaN;
        volatile float gpuTemp = Float.NaN;
        volatile float cpuGhz = Float.NaN;
        volatile long ramFree = -1, ramTotal = -1;
        /** Last location fix {lat, lon, altitude m (NaN if unknown)}; null until one exists. */
        volatile double[] lastFix = null;
        volatile float quakeMag = Float.NaN;
        volatile int quakeKm = -1;
        volatile long quakeAt = 0;
        volatile String quakeCc = null;
        long quakeFetchedAt = 0, quakeTriedAt = 0;
        volatile float batTemp = Float.NaN;
        volatile int batSoh = -1, batCycles = -1;   // health %, charge cycles (-1 unknown)
        long healthReadAt = 0;
        volatile float amps = Float.NaN;
        volatile float watts = Float.NaN;
        volatile String weatherTemp = null;
        volatile Drawable weatherIcon = null;
        volatile boolean tintWeatherIcon = true;

        boolean wasAttached = false;
        boolean stopped = false;

        final View notifArea;
        final SysState sys;

        Updater(ViewGroup bar, View clock, View statusIcons, LinearLayout imsRow, Extras extras, View notifArea) {
            this.notifArea = notifArea;
            this.sys = new SysState(bar.getContext());
            this.bar = bar;
            this.clock = clock;
            this.statusIcons = statusIcons;
            this.imsRow = imsRow;
            this.extras = extras;
            this.ctx = bar.getContext();
            ScreenState.init(ctx);
            HandlerThread t = new HandlerThread("NetSpeedXposed-dual");
            t.start();
            worker = new Handler(t.getLooper());
        }

        final Runnable sysTick = new Runnable() {
            @Override
            public void run() {
                if (dead()) return;
                if (Cfg.b(Cfg.SYS_ICONS, Cfg.D_SYS_ICONS) && ScreenState.on()) sys.refresh();
                worker.postDelayed(this, 2000);
            }
        };

        void start() {
            worker.post(sysTick);
            main.post(uiTick);
            main.post(secTick);
            worker.post(powerTick);
            worker.post(bandTick);
            // weather runs even when the ROM's status bar has no spot for the old extras (e.g. Lunaris)
            if (Cfg.b(Cfg.SHOW_WEATHER, Cfg.D_SHOW_WEATHER)) worker.post(weatherTick);
            if (extras != null) {
                worker.post(tempTick);
                if (Cfg.b(Cfg.SHOW_WEATHER, Cfg.D_SHOW_WEATHER)) {
                    try {
                        ctx.getContentResolver().registerContentObserver(OMNI_WEATHER, true,
                                new ContentObserver(worker) {
                                    @Override
                                    public void onChange(boolean selfChange) {
                                        loadWeather();
                                    }
                                });
                    } catch (Throwable t) {
                        logOnce("observer", "OmniJaws observer failed: " + t);
                    }
                }
            }
        }

        /** Stop once this status bar has been replaced (re-inflated). */
        boolean dead() {
            if (stopped) return true;
            if (bar.isAttachedToWindow()) wasAttached = true;
            else if (wasAttached) {
                stopped = true;
                worker.getLooper().quitSafely();
            }
            return stopped;
        }

        int seenVersion = -1;

        // ---- Colours follow the app on screen (its icon colour; wallpaper on the home screen).

        int sysColor = 0xFFFFFFFF;    // the colour System UI wants (white / dark), before ours
        int appliedColor = 0;         // what we last put on the clock
        boolean iconsTinted = false;
        int tintColor = 0;
        long wallpaperReadAt = 0;
        final android.graphics.Paint tintPaint = new android.graphics.Paint();

        LumaSampler sampler;
        android.graphics.Rect sampledRect;
        boolean lightBg = false, contrastFixed = false;

        /** (Re)starts brightness sampling of the area under the status bar. */
        void ensureSampling() {
            if (bar.getWidth() == 0 || !bar.isAttachedToWindow()) return;
            int[] p = new int[2];
            bar.getLocationOnScreen(p);
            android.graphics.Rect r = new android.graphics.Rect(p[0], p[1], p[0] + bar.getWidth(), p[1] + bar.getHeight());
            if (sampler != null && r.equals(sampledRect)) return;
            try {
                if (sampler != null) android.view.CompositionSamplingListener.unregister(sampler);
                Object vri = XposedHelpers.callMethod(bar, "getViewRootImpl");
                android.view.SurfaceControl sc = (android.view.SurfaceControl) XposedHelpers.callMethod(vri, "getSurfaceControl");
                if (sc == null || !sc.isValid()) return;
                if (sampler == null) sampler = new LumaSampler(ctx.getMainExecutor());
                android.view.CompositionSamplingListener.register(sampler, bar.getDisplay().getDisplayId(), sc, r);
                sampledRect = r;
                XposedBridge.log(TAG + "auto contrast: sampling brightness under the bar " + r);
            } catch (Throwable t) {
                logOnce("sampling", "auto contrast not available: " + t);
            }
        }

        /** Dark text on a light background, light text on a dark one, whatever the app asked for. */
        int contrastGuard(int sys) {
            if (!Cfg.b(Cfg.AUTO_CONTRAST, Cfg.D_AUTO_CONTRAST)) {
                contrastFixed = false;
                return sys;
            }
            ensureSampling();
            float l = sampler == null ? -1f : sampler.luma;
            if (l < 0) return sys;
            // hysteresis so it doesn't flicker on mid-grey screens
            if (!lightBg && l > 0.62f) lightBg = true;
            else if (lightBg && l < 0.45f) lightBg = false;
            boolean sysLight = AppColor.luminance(sys) > 0.5f;
            int out = sys;
            if (lightBg && sysLight) out = 0xFF202124;          // white on white -> dark
            else if (!lightBg && !sysLight && l < 0.30f) out = 0xFFFFFFFF;   // dark on dark -> white
            boolean fixed = out != sys;
            if (fixed != contrastFixed) {
                contrastFixed = fixed;
                XposedBridge.log(TAG + "auto contrast " + (fixed ? "on" : "off") + " (brightness under bar "
                        + String.format(Locale.US, "%.2f", l) + ")");
            }
            return out;
        }

        int dynamicColor(int clockColor) {
            if (clockColor != appliedColor) sysColor = clockColor;
            // App-following colours removed: always the system's own white / dark (this also
            // puts the clock and row-2 icons back if they were tinted).
            boolean on = false;
            int out = sysColor;
            // Auto contrast: some apps ask for white icons over a white top (or dark over dark).
            // Go by the real brightness under the bar instead.
            out = contrastGuard(out);
            if (on) {
                // the wallpaper may have changed: read its colour again every 10 minutes
                long nowMs = android.os.SystemClock.elapsedRealtime();
                if (nowMs - wallpaperReadAt > 10 * 60_000L) {
                    wallpaperReadAt = nowMs;
                    AppColor.forgetWallpaper();
                }
                int accent = AppColor.current(ctx);
                if (accent != 0) out = AppColor.readable(accent, darkBar(sysColor));
            }
            if (clock instanceof TextView && ((TextView) clock).getCurrentTextColor() != out) {
                ((TextView) clock).setTextColor(out);
            }
            appliedColor = out == sysColor ? 0 : out;
            // System icons on row 2 (Wi-Fi, Bluetooth...) are one-colour: tint them as a group.
            if (statusIcons != null) {
                boolean tint = out != sysColor;
                if (tint && (!iconsTinted || tintColor != out)) {
                    tintPaint.setColorFilter(new android.graphics.PorterDuffColorFilter(out, PorterDuff.Mode.SRC_IN));
                    statusIcons.setLayerType(View.LAYER_TYPE_HARDWARE, tintPaint);
                    iconsTinted = true;
                    tintColor = out;
                } else if (!tint && iconsTinted) {
                    statusIcons.setLayerType(View.LAYER_TYPE_NONE, null);
                    iconsTinted = false;
                }
            }
            return out;
        }

        // ---- UI thread: every second
        final Runnable uiTick = new Runnable() {
            @Override
            public void run() {
                if (dead()) return;
                if (!ScreenState.on()) { main.postDelayed(this, 2000); return; }
                int color = 0xFFFFFFFF;
                try {
                    color = clock instanceof TextView ? ((TextView) clock).getCurrentTextColor() : 0xFFFFFFFF;
                    color = dynamicColor(color);
                    applyLockState();
                    updateMirrors(color);
                    updatePower(color);
                    updateBands(color);
                    if (extras != null) updateDbm(color);
                    updateVoice(color);
                    updateTower(color);
                    hideRow2Extras();
                    updateSys(color);
                    updateBattery(color);
                    updateCameraText(color);
                    updateNetType(color);
                    try {
                        IdleDim.pass(bar);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "idle dim: " + t);
                    }
                    if (extras != null) {
                        updateExtras(color);
                        moveNetSpeed();
                        placePower();
                        placePowerSide();
                        updateCells(color);
                    }
                    Cfg.retryIfNeeded();
                    int v = Cfg.version();
                    if (v != seenVersion) {
                        seenVersion = v;
                        applyRowHeights(bar);
                        View wb = bar.findViewWithTag("dual_weather");
                        if (wb != null && wb.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) wb.getLayoutParams();
                            int gap = dp(bar.getResources(), Cfg.i(Cfg.WEATHER_GAP, Cfg.D_WEATHER_GAP));
                            if (mlp.getMarginStart() != gap) {
                                mlp.setMarginStart(gap);
                                wb.setLayoutParams(mlp);
                            }
                        }
                    }
                } catch (Throwable t) {
                    logOnce("ui", "update failed: " + t);
                }
                try {
                    updateReadable(color);
                } catch (Throwable t) {
                    logOnce("readable", "readable layout failed: " + t);
                }
                main.postDelayed(this, 1000);
            }
        };

        // ---- Lock screen: its own status bar shows the operator names where our row-1 extras
        // sit, so our extras step aside while the phone is locked.
        boolean lockRxDone = false;
        boolean hiddenForLock = false;

        boolean locked() {
            try {
                android.app.KeyguardManager km = ctx.getSystemService(android.app.KeyguardManager.class);
                return km != null && km.isKeyguardLocked();
            } catch (Throwable t) {
                return false;
            }
        }

        void applyLockState() {
            if (!lockRxDone) {
                lockRxDone = true;
                try {
                    android.content.IntentFilter f = new android.content.IntentFilter();
                    f.addAction(android.content.Intent.ACTION_SCREEN_OFF);
                    f.addAction(android.content.Intent.ACTION_SCREEN_ON);
                    f.addAction(android.content.Intent.ACTION_USER_PRESENT);
                    ctx.registerReceiver(new android.content.BroadcastReceiver() {
                        @Override
                        public void onReceive(android.content.Context c, android.content.Intent i) {
                            main.post(() -> applyLockState());
                            // the keyguard state settles a moment after the broadcast
                            main.postDelayed(() -> applyLockState(), 300);
                        }
                    }, f, android.content.Context.RECEIVER_EXPORTED);
                } catch (Throwable t) {
                    logOnce("lockrx", "lock screen receiver failed: " + t);
                }
            }
            boolean hide = locked();
            float a = hide ? 0f : 1f;
            if (extras != null && extras.root.getAlpha() != a) extras.root.setAlpha(a);
            View wb = bar.findViewWithTag("dual_weather");
            if (wb != null && wb.getAlpha() != a) wb.setAlpha(a);
            View cv = bar.findViewWithTag("dual_cells");
            if (cv != null && cv.getAlpha() != a) cv.setAlpha(a);
            View rbl = bar.findViewWithTag("dual_readable");
            float ra = hide && !Cfg.b(Cfg.LOCK_BAR, Cfg.D_LOCK_BAR) ? 0f : 1f;
            if (rbl != null && rbl.getAlpha() != ra) rbl.setAlpha(ra);
            sHideKeyguardBar = hide && ra > 0f;
            if (hide != hiddenForLock) {
                hiddenForLock = hide;
                XposedBridge.log(TAG + (hide ? "locked: row-1 extras hidden" : "unlocked: row-1 extras shown"));
            }
        }

        /** Put the net speed indicator (added by Hook) on row 1, right after the weather. */
        void moveNetSpeed() {
            if (!Cfg.b(Cfg.SPEED_LEFT, Cfg.D_SPEED_LEFT)) return;
            View ns = bar.findViewWithTag("netspeed_xposed");
            if (ns == null || ns.getParent() == extras.root) return;
            detach(ns);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_VERTICAL;
            lp.setMarginStart(dp(bar.getResources(), 3));
            extras.root.addView(ns, lp);
            if (ns instanceof TextView) {
                TextView tv = (TextView) ns;
                tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                tv.setPadding(0, 0, 0, 0);
                // Keep a fixed width (numbers changing shouldn't shift things), minus the padding.
                tv.setMinWidth((int) Math.ceil(tv.getPaint().measureText("↓999K/s")));
            }
            XposedBridge.log(TAG + "net speed moved next to weather");
        }

        /** Keeps the amps/watts text right after the net speed on row 1. */
        void placePower() {
            View pw = bar.findViewWithTag("dual_power");
            if (pw == null || pw.getParent() != extras.root) return;
            View ns = bar.findViewWithTag("netspeed_xposed");
            int want = ns != null && ns.getParent() == extras.root
                    ? extras.root.indexOfChild(ns) + 1 : extras.root.getChildCount() - 1;
            int cur = extras.root.indexOfChild(pw);
            if (cur == want || (cur == extras.root.getChildCount() - 1 && want >= cur)) return;
            ViewGroup.LayoutParams lp = pw.getLayoutParams();
            extras.root.removeView(pw);
            extras.root.addView(pw, Math.min(want, extras.root.getChildCount()), lp);
        }

        int lastCopies = -1;
        Drawable typeDrawable, shownType;
        long lastPingAt = 0, lastTestSwitch = 0, lastTestBell = 0, lastTestPing = 0;
        int typeHeight = 0;
        long typeSeenAt = 0;
        boolean netTypeShown = false;

        View findByName(View v, String name) {
            try {
                if (v.getId() != View.NO_ID && name.equals(v.getResources().getResourceEntryName(v.getId()))) return v;
            } catch (Throwable ignored) { }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View r = findByName(g.getChildAt(i), name);
                    if (r != null) return r;
                }
            }
            return null;
        }

        /** x of v inside root (sum of lefts up the tree). */
        int offsetIn(View v, View root) {
            int x = 0;
            for (View c = v; c != null && c != root; ) {
                x += c.getLeft() + (int) c.getTranslationX();
                android.view.ViewParent pp = c.getParent();
                c = pp instanceof View ? (View) pp : null;
            }
            return x;
        }

        /** "1.0km", "~850m", "≈1.5km", "?" for the tower's band line. */
        /** The network's broadcast name is worth showing: known, and not just the SIM's own name. */
        static boolean netDiffers(Bands.Sim s) {
            if (s.netName == null || s.netName.trim().isEmpty()) return false;
            if (Circles.roamCircle(s.simNumeric, s.netNumeric) != null || s.roaming) return true;
            String a = norm(s.netName), b = norm(s.simName);
            return !a.isEmpty() && !b.isEmpty() && !a.contains(b) && !b.contains(a);
        }

        private static String norm(String v) {
            return v == null ? "" : v.toLowerCase(Locale.US).replaceAll("[^a-z]", "").replace("ind", "");
        }

        static String distString(Bands.Sim s) {
            if (s.distM < 0) return "?";
            String mark = s.guess ? "\u2248" : s.approx ? "~" : "";
            int m = s.distM;
            if (m < 1000) return mark + m + "m";
            if (m < 10_000) return mark + String.format(Locale.US, "%.1fkm", m / 1000f);
            return mark + (m / 1000) + "km";
        }

        boolean towerShown = false;
        int lastTowerLogHash = 0;
        /** How much of the tower's extras are dropped to fit: 0 none ... 4 distance, SINR, dBm and cell counts. */
        int towerTrim = 0;

        /**
         * The tower, the Vo badge and the system icons share one row right of the camera. The
         * tower always takes its full width, so when the row is too narrow the system icons
         * at the end get squeezed and the battery is cut in half. Drop the tower's optional
         * parts (distance, then SINR, then dBm, then cell counts) until everything fits.
         */
        void fitTower(SignalTowerView tv, SignalTowerView.Side[] sd, float px, float scale) {
            if (!(tv.getParent() instanceof ViewGroup)) return;
            ViewGroup p = (ViewGroup) tv.getParent();
            if (p.getWidth() == 0) return;
            int[] loc = new int[2];
            p.getLocationOnScreen(loc);
            int end = loc[0] + p.getWidth() - p.getPaddingRight();
            // The row wraps its content, so its own left edge only says how wide the tower is
            // now. The real room is what its container allows, from the camera to the end.
            int start = loc[0] + p.getPaddingLeft();
            if (p.getParent() instanceof ViewGroup) {
                ViewGroup pp = (ViewGroup) p.getParent();
                int[] pl = new int[2];
                pp.getLocationOnScreen(pl);
                start = pl[0] + pp.getPaddingLeft();
            }
            int rl = rightLimit();
            if (rl != Integer.MIN_VALUE) start = Math.max(start, rl);
            int others = 0;
            StringBuilder who = new StringBuilder();
            for (int i = 0; i < p.getChildCount(); i++) {
                View c = p.getChildAt(i);
                if (c == tv || c.getVisibility() == View.GONE) continue;
                int w = wantedWidth(c);
                others += w;
                if (w > 0) {
                    String id = c.getTag() instanceof String ? (String) c.getTag() : "";
                    try {
                        if (id.isEmpty() && c.getId() != View.NO_ID) id = c.getResources().getResourceEntryName(c.getId());
                    } catch (Throwable ignored) { }
                    who.append(' ').append(id.isEmpty() ? c.getClass().getSimpleName() : id).append('=').append(w);
                }
            }
            ViewGroup.LayoutParams lp = tv.getLayoutParams();
            int tvMargins = lp instanceof ViewGroup.MarginLayoutParams
                    ? ((ViewGroup.MarginLayoutParams) lp).getMarginStart() + ((ViewGroup.MarginLayoutParams) lp).getMarginEnd() : 0;
            int room = end - start - others - tvMargins;
            logRowOnce(p, tv);
            int slack = dp(bar.getResources(), 4);
            int trim = 0;
            for (; trim <= 4; trim++) {
                SignalTowerView.Side a = trimmed(sd[0], trim), b = trimmed(sd[1], trim);
                // Coming back to a fuller tower needs 4dp spare, so it doesn't flicker.
                int need = (int) tv.widthFor(a, b, px, scale) + (trim < towerTrim ? slack : 0);
                if (need <= room || trim == 4) break;
            }
            trim = Math.min(trim, 4);
            if (trim != towerTrim) {
                XposedBridge.log(TAG + "tower trimmed to level " + trim + " (room " + room + "px, others " + others
                        + "px:" + who + "; camera to end " + (end - start) + "px)");
                towerTrim = trim;
            }
            sd[0] = trimmed(sd[0], trim);
            sd[1] = trimmed(sd[1], trim);
        }

        String lastRowDump = "";

        /** Logs what sits in the row with the tower (and inside groups), when it changes. */
        void logRowOnce(ViewGroup p, View tv) {
            StringBuilder b = new StringBuilder();
            dumpRow(p, b, 0);
            String d = b.toString();
            if (d.equals(lastRowDump)) return;
            lastRowDump = d;
            XposedBridge.log(TAG + "row 1 right (" + p.getWidth() + "px):" + d);
        }

        static void dumpRow(ViewGroup g, StringBuilder b, int depth) {
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                String id = "";
                try {
                    if (c.getId() != View.NO_ID) id = c.getResources().getResourceEntryName(c.getId());
                } catch (Throwable ignored) { }
                b.append("\n  ");
                for (int k = 0; k < depth; k++) b.append("  ");
                b.append(c.getClass().getSimpleName());
                if (!id.isEmpty()) b.append(" #").append(id);
                if (c.getTag() instanceof String) b.append(" [").append(c.getTag()).append(']');
                b.append(c.getVisibility() == View.VISIBLE ? " vis" : c.getVisibility() == View.INVISIBLE ? " INVIS" : " gone");
                b.append(" x=").append(c.getLeft()).append(" w=").append(c.getWidth()).append(" mw=").append(c.getMeasuredWidth());
                if (c.getAlpha() < 0.99f) b.append(" a=").append(c.getAlpha());
                if (c instanceof ViewGroup && c.getVisibility() != View.GONE && depth < 2)
                    dumpRow((ViewGroup) c, b, depth + 1);
            }
        }

        static SignalTowerView.Side trimmed(SignalTowerView.Side s, int trim) {
            if (trim == 0) return s;
            SignalTowerView.Side t = s.copy();
            if (trim >= 1) t.dist = null;
            if (trim >= 2) t.sinr = null;
            if (trim >= 3) t.dbm = null;
            if (trim >= 4) t.cells = -1;
            return t;
        }

        /** Width a view wants with its margins; for a group (the system icons), what its children want. */
        static int wantedWidth(View c) {
            int w = c.getMeasuredWidth();
            if (c instanceof ViewGroup && ((ViewGroup) c).getChildCount() > 0
                    && c instanceof LinearLayout && ((LinearLayout) c).getOrientation() == LinearLayout.HORIZONTAL) {
                ViewGroup g = (ViewGroup) c;
                int sum = g.getPaddingLeft() + g.getPaddingRight();
                for (int i = 0; i < g.getChildCount(); i++) {
                    View k = g.getChildAt(i);
                    if (k.getVisibility() != View.GONE) sum += wantedWidth(k);
                }
                w = Math.max(w, sum);
            }
            ViewGroup.LayoutParams lp = c.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                w += m.getMarginStart() + m.getMarginEnd();
            }
            return w;
        }

        /** The signal tower icon: SIM 1 waves left, SIM 2 right. */
        void updateTower(int color) {
            View v = bar.findViewWithTag("dual_tower");
            if (!(v instanceof SignalTowerView)) return;
            SignalTowerView tv = (SignalTowerView) v;
            boolean on = Cfg.b(Cfg.TOWER_ICON, Cfg.D_TOWER_ICON) && !sims.isEmpty();
            if (on != towerShown) {
                towerShown = on;
                XposedBridge.log(TAG + (on ? "signal tower shown (stock signal icons hidden)" : "signal tower off"));
            }
            // The tower already shows signal, network type, band and VoLTE / VoNR, so the row-1
            // copies of the stock icons are duplicates. Left visible they took ~140px of the
            // tower's room (squeezed to nothing, but still counted) and leaked as a stray ▲▼.
            if (imsRow != null) {
                int iv = on ? View.GONE : View.VISIBLE;
                if (imsRow.getVisibility() != iv) imsRow.setVisibility(iv);
            }
            if (!on) {
                if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
                return;
            }
            SignalTowerView.Side[] sd = {new SignalTowerView.Side(), new SignalTowerView.Side()};
            int dataSlot = DataActivity.slot();
            for (Bands.Sim s : sims) {
                if (s.slot < 1 || s.slot > 2) continue;
                SignalTowerView.Side x = sd[s.slot - 1];
                x.present = true;
                x.inService = s.inService;
                x.level = s.level;
                x.net = s.net;
                x.band = Cfg.b(Cfg.SHOW_BAND, Cfg.D_SHOW_BAND) ? s.band : null;
                String vo = Voice.label(s.sub, s.band);
                x.voice = vo == null ? null : "Vo" + vo;
                x.data = dataSlot == s.slot;
                cellsColor = color;
                x.cells = Cfg.b(Cfg.SHOW_CELLS, Cfg.D_SHOW_CELLS) ? s.cells : -1;
                x.sinrColor = sinrColor(s);
                x.inCall = s.inCall;
                x.dist = Cfg.b(Cfg.SHOW_DIST, Cfg.D_SHOW_DIST) ? distString(s) : null;
                if (Cfg.b(Cfg.SINR_MAST, Cfg.D_SINR_MAST) && !Float.isNaN(s.snr)) {
                    cellsColor = color;
                    x.sinr = String.valueOf(Math.round(s.snr));
                    x.sinrColor = sinrColor(s);
                }
                Object[] ob = SignalTowerView.operatorBadge(s.operator, s.mccMnc);
                if (ob != null) {
                    x.opLetter = (String) ob[0];
                    x.opColor = (Integer) ob[1];
                }
                if (SysState.nsRat == 4 && s.slot == dataSlot && System.currentTimeMillis() - SysState.nsAt < 150_000L) {
                    boolean dkb = darkBar(color);
                    x.tag = "E";
                    x.tagColor = SysState.nsEco != 0 ? SysViews.green(dkb) : SysViews.amber(dkb);
                }
                x.dbm = Cfg.b(Cfg.SHOW_DBM, Cfg.D_SHOW_DBM) && s.dbm != Bands.NO_DBM ? String.valueOf(s.dbm) : null;
                x.in = x.data && (DataActivity.in() || SysState.testing());
                x.out = x.data && (DataActivity.out() || SysState.testing());
            }
            float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, Cfg.i(Cfg.BAND_SP, Cfg.D_BAND_SP),
                    bar.getResources().getDisplayMetrics());
            Typeface tf = clock instanceof TextView ? ((TextView) clock).getTypeface() : null;
            float tscale = Cfg.i(Cfg.TOWER_PCT, Cfg.D_TOWER_PCT) / 100f;
            fitTower(tv, sd, px, tscale);
            tv.update(sd[0], sd[1], color, px, tf, tscale);
            int hash = java.util.Arrays.hashCode(new Object[]{sd[0].net, sd[0].level, sd[0].voice, sd[1].net,
                    sd[1].level, sd[1].voice, dataSlot});
            if (hash != lastTowerLogHash) {
                lastTowerLogHash = hash;
                logOnce("tower" + hash, "tower: SIM1 " + sd[0].net + " " + sd[0].level + "/4 " + sd[0].voice
                        + " | SIM2 " + sd[1].net + " " + sd[1].level + "/4 " + sd[1].voice + " | data SIM " + dataSlot);
            }
            if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
        }

        /**
         * 5G / 4G+ icon (the system's own image, "SA" included) with ↓↑ underneath. Each arrow
         * lights up from the modem's data activity, like Samsung; dim when idle.
         */
        void updateNetType(int color) {
            View v = bar.findViewWithTag("dual_nettype");
            if (!(v instanceof LinearLayout)) return;
            ImageView icon = v.findViewWithTag("dual_nettype_icon");
            ImageView arrows = v.findViewWithTag("dual_nettype_arrows");
            boolean fresh = typeDrawable != null && android.os.SystemClock.elapsedRealtime() - typeSeenAt < 3_000;
            boolean show = Cfg.b(Cfg.NET_TYPE, Cfg.D_NET_TYPE) && fresh && !towerShown;
            netTypeShown = show;
            if (!show) {
                if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
                return;
            }
            if (shownType != typeDrawable) {
                boolean firstType = shownType == null;
                shownType = typeDrawable;
                if (!firstType && Cfg.b(Cfg.ANIM_SWITCH, Cfg.D_ANIM_SWITCH)) {
                    final View pop = icon;
                    icon.post(() -> AnimFx.pop(pop, 1.7f));
                }
                Drawable d = typeDrawable.getConstantState() != null
                        ? typeDrawable.getConstantState().newDrawable(ctx.getResources()).mutate() : typeDrawable;
                int h = Math.max(dp(bar.getResources(), 8), Math.round(typeHeight * 0.72f));
                // Bold: the shift scales with the icon's drawn size (intrinsic px -> shown px).
                float scale = d.getIntrinsicHeight() > 0 ? (float) h / d.getIntrinsicHeight() : 1f;
                float boldPx = bar.getResources().getDisplayMetrics().density * 0.8f / Math.max(0.3f, scale);
                icon.setImageDrawable(new BoldDrawable(d, boldPx));
                ViewGroup.LayoutParams lp = icon.getLayoutParams();
                if (lp.height != h) {
                    lp.height = h;
                    icon.setLayoutParams(lp);
                }
            }
            icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
            long tAt = SysState.animTestAt;
            boolean testing = SysState.testing();
            if (testing && tAt != lastTestSwitch) {
                lastTestSwitch = tAt;
                if (Cfg.b(Cfg.ANIM_SWITCH, Cfg.D_ANIM_SWITCH)) {
                    final View popV = icon;
                    icon.post(() -> AnimFx.pop(popV, 1.7f));
                }
            }
            int dim = (color & 0x00FFFFFF) | 0x4D000000;
            if (arrows != null && arrows.getDrawable() instanceof ArrowsDrawable) {
                ArrowsDrawable ad = (ArrowsDrawable) arrows.getDrawable();
                boolean mv = Cfg.b(Cfg.ANIM_ARROWS, Cfg.D_ANIM_ARROWS);
                ad.setMotion(mv && (testing || DataActivity.in()), mv && (testing || DataActivity.out()));
                ad.setColors(DataActivity.in() ? color : dim, DataActivity.out() ? color : dim);
            }
            View pd = v.findViewWithTag("dual_nettype_ping");
            if (pd != null) {
                long pat = SysState.pingAt;
                boolean pOn = Cfg.b(Cfg.ANIM_PING, Cfg.D_ANIM_PING) && (testing
                        || (pat > 0 && System.currentTimeMillis() - pat < 30_000));
                if (!pOn) {
                    if (pd.getVisibility() != View.GONE) pd.setVisibility(View.GONE);
                } else {
                    int ms = SysState.pingMs;
                    int pc = ms < 0 ? 0xFFFF453A : ms < 100 ? 0xFF34C759 : ms < 300 ? 0xFFFFB020 : 0xFFFF453A;
                    if (pd.getBackground() instanceof android.graphics.drawable.GradientDrawable) {
                        ((android.graphics.drawable.GradientDrawable) pd.getBackground()).setColor(pc);
                    }
                    if (pd.getVisibility() != View.VISIBLE) pd.setVisibility(View.VISIBLE);
                    boolean testPing = testing && tAt != lastTestPing;
                    if (testPing) lastTestPing = tAt;
                    if (pat != lastPingAt || testPing) {
                        lastPingAt = pat;
                        AnimFx.pop(pd, 1.9f);
                    }
                }
            }
            TextView simNo = v.findViewWithTag("dual_nettype_sim");
            if (simNo != null) {
                int slot = DataActivity.slot();
                String t = slot > 0 ? String.valueOf(slot) : "";
                if (!t.contentEquals(simNo.getText())) simNo.setText(t);
                simNo.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);
                simNo.setTextColor(color);
            }
            if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
        }
        final java.util.Set<View> tightened = java.util.Collections.newSetFromMap(new WeakHashMap<>());

        /**
         * Pulls the network type (5G / 4G+) right up to the signal bars inside a SIM's signal
         * view, by removing the spacing around the type icon. The row-2 original is hidden,
         * so this only changes the row-1 copy.
         */
        void tightenMobile(View root) {
            if (tightened.contains(root)) return;
            tightened.add(root);
            StringBuilder names = new StringBuilder();
            tightenWalk(root, names);
            root.requestLayout();
            logOnce("mobileids", "signal view parts: " + names);
        }

        void tightenWalk(View v, StringBuilder names) {
            String name = "";
            try {
                if (v.getId() != View.NO_ID) name = v.getResources().getResourceEntryName(v.getId());
            } catch (Throwable ignored) { }
            if (!name.isEmpty()) names.append(name).append(' ');
            if (name.equals("mobile_type") || name.equals("mobile_type_container")
                    || name.equals("mobile_roaming_space")) {
                v.setPaddingRelative(v.getPaddingStart(), v.getPaddingTop(), 0, v.getPaddingBottom());
                if (v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
                    lp.setMarginEnd(0);
                    v.setLayoutParams(lp);
                }
            }
            if (name.equals("mobile_signal") || name.equals("mobile_group")
                    || name.equals("mobile_signal_container")) {
                if (v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
                    lp.setMarginStart(0);
                    v.setLayoutParams(lp);
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) tightenWalk(g.getChildAt(i), names);
            }
        }

        /**
         * Lunaris can keep two kinds of signal view per SIM in the container (an older and a
         * newer one) and sometimes reports both as shown. When more than one kind is shown,
         * keep the newer ("Modern...") kind, else the one with the most shown views.
         */
        /** Subscription id of a signal view, or null if it can't be read. */
        Integer subIdOf(View v) {
            for (String m : new String[]{"getSubId", "getSubscriptionId"}) {
                try {
                    Object o = XposedHelpers.callMethod(v, m);
                    if (o instanceof Integer) return (Integer) o;
                } catch (Throwable ignored) { }
            }
            for (String f : new String[]{"subId", "mSubId", "subscriptionId"}) {
                try {
                    Object o = XposedHelpers.getObjectField(v, f);
                    if (o instanceof Integer) return (Integer) o;
                } catch (Throwable ignored) { }
            }
            return null;
        }

        /**
         * Signal views to leave out because they're extras: at most one per SIM (by subscription,
         * the newest wins), and never more than the number of active SIMs.
         */
        java.util.Set<View> mobileDupes(ViewGroup g, String keepClass) {
            java.util.Set<View> out = new java.util.HashSet<>();
            List<View> mobile = new ArrayList<>();
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                String slot;
                try {
                    slot = String.valueOf(XposedHelpers.callMethod(c, "getSlot"));
                } catch (Throwable t) {
                    continue;
                }
                if (!slot.toLowerCase(Locale.US).contains("mobile")) continue;
                if (c.getVisibility() != View.VISIBLE || c.getWidth() == 0 || !iconShown(c)) continue;
                if (keepClass != null && !keepClass.equals(c.getClass().getName())) continue;
                mobile.add(c);
            }
            Map<Integer, View> bySub = new HashMap<>();
            for (View c : mobile) {
                Integer sub = subIdOf(c);
                if (sub == null) continue;
                View prev = bySub.put(sub, c);
                if (prev != null) out.add(prev);
            }
            int simCount = Math.max(1, sims.size());
            List<View> left = new ArrayList<>();
            for (View c : mobile) if (!out.contains(c)) left.add(c);
            for (int i = 0; i < left.size() - simCount; i++) out.add(left.get(i));
            if (!out.isEmpty()) logOnce("mobiledupes" + mobile.size(), "signal icons: " + mobile.size()
                    + " shown for " + simCount + " SIMs, leaving out " + out.size() + " extra");
            return out;
        }

        String mobileClassToKeep(ViewGroup g) {
            Map<String, Integer> counts = new HashMap<>();
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                String slot;
                try {
                    slot = String.valueOf(XposedHelpers.callMethod(c, "getSlot"));
                } catch (Throwable t) {
                    continue;
                }
                if (!slot.toLowerCase(Locale.US).contains("mobile")) continue;
                if (c.getVisibility() != View.VISIBLE || c.getWidth() == 0 || !iconShown(c)) continue;
                counts.merge(c.getClass().getName(), 1, Integer::sum);
            }
            if (counts.size() < 2) return null;
            String best = null;
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                String simple = e.getKey().substring(e.getKey().lastIndexOf('.') + 1);
                if (simple.startsWith("Modern")) return e.getKey();
                if (best == null || e.getValue() > counts.get(best)) best = e.getKey();
            }
            logOnce("mobileclasses", "signal view kinds " + counts + ", keeping " + best);
            return best;
        }

        /**
         * Whether System UI itself wants this icon shown. Some icons stay in the container
         * but hidden (e.g. an older and a newer version of the signal icon), and they
         * report that here rather than through their view visibility.
         */
        boolean iconShown(View child) {
            try {
                Object v = XposedHelpers.callMethod(child, "isIconVisible");
                if (v instanceof Boolean && !((Boolean) v)) return false;
            } catch (Throwable ignored) { }
            try {
                Object st = XposedHelpers.callMethod(child, "getVisibleState");
                if (st instanceof Integer && (Integer) st != 0) return false; // 0 = STATE_ICON
            } catch (Throwable ignored) { }
            return true;
        }

        /** Row-2 icon view -> its row-1 copy, and the bitmap it is drawn into. */
        final Map<View, ImageView> snaps = new WeakHashMap<>();
        final Map<View, Bitmap> bitmaps = new WeakHashMap<>();

        /**
         * Shows VoLTE / VoNR / mobile signal on row 1 next to the battery. On this ROM those
         * icons are not plain images and the container can't be told to ignore them, so each
         * one is drawn into a bitmap shown on row 1, and its original is skipped when row 2
         * draws (see the dispatchDraw hook). Row 2's icons are shifted right to close the gap.
         */
        boolean loggedRestrict = false;

        /**
         * The icon container shows at most ~7 icons. The ones copied to row 1 still count
         * there, so on 5G (VoNR + two signals + VoLTE) icons like Bluetooth got dropped.
         * Row 2 has plenty of room, so turn the limit off.
         */
        void unrestrict(ViewGroup g) {
            try {
                java.lang.reflect.Field f = null;
                for (Class<?> c = g.getClass(); c != null && f == null; c = c.getSuperclass()) {
                    try {
                        f = c.getDeclaredField("mShouldRestrictIcons");
                    } catch (NoSuchFieldException ignored) { }
                }
                if (f == null) throw new NoSuchFieldException("mShouldRestrictIcons");
                f.setAccessible(true);
                if (f.getBoolean(g)) {
                    f.setBoolean(g, false);
                    g.requestLayout();
                    if (!loggedRestrict) {
                        loggedRestrict = true;
                        XposedBridge.log(TAG + "status icon limit turned off for row 2");
                    }
                }
            } catch (Throwable t) {
                try {
                    XposedHelpers.callMethod(g, "setShouldRestrictIcons", false);
                } catch (Throwable t2) {
                    logOnce("restrict", "couldn't lift the status icon limit: " + t2);
                }
            }
        }

        void updateMirrors(int color) {
            if (!(statusIcons instanceof ViewGroup) || imsRow == null) return;
            ViewGroup g = (ViewGroup) statusIcons;
            unrestrict(g);
            List<View> moved = new ArrayList<>();
            float rightAll = 0, rightOther = -1;
            String keepClass = mobileClassToKeep(g);
            java.util.Set<View> dupes = mobileDupes(g, keepClass);
            for (int i = 0; i < g.getChildCount(); i++) {
                View child = g.getChildAt(i);
                boolean shown = child.getVisibility() == View.VISIBLE && child.getWidth() > 0
                        && child.getAlpha() > 0.01f && iconShown(child);
                float right = child.getX() + child.getWidth();
                String slot;
                try {
                    slot = String.valueOf(XposedHelpers.callMethod(child, "getSlot"));
                } catch (Throwable t) {
                    slot = "";
                }
                logOnce("slot:" + slot, "status icon slot: " + slot + " (" + child.getClass().getSimpleName() + ")");
                if (!isRow1Slot(slot)) {
                    if (shown) rightOther = Math.max(rightOther, right);
                    if (shown) rightAll = Math.max(rightAll, right);
                    continue;
                }
                moved.add(child);
                // Two kinds of signal view for the same SIM: show only one kind.
                if (keepClass != null && slot.toLowerCase(Locale.US).contains("mobile")
                        && !keepClass.equals(child.getClass().getName())) {
                    shown = false;
                }
                // Same kind twice for one SIM (left over after a network change): one copy only.
                if (dupes.contains(child)) shown = false;
                if (shown) rightAll = Math.max(rightAll, right);

                ImageView m = snaps.get(child);
                if (m == null) {
                    m = new ImageView(ctx);
                    m.setScaleType(ImageView.ScaleType.CENTER);
                    imsRow.addView(m, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    snaps.put(child, m);
                    XposedBridge.log(TAG + "showing " + slot + " on row 1");
                }
                // Keep row-1 order the same as row 2's.
                int want = moved.size() - 1;
                if (imsRow.indexOfChild(m) != want && want < imsRow.getChildCount()) {
                    imsRow.removeView(m);
                    imsRow.addView(m, want);
                }
                if (!shown || child.getHeight() == 0
                        || (voiceSlot(slot) && (voBadgeShown || towerShown))
                        || (towerShown && slot.toLowerCase(Locale.US).contains("mobile"))) {
                    m.setVisibility(View.GONE);
                    continue;
                }
                int cropX = 0;
                if (slot.toLowerCase(Locale.US).contains("mobile")) {
                    tightenMobile(child);
                    View type = findByName(child, "mobile_type");
                    View sig = findByName(child, "mobile_signal");
                    if (type instanceof ImageView && type.isShown() && ((ImageView) type).getDrawable() != null) {
                        typeDrawable = ((ImageView) type).getDrawable();
                        typeHeight = type.getHeight();
                        typeSeenAt = android.os.SystemClock.elapsedRealtime();
                    }
                    // Our own type + arrows replaces the stock type: start the copy at the bars.
                    if (netTypeShown && sig != null) cropX = Math.max(0, offsetIn(sig, child));
                }
                // Draw with a margin: parts like the "SA" mark can sit outside the view's bounds.
                int pad = dp(ctx.getResources(), 3);
                int bw = child.getWidth() - cropX + 2 * pad, bh = child.getHeight() + 2 * pad;
                Bitmap bmp = bitmaps.get(child);
                if (bmp == null || bmp.getWidth() != bw || bmp.getHeight() != bh) {
                    bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                    bitmaps.put(child, bmp);
                }
                if (m.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                    LinearLayout.LayoutParams mlp = (LinearLayout.LayoutParams) m.getLayoutParams();
                    if (mlp.leftMargin != -pad || mlp.topMargin != -pad) {
                        mlp.setMargins(-pad, -pad, -pad, -pad);
                        mlp.gravity = Gravity.CENTER_VERTICAL;
                        m.setLayoutParams(mlp);
                    }
                }
                bmp.eraseColor(0);
                try {
                    Canvas cv = new Canvas(bmp);
                    cv.translate(pad - cropX, pad);
                    child.draw(cv);
                } catch (Throwable t) {
                    logOnce("snap:" + slot, "drawing " + slot + " failed: " + t);
                    m.setVisibility(View.GONE);
                    continue;
                }
                m.setImageBitmap(bmp);
                m.invalidate();
                m.setVisibility(View.VISIBLE);
            }
            // Log what's being copied whenever the set changes (helps spot duplicates).
            int visibleCopies = 0;
            for (ImageView m : snaps.values()) if (m.getVisibility() == View.VISIBLE) visibleCopies++;
            if (visibleCopies != lastCopies) {
                lastCopies = visibleCopies;
                StringBuilder sb = new StringBuilder("row-1 icons now " + visibleCopies + ":");
                for (View c : moved) {
                    sb.append(' ').append(c.getClass().getSimpleName())
                            .append(iconShown(c) ? "[on]" : "[off]")
                            .append(c.getWidth()).append("px");
                }
                XposedBridge.log(TAG + sb);
            }
            // Drop copies of icons that no longer exist (e.g. a SIM removed).
            for (View old : new ArrayList<>(snaps.keySet())) {
                if (old.getParent() != g) {
                    ImageView m = snaps.remove(old);
                    if (m != null) imsRow.removeView(m);
                }
            }
            synchronized (SKIP) {
                SKIP.clear();
                SKIP.addAll(moved);
            }
            // Close the gap the hidden icons leave at the right end of row 2.
            float shift = rightOther >= 0 && rightAll > rightOther ? rightAll - rightOther : 0;
            if (rightOther < 0) shift = 0;
            if (Math.abs(g.getTranslationX() - shift) > 0.5f) g.setTranslationX(shift);
            g.invalidate();
        }

        /** Two lines, one per SIM that has a band: "n78" + small "1", "B3" + small "2". */
        /** dBm left out because row 1's right side (after the camera) ran out of room. */
        boolean dbmDropped = false;
        int dbmExtraPx = 0;

        /** Screen x where row 1's right area starts: the camera cutout's right edge (plus 2dp). */
        int rightLimit() {
            int limit = Integer.MIN_VALUE;
            try {
                android.view.WindowInsets wi = bar.getRootWindowInsets();
                android.view.DisplayCutout dc = wi == null ? null : wi.getDisplayCutout();
                if (dc != null) {
                    int[] b = new int[2];
                    bar.getLocationOnScreen(b);
                    for (android.graphics.Rect r : dc.getBoundingRects()) {
                        if (r.top > b[1] + bar.getHeight() || r.right >= b[0] + bar.getWidth()) continue;
                        limit = Math.max(limit, r.right + dp(bar.getResources(), 2));
                    }
                }
            } catch (Throwable ignored) { }
            return limit;
        }

        /** What right-side extras line up before: the band label, or the tower when the band is inside it. */
        View rightAnchor() {
            View b = bar.findViewWithTag("dual_band");
            View t = bar.findViewWithTag("dual_tower");
            if (b != null && b.getVisibility() == View.VISIBLE) return b;
            if (t != null && t.getVisibility() == View.VISIBLE) return t;
            return b;
        }

        /** Leftmost on-screen x of the row-1 right-side items we add (power if there, else band). */
        int rightGroupLeft(View band) {
            View pw = bar.findViewWithTag("dual_power");
            View first = pw != null && pw.getVisibility() == View.VISIBLE && pw.getParent() == band.getParent()
                    ? pw : band;
            if (first.getWidth() == 0) return Integer.MAX_VALUE;
            int[] p = new int[2];
            first.getLocationOnScreen(p);
            return p[0];
        }

        int lastBandColor = 0;
        boolean voBadgeShown = false;
        int lastVoColor = 0;

        /** One "Vo" badge: per SIM its voice type now (NR / LTE / WiFi), small SIM number. */
        void updateVoice(int color) {
            View v = bar.findViewWithTag("dual_vo");
            if (!(v instanceof LinearLayout)) return;
            TextView box = v.findViewWithTag("dual_vo_box");
            TextView lines = v.findViewWithTag("dual_vo_lines");
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            if (Cfg.b(Cfg.VO_BADGE, Cfg.D_VO_BADGE)) {
                for (Bands.Sim s : sims) {
                    String t = Voice.label(s.sub, s.band);
                    if (t == null) continue;
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(t);
                    int st = sb.length();
                    sb.append(String.valueOf(s.slot));
                    sb.setSpan(new android.text.style.SubscriptSpan(), st, sb.length(), 0);
                    sb.setSpan(new android.text.style.RelativeSizeSpan(0.7f), st, sb.length(), 0);
                }
            }
            boolean show = sb.length() > 0 && !towerShown;
            voBadgeShown = show;
            if (!show) {
                if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
                return;
            }
            int sp = Cfg.i(Cfg.BAND_SP, Cfg.D_BAND_SP);
            Hook.setSp(lines, sp);
            Hook.setSp(box, sp);
            boolean bold = Cfg.b(Cfg.BOLD, Cfg.D_BOLD);
            Hook.matchClockFont(clock, lines, bold);
            Hook.matchClockFont(clock, box, true);
            lines.setTextColor(color);
            box.setTextColor(color);
            if (box.getBackground() != null) box.setBackground(null); // no frame
            if (!sb.toString().equals(String.valueOf(lines.getText()))) lines.setText(sb);
            fitWidthSpanned(lines, sb);
            if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
        }

        /** "350m", "1.2km", "12km". */
        String distLabel(int m) {
            if (m < 1000) return m + "m";
            if (m < 10_000) return String.format(java.util.Locale.US, "%.1fkm", m / 1000f);
            return (m / 1000) + "km";
        }

        /** Like fitWidth, but counts styled parts (small SIM number, tower symbol) correctly. */
        void fitWidthSpanned(TextView tv, CharSequence text) {
            float max = 0;
            int start = 0;
            String plain = text.toString();
            android.text.TextPaint tp = tv.getPaint();
            while (start <= plain.length()) {
                int end = plain.indexOf('\n', start);
                if (end < 0) end = plain.length();
                max = Math.max(max, android.text.Layout.getDesiredWidth(text, start, end, tp));
                start = end + 1;
            }
            int w = (int) Math.ceil(max) + tv.getPaddingLeft() + tv.getPaddingRight() + 1;
            if (tv.getMinWidth() != w) tv.setMinWidth(w);
        }

        int bandLevel = 2;

        /** Band label text at a level: 0 band, 1 + dBm, 2 + distance. One line per SIM. */
        android.text.SpannableStringBuilder bandText(TextView tv, int color, int level) {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            if (!Cfg.b(Cfg.SHOW_BAND, Cfg.D_SHOW_BAND)) return sb;
            for (Bands.Sim s : sims) {
                if (s.band == null) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(s.band);
                int start = sb.length();
                sb.append(String.valueOf(s.slot));
                sb.setSpan(new android.text.style.SubscriptSpan(), start, sb.length(), 0);
                sb.setSpan(new android.text.style.RelativeSizeSpan(0.7f), start, sb.length(), 0);
                if (level >= 1 && s.dbm != Bands.NO_DBM) sb.append(" ").append(String.valueOf(s.dbm));
                if (level >= 2) {
                    sb.append(' ');
                    int at = sb.length();
                    sb.append('\u2009'); // stands in for the tower symbol
                    TowerDrawable td = new TowerDrawable(color, Math.round(tv.getTextSize() * 0.95f));
                    sb.setSpan(new android.text.style.ImageSpan(td,
                            android.text.style.DynamicDrawableSpan.ALIGN_CENTER), at, at + 1, 0);
                    sb.append(s.distM < 0 ? "?" : (s.guess ? "\u2248" : s.approx ? "~" : "") + distLabel(s.distM));
                }
            }
            return sb;
        }

        /** Width of the widest line, counting spans (small numbers, symbols). */
        float spannedWidth(TextView tv, CharSequence text) {
            float max = 0;
            int start = 0;
            String plain = text.toString();
            while (start <= plain.length()) {
                int end = plain.indexOf('\n', start);
                if (end < 0) end = plain.length();
                max = Math.max(max, android.text.Layout.getDesiredWidth(text, start, end, tv.getPaint()));
                start = end + 1;
            }
            return max;
        }

        void updateBands(int color) {
            View v = bar.findViewWithTag("dual_band");
            if (!(v instanceof TextView)) return;
            TextView tv = (TextView) v;
            // The band is drawn inside the signal tower when that's on.
            if (towerShown) {
                if (tv.getVisibility() != View.GONE) tv.setVisibility(View.GONE);
                return;
            }

            // Three levels: 2 = band + dBm + distance, 1 = band + dBm, 0 = band only.
            // Use the fullest one that fits before the camera; drop the distance first.
            boolean wantDbm = Cfg.b(Cfg.SHOW_DBM, Cfg.D_SHOW_DBM);
            // dBm now sits left of the camera (dual_dbm); distance sits with the tower counts.
            int maxLevel = 0;
            android.text.SpannableStringBuilder[] lv = new android.text.SpannableStringBuilder[3];
            float[] w = new float[3];
            for (int l = 0; l <= maxLevel; l++) {
                lv[l] = bandText(tv, color, l);
                w[l] = spannedWidth(tv, lv[l]);
            }
            int level = maxLevel;
            int limit = rightLimit();
            if (limit != Integer.MIN_VALUE && tv.getVisibility() == View.VISIBLE && tv.getWidth() > 0) {
                int[] pos = new int[2];
                tv.getLocationOnScreen(pos);
                int right = pos[0] + tv.getWidth();   // the group is right-aligned: this edge stays put
                int slack = dp(bar.getResources(), 4);
                level = 0;
                for (int l = maxLevel; l >= 1; l--) {
                    int leftIf = right - (int) Math.ceil(w[l]) - tv.getPaddingLeft() - tv.getPaddingRight();
                    if (leftIf - (l > bandLevel ? slack : 0) >= limit) {
                        level = l;
                        break;
                    }
                }
            }
            if (level != bandLevel) {
                XposedBridge.log(TAG + "band label: " + (level == 2 ? "band + dBm + distance"
                        : level == 1 ? "band + dBm (no room for distance)" : "band only (no room for dBm)"));
                bandLevel = level;
            }
            android.text.SpannableStringBuilder sb = lv[level] != null ? lv[level] : new android.text.SpannableStringBuilder();
            if (sb.length() == 0) {
                if (tv.getVisibility() != View.GONE) tv.setVisibility(View.GONE);
                return;
            }
            tv.setTextColor(color);
            Hook.setSp(tv, Cfg.i(Cfg.BAND_SP, Cfg.D_BAND_SP));
            Hook.matchClockFont(clock, tv, Cfg.b(Cfg.BOLD, Cfg.D_BOLD));
            if (!sb.toString().equals(String.valueOf(tv.getText())) || lastBandColor != color) {
                tv.setText(sb);
                lastBandColor = color;
            }
            fitWidthSpanned(tv, sb);
            if (tv.getVisibility() != View.VISIBLE) tv.setVisibility(View.VISIBLE);
        }

        boolean powerOnRight = false;
        /** Per left column (by tag): 0 hidden for lack of room, 1 short form, 2 full form. */
        final Map<String, Integer> leftMode = new HashMap<>();

        /** Light text on a dark bar? (colored values only there; plain on light bars) */
        boolean darkBar(int color) {
            return (0.299f * ((color >> 16) & 0xFF) + 0.587f * ((color >> 8) & 0xFF) + 0.114f * (color & 0xFF)) > 128;
        }

        /** Small grey label after line 1's value, e.g. "-108ᵈᴮᵐ". */
        void tinyLabel(android.text.SpannableStringBuilder sb, String label, int color) {
            int st = sb.length();
            sb.append(label);
            sb.setSpan(new android.text.style.RelativeSizeSpan(0.55f), st, sb.length(), 0);
            sb.setSpan(new android.text.style.SuperscriptSpan(), st, sb.length(), 0);
            sb.setSpan(new android.text.style.ForegroundColorSpan((color & 0x00FFFFFF) | 0x99000000), st, sb.length(), 0);
        }

        /**
         * A two-line column left of the camera, placed after `after`. Shows `full` if it fits,
         * else `brief`, else hides. Never cut off.
         */
        void leftColumn(String tag, CharSequence full, CharSequence brief, int color, View after) {
            Resources res = bar.getResources();
            TextView tv = (TextView) bar.findViewWithTag(tag);
            if (tv == null) {
                tv = new TextView(bar.getContext());
                tv.setTag(tag);
                tv.setIncludeFontPadding(false);
                tv.setLineSpacing(0, 0.85f);
                tv.setMaxLines(2);
                tv.setHorizontallyScrolling(true);
                tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                tv.setVisibility(View.GONE);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.CENTER_VERTICAL;
                lp.setMarginStart(dp(res, 3));
                extras.root.addView(tv, lp);
            }
            if (full == null || full.length() == 0) {
                if (tv.getVisibility() != View.GONE) tv.setVisibility(View.GONE);
                return;
            }
            tv.setTextColor(color);
            Hook.setSp(tv, Cfg.i(Cfg.BAND_SP, Cfg.D_BAND_SP));
            Hook.matchClockFont(clock, tv, Cfg.b(Cfg.BOLD, Cfg.D_BOLD));
            tv.setLetterSpacing(-0.04f);

            int want = after != null ? extras.root.indexOfChild(after) + 1 : extras.root.getChildCount();
            int cur = extras.root.indexOfChild(tv);
            if (cur != want && cur != want - 1) {
                ViewGroup.LayoutParams lp = tv.getLayoutParams();
                extras.root.removeView(tv);
                extras.root.addView(tv, Math.min(want > cur ? want - 1 : want, extras.root.getChildCount()), lp);
            }
            int room = Integer.MAX_VALUE;
            int limit = leftLimit();
            if (after != null && after.getWidth() > 0 && limit != Integer.MAX_VALUE) {
                int[] p = new int[2];
                after.getLocationOnScreen(p);
                int aw = Math.max(after.getWidth(), after instanceof TextView ? ((TextView) after).getMinWidth() : 0);
                room = limit - (p[0] + aw) - dp(res, 3);
            }
            int prev = leftMode.containsKey(tag) ? leftMode.get(tag) : 2;
            int slack = dp(res, 4);
            int mode = 0;
            CharSequence show = null;
            float wFull = spannedWidth(tv, full) + 1, wBrief = brief != null ? spannedWidth(tv, brief) + 1 : Float.MAX_VALUE;
            if (wFull <= room - (prev < 2 ? slack : 0)) {
                mode = 2;
                show = full;
            } else if (brief != null && wBrief <= room - (prev < 1 ? slack : 0)) {
                mode = 1;
                show = brief;
            }
            if (mode != prev) {
                leftMode.put(tag, mode);
                XposedBridge.log(TAG + tag + (mode == 2 ? " shown" : mode == 1 ? " shown without labels"
                        : " hidden: no room left of the camera") + " (room " + room + "px)");
            }
            if (mode == 0) {
                if (tv.getVisibility() != View.GONE) tv.setVisibility(View.GONE);
                return;
            }
            if (!show.toString().equals(String.valueOf(tv.getText())) || !(tv.getText() instanceof android.text.Spanned)
                    || tv.getCurrentTextColor() != color) tv.setText(show);
            fitWidthSpanned(tv, show);
            if (tv.getVisibility() != View.VISIBLE) tv.setVisibility(View.VISIBLE);
        }

        View visibleIn(View v) {
            return v != null && v.getParent() == extras.root && v.getVisibility() == View.VISIBLE ? v : null;
        }

        // ---- Text curved around the camera hole: date over it, sunrise -> sunset under it.

        String camLog = "";

        void updateCameraText(int color) {
            CameraTextView v = (CameraTextView) bar.findViewWithTag("dual_camtext");
            if (v == null) {
                v = new CameraTextView(ctx);
                v.setTag("dual_camtext");
                bar.addView(v, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            // The readable layout already shows the date and sunrise / sunset on the left, and keeps
            // the camera area clear.
            if (!Cfg.b(Cfg.CAM_TEXT, Cfg.D_CAM_TEXT) || Cfg.b(Cfg.READABLE, Cfg.D_READABLE) || Cfg.b(Cfg.PRO, Cfg.D_PRO)) {
                if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
                return;
            }
            // the camera hole, in the bar's coordinates
            android.graphics.Rect cam = null;
            try {
                android.view.WindowInsets wi = bar.getRootWindowInsets();
                android.view.DisplayCutout dc = wi == null ? null : wi.getDisplayCutout();
                if (dc != null) {
                    // relative to the text layer itself (the bar's padding moves it in from the edge)
                    int[] b = new int[2];
                    v.getLocationOnScreen(b);
                    for (android.graphics.Rect r : dc.getBoundingRects()) {
                        if (r.top > b[1] + bar.getHeight()) continue;
                        cam = new android.graphics.Rect(r);
                        cam.offset(-b[0], -b[1]);
                        break;
                    }
                }
            } catch (Throwable ignored) { }
            if (cam == null || cam.width() <= 0) {
                logOnce("nocam", "no camera cutout found, no curved text");
                if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
                return;
            }
            Resources res = bar.getResources();
            v.cx = cam.exactCenterX();
            v.cy = cam.exactCenterY();
            v.r = Math.min(cam.width(), cam.height()) / 2f;
            v.textPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                    Math.max(5, Cfg.i(Cfg.TEMP_SP, Cfg.D_TEMP_SP) - 1), res.getDisplayMetrics());
            v.setFont(clock instanceof TextView ? ((TextView) clock).getTypeface() : null);
            boolean dk = darkBar(color);
            // Battery health on top, charge cycles under the camera.
            int soh = batSoh, cyc = batCycles;
            v.top = soh > 0 ? "Health " + soh + "%" : "";
            v.bottom = cyc >= 0 ? cyc + (cyc == 1 ? " cycle" : " cycles") : "";
            // health colour: green from 90 %, amber 80-89 %, red below 80 % (worn)
            v.topColor = soh <= 0 || soh >= 90 ? color : soh >= 80
                    ? (dk ? 0xFFFF9F0A : 0xFFC46A00) : (dk ? 0xFFFF453A : 0xFFC4281C);
            v.bottomColor = dk ? 0xFF64D2FF : 0xFF0A6FA8;
            // Under the camera is row 2: skip that line if notification icons reach under it.
            float ringOuter = v.r + v.textPx * 1.6f;
            int notifRight = 0;
            View icons = byId(bar, "notificationIcons");
            View sysRowV = bar.findViewWithTag("dual_sys");
            if (sysRowV != null && sysRowV.getVisibility() == View.VISIBLE) {
                icons = null;
                if (sysRowV.getWidth() > 0) {
                    int[] sp = new int[2], bp0 = new int[2];
                    sysRowV.getLocationOnScreen(sp);
                    bar.getLocationOnScreen(bp0);
                    notifRight = sp[0] - bp0[0] + sysRowV.getWidth();
                }
            }
            if (icons instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) icons;
                int[] bp = new int[2], ip = new int[2];
                bar.getLocationOnScreen(bp);
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c.getVisibility() != View.VISIBLE || c.getWidth() == 0) continue;
                    c.getLocationOnScreen(ip);
                    notifRight = Math.max(notifRight, ip[0] - bp[0] + c.getWidth());
                }
            }
            int[] vp = new int[2], bp2 = new int[2];
            v.getLocationOnScreen(vp);
            bar.getLocationOnScreen(bp2);
            v.showBottom = notifRight < v.cx + (vp[0] - bp2[0]) - ringOuter;
            // Bottom blocked by notification icons: date and sun times take turns on the top arc.
            if (!v.showBottom && (System.currentTimeMillis() / 5000) % 2 == 1) {
                v.top = v.bottom;
                v.topColor = v.bottomColor;
            }
            v.setAlpha(hiddenForLock ? 0f : 1f);
            String lg = v.top + "|" + v.bottom + "|" + v.showBottom;
            if (!lg.equals(camLog)) {
                if (camLog.isEmpty()) XposedBridge.log(TAG + "curved text around the camera at " + cam);
                camLog = lg;
            }
            if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
            v.invalidate();
        }

        // ---- Battery as a Li-ion cell, replacing the stock icon.

        int battCapMah = -2;      // -2 not read yet, -1 unknown
        float emaAmps = Float.NaN;

        int designCapacity() {
            if (battCapMah != -2) return battCapMah;
            battCapMah = -1;
            try {
                Class<?> pp = Class.forName("com.android.internal.os.PowerProfile");
                Object prof = pp.getConstructor(Context.class).newInstance(ctx);
                double mah = (Double) pp.getMethod("getBatteryCapacity").invoke(prof);
                if (mah > 500 && mah < 20000) battCapMah = (int) Math.round(mah);
            } catch (Throwable t) {
                logOnce("battcap", "battery design capacity not readable: " + t);
            }
            return battCapMah;
        }

        static String dur(double hours) {
            if (Double.isNaN(hours) || hours <= 0 || hours > 99) return "--";
            int m = (int) Math.round(hours * 60);
            return m < 60 ? m + "m" : (m / 60) + "h" + String.format(Locale.US, "%02d", m % 60);
        }

        final PercentOverlay pctOverlay = new PercentOverlay();
        final android.graphics.Paint battTint = new android.graphics.Paint();
        int battTintColor = 0;
        View pctHost = null;

        /** Largest ImageView inside the stock battery view: the icon itself. */
        View batteryIcon(View v) {
            View best = null;
            if (v instanceof ImageView && v.getWidth() > 0) best = v;
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = batteryIcon(g.getChildAt(i));
                    if (c != null && (best == null || c.getWidth() * c.getHeight() > best.getWidth() * best.getHeight())) best = c;
                }
            }
            return best;
        }

        /** Battery % written inside the stock icon (dark over the filled part, light over the empty part). */
        void updatePercentInside(View stock, int color, boolean on) {
            if (!on || stock.getVisibility() != View.VISIBLE) {
                if (pctHost != null) {
                    pctHost.getOverlay().remove(pctOverlay);
                    pctHost = null;
                }
                return;
            }
            View icon = batteryIcon(stock);
            if (icon == null || icon.getWidth() == 0) return;
            // keep the stock icon readable when auto contrast flips the colours
            if (contrastFixed) {
                battTint.setColorFilter(new android.graphics.PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
                if (icon.getLayerType() != View.LAYER_TYPE_HARDWARE || battTintColor != color) {
                    icon.setLayerType(View.LAYER_TYPE_HARDWARE, battTint);
                    battTintColor = color;
                }
            } else if (battTintColor != 0) {
                icon.setLayerType(View.LAYER_TYPE_NONE, null);
                battTintColor = 0;
            }
            // The % is drawn on the battery's container (on top of the icon, not tinted with it).
            if (pctHost != stock) {
                if (pctHost != null) pctHost.getOverlay().remove(pctOverlay);
                stock.getOverlay().add(pctOverlay);
                pctHost = stock;
                XposedBridge.log(TAG + "battery % inside the stock icon (" + icon.getClass().getSimpleName()
                        + " " + icon.getWidth() + "x" + icon.getHeight() + ")");
            }
            Intent bi = null;
            try {
                bi = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            } catch (Throwable ignored) { }
            if (bi == null) return;
            int lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int pct = lvl < 0 ? -1 : Math.round(lvl * 100f / Math.max(1, scale));
            // The drawable's own bounds inside the ImageView (it may be centred / scaled).
            android.graphics.Rect b = new android.graphics.Rect(0, 0, icon.getWidth(), icon.getHeight());
            if (icon instanceof ImageView && ((ImageView) icon).getDrawable() != null) {
                Drawable d = ((ImageView) icon).getDrawable();
                android.graphics.RectF rf = new android.graphics.RectF(d.getBounds());
                ((ImageView) icon).getImageMatrix().mapRect(rf);
                rf.offset(icon.getPaddingLeft(), icon.getPaddingTop());
                if (rf.width() > 2 && rf.height() > 2) b.set(Math.round(rf.left), Math.round(rf.top), Math.round(rf.right), Math.round(rf.bottom));
            }
            // icon rect -> container coordinates
            int ox = 0, oy = 0;
            for (View c = icon; c != null && c != stock; ) {
                ox += c.getLeft();
                oy += c.getTop();
                android.view.ViewParent pp = c.getParent();
                c = pp instanceof View ? (View) pp : null;
            }
            b.offset(ox, oy);
            pctOverlay.setBounds(b);
            pctOverlay.set(pct, color);
        }

        // ---- Row 2: our own icons instead of the stock notification + system icons.

        boolean stockOurs = true;
        final Map<String, Bitmap> traySnaps = new HashMap<>();

        /** Moves the stock icon areas into the hidden host (ours = true) or back onto row 2. */
        void placeStock(boolean ours) {
            if (ours == stockOurs) return;
            ViewGroup row2 = (ViewGroup) bar.findViewWithTag("dual_row2");
            ViewGroup host = (ViewGroup) bar.findViewWithTag("dual_hidden");
            View sysRow = bar.findViewWithTag("dual_sys"), spacer = bar.findViewWithTag("dual_spacer");
            if (row2 == null || host == null || sysRow == null || spacer == null) return;
            stockOurs = ours;
            if (notifArea != null) detach(notifArea);
            if (statusIcons != null) detach(statusIcons);
            if (ours) {
                if (notifArea != null) host.addView(notifArea, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
                if (statusIcons != null) host.addView(statusIcons, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
                sysRow.setVisibility(View.VISIBLE);
                spacer.setVisibility(View.VISIBLE);
            } else {
                int at = row2.indexOfChild(spacer);
                if (notifArea != null) row2.addView(notifArea, at, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                if (statusIcons != null) row2.addView(statusIcons, row2.indexOfChild(host), new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
                sysRow.setVisibility(View.GONE);
                spacer.setVisibility(View.GONE);
            }
            XposedBridge.log(TAG + (ours ? "row 2: own icons" : "row 2: stock icons"));
        }

        /** Stock slots row 2 draws itself (or row 1 shows): everything else goes into the tray. */
        boolean handledSlot(String slot) {
            String s = slot.toLowerCase(Locale.US);
            for (String k : new String[]{"wifi", "bluetooth", "zen", "dnd", "volume", "alarm", "mobile", "ims", "volte",
                    "vonr", "vowifi", "call_strength", "no_calling", "battery", "speakerphone", "mute"}) {
                if (s.contains(k)) return true;
            }
            return false;
        }

        void updateSys(int color) {
            boolean ours = Cfg.b(Cfg.SYS_ICONS, Cfg.D_SYS_ICONS);
            placeStock(ours);
            if (!ours) return;
            ViewGroup sysRow = (ViewGroup) bar.findViewWithTag("dual_sys");
            if (sysRow == null) return;
            Resources res = bar.getResources();
            sys.refreshQuick();
            Typeface base = clock instanceof TextView ? ((TextView) clock).getTypeface() : null;
            Typeface tf = base != null ? Typeface.create(base, Typeface.BOLD) : Typeface.DEFAULT_BOLD;
            float small = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, Cfg.i(Cfg.TEMP_SP, Cfg.D_TEMP_SP),
                    res.getDisplayMetrics());
            float big = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, Cfg.i(Cfg.WEATHER_SP, Cfg.D_WEATHER_SP) + 2,
                    res.getDisplayMetrics());

            // NetSwitch (Magisk module): poor signal / SIM 2 / 4G
            SysViews.NetSw ns = (SysViews.NetSw) sysRow.findViewWithTag("dual_netsw");
            boolean nsOn = System.currentTimeMillis() - SysState.nsAt < 150_000L;
            boolean nsCh = ns.set(color, tf, small, big) || ns.sim != SysState.nsSim || ns.rat != SysState.nsRat
                    || ns.poor != (SysState.nsPoor != 0) || ns.eco != (SysState.nsEco != 0) || ns.on != nsOn;
            ns.sim = SysState.nsSim;
            ns.rat = SysState.nsRat;
            ns.poor = SysState.nsPoor != 0;
            ns.eco = SysState.nsEco != 0;
            ns.on = nsOn;
            show(ns, ns.show(), nsCh, ns.want());

            // notifications: count (silent in brackets); without the listener, count the stock icons
            SysViews.Notif n = (SysViews.Notif) sysRow.findViewWithTag("dual_notif");
            int all = sys.notifAll, silent = sys.notifSilent;
            if (all < 0) {
                View icons = byId(bar, "notificationIcons");
                if (icons instanceof ViewGroup) {
                    all = 0;
                    ViewGroup g = (ViewGroup) icons;
                    for (int i = 0; i < g.getChildCount(); i++) if (g.getChildAt(i).getVisibility() == View.VISIBLE) all++;
                    silent = 0;
                }
            }
            boolean ch = n.set(color, tf, small, big) || n.all != all || n.silent != silent;
            if (all > n.all && n.all >= 0 && Cfg.b(Cfg.ANIM_BELL, Cfg.D_ANIM_BELL)) n.bump(n.all);
            if (SysState.testing() && SysState.animTestAt != lastTestBell) {
                lastTestBell = SysState.animTestAt;
                if (Cfg.b(Cfg.ANIM_BELL, Cfg.D_ANIM_BELL)) n.bump(Math.max(0, all - 1));
            }
            n.all = all;
            n.silent = silent;
            show(n, true, ch, n.want());

            SysViews.Bt bt = (SysViews.Bt) sysRow.findViewWithTag("dual_bt");
            List<SysState.Dev> devs = sys.btDevs;
            ch = bt.set(color, tf, small, big) || bt.on != sys.btOn || !sameDevs(bt.devs, devs);
            bt.on = sys.btOn;
            bt.devs = devs;
            show(bt, bt.on, ch, bt.want());

            SysViews.Ringer rg = (SysViews.Ringer) sysRow.findViewWithTag("dual_ringer");
            int mode = sys.dnd ? 3 : sys.ringer == android.media.AudioManager.RINGER_MODE_VIBRATE ? 1
                    : sys.ringer == android.media.AudioManager.RINGER_MODE_SILENT ? 2 : 0;
            ch = rg.set(color, tf, small, big) || rg.mode != mode;
            rg.mode = mode;
            show(rg, mode != 0, ch, rg.want());

            SysViews.Alarm al = (SysViews.Alarm) sysRow.findViewWithTag("dual_alarm");
            ch = al.set(color, tf, small, big) || al.at != sys.nextAlarm;
            al.at = sys.nextAlarm;
            show(al, al.at > 0, ch, al.want());

            SysViews.Wifi wf = (SysViews.Wifi) sysRow.findViewWithTag("dual_wifi");
            ch = wf.set(color, tf, small, big) || wf.on != sys.wifi || wf.rssi != sys.wifiRssi || wf.level != sys.wifiLevel
                    || wf.freq != sys.wifiFreq || wf.std != sys.wifiStd || wf.mbps != sys.wifiMbps
                    || wf.down != sys.wifiDown || wf.up != sys.wifiUp;
            wf.on = sys.wifi;
            wf.rssi = sys.wifiRssi;
            wf.level = sys.wifiLevel;
            wf.freq = sys.wifiFreq;
            wf.std = sys.wifiStd;
            wf.mbps = sys.wifiMbps;
            wf.down = sys.wifiDown;
            wf.up = sys.wifiUp;
            show(wf, wf.on, ch, wf.want());

            // tray: the other stock icons that are on, small, in the bar's dim colour
            SysViews.Tray tray = (SysViews.Tray) sysRow.findViewWithTag("dual_tray");
            tray.set(color, tf, small, big);
            tray.icons.clear();
            tray.extras.clear();
            if (statusIcons instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) statusIcons;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    String slot;
                    try {
                        slot = String.valueOf(XposedHelpers.callMethod(c, "getSlot"));
                    } catch (Throwable t) {
                        continue;
                    }
                    if (handledSlot(slot)) continue;
                    if (c.getVisibility() != View.VISIBLE || c.getWidth() == 0 || c.getHeight() == 0 || !iconShown(c)) continue;
                    Bitmap b = traySnaps.get(slot);
                    if (b == null || b.getWidth() != c.getWidth() || b.getHeight() != c.getHeight()) {
                        b = Bitmap.createBitmap(c.getWidth(), c.getHeight(), Bitmap.Config.ARGB_8888);
                        traySnaps.put(slot, b);
                    }
                    b.eraseColor(0);
                    try {
                        c.draw(new Canvas(b));
                    } catch (Throwable t) {
                        continue;
                    }
                    tray.icons.add(b);
                    tray.extras.add(slot.toLowerCase(Locale.US).contains("hotspot") && sys.hotspotClients > 0
                            ? String.valueOf(sys.hotspotClients) : "");
                    logOnce("tray:" + slot, "tray icon: " + slot);
                }
            }
            // room: row 2 minus the weather on the right; the tray gets what is left
            ViewGroup row2 = (ViewGroup) bar.findViewWithTag("dual_row2");
            View wb = bar.findViewWithTag("dual_weather");
            if (row2 != null && row2.getWidth() > 0) {
                int room = row2.getWidth() - row2.getPaddingLeft() - row2.getPaddingRight()
                        - (wb != null && wb.getVisibility() == View.VISIBLE ? wb.getWidth() + dp(res, 6) : 0);
                if (sysRow instanceof SysViews.CapRow && ((SysViews.CapRow) sysRow).maxW != room) {
                    ((SysViews.CapRow) sysRow).maxW = room;
                    sysRow.requestLayout();
                }
                int used = 0;
                for (int i = 0; i < sysRow.getChildCount(); i++) {
                    View c = sysRow.getChildAt(i);
                    if (c == tray || c.getVisibility() != View.VISIBLE) continue;
                    used += c.getMeasuredWidth() + (c.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                            ? ((ViewGroup.MarginLayoutParams) c.getLayoutParams()).getMarginStart() : 0);
                }
                if (sysRow instanceof SysViews.CapRow) used += ((SysViews.CapRow) sysRow).cutShift;
                tray.maxW = Math.max(0, room - used - dp(res, 7));
                // ...and never so wide that it runs under the camera (it would be pushed past it).
                if (sysRow instanceof SysViews.CapRow)
                    tray.maxW = Math.min(tray.maxW, ((SysViews.CapRow) sysRow).roomBefore(tray));
            }
            tray.requestLayout();
            tray.invalidate();
            if (tray.getVisibility() != (tray.icons.isEmpty() ? View.GONE : View.VISIBLE))
                tray.setVisibility(tray.icons.isEmpty() ? View.GONE : View.VISIBLE);
            sysRow.setAlpha(hiddenForLock ? 0f : 1f);
        }

        static boolean sameDevs(List<SysState.Dev> a, List<SysState.Dev> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) {
                SysState.Dev x = a.get(i), y = b.get(i);
                if (x.kind != y.kind || x.battery != y.battery || !x.codec.equals(y.codec)) return false;
            }
            return true;
        }

        /** Visible or gone; re-measure when its content or width changed. */
        void show(SysViews.Base v, boolean on, boolean changed, int wantW) {
            int vis = on && wantW > 0 ? View.VISIBLE : View.GONE;
            if (v.getVisibility() != vis) v.setVisibility(vis);
            if (vis == View.VISIBLE && (changed || v.getMeasuredWidth() != wantW)) v.relayout(wantW);
        }

        /** Portrait battery with the % inside, replacing the stock icon. */
        void portraitBattery(View stock, int color) {
            updatePercentInside(stock, color, false);
            View cell = bar.findViewWithTag("dual_batt");
            if (cell != null && cell.getVisibility() != View.GONE) cell.setVisibility(View.GONE);
            ViewGroup p = (ViewGroup) stock.getParent();
            SysViews.Battery v = (SysViews.Battery) bar.findViewWithTag("dual_pbatt");
            if (v == null) {
                v = new SysViews.Battery(ctx);
                v.setTag("dual_pbatt");
                ViewGroup.LayoutParams lp;
                if (p instanceof LinearLayout) {
                    LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
                    llp.gravity = Gravity.CENTER_VERTICAL;
                    llp.setMarginStart(dp(bar.getResources(), 2));
                    lp = llp;
                } else {
                    lp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
                }
                p.addView(v, p.indexOfChild(stock) + 1, lp);
                XposedBridge.log(TAG + "portrait battery added");
            }
            if (stock.getVisibility() != View.GONE) stock.setVisibility(View.GONE);
            Intent bi = null;
            try {
                bi = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            } catch (Throwable ignored) { }
            if (bi == null) return;
            int lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int pct = lvl < 0 ? -1 : Math.round(lvl * 100f / Math.max(1, scale));
            int status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean chg = status == BatteryManager.BATTERY_STATUS_CHARGING;
            Typeface base = clock instanceof TextView ? ((TextView) clock).getTypeface() : null;
            Typeface tf = base != null ? Typeface.create(base, Typeface.BOLD) : Typeface.DEFAULT_BOLD;
            float small = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, Cfg.i(Cfg.TEMP_SP, Cfg.D_TEMP_SP),
                    bar.getResources().getDisplayMetrics());
            boolean ch = v.set(color, tf, small, small) || v.level != pct || v.charging != chg;
            v.level = pct;
            v.charging = chg;
            if (ch) {
                v.requestLayout();
                v.invalidate();
            }
            if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
        }

        void updateBattery(int color) {
            View stock = byId(bar, "battery");
            if (stock == null || !(stock.getParent() instanceof ViewGroup)) {
                logOnce("nobatt", "stock battery view not found");
                return;
            }
            if (Cfg.b(Cfg.BATT_PORTRAIT, Cfg.D_BATT_PORTRAIT)) {
                portraitBattery(stock, color);
                return;
            }
            View pb = bar.findViewWithTag("dual_pbatt");
            if (pb != null && pb.getVisibility() != View.GONE) pb.setVisibility(View.GONE);
            boolean on = Cfg.b(Cfg.BATT_CELL, Cfg.D_BATT_CELL);
            updatePercentInside(stock, color, !on && Cfg.b(Cfg.BATT_PCT_IN, Cfg.D_BATT_PCT_IN));
            ViewGroup p = (ViewGroup) stock.getParent();
            BatteryCellView v = (BatteryCellView) bar.findViewWithTag("dual_batt");
            if (v == null) {
                v = new BatteryCellView(ctx);
                v.setTag("dual_batt");
                ViewGroup.LayoutParams lp;
                if (p instanceof LinearLayout) {
                    LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
                    llp.gravity = Gravity.CENTER_VERTICAL;
                    llp.setMarginStart(dp(bar.getResources(), 2));
                    lp = llp;
                } else {
                    lp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
                }
                p.addView(v, p.indexOfChild(stock) + 1, lp);
                XposedBridge.log(TAG + "battery cell added");
            }
            if (!on) {
                if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
                if (stock.getVisibility() != View.VISIBLE) stock.setVisibility(View.VISIBLE);
                return;
            }
            if (stock.getVisibility() != View.GONE) stock.setVisibility(View.GONE);

            Intent bi = null;
            try {
                bi = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            } catch (Throwable ignored) { }
            if (bi == null) return;
            int lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int pct = lvl < 0 ? -1 : Math.round(lvl * 100f / Math.max(1, scale));
            int status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean chg = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                    && bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            int mv = bi.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            BatteryManager bm = ctx.getSystemService(BatteryManager.class);
            long cc = -1, ttf = -1;
            try {
                cc = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);  // µAh
            } catch (Throwable ignored) { }
            try {
                ttf = bm.computeChargeTimeRemaining();
            } catch (Throwable ignored) { }
            // Normally µAh; some kernels report mAh (then the value is small).
            double mahLeft = cc > 0 && cc != Long.MIN_VALUE ? (cc < 30_000 ? cc : cc / 1000.0) : Double.NaN;
            int cap = designCapacity();
            if (Double.isNaN(mahLeft) && cap > 0 && pct >= 0) mahLeft = cap * pct / 100.0;

            // line 1: voltage + charge left / total
            StringBuilder l1 = new StringBuilder();
            if (mv > 0) l1.append(String.format(Locale.US, "%.2fV", mv / (mv > 100 ? 1000f : 1f)));
            if (!Double.isNaN(mahLeft)) {
                if (l1.length() > 0) l1.append(' ');
                l1.append(String.format(Locale.US, "%.1f", mahLeft / 1000.0));
                if (cap > 0) l1.append('/').append(String.format(Locale.US, "%.1f", cap / 1000.0));
                l1.append("Ah");
            }
            // line 2: time to empty / full
            float a = amps;
            if (!Float.isNaN(a)) emaAmps = Float.isNaN(emaAmps) ? Math.abs(a) : emaAmps * 0.8f + Math.abs(a) * 0.2f;
            String l2;
            if (chg) {
                l2 = "\u03DF " + (ttf > 0 ? dur(ttf / 3600_000.0) : status == BatteryManager.BATTERY_STATUS_FULL ? "full" : "--");
            } else {
                double h = !Double.isNaN(mahLeft) && !Float.isNaN(emaAmps) && emaAmps > 0.02f
                        ? mahLeft / (emaAmps * 1000.0) : Double.NaN;
                l2 = "\u2193 " + dur(h);
            }

            float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, Cfg.i(Cfg.TEMP_SP, Cfg.D_TEMP_SP),
                    bar.getResources().getDisplayMetrics());
            boolean text = Cfg.b(Cfg.BATT_TEXT, Cfg.D_BATT_TEXT);
            // The details give way to the tower block: shown only if both fit right of the camera.
            if (text) {
                View anchor = rightAnchor();
                int rl = rightLimit();
                if (anchor != null && anchor.getVisibility() == View.VISIBLE && anchor.getWidth() > 0
                        && rl != Integer.MIN_VALUE && v.getWidth() > 0) {
                    int[] pos = new int[2];
                    anchor.getLocationOnScreen(pos);
                    View pw = bar.findViewWithTag("dual_power");
                    int pwW = pw != null && pw.getVisibility() == View.VISIBLE && pw.getParent() == anchor.getParent()
                            ? pw.getWidth() + dp(bar.getResources(), 3) : 0;
                    int detailsW = (int) Math.ceil(v.detailsWidth());
                    int free = pos[0] - pwW - rl - dp(bar.getResources(), 3)
                            + (battTextShown ? detailsW : 0) - (Cfg.b(Cfg.SHOW_CELLS, Cfg.D_SHOW_CELLS) ? cellsWantPx : 0);
                    boolean fits = free >= detailsW + (battTextShown ? 0 : dp(bar.getResources(), 6));
                    if (fits != battTextShown) {
                        battTextShown = fits;
                        XposedBridge.log(TAG + "battery details " + (fits ? "shown" : "hidden to leave room for the tower block")
                                + " (free " + free + "px, details " + detailsW + "px)");
                    }
                    text = fits;
                }
            }
            boolean relayout = v.textPx != px || v.showText != text || !v.line1.equals(l1.toString())
                    || !v.line2.equals(l2) || v.level != pct;
            v.level = pct;
            v.charging = chg;
            v.line1 = l1.toString();
            v.line2 = l2;
            v.color = color;
            v.textPx = px;
            v.showText = text;
            v.tf = clock instanceof TextView ? ((TextView) clock).getTypeface() : null;
            if (relayout) v.requestLayout();
            v.invalidate();
            if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
        }

        // ---- Readable layout: one view draws four lines of large text from the same data;
        // the old row 1 / row 2 keep running (System UI and the other updates need them) but are not drawn.

        boolean readableLogged = false;

        /** Transparent without touching the view's own alpha / visibility (System UI animates those). */
        void setHidden(View v, boolean hide) {
            if (v == null) return;
            try {
                float want = hide ? 0f : 1f;
                Object cur = XposedHelpers.callMethod(v, "getTransitionAlpha");
                if (cur instanceof Float && (Float) cur == want) return;
                XposedHelpers.callMethod(v, "setTransitionAlpha", want);
            } catch (Throwable ignored) { }
        }

        void updateReadable(int color) {
            boolean pro = Cfg.b(Cfg.PRO, Cfg.D_PRO);
            boolean on = pro || Cfg.b(Cfg.READABLE, Cfg.D_READABLE);
            ReadableBar rb = (ReadableBar) bar.findViewWithTag("dual_readable");
            View contents = byId(bar, "status_bar_contents");
            View row2 = bar.findViewWithTag("dual_row2");
            if (!on) {
                sReadableOn = false;
                if (rb != null && rb.getVisibility() != View.GONE) rb.setVisibility(View.GONE);
                setHidden(contents, false);
                setHidden(row2, false);
                return;
            }
            if (rb == null) {
                rb = new ReadableBar(ctx);
                rb.setTag("dual_readable");
                bar.addView(rb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
            if (!readableLogged) {
                readableLogged = true;
                XposedBridge.log(TAG + "readable layout on; contents=" + (contents == null ? "null" : contents.getClass().getName())
                        + " drawHook=" + sDrawHookOk);
            }
            // Cover the whole bar, side padding included.
            if (rb.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) rb.getLayoutParams();
                int ps = bar.getPaddingStart(), pe = bar.getPaddingEnd(), pt = bar.getPaddingTop(), pb = bar.getPaddingBottom();
                if (lp.getMarginStart() != -ps || lp.getMarginEnd() != -pe || lp.topMargin != -pt || lp.bottomMargin != -pb) {
                    lp.setMarginStart(-ps);
                    lp.setMarginEnd(-pe);
                    lp.topMargin = -pt;
                    lp.bottomMargin = -pb;
                    rb.setLayoutParams(lp);
                }
            }
            if (bar.indexOfChild(rb) != bar.getChildCount() - 1) rb.bringToFront();
            if (rb.getVisibility() != View.VISIBLE) rb.setVisibility(View.VISIBLE);
            sHideA = contents;
            sHideB = row2;
            sReadableOn = true;
            setHidden(contents, true);
            setHidden(row2, true);
            rb.setAlpha(hiddenForLock && !Cfg.b(Cfg.LOCK_BAR, Cfg.D_LOCK_BAR) ? 0f : 1f);

            // ---- fill the model
            boolean ours = Cfg.b(Cfg.SYS_ICONS, Cfg.D_SYS_ICONS);
            if (!ours) sys.refreshQuick();
            rb.pro = pro;
            rb.chips = false;   // the chip look and the gauge-line switch were removed from Settings: gauge-line look always
            rb.look2 = true;
            rb.fold = Cfg.i(Cfg.LAYOUT, Cfg.D_LAYOUT) >= 1;
            rb.stretch = Cfg.i(Cfg.LAYOUT, Cfg.D_LAYOUT) >= 2;
            rb.tower = Cfg.i(Cfg.LAYOUT, Cfg.D_LAYOUT) == 3;
            rb.simUp = Cfg.b(Cfg.SIM_UP, Cfg.D_SIM_UP);
            if (pro) {
                rb.soh = batSoh;
                rb.cycles = batCycles;
                if (!Cfg.b(Cfg.STATS_COL, Cfg.D_STATS_COL)) StatsCol.refresh(ctx, darkBar(color));
                rb.cpuLoad = StatsCol.cpuPct();
                rb.sats = StatsCol.sats();
                rb.wake = StatsCol.wake();
                rb.ping = SysState.pingMs;
                long upMs = android.os.SystemClock.uptimeMillis(), elMs = Math.max(1, android.os.SystemClock.elapsedRealtime());
                rb.deep = (int) Math.round(100.0 * (1.0 - (double) upMs / elMs));
                Vpn.update(ctx);
                rb.vpn = Vpn.active;
                // camera hole in the readable view's own pixels
                try {
                    android.view.WindowInsets wi = bar.getRootWindowInsets();
                    android.view.DisplayCutout dc = wi == null ? null : wi.getDisplayCutout();
                    float cx = Float.NaN, cy = Float.NaN, cr = Float.NaN;
                    if (dc != null && rb.getWidth() > 0) {
                        int[] b = new int[2];
                        rb.getLocationOnScreen(b);
                        for (android.graphics.Rect r : dc.getBoundingRects()) {
                            if (r.top > b[1] + rb.getHeight() || r.width() <= 0) continue;
                            cx = r.exactCenterX() - b[0];
                            cy = r.exactCenterY() - b[1];
                            cr = Math.min(r.width(), r.height()) / 2f;
                            break;
                        }
                    }
                    rb.camX = cx;
                    rb.camY = cy;
                    rb.camR = cr;
                } catch (Throwable ignored) { }
            }
            rb.color = color;
            rb.base = clock instanceof TextView ? ((TextView) clock).getTypeface() : null;
            rb.cpu = cpuTemp;
            rb.bat = batTemp;
            rb.amps = amps;
            rb.watts = watts;
            rb.powerColor = Float.isNaN(amps) ? color : powerColor(color);
            try {   // no fresh fix: the last real one beats the built-in default
                if (TowerDb.myLocation(ctx) == null) { double[] k = TowerDb.lastKnown(ctx); if (k != null) lastLatLon = new double[]{k[0], k[1]}; }
            } catch (Throwable ignored) { }
            rb.latLon = lastLatLon;
            rb.gpu = gpuTemp;
            rb.ghz = cpuGhz;
            rb.ramFree = ramFree;
            rb.ramTotal = ramTotal;
            try { rb.hz = bar.getDisplay() == null ? -1 : Math.round(bar.getDisplay().getRefreshRate()); } catch (Throwable t) { rb.hz = -1; }
            rb.fix = lastFix;
            ElevTrack.start(ctx);
            try { if (lastFix != null && lastFix.length > 2) ElevTrack.gps(lastFix[2]); } catch (Throwable ignored) { }
            rb.climbUp = ElevTrack.up;
            rb.climbDown = ElevTrack.down;
            rb.climbOn = ElevTrack.active;
            rb.groundElev = wxElev;
            rb.quakeMag = quakeMag;
            rb.quakeKm = quakeKm;
            rb.quakeAt = quakeAt;
            rb.quakeCc = quakeCc;
            rb.used4g = NrFallback.used4g;
            rb.used5g = NrFallback.used5g;
            rb.m4g = NrFallback.m4g;
            rb.m5g = NrFallback.m5g;
            rb.limit4g = NrFallback.limitBytes();
            rb.nrShare = NrFallback.nrShare();
            String satF = SkyStrip.frame(Sat.text, Sat.live, System.currentTimeMillis());
            boolean satX = satF != null && !satF.equals(Sat.text);     // one of the extra frames: plain grey text
            rb.satText = satF;
            rb.satLive = !satX && Sat.live;
            rb.satIssS = satX ? "" : Sat.issText;
            rb.satNameS = satX ? "" : Sat.satName;
            rb.satCityS = satX ? "" : Sat.satCity;
            rb.sampleSpeed();

            int all = sys.notifAll, silent = sys.notifSilent;
            if (all < 0) {
                View icons = byId(bar, "notificationIcons");
                if (icons instanceof ViewGroup) {
                    all = 0;
                    ViewGroup g = (ViewGroup) icons;
                    for (int i = 0; i < g.getChildCount(); i++) if (g.getChildAt(i).getVisibility() == View.VISIBLE) all++;
                    silent = 0;
                }
            }
            rb.notifAll = all;
            rb.notifSilent = silent;
            rb.btOn = sys.btOn;
            rb.devs = sys.btDevs;
            rb.ringer = sys.dnd ? 3 : sys.ringer == android.media.AudioManager.RINGER_MODE_VIBRATE ? 1
                    : sys.ringer == android.media.AudioManager.RINGER_MODE_SILENT ? 2 : 0;
            rb.alarmAt = sys.nextAlarm;
            rb.wifiOn = sys.wifi;
            rb.wifiLevel = sys.wifiLevel;
            rb.wifiRssi = sys.wifiRssi;
            rb.wifiFreq = sys.wifiFreq;
            List<Bitmap> tr = new ArrayList<>();
            View tv = bar.findViewWithTag("dual_tray");
            if (tv instanceof SysViews.Tray) tr.addAll(((SysViews.Tray) tv).icons);
            rb.tray = tr;

            int dataSlot = DataActivity.slot();
            List<ReadableBar.SimInfo> out = new ArrayList<>();
            for (Bands.Sim s : sims) {
                ReadableBar.SimInfo si = new ReadableBar.SimInfo();
                si.slot = s.slot;
                si.rat = s.net;
                si.inService = s.inService;
                si.dbm = s.dbm;
                Object[] ob = SignalTowerView.operatorBadge(s.operator, s.mccMnc);
                if (ob != null) {
                    si.opLetter = (String) ob[0];
                    si.opColor = (Integer) ob[1];
                }
                si.band = s.caBands != null ? s.caBands : s.band;
                si.dataToday = s.dataToday;
                si.pci = s.pci;
                si.arfcn = s.arfcn;
                si.tac = s.tac;
                si.site = s.site;
                si.bw = s.bwMhz;
                si.nbr = s.nbr;
                si.nbrDbm = s.nbrDbm;
                Integer th = TowerElev.DELTA.get(s.slot);
                si.towerH = th == null ? null : (th >= 0 ? "+" : "\u2212") + Math.abs(th) + " m";
                Integer lr = TowerElev.LOS.get(s.slot);
                si.los = lr == null ? -1 : lr;
                String d = Cfg.b(Cfg.SHOW_DIST, Cfg.D_SHOW_DIST) ? distString(s) : "?";
                si.dist = "?".equals(d) ? null : d;
                si.voice = Voice.label(s.sub, s.band);
                si.data = dataSlot == s.slot;
                si.dataOn = !si.data || s.dataOn;   // network type shows on every SIM; hidden only on the data SIM while mobile data is off
                si.inCall = s.inCall;
                // circle check: on another circle's network counts as roaming even when the operator doesn't say so
                String rc = Circles.roamCircle(s.simNumeric, s.netNumeric);
                si.roaming = s.roaming || rc != null;
                String[] nc = Circles.of(s.netNumeric);
                si.circle = rc != null ? rc : s.roaming && nc != null ? nc[1] : null;
                // the network hides it (same code everywhere, no flag): your position says you're in another circle
                if (!si.roaming) {
                    String[] sc = Circles.of(s.simNumeric);
                    String gc = sc == null ? null : GeoCircle.away(sc[1]);
                    if (gc != null) { si.roaming = true; si.circle = gc; }
                }
                si.netName = null;      // names go system-wide (carrier name); under the box: tower area only
                si.nrMode = s.nrMode;
                // fallback on: "4G·0.8G" (4G data used today) instead of the 5G mode
                if (si.data && NrFallback.active) si.nrMode = "4G";
                si.cqi = s.cqi == CellDetail.NONE ? -1 : s.cqi;
                si.rsrq = s.rsrq == CellDetail.NONE ? Integer.MIN_VALUE : s.rsrq;
                si.cells = s.cells;
                // Your own location first (2026-11-14: the per-tower lookup could land a SIM's area name
                // in a neighbouring locality - Pimpri-Chinchwad or Wakad instead of Ravet - since the tower
                // itself can sit a km or more away, across a locality boundary. Your own GPS fix doesn't drift
                // like that, so both SIMs now show the one place you're actually in; falls back to the old
                // per-tower name only while your own fix has nothing yet.
                String ar = !TowerArea.PLACE.isEmpty() ? TowerArea.PLACE : TowerArea.BY_SLOT.get(s.slot);
                si.area = ar == null || ar.isEmpty() ? null : ar;
                si.bearing = TowerArea.BEARING.get(s.slot);
                si.hoToday = ReadableBar.handoversFor(s.slot);
                try { cellTimeAndTrend(si, s); } catch (Throwable ignored) { }
                si.snr = s.snr;
                si.eco = si.data && SysState.nsEco != 0 && System.currentTimeMillis() - SysState.nsAt < 150_000L;
                out.add(si);
            }
            rb.sims = out;
            rb.dataIn = DataActivity.in();
            rb.dataOut = DataActivity.out();

            long nowT = System.currentTimeMillis();
            rb.wxTemp = wapiTemp != null && nowT - wapiTempAt < 60 * 60_000L ? wapiTemp
                    : weatherTemp != null ? weatherTemp
                    : omTemp != null && nowT - omTempAt < 90 * 60_000L ? omTemp : null;
            rb.wxIcon = weatherIcon;
            rb.wxCode = wxIconCode;
            rb.wxTint = tintWeatherIcon;
            rb.wxMax = wxMax;
            rb.wxMin = wxMin;
            rb.wxFeels = wxFeels;
            rb.wxWind = wxWind;
            rb.wxUv = wxUv;
            rb.wxVis = wxVis;
            rb.wxPres = wxPres;
            rb.wxPresTrend = wxPresTrend;
            rb.presSeries = presSeries;
            rb.uvSeries = uvSeries;
            rb.wxCloud = wxCloud;
            rb.wxHum = wxHum;
            rb.wxRain = wxRain;
            rb.wxAqi = wxAqi;
            rb.wxArrow = wxWindDir >= 0 ? windArrow(wxWindDir) : "";

            Intent bi = null;
            try {
                bi = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            } catch (Throwable ignored) { }
            if (bi != null) {
                int lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                rb.battPct = lvl < 0 ? -1 : Math.round(lvl * 100f / Math.max(1, scale));
                rb.charging = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING;
            }
            rb.invalidate();
        }

        // ---- Clock: seconds + timezone beside the digits, seconds line under them.

        final SecondsLineDrawable secLine = new SecondsLineDrawable();
        boolean secLineAdded = false;

        /** Runs on each whole second (only while the bar is shown), so the seconds never skip. */
        final Runnable secTick = new Runnable() {
            @Override
            public void run() {
                if (dead()) return;
                if (!ScreenState.on()) { main.postDelayed(this, 2000); return; }
                try {
                    updateClockInfo();
                    updateClockExtras();
                } catch (Throwable t) {
                    logOnce("clockx", "clock extras failed: " + t);
                }
                long now = System.currentTimeMillis();
                main.postDelayed(this, 1000 - now % 1000 + 5);
            }
        };

        final ClockInfoDrawable clockInfo = new ClockInfoDrawable();
        boolean clockInfoAdded = false;
        float clockShift = 0;

        /**
         * Smaller clock moved up, with one small line under it that takes turns every 5 s:
         * date ("Sat 17 Oct") and sunrise -> sunset.
         */
        void updateClockInfo() {
            if (!(clock instanceof TextView)) return;
            TextView ck = (TextView) clock;
            Resources res = bar.getResources();
            boolean on = Cfg.b(Cfg.CLOCK_SUN, Cfg.D_CLOCK_SUN);
            View side = bar.findViewWithTag("dual_clock_side");
            if (!on) {
                if (clockInfoAdded) {
                    clock.getOverlay().remove(clockInfo);
                    clockInfoAdded = false;
                }
                if (ck.getTranslationY() != 0) ck.setTranslationY(0);
                if (side != null && side.getTranslationY() != 0) side.setTranslationY(0);
                sClockInfoPx = 0;
                return;
            }
            Hook.setSp(ck, Cfg.i(Cfg.CLOCK_SP, Cfg.D_CLOCK_SP));
            int color = ck.getCurrentTextColor();
            boolean dk = darkBar(color);
            android.text.Layout lay = ck.getLayout();
            if (lay == null || lay.getLineCount() == 0) return;

            float infoPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, Cfg.i(Cfg.TEMP_SP, Cfg.D_TEMP_SP),
                    res.getDisplayMetrics());
            clockInfo.p.setTextSize(infoPx);
            if (ck.getTypeface() != null) clockInfo.p.setTypeface(Typeface.create(ck.getTypeface(), Typeface.BOLD));
            boolean sunTurn = true;
            String txt;
            int col;
            if (sunTurn) {
                try {
                    android.location.Location l = TowerDb.myLocation(ctx);
                    if (l != null) lastLatLon = new double[]{l.getLatitude(), l.getLongitude()};
                    else { double[] k = TowerDb.lastKnown(ctx); if (k != null) lastLatLon = new double[]{k[0], k[1]}; }
                } catch (Throwable ignored) { }
                double[] sun = SunTimes.today(lastLatLon[0], lastLatLon[1]);
                java.util.Calendar nc = java.util.Calendar.getInstance();
                double nowM = nc.get(java.util.Calendar.HOUR_OF_DAY) * 60 + nc.get(java.util.Calendar.MINUTE);
                if (!Double.isNaN(sun[0]) && !Double.isNaN(sun[1]) && nowM >= sun[0] && nowM < sun[1]) {
                    txt = "\u2600\u2193" + SunTimes.hhmm(sun[1]);          // next: sunset
                    col = dk ? 0xFFFF7A45 : 0xFFC4451A;
                } else {
                    txt = "\u2600\u2191" + SunTimes.hhmm(sun[0]);          // next: sunrise
                    col = dk ? 0xFFFF9F0A : 0xFFC46A00;
                }
            } else {
                txt = new java.text.SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(new java.util.Date());
                col = color;
            }
            // Wide enough for the longer of the two, so nothing shifts when they swap.
            String sunSample = "\u2600\u219100:00";
            String dateSample = new java.text.SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(new java.util.Date());
            // The sun line is sized to the time's own width, so the seconds / zone sit right beside the clock.
            float timeW = lay.getLineWidth(0);
            float sunW = clockInfo.p.measureText(sunSample);
            if (timeW > 0 && sunW > timeW * 1.15f) {
                clockInfo.p.setTextSize(Math.max(infoPx * 0.7f, infoPx * timeW * 1.15f / sunW));
            }
            int infoW = (int) Math.ceil(Math.max(timeW, clockInfo.p.measureText(sunSample)));
            int want = infoW + ck.getTotalPaddingLeft() + ck.getTotalPaddingRight() + 1;
            if (ck.getMinWidth() != want) ck.setMinWidth(want);

            int left = ck.getTotalPaddingLeft() + (int) lay.getLineLeft(0);
            int base = ck.getTotalPaddingTop() + lay.getLineBaseline(0);
            Paint.FontMetrics fm = clockInfo.p.getFontMetrics();
            float gap = dp(res, 3) + Math.max(2, dp(res, 1));     // room for the seconds line
            float infoBase = base + gap - fm.ascent;
            float infoH = gap - fm.ascent + fm.descent;
            clockInfo.text = txt;
            clockInfo.color = col;
            clockInfo.baseline = infoBase;
            clockInfo.setBounds(left, 0, ck.getWidth(), ck.getHeight());
            if (!clockInfoAdded) {
                clock.getOverlay().add(clockInfo);
                clockInfoAdded = true;
                XposedBridge.log(TAG + "clock moved up with a date / sun line under it");
            }
            clockInfo.invalidateSelf();
            // Move the clock (and the seconds beside it) up so time + line are centred together.
            float shift = -infoH / 2f;
            try {
                // never push the time (or the seconds beside it) above the top of the bar
                int[] ap = new int[2], bp = new int[2];
                bar.getLocationOnScreen(bp);
                ck.getLocationOnScreen(ap);
                float minTop = dp(res, 1);
                Paint.FontMetrics cfm = ck.getPaint().getFontMetrics();
                float glyphTop = ap[1] - bp[1] - ck.getTranslationY() + ck.getTotalPaddingTop()
                        + lay.getLineBaseline(0) + cfm.ascent * 0.92f;
                float need = minTop - (glyphTop + shift);
                if (side != null && side.getVisibility() == View.VISIBLE && side.getWidth() > 0) {   // a hidden view reports (0,0)
                    int[] sp = new int[2];
                    side.getLocationOnScreen(sp);
                    float sideTop = sp[1] - bp[1] - side.getTranslationY();
                    need = Math.max(need, minTop - (sideTop + shift));
                }
                if (need > 0) shift += need;
            } catch (Throwable ignored) { }
            if (Math.abs(ck.getTranslationY() - shift) > 0.5f) ck.setTranslationY(shift);
            if (side != null && Math.abs(side.getTranslationY() - shift) > 0.5f) side.setTranslationY(shift);
            sClockInfoPx = Math.round(infoH / 2f);
        }

        static String zoneShort() {
            java.util.TimeZone tz = java.util.TimeZone.getDefault();
            boolean dst = tz.inDaylightTime(new java.util.Date());
            String n = tz.getDisplayName(dst, java.util.TimeZone.SHORT, Locale.getDefault());
            if (n == null || n.startsWith("GMT") || n.startsWith("UTC")) {
                if ("Asia/Kolkata".equals(tz.getID()) || "Asia/Calcutta".equals(tz.getID())) return "IST";
                n = tz.getDisplayName(dst, java.util.TimeZone.SHORT, Locale.US);
            }
            return n != null && !n.startsWith("GMT") ? n : utcOffset();
        }

        static String utcOffset() {
            int off = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000;
            String sign = off < 0 ? "-" : "+";
            off = Math.abs(off);
            return "UTC" + sign + (off / 60) + (off % 60 != 0 ? String.format(Locale.US, ":%02d", off % 60) : "");
        }

        void updateClockExtras() {
            if (!(clock instanceof TextView) || !(clock.getParent() instanceof ViewGroup)) return;
            TextView ck = (TextView) clock;
            boolean on = Cfg.b(Cfg.CLOCK_SECONDS, Cfg.D_CLOCK_SECONDS);
            int color = ck.getCurrentTextColor();
            boolean dk = darkBar(color);
            int accent = dk ? 0xFF64D2FF : 0xFF0086A8;
            ViewGroup p = (ViewGroup) clock.getParent();
            TextView side = (TextView) bar.findViewWithTag("dual_clock_side");
            if (side == null) {
                side = new TextView(ctx);
                side.setTag("dual_clock_side");
                side.setIncludeFontPadding(false);
                side.setLineSpacing(0, 0.9f);
                side.setMaxLines(2);
                side.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.CENTER_VERTICAL;
                lp.setMarginStart(dp(bar.getResources(), 1));
                p.addView(side, p.indexOfChild(clock) + 1, p instanceof LinearLayout ? lp
                        : new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (!on) {
                if (side.getVisibility() != View.GONE) side.setVisibility(View.GONE);
                if (secLineAdded) {
                    clock.getOverlay().remove(secLine);
                    secLineAdded = false;
                }
                return;
            }
            java.util.Calendar cal = java.util.Calendar.getInstance();
            int sec = cal.get(java.util.Calendar.SECOND);
            // seconds (accent, top) over the timezone (grey, bottom)
            Hook.setSp(side, Cfg.i(Cfg.TEMP_SP, Cfg.D_TEMP_SP));
            Hook.matchClockFont(clock, side, true);
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            sb.append(String.format(Locale.US, "%02d", sec));
            sb.setSpan(new android.text.style.ForegroundColorSpan(accent), 0, 2, 0);
            sb.append('\n');
            int st = sb.length();
            sb.append(zoneShort());
            sb.setSpan(new android.text.style.ForegroundColorSpan((color & 0x00FFFFFF) | 0x99000000), st, sb.length(), 0);
            sb.setSpan(new android.text.style.RelativeSizeSpan(0.85f), st, sb.length(), 0);
            side.setText(sb);
            side.setAlpha(clock.getAlpha());
            if (side.getVisibility() != View.VISIBLE) side.setVisibility(View.VISIBLE);

            // seconds line under the digits
            android.text.Layout lay = ck.getLayout();
            if (lay != null && lay.getLineCount() > 0) {
                int left = ck.getTotalPaddingLeft() + (int) lay.getLineLeft(0);
                int right = left + (int) Math.ceil(lay.getLineWidth(0));
                int base = ck.getTotalPaddingTop() + lay.getLineBaseline(0);
                int y = base + dp(bar.getResources(), 2);
                int th = Math.max(2, dp(bar.getResources(), 1));
                secLine.setBounds(left, y, right, y + th);
                if (!secLineAdded) {
                    clock.getOverlay().add(secLine);
                    secLineAdded = true;
                }
                secLine.set((sec + 1) / 60f, accent, (color & 0x00FFFFFF) | 0x40000000);
            }
        }

        // ---- Row 2: date, timezone, week, day of year, sunrise -> sunset.

        double[] lastLatLon = {18.62, 73.80};   // the last real fix if there is one, else Pune, until the phone knows better
        String dateLevelLog = "";

        android.text.SpannableStringBuilder dateText(int level, int color) {
            boolean dk = darkBar(color);
            int grey = (color & 0x00FFFFFF) | 0x99000000;
            java.util.Calendar cal = java.util.Calendar.getInstance();
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            sb.append(new java.text.SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(cal.getTime()));
            if (level >= 1) {
                sb.append(" \u00B7 ");
                int st = sb.length();
                sb.append(zoneShort());
                sb.setSpan(new android.text.style.ForegroundColorSpan(dk ? 0xFF64D2FF : 0xFF0086A8), st, sb.length(), 0);
                sb.append(' ');
                st = sb.length();
                sb.append(utcOffset());
                sb.setSpan(new android.text.style.RelativeSizeSpan(0.75f), st, sb.length(), 0);
                sb.setSpan(new android.text.style.ForegroundColorSpan(grey), st, sb.length(), 0);
            }
            if (level >= 2) {
                sb.append('\n');
                int st = sb.length();
                java.util.Calendar iso = java.util.Calendar.getInstance();
                iso.setFirstDayOfWeek(java.util.Calendar.MONDAY);
                iso.setMinimalDaysInFirstWeek(4);
                sb.append("W").append(String.valueOf(iso.get(java.util.Calendar.WEEK_OF_YEAR)))
                        .append(" \u00B7 day ").append(String.valueOf(cal.get(java.util.Calendar.DAY_OF_YEAR)));
                sb.setSpan(new android.text.style.ForegroundColorSpan(grey), st, sb.length(), 0);
                if (level >= 3) {
                    try {
                        android.location.Location l = TowerDb.myLocation(ctx);
                        if (l != null) lastLatLon = new double[]{l.getLatitude(), l.getLongitude()};
                    else { double[] k = TowerDb.lastKnown(ctx); if (k != null) lastLatLon = new double[]{k[0], k[1]}; }
                    } catch (Throwable ignored) { }
                    double[] sun = SunTimes.today(lastLatLon[0], lastLatLon[1]);
                    sb.append(" \u00B7 ");
                    st = sb.length();
                    sb.append("\u2600 ").append(SunTimes.hhmm(sun[0])).append(" \u2192 ").append(SunTimes.hhmm(sun[1]));
                    sb.setSpan(new android.text.style.ForegroundColorSpan(dk ? 0xFFFF9F0A : 0xFFC46A00), st, sb.length(), 0);
                }
            }
            return sb;
        }

        void updateDateInfo(int color) {
            ViewGroup row2 = (ViewGroup) bar.findViewWithTag("dual_row2");
            if (row2 == null) return;
            Resources res = bar.getResources();
            TextView tv = (TextView) bar.findViewWithTag("dual_dateinfo");
            if (tv == null) {
                tv = new TextView(ctx);
                tv.setTag("dual_dateinfo");
                tv.setIncludeFontPadding(false);
                tv.setLineSpacing(0, 0.85f);
                tv.setMaxLines(2);
                tv.setHorizontallyScrolling(true);
                tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.CENTER_VERTICAL;
                lp.setMarginStart(dp(res, 4));
                lp.setMarginEnd(dp(res, 4));
                row2.addView(tv, Math.min(1, row2.getChildCount()), lp);
            }
            tv.setAlpha(hiddenForLock ? 0f : 1f);
            if (!Cfg.b(Cfg.DATE_INFO, Cfg.D_DATE_INFO) || Cfg.b(Cfg.CLOCK_SUN, Cfg.D_CLOCK_SUN)) {
                if (tv.getVisibility() != View.GONE) tv.setVisibility(View.GONE);
                return;
            }
            Hook.setSp(tv, Cfg.i(Cfg.TABLE_SP, Cfg.D_TABLE_SP) + 1);
            Hook.matchClockFont(clock, tv, Cfg.b(Cfg.BOLD, Cfg.D_BOLD));
            tv.setTextColor(color);
            View box = bar.findViewWithTag("dual_cellinfo");
            int room = row2.getWidth() - row2.getPaddingLeft() - row2.getPaddingRight()
                    - visibleWidth(statusIcons) - visibleWidth(bar.findViewWithTag("dual_weather"))
                    - visibleWidth(box) - Math.min(notifNeeded(), row2.getWidth() * 2 / 5) - dp(res, 8 + 6);
            int level = -1;
            android.text.SpannableStringBuilder show = null;
            for (int l = 3; l >= 0; l--) {
                android.text.SpannableStringBuilder t = dateText(l, color);
                if (row2.getWidth() > 0 && spannedWidth(tv, t) + 1 <= room) {
                    level = l;
                    show = t;
                    break;
                }
            }
            String lg = level + "";
            if (!lg.equals(dateLevelLog)) {
                dateLevelLog = lg;
                XposedBridge.log(TAG + "date line level " + level + " (room " + room + "px)");
            }
            if (show == null) {
                if (tv.getVisibility() != View.GONE) tv.setVisibility(View.GONE);
                return;
            }
            if (!show.toString().equals(String.valueOf(tv.getText())) || tv.getCurrentTextColor() != color) tv.setText(show);
            fitWidthSpanned(tv, show);
            if (tv.getVisibility() != View.VISIBLE) tv.setVisibility(View.VISIBLE);
        }

        // ---- Row 2: cell table, between the notification icons and the system icons.

        /** Column ids in display order, and their priority (higher = kept longer). */
        static final String[] COLS = {"sim", "ta", "cqi"};
        static final int[] PRIO = {100, 80, 15};
        final java.util.Set<String> tableShown = new java.util.HashSet<>();
        String lastTableLog = "";
        int tableColor = 0;

        String colLabel(String id) {
            switch (id) {
                case "op": return "";
                case "mcc": return "MCC";
                case "tac": return "TAC";
                case "pci": return "PCI";
                case "arfcn": return "CH";
                case "site": return "eNB";
                case "snr": return "SNR";
                case "rsrq": return "RSRQ";
                case "rssi": return "RSSI";
                case "ta": return "TA";
                case "cqi": return "CQI";
                case "data": return "today";
                default: return "";
            }
        }

        String colValue(String id, Bands.Sim s) {
            switch (id) {
                case "sim": return String.valueOf(s.slot);
                case "op": return s.operator != null ? s.operator : "?";
                case "mcc": return s.mccMnc != null ? s.mccMnc : "?";
                case "tac": return s.tac >= 0 ? String.valueOf(s.tac) : "?";
                case "pci": return s.pci >= 0 ? String.valueOf(s.pci) : "?";
                case "arfcn": return s.arfcn >= 0 ? String.valueOf(s.arfcn) : "?";
                case "site": return s.site != null ? s.site : "?";
                case "snr": return Float.isNaN(s.snr) ? "?" : String.valueOf(Math.round(s.snr));
                case "rsrq": return s.rsrq != CellDetail.NONE ? String.valueOf(s.rsrq) : "?";
                case "rssi": return s.rssi != CellDetail.NONE ? String.valueOf(s.rssi) : "?";
                case "ta": return s.ta != CellDetail.NONE ? String.valueOf(s.ta) : "?";
                case "cqi": return s.cqi != CellDetail.NONE ? String.valueOf(s.cqi) : "?";
                case "data": return bytesLabel(s.dataToday);
                default: return "?";
            }
        }

        String bytesLabel(long b) {
            if (b < 0) return "?";
            if (b >= 1L << 30) return String.format(Locale.US, b >= 10L << 30 ? "%.0fG" : "%.1fG", b / (double) (1L << 30));
            if (b >= 1L << 20) return (b >> 20) + "M";
            return (b >> 10) + "K";
        }

        /** Two lines (SIM 1, SIM 2). Line 1 starts with a small grey label; line 2 keeps the space so values line up. */
        android.text.SpannableStringBuilder colText(String id, int color) {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            String label = colLabel(id);
            int grey = (color & 0x00FFFFFF) | 0x99000000;
            boolean first = true;
            for (Bands.Sim s : sims) {
                if (!first) sb.append('\n');
                if (!label.isEmpty()) {
                    int st = sb.length();
                    sb.append(label).append('\u2009');
                    sb.setSpan(new android.text.style.RelativeSizeSpan(0.6f), st, sb.length(), 0);
                    sb.setSpan(new android.text.style.ForegroundColorSpan(first ? grey : 0), st, sb.length(), 0);
                }
                int st = sb.length();
                sb.append(colValue(id, s));
                if (id.equals("sim")) {
                    sb.setSpan(new android.text.style.RelativeSizeSpan(0.75f), st, sb.length(), 0);
                    sb.setSpan(new android.text.style.ForegroundColorSpan(grey), st, sb.length(), 0);
                }
                first = false;
            }
            return sb;
        }

        int visibleWidth(View v) {
            if (v == null || v.getVisibility() != View.VISIBLE) return 0;
            return Math.max(0, v.getWidth() - Math.max(0, (int) v.getTranslationX()));
        }

        /** Width the notification icons need right now (sum of the shown icons). */
        int notifNeeded() {
            View icons = byId(bar, "notificationIcons");
            if (!(icons instanceof ViewGroup)) return 0;
            ViewGroup g = (ViewGroup) icons;
            int w = 0;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c.getVisibility() == View.VISIBLE) w += c.getWidth();
            }
            return w + g.getPaddingLeft() + g.getPaddingRight();
        }

        void updateCellTable(int color) {
            ViewGroup row2 = (ViewGroup) bar.findViewWithTag("dual_row2");
            if (row2 == null) return;
            Resources res = bar.getResources();
            LinearLayout box = (LinearLayout) bar.findViewWithTag("dual_cellinfo");
            if (box == null) {
                box = new LinearLayout(bar.getContext());
                box.setTag("dual_cellinfo");
                box.setOrientation(LinearLayout.HORIZONTAL);
                box.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
                lp.setMarginStart(dp(res, 4));
                lp.setMarginEnd(dp(res, 4));
                row2.addView(box, Math.min(1, row2.getChildCount()), lp);
                for (String id : COLS) {
                    TextView tv = new TextView(bar.getContext());
                    tv.setTag("dual_col_" + id);
                    tv.setIncludeFontPadding(false);
                    tv.setLineSpacing(0, 0.85f);
                    tv.setMaxLines(2);
                    tv.setHorizontallyScrolling(true);
                    tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                    tv.setVisibility(View.GONE);
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    clp.gravity = Gravity.CENTER_VERTICAL;
                    clp.setMarginStart(dp(res, 4));
                    box.addView(tv, clp);
                }
            }
            box.setAlpha(hiddenForLock ? 0f : 1f);
            if (!Cfg.b(Cfg.CELL_TABLE, Cfg.D_CELL_TABLE) || sims.isEmpty()) {
                if (box.getVisibility() != View.GONE) box.setVisibility(View.GONE);
                return;
            }
            if (box.getVisibility() != View.VISIBLE) box.setVisibility(View.VISIBLE);

            // Room: the row minus the system icons, the weather and what the notification icons need.
            int room = row2.getWidth() - row2.getPaddingLeft() - row2.getPaddingRight()
                    - visibleWidth(statusIcons) - visibleWidth(bar.findViewWithTag("dual_weather"))
                    - Math.min(notifNeeded(), row2.getWidth() * 2 / 5) - dp(res, 8 + 6);
            // Notification icons get at most 2/5 of the row; the rest go into their overflow dot.
            int sp = Cfg.i(Cfg.TABLE_SP, Cfg.D_TABLE_SP);
            int margin = dp(res, 4);
            Map<String, CharSequence> texts = new HashMap<>();
            Map<String, Integer> widths = new HashMap<>();
            for (String id : COLS) {
                TextView tv = (TextView) box.findViewWithTag("dual_col_" + id);
                Hook.setSp(tv, sp);
                Hook.matchClockFont(clock, tv, Cfg.b(Cfg.BOLD, Cfg.D_BOLD));
                tv.setLetterSpacing(-0.03f);
                tv.setTextColor(color);
                CharSequence t = colText(id, color);
                texts.put(id, t);
                widths.put(id, (int) Math.ceil(spannedWidth(tv, t)) + 1 + margin);
            }
            // Most important first, as long as they fit (a column not shown yet needs a little extra).
            Integer[] order = new Integer[COLS.length];
            for (int i = 0; i < order.length; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> PRIO[b] - PRIO[a]);
            java.util.Set<String> keep = new java.util.HashSet<>();
            int used = 0;
            View snrRow1 = bar.findViewWithTag("dual_snr");
            boolean snrOnRow1 = snrRow1 != null && snrRow1.getVisibility() == View.VISIBLE;
            for (int i : order) {
                String id = COLS[i];
                // Never reported by this modem (all "?"): leave the column out.
                if (!id.equals("sim")) {
                    boolean any = false;
                    for (Bands.Sim sm : sims) if (!colValue(id, sm).equals("?")) any = true;
                    if (!any) continue;
                }
                if (id.equals("snr") && snrOnRow1) continue;
                int w = widths.get(id) + (tableShown.contains(id) ? 0 : dp(res, 6));
                if (row2.getWidth() == 0 || used + w > room) continue;
                keep.add(id);
                used += widths.get(id);
            }
            if (keep.size() == 1) keep.clear(); // SIM numbers alone mean nothing
            for (String id : COLS) {
                TextView tv = (TextView) box.findViewWithTag("dual_col_" + id);
                if (keep.contains(id)) {
                    CharSequence t = texts.get(id);
                    if (!t.toString().equals(String.valueOf(tv.getText())) || tableColor != color) tv.setText(t);
                    fitWidthSpanned(tv, t);
                    if (tv.getVisibility() != View.VISIBLE) tv.setVisibility(View.VISIBLE);
                } else if (tv.getVisibility() != View.GONE) {
                    tv.setVisibility(View.GONE);
                }
            }
            tableShown.clear();
            tableShown.addAll(keep);
            tableColor = color;
            StringBuilder lg = new StringBuilder();
            for (String id : COLS) if (keep.contains(id)) lg.append(id).append(' ');
            String l = lg.toString();
            if (!l.equals(lastTableLog)) {
                lastTableLog = l;
                XposedBridge.log(TAG + "cell table columns: " + (l.isEmpty() ? "none" : l) + "(room " + room + "px)");
            }
        }

        /** dBm then SNR per SIM (same line order as the band label), after the amps. */
        void updateDbm(int color) {
            if (extras == null) return;
            View pw = visibleIn(bar.findViewWithTag("dual_power"));
            View ns = visibleIn(bar.findViewWithTag("netspeed_xposed"));
            View after = pw != null ? pw : ns;
            boolean bands = Cfg.b(Cfg.SHOW_BAND, Cfg.D_SHOW_BAND);
            android.text.SpannableStringBuilder full = new android.text.SpannableStringBuilder(),
                    brief = new android.text.SpannableStringBuilder();
            // With the signal tower on, dBm is drawn under its wave fans instead.
            if (Cfg.b(Cfg.SHOW_DBM, Cfg.D_SHOW_DBM) && bands && !towerShown) {
                for (Bands.Sim s : sims) {
                    if (s.band == null) continue;
                    String v = s.dbm != Bands.NO_DBM ? String.valueOf(s.dbm) : "?";
                    if (full.length() > 0) { full.append('\n'); brief.append('\n'); }
                    full.append(v);
                    brief.append(v);
                    if (full.toString().indexOf('\n') < 0) tinyLabel(full, "dBm", color);
                }
            }
            leftColumn("dual_dbm", full, brief, color, after);

            View dbmV = visibleIn(bar.findViewWithTag("dual_dbm"));
            android.text.SpannableStringBuilder sf = new android.text.SpannableStringBuilder(),
                    sbr = new android.text.SpannableStringBuilder();
            // With the signal tower on, SINR is under its fans: don't repeat it here.
            if (Cfg.b(Cfg.SHOW_SNR, Cfg.D_SHOW_SNR) && bands && !towerShown) {
                boolean any = false;
                for (Bands.Sim s : sims) {
                    if (s.band == null) continue;
                    if (sf.length() > 0) { sf.append('\n'); sbr.append('\n'); }
                    String v = Float.isNaN(s.snr) ? "?" : String.valueOf(Math.round(s.snr));
                    if (!Float.isNaN(s.snr)) any = true;
                    for (android.text.SpannableStringBuilder b : new android.text.SpannableStringBuilder[]{sf, sbr}) {
                        int st = b.length();
                        b.append(v);
                        // green = clean, yellow = fair, red = noisy (on a dark bar)
                        if (darkBar(color) && !Float.isNaN(s.snr)) {
                            int c = s.snr >= 13 ? 0xFF34C759 : s.snr >= 0 ? 0xFFFFCC00 : 0xFFFF453A;
                            b.setSpan(new android.text.style.ForegroundColorSpan(c), st, b.length(), 0);
                        }
                    }
                    if (sf.toString().indexOf('\n') < 0) tinyLabel(sf, "SNR", color);
                }
                if (!any) { sf.clear(); sbr.clear(); }
            }
            leftColumn("dual_snr", sf, sbr, color, dbmV != null ? dbmV : after);
        }

        /**
         * Screen x where row 1's left area ends: the camera cutout's left edge (minus 2dp),
         * or the right edge of the left container when there is no cutout.
         */
        int leftLimit() {
            int limit = Integer.MAX_VALUE;
            try {
                android.view.WindowInsets wi = bar.getRootWindowInsets();
                android.view.DisplayCutout dc = wi == null ? null : wi.getDisplayCutout();
                if (dc != null) {
                    int[] b = new int[2];
                    bar.getLocationOnScreen(b);
                    for (android.graphics.Rect r : dc.getBoundingRects()) {
                        if (r.top > b[1] + bar.getHeight() || r.left <= b[0]) continue;
                        limit = Math.min(limit, r.left - dp(bar.getResources(), 2));
                    }
                }
            } catch (Throwable ignored) { }
            // No cutout: the left side may use up to the middle of the bar.
            if (limit == Integer.MAX_VALUE && bar.getWidth() > 0) {
                int[] b = new int[2];
                bar.getLocationOnScreen(b);
                limit = b[0] + bar.getWidth() / 2 - dp(bar.getResources(), 4);
            }
            return limit;
        }

        /**
         * Amps/watts sit after the net speed when there's room; otherwise they move to the
         * right of the camera, just before the band label, so the values are never cut off.
         */
        boolean cellsNoRoom = false;
        int cellsWidth = 0;

        /** Antenna + cells heard per SIM, after the amps (or the net speed), never past the camera. */
        int mastColor = 0, mastH = 0, mastW = 0;
        /** Space between the item before the counter and the camera (px), -1 until measured. */
        int cellsRoom = -1;
        boolean distLeftShown = true;

        float cellsTs = 20f;
        int cellsColor = 0xFFFFFFFF;

        /** SINR quality colour: green good (13+ dB), yellow fair (0..13), red poor; text colour if unknown. */
        int sinrColor(Bands.Sim s) {
            if (Float.isNaN(s.snr)) return cellsColor;
            boolean dk = darkBar(cellsColor);
            if (s.snr >= 13) return dk ? 0xFF34C759 : 0xFF1E8E3E;
            if (s.snr >= 0) return dk ? 0xFFFFCC00 : 0xFFB38600;
            return dk ? 0xFFFF453A : 0xFFC4281C;
        }

        void icon(android.text.SpannableStringBuilder sb, Drawable d) {
            int at = sb.length();
            sb.append('\u2009');
            sb.setSpan(new android.text.style.ImageSpan(d, android.text.style.DynamicDrawableSpan.ALIGN_CENTER),
                    at, at + 1, 0);
        }

        /**
         * One line per SIM: [nodes, coloured by SINR] towers heard + small SIM no., then
         * [dotted line + pin] distance, then the SINR in dB. mode 0: counts only; 1: full distance;
         * 2: whole-km distance; 3: distance only (taking turns with the counts).
         */
        android.text.SpannableStringBuilder cellsText(int mode, boolean sinr) {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            if (!Cfg.b(Cfg.SHOW_CELLS, Cfg.D_SHOW_CELLS)) return sb;
            int sym = Math.round(cellsTs * 0.85f);
            boolean dk = darkBar(cellsColor);
            for (Bands.Sim s : sims) {
                if (sb.length() > 0) sb.append('\n');
                int st;
                if (mode != 3) {
                    icon(sb, new NodesDrawable(sinrColor(s), sym));
                    sb.append(s.cells >= 0 ? String.valueOf(s.cells) : "?");
                    st = sb.length();
                    sb.append(String.valueOf(s.slot));
                    sb.setSpan(new android.text.style.SubscriptSpan(), st, sb.length(), 0);
                    sb.setSpan(new android.text.style.RelativeSizeSpan(0.7f), st, sb.length(), 0);
                }
                if (mode != 0) {
                    if (mode != 3) sb.append('\u2009');
                    icon(sb, new PinDrawable(cellsColor, dk ? 0xFFFF3B30 : 0xFFC62828,
                            Math.round(sym * 1.25f), sym));
                    if (s.distM < 0) {
                        sb.append('?');
                    } else {
                        String mark = s.guess ? "\u2248" : s.approx ? "~" : "";
                        if (!mark.isEmpty()) {
                            st = sb.length();
                            sb.append(mark);
                            sb.setSpan(new android.text.style.RelativeSizeSpan(0.75f), st, sb.length(), 0);
                        }
                        int m = s.distM;
                        String num, unit;
                        if (m < 1000) {
                            num = String.valueOf(mode == 2 ? Math.round(m / 100f) * 100 : m);
                            unit = "m";
                        } else if (mode == 2 || m >= 10_000) {
                            num = String.valueOf(Math.round(m / 1000f));
                            unit = "km";
                        } else {
                            num = String.format(java.util.Locale.US, "%.1f", m / 1000f);
                            unit = "km";
                        }
                        sb.append(num);
                        st = sb.length();
                        sb.append(unit);
                        sb.setSpan(new android.text.style.RelativeSizeSpan(0.7f), st, sb.length(), 0);
                    }
                }
                if (sinr && mode != 3 && !Float.isNaN(s.snr)) {
                    sb.append('\u2009');
                    st = sb.length();
                    sb.append(String.valueOf(Math.round(s.snr)));
                    sb.setSpan(new android.text.style.ForegroundColorSpan(sinrColor(s)), st, sb.length(), 0);
                    st = sb.length();
                    sb.append("dB");
                    sb.setSpan(new android.text.style.RelativeSizeSpan(0.6f), st, sb.length(), 0);
                    sb.setSpan(new android.text.style.ForegroundColorSpan((cellsColor & 0x00FFFFFF) | 0x99000000),
                            st, sb.length(), 0);
                }
            }
            return sb;
        }

        boolean cellsSinr = false;
        int cellsWantPx = 0;
        boolean battTextShown = true;

        /** Where the tower block is: 0 left (after amps / net speed), 1 right of the camera. */
        int cellsSide = 0;
        /** Text mode in use: 0 counts only, 1 with distance, 2 with short distance, -1 hidden. */
        int cellsMode = -2;

        void updateCells(int color) {
            View v = bar.findViewWithTag("dual_cells");
            if (!(v instanceof TextView)) return;
            TextView tv = (TextView) v;
            // With the signal tower on, the distance is on its band lines instead.
            boolean wantDist = Cfg.b(Cfg.SHOW_DIST, Cfg.D_SHOW_DIST) && !towerShown;
            cellsColor = color;
            cellsTs = tv.getTextSize();
            if (towerShown || cellsText(0, false).length() == 0) {
                if (tv.getVisibility() != View.GONE) tv.setVisibility(View.GONE);
                return;
            }
            Resources res = bar.getResources();
            tv.setTextColor(color);
            Hook.setSp(tv, Cfg.i(Cfg.POWER_SP, Cfg.D_POWER_SP));
            Hook.matchClockFont(clock, tv, Cfg.b(Cfg.BOLD, Cfg.D_BOLD));
            tv.setLetterSpacing(-0.04f);
            int ih = Math.round(tv.getTextSize() * (sims.size() > 1 ? 1.7f : 1.0f));
            // Mast width scales with the "Tower mast size" setting; its height spans both lines.
            // Waves need width <= 0.83 x height, so very large sizes also grow the height a bit.
            int iw = Math.round(tv.getTextSize() * 0.95f * Cfg.i(Cfg.MAST_PCT, Cfg.D_MAST_PCT) / 100f);
            ih = Math.max(ih, Math.round(iw / 0.83f));
            int margin = dp(res, 3);
            int extra = iw + dp(res, 1) + tv.getPaddingLeft() + tv.getPaddingRight() + 1;

            // Room on both sides of the camera, measured NOW (not from the last layout).
            View pw = bar.findViewWithTag("dual_power");
            View ns = bar.findViewWithTag("netspeed_xposed");
            View dbmV = visibleIn(bar.findViewWithTag("dual_snr"));
            if (dbmV == null) dbmV = visibleIn(bar.findViewWithTag("dual_dbm"));
            View after = dbmV != null ? dbmV
                    : pw != null && pw.getParent() == extras.root && pw.getVisibility() == View.VISIBLE ? pw
                    : ns != null && ns.getParent() == extras.root && ns.getVisibility() == View.VISIBLE ? ns : null;
            int leftRoom = -1;
            int limit = leftLimit();
            if (after != null && after.getWidth() > 0 && limit != Integer.MAX_VALUE) {
                int[] p = new int[2];
                after.getLocationOnScreen(p);
                // The amps may have just changed width: use the width they asked for.
                int w = Math.max(after.getWidth(), after instanceof TextView ? ((TextView) after).getMinWidth() : 0);
                leftRoom = limit - (p[0] + w) - margin;
            }
            cellsRoom = leftRoom;
            View band = rightAnchor();
            int rightRoom = -1;
            int rl = rightLimit();
            if (band != null && band.getParent() instanceof ViewGroup && band.getVisibility() == View.VISIBLE
                    && rl != Integer.MIN_VALUE) {
                View first = pw != null && pw.getVisibility() == View.VISIBLE && pw.getParent() == band.getParent()
                        ? pw : band;
                if (first.getWidth() > 0) {
                    int[] p = new int[2];
                    first.getLocationOnScreen(p);
                    int left = p[0];
                    rightRoom = left - rl - margin;
                }
            }

            // Best option that fits: left with distance, right with distance, left counts only.
            // Switching to a different option needs 4dp extra, so it doesn't flicker.
            int slack = dp(res, 4);
            int side = -1, mode = -1;
            android.text.SpannableStringBuilder sb = null;
            // Mode 4: no room for both, so the same spot flips every 4 s between the counts and
            // the distance (mode 3); its width is the wider of the two, so nothing jumps.
            // {side, mode, with SINR value}: the SINR value is the first thing to go.
            // With the signal tower on, the SINR is under its fans instead.
            boolean wantSinr = Cfg.b(Cfg.SINR_MAST, Cfg.D_SINR_MAST) && !towerShown;
            int[][] options = wantDist
                    ? new int[][]{{1, 1, 1}, {1, 1, 0}, {1, 2, 0}, {1, 4, 0}, {0, 1, 1}, {0, 1, 0}, {0, 2, 0},
                    {0, 4, 0}, {1, 0, 1}, {0, 0, 1}, {0, 0, 0}, {1, 0, 0}}
                    : new int[][]{{1, 0, 1}, {0, 0, 1}, {1, 0, 0}, {0, 0, 0}};
            boolean distPhase = (android.os.SystemClock.elapsedRealtime() / 4000) % 2 == 1;
            float altWidth = 0;
            boolean sinrNow = false;
            // What the tower block wants right of the camera (its best option), for the battery
            // details to leave room for.
            cellsWantPx = 0;
            for (int[] o : options) {
                if (o[0] != 1 || o[1] == 4 || (o[2] == 1 && !wantSinr)) continue;
                cellsWantPx = (int) Math.ceil(spannedWidth(tv, cellsText(o[1], o[2] == 1)) + extra) + margin;
                break;
            }
            for (int[] o : options) {
                if (o[2] == 1 && !wantSinr) continue;
                android.text.SpannableStringBuilder t;
                float need;
                if (o[1] == 4) {
                    android.text.SpannableStringBuilder c = cellsText(0, false), d = cellsText(3, false);
                    altWidth = Math.max(spannedWidth(tv, c), spannedWidth(tv, d));
                    need = altWidth + extra;
                    t = distPhase ? d : c;
                } else {
                    t = cellsText(o[1], o[2] == 1);
                    need = spannedWidth(tv, t) + extra;
                }
                int room = o[0] == 0 ? leftRoom : rightRoom;
                boolean same = o[0] == cellsSide && o[1] == cellsMode && (o[2] == 1) == cellsSinr;
                if (room >= 0 && need <= room - (same ? 0 : slack)) {
                    side = o[0];
                    mode = o[1];
                    sinrNow = o[2] == 1;
                    sb = t;
                    break;
                }
            }
            if (side != cellsSide || mode != cellsMode || sinrNow != cellsSinr) {
                XposedBridge.log(TAG + "tower block: " + (mode < 0 ? "hidden (no room)"
                        : (side == 0 ? "left" : "right of the camera") + (mode == 0 ? ", counts only"
                        : mode == 4 ? ", counts and distance taking turns" : ", with distance")
                        + (sinrNow ? " + SINR" : ""))
                        + " (room left " + leftRoom + "px, right " + rightRoom + "px)");
            }
            if (mode < 0) {
                cellsMode = -1;
                cellsNoRoom = true;
                if (tv.getVisibility() != View.GONE) tv.setVisibility(View.GONE);
                return;
            }
            cellsNoRoom = false;
            cellsMode = mode;
            cellsSinr = sinrNow;

            tv.setText(sb);
            if (mastColor != color || mastH != ih || mastW != iw) {
                mastColor = color;
                mastH = ih;
                mastW = iw;
                tv.setCompoundDrawablesRelative(new MastDrawable(color, iw, ih, darkBar(color)), null, null, null);
                tv.setCompoundDrawablePadding(dp(res, 1));
            }
            float textW = mode == 4 ? altWidth : spannedWidth(tv, sb);
            int withIcon = (int) Math.ceil(textW) + 1 + tv.getPaddingLeft() + tv.getPaddingRight() + iw + dp(res, 1);
            if (tv.getMinWidth() != withIcon) tv.setMinWidth(withIcon);
            cellsWidth = tv.getMinWidth();

            if (side == 1) {
                ViewGroup rp = (ViewGroup) band.getParent();
                View first = pw != null && pw.getVisibility() == View.VISIBLE && pw.getParent() == rp ? pw : band;
                int want = rp.indexOfChild(first);
                if (tv.getParent() != rp || rp.indexOfChild(tv) != want - 1) {
                    detach(tv);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.gravity = Gravity.CENTER_VERTICAL;
                    lp.setMarginEnd(margin);
                    rp.addView(tv, rp.indexOfChild(first), lp);
                }
            } else {
                int want = after != null ? extras.root.indexOfChild(after) + 1 : extras.root.getChildCount();
                if (tv.getParent() != extras.root) {
                    detach(tv);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.gravity = Gravity.CENTER_VERTICAL;
                    lp.setMarginStart(margin);
                    extras.root.addView(tv, Math.min(want, extras.root.getChildCount()), lp);
                } else {
                    int cur = extras.root.indexOfChild(tv);
                    if (cur != want && cur != want - 1) {
                        ViewGroup.LayoutParams lp = tv.getLayoutParams();
                        extras.root.removeView(tv);
                        extras.root.addView(tv, Math.min(want > cur ? want - 1 : want, extras.root.getChildCount()), lp);
                    }
                }
            }
            cellsSide = side;
            // Hidden on the lock screen like the rest of the left side, wherever it sits.
            if (tv.getAlpha() != extras.root.getAlpha()) tv.setAlpha(extras.root.getAlpha());
            if (tv.getVisibility() != View.VISIBLE) tv.setVisibility(View.VISIBLE);
        }

        void placePowerSide() {
            View pw = bar.findViewWithTag("dual_power");
            View bandView = rightAnchor();
            if (!(pw instanceof TextView) || pw.getVisibility() != View.VISIBLE) return;
            Resources res = bar.getResources();
            int margin = dp(res, 3);
            int need = ((TextView) pw).getMinWidth() + margin;

            // Right edge of what comes before the amps on the left side.
            View ns = bar.findViewWithTag("netspeed_xposed");
            View anchor = ns != null && ns.getParent() == extras.root && ns.getVisibility() == View.VISIBLE
                    ? ns : extras.root;
            int[] p = new int[2];
            anchor.getLocationOnScreen(p);
            int anchorRight = p[0] + (anchor == extras.root && pw.getParent() == extras.root
                    ? extras.root.getWidth() - pw.getWidth() - margin : anchor.getWidth());
            int limit = leftLimit();
            if (anchor.getWidth() == 0 || limit == Integer.MAX_VALUE) return;

            // A little slack before moving back, so it doesn't flip back and forth.
            boolean fitsLeft = anchorRight + need <= limit - (powerOnRight ? dp(res, 4) : 0);
            boolean wantRight = !fitsLeft;
            if (wantRight == powerOnRight && (wantRight ? pw.getParent() != extras.root : pw.getParent() == extras.root)) return;

            detach(pw);
            if (wantRight && bandView != null && bandView.getParent() instanceof ViewGroup) {
                ViewGroup rp = (ViewGroup) bandView.getParent();
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.CENTER_VERTICAL;
                lp.setMarginEnd(margin);
                rp.addView(pw, rp.indexOfChild(bandView), lp);
                ((TextView) pw).setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
                powerOnRight = true;
                XposedBridge.log(TAG + "amps/watts moved right of the camera (no room on the left: needs "
                        + need + "px from x=" + anchorRight + ", camera at x=" + limit + ")");
            } else {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.CENTER_VERTICAL;
                lp.setMarginStart(margin);
                extras.root.addView(pw, lp);
                ((TextView) pw).setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                powerOnRight = false;
                placePower();
                XposedBridge.log(TAG + "amps/watts back after the net speed (x=" + anchorRight
                        + ", needs " + need + "px, camera at x=" + limit + ")");
            }
        }

        void updatePower(int color) {
            View v = bar.findViewWithTag("dual_power");
            if (!(v instanceof TextView)) return;
            TextView tv = (TextView) v;
            if (!Cfg.b(Cfg.SHOW_POWER, Cfg.D_SHOW_POWER) || Float.isNaN(amps) || combined()) {
                if (tv.getVisibility() != View.GONE) tv.setVisibility(View.GONE);
                return;
            }
            tv.setTextColor(color);
            Hook.setSp(tv, Cfg.i(Cfg.POWER_SP, Cfg.D_POWER_SP));
            Hook.matchClockFont(clock, tv, Cfg.b(Cfg.BOLD, Cfg.D_BOLD));
            // Compact: + while charging, − on battery. Units at 70% size.
            boolean chg = amps > 0;
            tv.setTextColor(powerColor(color));
            tv.setLetterSpacing(-0.04f);
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            sb.append(chg ? "+" : "\u2212");
            sb.append(String.format(java.util.Locale.US, "%.2f", Math.abs(amps)));
            int u = sb.length();
            sb.append("A");
            sb.setSpan(new android.text.style.RelativeSizeSpan(0.7f), u, sb.length(), 0);
            if (!Float.isNaN(watts)) {
                float aw = Math.abs(watts);
                sb.append('\n');
                sb.append(chg ? "+" : "\u2212");
                sb.append(String.format(java.util.Locale.US, aw < 10 ? "%.1f" : "%.0f", aw));
                u = sb.length();
                sb.append("W");
                sb.setSpan(new android.text.style.RelativeSizeSpan(0.7f), u, sb.length(), 0);
            }
            tv.setText(sb);
            fitWidth(tv, sb);
            if (tv.getVisibility() != View.VISIBLE) tv.setVisibility(View.VISIBLE);
        }

        /** Width of the widest line, measured with its spans (small units), plus padding. */
        void fitWidth(TextView tv, CharSequence text) {
            float max = 0;
            int st = 0;
            String plain = text.toString();
            while (st <= plain.length()) {
                int e = plain.indexOf('\n', st);
                if (e < 0) e = plain.length();
                max = Math.max(max, android.text.Layout.getDesiredWidth(text, st, e, tv.getPaint()));
                st = e + 1;
            }
            int w = (int) Math.ceil(max) + 1 + tv.getPaddingLeft() + tv.getPaddingRight();
            if (tv.getMinWidth() != w) tv.setMinWidth(w);
        }

        /** Temperatures and amps/watts drawn as one block (in the temperatures view). */
        boolean combined() {
            return extras != null && Cfg.b(Cfg.COMBINE_TEMPS, Cfg.D_COMBINE_TEMPS);
        }

        /** Heat colour: cyan cool, green normal, orange warm, red hot. */
        int heatColor(float t, boolean cpu, int color) {
            boolean dk = darkBar(color);
            float a = cpu ? 45 : 35, b = cpu ? 60 : 40, c = cpu ? 75 : 45;
            if (t < a) return dk ? 0xFF64D2FF : 0xFF0086A8;
            if (t < b) return dk ? 0xFF34C759 : 0xFF1E8E3E;
            if (t < c) return dk ? 0xFFFF9F0A : 0xFFC46A00;
            return dk ? 0xFFFF453A : 0xFFC4281C;
        }

        ThermoDrawable thermoCpu, thermoBat;
        boolean thermoAnimOn = false;

        /** ~12 fps redraw of the temperatures while they're on screen, so the thermometers move. */
        final Runnable thermoAnim = new Runnable() {
            @Override
            public void run() {
                if (dead()) return;
                boolean on = extras != null && extras.temps.isShown() && combined() && extras.root.getAlpha() > 0
                        && (thermoCpu != null || thermoBat != null);
                on = on && ScreenState.on();
                if (on) extras.temps.invalidate();
                main.postDelayed(this, on && Cfg.b(Cfg.ANIM_HEAT, Cfg.D_ANIM_HEAT) ? 80 : 1000);
            }
        };

        int heatStage(float t, boolean cpu) {
            float a = cpu ? 45 : 35, b = cpu ? 60 : 40, c = cpu ? 75 : 45;
            return t < a ? 0 : t < b ? 1 : t < c ? 2 : 3;
        }

        /** One temperature line: animated thermometer, small grey letter, coloured value (no unit). */
        void tempPart(android.text.SpannableStringBuilder sb, String letter, float t, boolean cpu, int color, float ts) {
            int hc = heatColor(t, cpu, color);
            float lo = cpu ? 30 : 25, hi = cpu ? 85 : 50;
            int tw = Math.round(ts * 0.45f), th = Math.round(ts * 0.95f);
            ThermoDrawable d = cpu ? thermoCpu : thermoBat;
            if (d == null || d.width() != tw || d.height() != th) {
                d = new ThermoDrawable(tw, th, cpu ? 0f : 1.7f);
                if (cpu) thermoCpu = d; else thermoBat = d;
            }
            d.set(color, hc, (t - lo) / (hi - lo), heatStage(t, cpu));
            if (!thermoAnimOn) {
                thermoAnimOn = true;
                main.post(thermoAnim);
            }
            icon(sb, d);
            int st = sb.length();
            sb.append(letter);
            sb.setSpan(new android.text.style.RelativeSizeSpan(0.65f), st, sb.length(), 0);
            sb.setSpan(new android.text.style.ForegroundColorSpan((color & 0x00FFFFFF) | 0x99000000), st, sb.length(), 0);
            st = sb.length();
            sb.append(String.valueOf(Math.round(t)));
            int stage = heatStage(t, cpu);
            boolean test = SysState.testing();
            if (Cfg.b(Cfg.ANIM_HEAT, Cfg.D_ANIM_HEAT) && (stage >= 2 || test)) {
                boolean dk = darkBar(color);
                boolean hot = stage >= 3 || test;
                int a = hot ? (dk ? 0xFFFF453A : 0xFFC4281C) : (dk ? 0xFFFF9F0A : 0xFFC46A00);
                int b = hot ? (dk ? 0xFFFFD60A : 0xFFB86E00) : (dk ? 0xFFFF6A3D : 0xFFD84315);
                sb.setSpan(new PulseColorSpan(a, b, hot ? 800 : 1600), st, sb.length(), 0);
            } else {
                sb.setSpan(new android.text.style.ForegroundColorSpan(hc), st, sb.length(), 0);
            }
        }

        /** Amps (line 0) or watts (line 1): no minus sign; green with a bolt while charging. */
        /** Colour of the current / power values: blue while charging, else by load (green .. red). */
        int powerColor(int color) {
            boolean dk = darkBar(color);
            if (amps > 0) return SysViews.blue(dk);
            float w = !Float.isNaN(watts) ? Math.abs(watts) : Math.abs(amps) * 3.9f;
            return w < 1.5f ? SysViews.green(dk) : w < 4f ? SysViews.yellow(dk) : w < 7f ? SysViews.orange(dk) : SysViews.red(dk);
        }

        void powerPart(android.text.SpannableStringBuilder sb, int line, int color) {
            boolean chg = amps > 0;
            int st = sb.length();
            sb.append(chg ? "+" : "\u2212");
            String unit;
            if (line == 0) {
                sb.append(String.format(java.util.Locale.US, "%.2f", Math.abs(amps)));
                unit = "A";
            } else {
                float aw = Math.abs(watts);
                sb.append(String.format(java.util.Locale.US, aw < 10 ? "%.1f" : "%.0f", aw));
                unit = "W";
            }
            int u = sb.length();
            sb.append(unit);
            sb.setSpan(new android.text.style.RelativeSizeSpan(0.7f), u, sb.length(), 0);
            sb.setSpan(new android.text.style.ForegroundColorSpan(powerColor(color)), st, sb.length(), 0);
        }

        void updateExtras(int color) {
            if (combined()) {
                updateCombined(color);
            } else {
                updateTempsPlain(color);
            }
            updateWeather(color);
        }

        void updateCombined(int color) {
            boolean bold = Cfg.b(Cfg.BOLD, Cfg.D_BOLD);
            Hook.setSp(extras.temps, Cfg.i(Cfg.TEMP_SP, Cfg.D_TEMP_SP));
            Hook.matchClockFont(clock, extras.temps, bold);
            extras.temps.setTextColor(color);
            extras.temps.setLetterSpacing(-0.03f);
            float ts = extras.temps.getTextSize();
            boolean cpuOn = Cfg.b(Cfg.SHOW_CPU, Cfg.D_SHOW_CPU) && !Float.isNaN(cpuTemp);
            boolean batOn = Cfg.b(Cfg.SHOW_BAT, Cfg.D_SHOW_BAT) && !Float.isNaN(batTemp);
            boolean pwOn = Cfg.b(Cfg.SHOW_POWER, Cfg.D_SHOW_POWER) && !Float.isNaN(amps);
            int lines = Math.max((cpuOn ? 1 : 0) + (batOn ? 1 : 0), pwOn ? (Float.isNaN(watts) ? 1 : 2) : 0);
            // Temperature part widths, so the divider lines up on both lines.
            android.text.SpannableStringBuilder[] tp = new android.text.SpannableStringBuilder[2];
            int ti = 0;
            if (cpuOn) { tp[ti] = new android.text.SpannableStringBuilder(); tempPart(tp[ti++], "C", cpuTemp, true, color, ts); }
            if (batOn) { tp[ti] = new android.text.SpannableStringBuilder(); tempPart(tp[ti++], "B", batTemp, false, color, ts); }
            float tw = 0;
            for (android.text.SpannableStringBuilder t : tp) if (t != null) tw = Math.max(tw, spannedWidth(extras.temps, t));
            float space = extras.temps.getPaint().measureText("\u2009");
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            for (int i = 0; i < lines; i++) {
                if (i > 0) sb.append('\n');
                if (tp[i] != null) sb.append(tp[i]);
                if (pwOn && (i == 0 || !Float.isNaN(watts))) {
                    if (ti > 0) {
                        // pad short temperature parts so the divider lines up
                        float have = tp[i] != null ? spannedWidth(extras.temps, tp[i]) : 0;
                        int pads = space > 0 ? Math.round((tw - have) / space) : 0;
                        for (int k = 0; k < pads; k++) sb.append('\u2009');
                        int st = sb.length();
                        sb.append(" \u2502 ");
                        sb.setSpan(new android.text.style.ForegroundColorSpan((color & 0x00FFFFFF) | 0x66000000),
                                st, sb.length(), 0);
                    }
                    powerPart(sb, i, color);
                }
            }
            extras.temps.setText(sb);
            fitWidthSpanned(extras.temps, sb);
            extras.temps.setVisibility(sb.length() > 0 ? View.VISIBLE : View.GONE);
        }

        void updateTempsPlain(int color) {
            StringBuilder sb = new StringBuilder();
            if (Cfg.b(Cfg.SHOW_CPU, Cfg.D_SHOW_CPU) && !Float.isNaN(cpuTemp)) {
                sb.append(HeatWarn.throttled ? "C\u2193 " : "C ").append(Math.round(cpuTemp)).append('°');
            }
            if (Cfg.b(Cfg.SHOW_BAT, Cfg.D_SHOW_BAT) && !Float.isNaN(batTemp)) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(HeatWarn.throttled ? "B\u2193 " : "B ").append(Math.round(batTemp)).append('°');
            }
            extras.temps.setTextColor(color);
            boolean bold = Cfg.b(Cfg.BOLD, Cfg.D_BOLD);
            Hook.setSp(extras.temps, Cfg.i(Cfg.TEMP_SP, Cfg.D_TEMP_SP));
            Hook.setSp(extras.weatherTemp, Cfg.i(Cfg.WEATHER_SP, Cfg.D_WEATHER_SP));
            Hook.matchClockFont(clock, extras.temps, bold);
            extras.temps.setLetterSpacing(0f);
            extras.temps.setMinWidth(0);
            extras.temps.setText(sb);
            extras.temps.setVisibility(sb.length() > 0 ? View.VISIBLE : View.GONE);
        }

        /** The old row-2 cell table / date line: removed, kept hidden if a view is left over. */
        void hideRow2Extras() {
            for (String tag : new String[]{"dual_cellinfo", "dual_dateinfo"}) {
                View v = bar.findViewWithTag(tag);
                if (v != null && v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
            }
        }

        // ---- Weather details beside the weather icon (Open-Meteo, every 15 min).

        volatile float wxVis = Float.NaN;
        volatile float[] presSeries, uvSeries;
        volatile float wxPres = Float.NaN, wxPresTrend = Float.NaN;   // hPa at sea level, change over the last 3 h
        volatile float wxElev = Float.NaN;
        volatile int wxCloud = -1;
        volatile float wxFeels = Float.NaN, wxWind = Float.NaN, wxUv = Float.NaN, wxMax = Float.NaN, wxMin = Float.NaN;
        volatile int wxHum = -1, wxRain = -1, wxAqi = -1, wxWindDir = -1;
        long wxFetchedAt = 0, wxTriedAt = 0;
        private int lastRq = -1;
        private long lastRefreshAt = 0;

        /** "Refresh data now" in the app: forget the last fetch times of weather, tide and El Nino (at most once every 10 minutes). */
        void checkRefresh(long now) {
            try {
                int rq = Cfg.i(Cfg.REFRESH_REQ, 0);
                if (lastRq == -1) { lastRq = rq; return; }
                if (rq == lastRq) return;
                lastRq = rq;
                if (now - lastRefreshAt < 10 * 60_000L) return;
                lastRefreshAt = now;
                wxTriedAt = 0; wxFetchedAt = 0;
                Tide.triedAt = 0; Tide.fetchedFor = null;
                EarthSci.triedAt = 0; EarthSci.at = 0;
            } catch (Throwable ignored) { }
        }
        double[] wxLatLon = {18.62, 73.80};
        boolean wxHaveFix;
        String wxLog = "";

        static String httpGet(String url) throws Exception {
            try { String r = httpGet0(url); Diag.ok(url); return r; } catch (Exception e) { Diag.fail(url, e); throw e; }
        }

        static String httpGet0(String url) throws Exception {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(8_000);
            c.setReadTimeout(8_000);
            c.setRequestProperty("User-Agent", "NetSpeedXposed/1.0");
            int code = c.getResponseCode();
            if (code == 204) return "";   // FDSN services: no matching events
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        }

        private int quakeMinUsed = -1, quakeKmUsed = -1;
        private long satPubAt = 0;
        /** Last good answer from each feed: {mag, km, time ms} + country; mag NaN = none. Kept when a feed fails. */
        private double[] qUsgs = null, qEmsc = null;
        private String ccUsgs = null, ccEmsc = null;
        private long usgsAt = 0, emscAt = 0;

        /**
         * Worker thread: the most recent earthquake anywhere in the world in the last 24 h, at or above the magnitude set in
         * NetSpeed settings. Asks USGS and EMSC and shows the newer of the two. A feed that fails keeps its last
         * answer (for up to 2 h) instead of wiping the other's. Every 5 min (retry after 2 min on failure), or
         * soon after the magnitude setting changes.
         */
        void fetchQuake() {
            long now = System.currentTimeMillis();
            int minX10 = Math.max(10, Math.min(70, Cfg.i(Cfg.QUAKE_MIN, Cfg.D_QUAKE_MIN)));
            int kmX100 = Math.max(1, Math.min(200, Cfg.i(Cfg.QUAKE_KM, Cfg.D_QUAKE_KM)));
            boolean changed = minX10 != quakeMinUsed || kmX100 != quakeKmUsed;
            if (now - quakeTriedAt < 60_000L) return;
            if (!changed && (now - quakeFetchedAt < 5 * 60_000L || now - quakeTriedAt < 2 * 60_000L)) return;
            quakeTriedAt = now;
            if (changed) { qUsgs = null; qEmsc = null; }
            double[] ll = lastFix != null ? lastFix : lastLatLon;
            float min = minX10 / 10f;
            // distance limit around you (USGS takes km, EMSC takes degrees); 200 = anywhere
            String usgsArea = "", emscArea = "";
            if (kmX100 < 200 && ll != null) {
                int km = kmX100 * 100;
                usgsArea = String.format(Locale.US, "&latitude=%.3f&longitude=%.3f&maxradiuskm=%d", ll[0], ll[1], km);
                emscArea = String.format(Locale.US, "&lat=%.3f&lon=%.3f&maxradius=%.2f", ll[0], ll[1], km / 111.195);
            }
            java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            String start = iso.format(new java.util.Date(now - 24 * 3600_000L));
            // USGS
            try {
                String body = httpGet(String.format(Locale.US,
                        "https://earthquake.usgs.gov/fdsnws/event/1/query?format=geojson"
                                + "&minmagnitude=%.1f&starttime=%s&orderby=time&limit=1%s",
                        min, start, usgsArea));
                org.json.JSONArray fs = body.isEmpty() ? new org.json.JSONArray()
                        : new org.json.JSONObject(body).getJSONArray("features");
                if (fs.length() > 0) {
                    org.json.JSONObject f = fs.getJSONObject(0);
                    org.json.JSONObject pr = f.getJSONObject("properties");
                    org.json.JSONArray co = f.getJSONObject("geometry").getJSONArray("coordinates");
                    qUsgs = new double[]{pr.getDouble("mag"),
                            ll == null ? -1 : kmBetween(ll[0], ll[1], co.getDouble(1), co.getDouble(0)),
                            pr.optLong("time", 0)};
                    ccUsgs = countryCode(pr.optString("place", ""));
                } else {
                    qUsgs = new double[]{Double.NaN, -1, 0};
                    ccUsgs = null;
                }
                usgsAt = now;
            } catch (Throwable t) {
                logOnce("quake-usgs", "USGS earthquake feed failed (will retry): " + t);
            }
            // EMSC, worldwide ("no events" comes back as HTTP 204)
            try {
                String body = httpGet(String.format(Locale.US,
                        "https://www.seismicportal.eu/fdsnws/event/1/query?format=json"
                                + "&minmag=%.1f&start=%s&orderby=time&limit=1%s",
                        min, start, emscArea));
                org.json.JSONArray fs = body.isEmpty() ? null : new org.json.JSONObject(body).optJSONArray("features");
                if (fs != null && fs.length() > 0) {
                    org.json.JSONObject pr = fs.getJSONObject(0).getJSONObject("properties");
                    long at = 0;
                    try {
                        at = iso.parse(pr.optString("time", "").replaceAll("\\..*$", "").replace("Z", "")).getTime();
                    } catch (Throwable ignored) { }
                    qEmsc = new double[]{pr.getDouble("mag"),
                            ll == null ? -1 : kmBetween(ll[0], ll[1], pr.getDouble("lat"), pr.getDouble("lon")), at};
                    ccEmsc = countryCode(pr.optString("flynn_region", ""));
                } else {
                    qEmsc = new double[]{Double.NaN, -1, 0};
                    ccEmsc = null;
                }
                emscAt = now;
            } catch (Throwable t) {
                logOnce("quake-emsc", "EMSC earthquake feed failed (will retry): " + t);
            }
            // a feed's answer is trusted for 2 h; older ones (feed down for long) are dropped
            double[] u = now - usgsAt < 2 * 3600_000L ? qUsgs : null;
            double[] e = now - emscAt < 2 * 3600_000L ? qEmsc : null;
            if (u == null && e == null) return;
            double[] best = null;
            String cc = null;
            for (int i = 0; i < 2; i++) {
                double[] q = i == 0 ? u : e;
                if (q == null || Double.isNaN(q[0])) continue;
                if (now - (long) q[2] > 24 * 3600_000L && q[2] > 0) continue;   // aged out of the 24 h window
                // newest wins; if both feeds report the same quake (within 2 min), keep USGS's copy
                if (best == null || q[2] > best[2] + 120_000L) { best = q; cc = i == 0 ? ccUsgs : ccEmsc; }
            }
            quakeMag = best == null ? Float.NaN : (float) best[0];
            quakeKm = best == null ? -1 : (int) Math.round(best[1]);
            quakeAt = best == null ? 0 : (long) best[2];
            quakeCc = best == null ? null : cc;
            quakeFetchedAt = now;
            quakeMinUsed = minX10;
            quakeKmUsed = kmX100;
        }

        /**
         * "45 km SW of Bandar Abbas, Iran" -> "IR"; "Nepal" -> "NP"; "Andaman Islands, India region" -> "IN".
         * Unknown names (seas, ridges) become their word initials, e.g. "Arabian Sea" -> "AS".
         */
        static String countryCode(String place) {
            if (place == null) return null;
            String n = place.contains(",") ? place.substring(place.lastIndexOf(',') + 1) : place;
            // USGS writes US places with the state: "10 km N of Ridgecrest, CA", "Alaska", "Hawaii"
            String st = n.trim();
            if (st.matches("[A-Z]{2}") && java.util.Arrays.asList("AL","AK","AZ","AR","CA","CO","CT","DE","FL","GA","HI","ID","IL",
                    "IN","IA","KS","KY","LA","ME","MD","MA","MI","MN","MS","MO","MT","NE","NV","NH","NJ","NM","NY","NC","ND","OH",
                    "OK","OR","PA","RI","SC","SD","TN","TX","UT","VT","VA","WA","WV","WI","WY").contains(st)) return "US";
            if (st.matches("(?i)alaska|hawaii|california|nevada|oklahoma|texas|washington|oregon|utah|montana|idaho|wyoming"
                    + "|new mexico|arizona")) return "US";
            if (st.equalsIgnoreCase("puerto rico") || st.equals("PR")) return "PR";
            if (n.contains("-")) n = n.substring(0, n.indexOf('-'));   // "NEPAL-INDIA BORDER REGION" -> NEPAL
            n = n.replaceAll("(?i)\\b(northern|southern|eastern|western|central|near coast of|offshore|border)\\b", " ")
                    .replaceAll("\\s+", " ");
            n = n.replaceAll("(?i)\\b(region|border region)\\b", "").replaceAll("(?i)^.*\\bof\\s+", "").trim();
            if (n.isEmpty()) return null;
            String key = n.toLowerCase(Locale.US).replace("the ", "");
            java.util.Map<String, String> alias = new java.util.HashMap<>();
            alias.put("myanmar", "MM"); alias.put("burma", "MM"); alias.put("iran", "IR"); alias.put("russia", "RU");
            alias.put("taiwan", "TW"); alias.put("china", "CN"); alias.put("tibet", "CN"); alias.put("xizang", "CN");
            alias.put("south korea", "KR"); alias.put("north korea", "KP"); alias.put("vietnam", "VN");
            alias.put("laos", "LA"); alias.put("syria", "SY"); alias.put("turkey", "TR"); alias.put("türkiye", "TR");
            alias.put("n.z.", "NZ"); alias.put("nz", "NZ"); alias.put("p.n.g.", "PG"); alias.put("png", "PG");
            alias.put("u.s.", "US"); alias.put("uk", "GB"); alias.put("u.k.", "GB"); alias.put("uae", "AE");
            alias.put("drc", "CD"); alias.put("congo", "CG");
            alias.put("kashmir", "IN"); alias.put("andaman islands", "IN"); alias.put("nicobar islands", "IN");
            String a = alias.get(key);
            if (a != null) return a;
            for (String iso : Locale.getISOCountries()) {
                if (new Locale("", iso).getDisplayCountry(Locale.ENGLISH).equalsIgnoreCase(n)) return iso;
            }
            // not a country (ridges, seas, island groups): the region's own name, title case, instead of initials
            String reg = st.replaceAll("(?i)^.*\\bof\\s+", "").trim();
            if (reg.isEmpty()) return null;
            StringBuilder b = new StringBuilder();
            for (String w : reg.toLowerCase(Locale.US).split(" ")) {
                if (w.isEmpty()) continue;
                if (b.length() > 0) b.append(' ');
                StringBuilder ww = new StringBuilder(w);
                for (int k = 0; k < ww.length(); k++)
                    if (k == 0 || ww.charAt(k - 1) == '-') ww.setCharAt(k, Character.toUpperCase(ww.charAt(k)));
                b.append(ww);
            }
            return b.toString();
        }

        static double kmBetween(double la1, double lo1, double la2, double lo2) {
            double r = Math.PI / 180, dla = (la2 - la1) * r, dlo = (lo2 - lo1) * r;
            double a = Math.sin(dla / 2) * Math.sin(dla / 2)
                    + Math.cos(la1 * r) * Math.cos(la2 * r) * Math.sin(dlo / 2) * Math.sin(dlo / 2);
            return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        }

        /** Nearest airport's latest METAR (aviationweather.gov): real "raining or not", independent of forecasts. */
        void fetchMetar(double lat, double lon) throws Exception {
            for (double d : new double[]{0.6, 1.2}) {
                String url = String.format(Locale.US,
                        "https://aviationweather.gov/api/data/metar?format=json&hours=3&bbox=%.2f,%.2f,%.2f,%.2f",
                        lat - d, lon - d, lat + d, lon + d);
                String body = httpGet(url);
                if (body == null || body.trim().isEmpty()) continue;
                org.json.JSONArray a = new org.json.JSONArray(body);
                org.json.JSONObject best = null;
                double bestKm = 1e9;
                for (int i = 0; i < a.length(); i++) {
                    org.json.JSONObject m = a.getJSONObject(i);
                    double km = kmBetween(lat, lon, m.optDouble("lat", 99), m.optDouble("lon", 999));
                    // nearest station; for the same station (several reports in 3 h) the newest
                    if (km < bestKm - 0.5 || (Math.abs(km - bestKm) <= 0.5 && best != null
                            && m.optLong("obsTime", 0) > best.optLong("obsTime", 0))) { bestKm = Math.min(km, bestKm); best = m; }
                }
                if (best == null) continue;
                SkyView.obsWx = best.isNull("wxString") ? "" : best.optString("wxString", "");
                SkyView.obsStation = best.optString("icaoId", "?");
                SkyView.obsKm = (float) bestKm;
                SkyView.obsTemp = best.isNull("temp") ? Float.NaN : (float) best.optDouble("temp", Double.NaN);
                long t = best.optLong("obsTime", 0);
                SkyView.obsAt = t > 0 ? t * 1000L : System.currentTimeMillis();
                logOnce("metar-" + SkyView.obsStation + SkyView.obsWx, "airport " + SkyView.obsStation + " "
                        + Math.round(bestKm) + " km: '" + SkyView.obsWx + "'");
                try { refreshWeatherIcon(); } catch (Throwable ignored) { }
                return;
            }
        }

        /** Worker thread: feels-like, humidity, wind, UV, rain chance (next 3 h) and AQI. */
        void fetchWeatherDetails() {
            checkRefresh(System.currentTimeMillis());
            try {
                android.location.Location gl = TowerDb.myLocation(ctx);
                double[] gp = gl != null ? new double[]{gl.getLatitude(), gl.getLongitude()} : wxLatLon;
                GeoCircle.tick(ctx, gp[0], gp[1]);
            } catch (Throwable ignored) { }
            if (Cfg.b(Cfg.HAZARDS, Cfg.D_HAZARDS)) {
                try {
                    android.location.Location hl = TowerDb.myLocation(ctx);
                    double[] hp = hl != null ? new double[]{hl.getLatitude(), hl.getLongitude()} : wxLatLon;
                    Hazards.tick(u -> httpGet(u), hp[0], hp[1], System.currentTimeMillis());
                } catch (Throwable t) {
                    logOnce("haz", "disasters / Kp failed (will retry): " + t);
                }
            }
            if (!Cfg.b(Cfg.WX_DETAILS, Cfg.D_WX_DETAILS)) return;
            long now = System.currentTimeMillis();
            if (now - wxFetchedAt < 15 * 60_000L || now - wxTriedAt < 2 * 60_000L) return;
            wxTriedAt = now;
            try {
                android.location.Location l0 = TowerDb.myLocation(ctx);
                if (l0 != null) wxLatLon = new double[]{l0.getLatitude(), l0.getLongitude()};
                else { double[] k = TowerDb.lastKnown(ctx); if (k != null) { wxLatLon = new double[]{k[0], k[1]}; wxHaveFix = true; } }
                fetchMetar(wxLatLon[0], wxLatLon[1]);
            } catch (Throwable t) {
                logOnce("metar", "airport weather failed (will retry): " + t);
            }
            try {
                android.location.Location l = TowerDb.myLocation(ctx);
                if (l != null) wxLatLon = new double[]{l.getLatitude(), l.getLongitude()};
                else { double[] k = TowerDb.lastKnown(ctx); if (k != null) { wxLatLon = new double[]{k[0], k[1]}; wxHaveFix = true; } }
                wxHaveFix = wxHaveFix || l != null;
                String ll = String.format(Locale.US, "latitude=%.4f&longitude=%.4f", wxLatLon[0], wxLatLon[1]);
                {   // the map and soil lookups can take a minute: own thread, so the weather never waits for them
                    final double wla = wxLatLon[0], wlo = wxLatLon[1];
                    EarthLine.background("river", () -> {
                        try { if (Cfg.b(Cfg.RIVER, Cfg.D_RIVER)) RiverFlow.tick(u -> httpGet(u), wla, wlo); } catch (Throwable ignored) { }
                    });
                    if (Cfg.b(Cfg.EL_HABITAT, Cfg.D_EL_HABITAT)) EarthLine.background("habitat", () -> {
                        try { Habitat.tick(ctx, wla, wlo); } catch (Throwable ignored) { }
                    });
                    EarthLine.background("earth", () -> {
                        try {
                            EarthLine.tick(u -> httpGet(u), wla, wlo, Cfg.b(Cfg.EL_RAIN, Cfg.D_EL_RAIN), Cfg.b(Cfg.EL_LAND, Cfg.D_EL_LAND),
                                    Cfg.b(Cfg.EL_QUAKE, Cfg.D_EL_QUAKE), Cfg.b(Cfg.EL_EARTH, Cfg.D_EL_EARTH), Cfg.b(Cfg.RIVER, Cfg.D_RIVER),
                                    Cfg.b(Cfg.EL_SOIL, Cfg.D_EL_SOIL), Cfg.b(Cfg.EL_POWER, Cfg.D_EL_POWER));
                        } catch (Throwable ignored) { }
                    });
                }
                try {   // which place the weather is for, by the very coordinates it is fetched with
                    String wn = TowerArea.nameAt(ctx, wxLatLon[0], wxLatLon[1]);
                    String src = l != null ? "your phone's live location" : wxHaveFix ? "your last known location (older than 10 min)" : "built-in default (no location yet)";
                    TowerArea.WX_WHERE = String.format(Locale.US, "%.4f, %.4f from %s -> %s", wxLatLon[0], wxLatLon[1], src, wn == null ? "no name yet" : wn);
                    if (wn != null) TowerArea.WX_NAME = wn + (l == null && !wxHaveFix ? " (default)" : "");
                } catch (Throwable ignored) { }
                org.json.JSONObject f = new org.json.JSONObject(httpGet("https://api.open-meteo.com/v1/forecast?" + ll
                        + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,wind_direction_10m,uv_index,visibility,cloud_cover"
                        + ",weather_code,wind_gusts_10m,dew_point_2m,cloud_cover_low,cloud_cover_mid,cloud_cover_high,shortwave_radiation"
                        + "&hourly=precipitation_probability,cape,wind_direction_850hPa,wind_direction_700hPa,wind_direction_500hPa&forecast_hours=3"
                        + "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto"));
                try {
                    org.json.JSONObject dly = f.getJSONObject("daily");
                    wxMax = (float) dly.getJSONArray("temperature_2m_max").getDouble(0);
                    wxMin = (float) dly.getJSONArray("temperature_2m_min").getDouble(0);
                } catch (Throwable ignored) { }
                // ground height at your position (Open-Meteo's terrain model): altitude when there's no GPS fix
                if (f.has("elevation") && !f.isNull("elevation")) wxElev = (float) f.optDouble("elevation", Double.NaN);
                org.json.JSONObject cur = f.getJSONObject("current");
                wxFeels = (float) cur.optDouble("apparent_temperature", Double.NaN);
                if (cur.has("temperature_2m") && !cur.isNull("temperature_2m")) {
                    omTemp = String.valueOf(Math.round(cur.optDouble("temperature_2m")));
                    omTempAt = now;
                }
                wxHum = cur.has("relative_humidity_2m") ? (int) Math.round(cur.optDouble("relative_humidity_2m")) : -1;
                wxWind = (float) cur.optDouble("wind_speed_10m", Double.NaN);
                wxWindDir = cur.has("wind_direction_10m") ? (int) Math.round(cur.optDouble("wind_direction_10m")) : -1;
                wxUv = (float) cur.optDouble("uv_index", Double.NaN);
                wxVis = (float) cur.optDouble("visibility", Double.NaN);          // metres
                wxCloud = cur.has("cloud_cover") && !cur.isNull("cloud_cover") ? (int) Math.round(cur.optDouble("cloud_cover")) : -1;
                SkyStrip.cloud = wxCloud;
                try { SkyView.rad = cur.has("shortwave_radiation") && !cur.isNull("shortwave_radiation") ? (float) cur.optDouble("shortwave_radiation", -1) : -1f; SkyView.radAt = System.currentTimeMillis(); } catch (Throwable ignored) { }
                // the sky background's second opinion: WMO code, wind, cloud cover, visibility
                SkyView.wmo = cur.has("weather_code") && !cur.isNull("weather_code") ? cur.optInt("weather_code", -1) : -1;
                float gust = (float) cur.optDouble("wind_gusts_10m", Double.NaN) * 0.7f;
                SkyView.windKmh = Float.isNaN(gust) ? wxWind : Float.isNaN(wxWind) ? gust : Math.max(wxWind, gust);
                SkyView.cloudPct = wxCloud;
                SkyView.visM = wxVis;
                SkyView.wmoAt = now;
                try { WxSky.update(cur, f.getJSONObject("hourly")); } catch (Throwable ignored) { }
                org.json.JSONArray pp = f.getJSONObject("hourly").optJSONArray("precipitation_probability");
                int rain = -1;
                if (pp != null) for (int i = 0; i < pp.length(); i++) if (!pp.isNull(i)) rain = Math.max(rain, pp.getInt(i));
                wxRain = rain;
                try {
                    org.json.JSONObject a = new org.json.JSONObject(httpGet(
                            "https://air-quality-api.open-meteo.com/v1/air-quality?" + ll + "&current=us_aqi"));
                    org.json.JSONObject ac = a.getJSONObject("current");
                    wxAqi = ac.isNull("us_aqi") ? -1 : (int) Math.round(ac.getDouble("us_aqi"));
                    SkyView.aqi = wxAqi;
                } catch (Throwable t) {
                    logOnce("wxaqi", "air quality not available: " + t);
                }
                try {   // sea-level pressure now and 3 h ago (trend)
                    org.json.JSONObject pr = new org.json.JSONObject(httpGet("https://api.open-meteo.com/v1/forecast?" + ll
                            + "&hourly=pressure_msl&past_hours=3&forecast_hours=1&timezone=auto"));
                    org.json.JSONArray pa = pr.getJSONObject("hourly").getJSONArray("pressure_msl");
                    if (pa.length() >= 2 && !pa.isNull(0) && !pa.isNull(pa.length() - 1)) {
                        wxPres = (float) pa.getDouble(pa.length() - 1);
                        wxPresTrend = wxPres - (float) pa.getDouble(0);
                        float[] ser = new float[pa.length()];
                        for (int i = 0; i < ser.length; i++) ser[i] = pa.isNull(i) ? (i > 0 ? ser[i - 1] : wxPres) : (float) pa.getDouble(i);
                        presSeries = ser;
                    }
                } catch (Throwable t) {
                    logOnce("wxpres", "pressure not available: " + t);
                }
                try {   // UV index by hour for today (the day curve)
                    org.json.JSONObject ur = new org.json.JSONObject(httpGet("https://api.open-meteo.com/v1/forecast?" + ll
                            + "&hourly=uv_index&forecast_days=1&timezone=auto"));
                    org.json.JSONArray ua = ur.getJSONObject("hourly").getJSONArray("uv_index");
                    if (ua.length() >= 24) {
                        float[] us = new float[24];
                        for (int i = 0; i < 24; i++) us[i] = ua.isNull(i) ? 0f : (float) ua.getDouble(i);
                        uvSeries = us;
                    }
                } catch (Throwable t) {
                    logOnce("wxuv", "uv curve not available: " + t);
                }
                wxFetchedAt = now;
                try { fetchTide(wxLatLon[0], wxLatLon[1], now); } catch (Throwable ignored) { }
                try { fetchWetland(wxLatLon[0], wxLatLon[1], now); } catch (Throwable ignored) { }
                try { fetchDam(wxLatLon[0], wxLatLon[1], now); } catch (Throwable ignored) { }
                try { refreshWeatherIcon(); } catch (Throwable ignored) { }
                String lg = "feels " + wxFeels + " hum " + wxHum + " wind " + wxWind + "@" + wxWindDir + " uv " + wxUv
                        + " rain " + wxRain + "% aqi " + wxAqi;
                if (!lg.equals(wxLog)) {
                    wxLog = lg;
                    XposedBridge.log(TAG + "weather details: " + lg);
                }
            } catch (Throwable t) {
                logOnce("wxfail", "weather details failed (will retry): " + t);
            }
            try {
                fetchWeatherApi();
            } catch (Throwable t) {
                logOnce("wapifail" + t.getClass().getSimpleName(), "WeatherAPI failed (will retry): " + t.getClass().getSimpleName()
                        + (t.getMessage() != null && !t.getMessage().contains("key=") ? " " + t.getMessage() : ""));
            }
        }

        /** Nearest CWC dam and how full it is, for the chip on row 3 (every 3 h, or when another dam becomes the nearest). */
        void fetchDam(double lat, double lon, long now) {
            if (!Cfg.b(Cfg.DAM, Cfg.D_DAM) || !Dam.due(lat, lon, now)) return;
            if (!Dam.refresh(ctx.getCacheDir(), lat, lon, now)) {
                logOnce("dam", "dam not available (will retry): " + Dam.lastError);
            } else {
                XposedBridge.log(TAG + "dam: " + Dam.cur.name + " " + Math.round(Dam.curRead.pct) + "%");
            }
        }

        /** Nearest wetland and Ramsar site (OpenStreetMap), every 12 h or after moving 5 km. Runs on its own thread: Overpass can take minutes. */
        void fetchWetland(final double lat, final double lon, final long now) {
            if (!Cfg.b(Cfg.EL_WETLAND, Cfg.D_EL_WETLAND) || Wetland.running || !Wetland.due(lat, lon, now)) return;
            Wetland.triedAt = now;
            Wetland.running = true;
            new Thread(() -> {
                try {
                    Wetland.lookup(lat, lon, System.currentTimeMillis());
                    XposedBridge.log(TAG + "wetland: " + Wetland.why);
                } catch (Throwable t) {
                    Wetland.why = "failed: " + t;
                    logOnce("wetland", "wetland not available (will retry): " + t);
                } finally {
                    Wetland.running = false;
                }
            }, "wetland-lookup").start();
        }

        /** Sea level at the nearest coast for the tide on row 3 (every 6 h, or when the nearest coast changes). */
        void fetchTide(final double lat, final double lon, final long now) {
            checkRefresh(now);
            if (Cfg.b(Cfg.EARTH_SLOT, Cfg.D_EARTH_SLOT) && EarthSci.due(now)) {
                EarthSci.triedAt = now;
                new Thread(() -> {
                    try { EarthSci.parse(EarthSci.get(EarthSci.URL_IDX), EarthSci.get(EarthSci.URL_ONI), System.currentTimeMillis()); }
                    catch (Throwable t) { EarthSci.why = "failed: " + t; }
                }, "enso-fetch").start();
            }
            if (Cfg.b(Cfg.TIDE, Cfg.D_TIDE) && Tide.beachPt(lat, lon) == null && !Tide.beachRunning && now - Tide.beachTriedAt > 10 * 60_000L) {
                Tide.beachTriedAt = now;
                Tide.beachRunning = true;
                new Thread(() -> {
                    try {
                        java.util.List<Beach.Hit> hs = BeachList.nearest(lat, lon, 1);   // built in, no internet needed
                        if (!hs.isEmpty()) {
                            Tide.beach = new double[]{hs.get(0).lat, hs.get(0).lon, lat, lon};
                            Tide.beachName = hs.get(0).name;
                            Tide.beachAt = System.currentTimeMillis();
                            Tide.beachWhy = "ok (built-in list): " + hs.get(0).name + " " + Math.round(hs.get(0).km) + " km";
                            XposedBridge.log(TAG + "nearest beach: " + Tide.beachWhy);
                        } else Tide.beachWhy = "no named beach within about 210 km";
                    } catch (Throwable t) {
                        Tide.beachWhy = "failed: " + t;
                        logOnce("beach", "nearest beach not available (will retry): " + t);
                    } finally {
                        Tide.beachRunning = false;
                    }
                }, "beach-lookup").start();
            }
            if (!Cfg.b(Cfg.TIDE, Cfg.D_TIDE) || !Tide.due(lat, lon, now)) return;
            Tide.triedAt = now;
            try {
                Tide.parse(httpGet(Tide.url(lat, lon)), lat, lon, now);
                XposedBridge.log(TAG + "tide: " + Tide.place + ", " + Tide.times.length + " hours");
            } catch (Throwable t) {
                logOnce("tide", "tide not available (will retry): " + t);
            }
        }

        volatile String omTemp = null;           // Open-Meteo's own temperature: used when there's no weather app
        volatile long omTempAt = 0;
        volatile String wapiTemp = null;
        volatile long wapiTempAt = 0;

        /** WeatherAPI.com current + today, when the user has set a key; overrides the forecast values it has. */
        void fetchWeatherApi() throws Exception {
            String key = Cfg.s(Cfg.WAPI_KEY);
            if (key.isEmpty()) { wapiTemp = null; SkyView.wapiKind = -1; return; }
            String url = String.format(Locale.US, "https://api.weatherapi.com/v1/forecast.json?key=%s&q=%.4f,%.4f&days=1&aqi=no&alerts=no",
                    java.net.URLEncoder.encode(key, "UTF-8"), wxLatLon[0], wxLatLon[1]);
            org.json.JSONObject j;
            try {
                j = new org.json.JSONObject(httpGet(url));
            } catch (java.io.IOException e) {
                throw new java.io.IOException(e.getMessage() != null && e.getMessage().startsWith("HTTP") ? e.getMessage() : "network");
            }
            org.json.JSONObject cur = j.getJSONObject("current");
            double precip = cur.optDouble("precip_mm", 0);
            int code = cur.optJSONObject("condition") != null ? cur.getJSONObject("condition").optInt("code", -1) : -1;
            long now = System.currentTimeMillis();
            wapiTemp = String.valueOf(Math.round(cur.getDouble("temp_c")));
            wapiTempAt = now;
            wxFeels = (float) cur.optDouble("feelslike_c", wxFeels);
            wxHum = cur.has("humidity") ? cur.optInt("humidity", wxHum) : wxHum;
            wxWind = (float) cur.optDouble("wind_kph", wxWind);
            wxWindDir = cur.has("wind_degree") ? cur.optInt("wind_degree", wxWindDir) : wxWindDir;
            wxUv = (float) cur.optDouble("uv", wxUv);
            if (cur.has("vis_km")) wxVis = (float) (cur.optDouble("vis_km") * 1000);
            if (cur.has("cloud")) wxCloud = cur.optInt("cloud", wxCloud);
            SkyView.cloudPct = wxCloud;
            SkyView.visM = wxVis;
            float gust = (float) cur.optDouble("gust_kph", Double.NaN) * 0.7f;
            SkyView.windKmh = Float.isNaN(gust) ? wxWind : Math.max(wxWind, gust);
            int kind = SkyView.fromWapi(code, precip);
            SkyView.wapiKind = kind;
            SkyView.wapiCode = code;
            SkyView.wapiPrecip = precip;
            SkyView.wapiAt = now;
            try {
                org.json.JSONObject day0 = j.getJSONObject("forecast").getJSONArray("forecastday").getJSONObject(0);
                org.json.JSONObject dd = day0.getJSONObject("day");
                wxMax = (float) dd.getDouble("maxtemp_c");
                wxMin = (float) dd.getDouble("mintemp_c");
                // rain chance: the next 3 hours
                org.json.JSONArray hrs = day0.getJSONArray("hour");
                long sNow = now / 1000L;
                int rain = -1;
                for (int i = 0; i < hrs.length(); i++) {
                    org.json.JSONObject h = hrs.getJSONObject(i);
                    long te = h.optLong("time_epoch", 0);
                    if (te + 3600 > sNow && te < sNow + 3 * 3600) rain = Math.max(rain, h.optInt("chance_of_rain", -1));
                }
                if (rain >= 0) wxRain = rain;
            } catch (Throwable ignored) { }
            try { refreshWeatherIcon(); } catch (Throwable ignored) { }
            logOnce("wapi-" + code + "-" + wapiTemp, "WeatherAPI: " + wapiTemp + "°, code " + code + " precip " + precip
                    + " -> " + kind + ", rain next 3 h " + wxRain + "%");
        }

        /** Per SIM: tower key and since when; recent dBm samples {time, dBm}. */
        private static final java.util.Map<Integer, Object[]> CELL_SINCE = new java.util.HashMap<>();
        private static final java.util.Map<Integer, java.util.ArrayDeque<long[]>> DBM_HIST = new java.util.HashMap<>();

        /** How long on this tower ("3m"), and the signal change over the last minute ("+4dB"), when it moved 3 dB or more. */
        static void cellTimeAndTrend(ReadableBar.SimInfo si, Bands.Sim s) {
            long now = System.currentTimeMillis();
            String key = Bands.lastKey(s.sub);
            if (key == null || !s.inService) { CELL_SINCE.remove(s.slot); DBM_HIST.remove(s.slot); return; }
            Object[] cs = CELL_SINCE.get(s.slot);
            if (cs == null || !key.equals(cs[0])) {
                if (cs != null) ReadableBar.handover(now, s.slot);          // tower switch counted for today
                cs = new Object[]{key, now};
                CELL_SINCE.put(s.slot, cs);
                DBM_HIST.remove(s.slot);                       // new tower: its own trend
            }
            long sec = (now - (Long) cs[1]) / 1000L;
            si.onCell = sec < 60 ? sec + "s" : sec < 3600 ? (sec / 60) + "m"
                    : (sec / 3600) + "h" + String.format(Locale.US, "%02d", (sec % 3600) / 60);
            Object[] tr = TREND.get(s.slot);
            if (tr != null && !key.equals(tr[2])) { TREND.remove(s.slot); tr = null; }
            if (tr != null) si.trend = (String) tr[0];             // keep what's shown unless replaced below
            if (s.dbm == Bands.NO_DBM) return;
            java.util.ArrayDeque<long[]> h = DBM_HIST.computeIfAbsent(s.slot, x -> new java.util.ArrayDeque<>());
            h.addLast(new long[]{now, s.dbm});
            while (!h.isEmpty() && now - h.peekFirst()[0] > 90_000L) h.removeFirst();
            long[] old = null;
            for (long[] e : h) if (now - e[0] >= 45_000L) old = e;    // newest sample at least 45 s old
            if (old == null) return;
            // compare the last ~10 s average with a minute ago
            long sum = 0; int n = 0;
            for (long[] e : h) if (now - e[0] <= 10_000L) { sum += e[1]; n++; }
            if (n == 0) return;
            int d = Math.round((float) sum / n - old[1]);
            // steady display: appears at a 4 dB change, then stays at least 30 s and while the change is still 2 dB+
            String txt = (d > 0 ? "+" : "−") + Math.abs(d) + "dB";
            if (Math.abs(d) >= 4) {
                TREND.put(s.slot, new Object[]{txt, now, key});
                si.trend = txt;
            } else if (tr != null) {
                boolean young = now - (Long) tr[1] < 30_000L;
                if (Math.abs(d) >= 2) { tr[0] = txt; si.trend = txt; }
                else if (young) si.trend = (String) tr[0];
                else { TREND.remove(s.slot); si.trend = null; }
            }
        }

        /** Per SIM: {shown trend text, since, tower key}. */
        private static final java.util.Map<Integer, Object[]> TREND = new java.util.HashMap<>();

        static String windArrow(int fromDeg) {
            // Arrow points where the wind blows TO.
            String[] a = {"\u2193", "\u2199", "\u2190", "\u2196", "\u2191", "\u2197", "\u2192", "\u2198"};
            return a[(int) Math.round(((fromDeg % 360) + 360) % 360 / 45.0) % 8];
        }

        /** One weather item: small grey label (optional) + coloured value + small unit. */
        android.text.SpannableStringBuilder wxItem(String label, String value, String unit, int valueColor, int color) {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            int grey = (color & 0x00FFFFFF) | 0x99000000;
            if (label != null) {
                sb.append(label);
                sb.setSpan(new android.text.style.RelativeSizeSpan(0.7f), 0, sb.length(), 0);
                sb.setSpan(new android.text.style.ForegroundColorSpan(grey), 0, sb.length(), 0);
            }
            int st = sb.length();
            sb.append(value);
            sb.setSpan(new android.text.style.ForegroundColorSpan(valueColor), st, sb.length(), 0);
            if (unit != null) {
                st = sb.length();
                sb.append(unit);
                sb.setSpan(new android.text.style.RelativeSizeSpan(0.7f), st, sb.length(), 0);
                sb.setSpan(new android.text.style.ForegroundColorSpan(grey), st, sb.length(), 0);
            }
            return sb;
        }

        String wxShownLog = "";
        int wxColor = 0;

        void updateWeatherDetails(int color) {
            LinearLayout box = (LinearLayout) bar.findViewWithTag("dual_weather");
            ViewGroup row2 = (ViewGroup) bar.findViewWithTag("dual_row2");
            if (box == null || row2 == null) return;
            Resources res = bar.getResources();
            TextView tv = (TextView) box.findViewWithTag("dual_wx");
            if (tv == null) {
                tv = new TextView(ctx);
                tv.setTag("dual_wx");
                tv.setIncludeFontPadding(false);
                tv.setLineSpacing(0, 0.85f);
                tv.setMaxLines(2);
                tv.setHorizontallyScrolling(true);
                tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.CENTER_VERTICAL;
                lp.setMarginStart(dp(res, 3));
                box.addView(tv, lp);
            }
            if (!Cfg.b(Cfg.WX_DETAILS, Cfg.D_WX_DETAILS) || extras.weatherTemp.getVisibility() != View.VISIBLE) {
                if (tv.getVisibility() != View.GONE) tv.setVisibility(View.GONE);
                return;
            }
            Hook.setSp(tv, Cfg.i(Cfg.TEMP_SP, Cfg.D_TEMP_SP));
            Hook.matchClockFont(clock, tv, Cfg.b(Cfg.BOLD, Cfg.D_BOLD));
            tv.setLetterSpacing(-0.03f);
            tv.setTextColor(color);
            boolean dk = darkBar(color);

            // {line, priority, text}; line 1: feels / rain / UV, line 2: humidity / wind / AQI
            List<Object[]> items = new ArrayList<>();
            if (wxRain >= 0) {
                int c = wxRain >= 50 ? (dk ? 0xFF0A84FF : 0xFF0A60C0) : wxRain >= 20 ? (dk ? 0xFF64D2FF : 0xFF0086A8) : color;
                items.add(new Object[]{1, 100, wxItem("\u2614", String.valueOf(wxRain), "%", c, color)});
            }
            if (!Float.isNaN(wxUv)) {
                float u = wxUv;
                int c = u < 3 ? (dk ? 0xFF34C759 : 0xFF1E8E3E) : u < 6 ? (dk ? 0xFFFFCC00 : 0xFFB38600)
                        : u < 8 ? (dk ? 0xFFFF9F0A : 0xFFC46A00) : u < 11 ? (dk ? 0xFFFF453A : 0xFFC4281C) : (dk ? 0xFFBF5AF2 : 0xFF8E44AD);
                items.add(new Object[]{1, 40, wxItem("UV", String.valueOf(Math.round(u)), null, c, color)});
            }
            if (wxHum >= 0) items.add(new Object[]{2, 60, wxItem("\uD83D\uDCA7", String.valueOf(wxHum), "%", color, color)});
            if (!Float.isNaN(wxWind)) {
                String arrow = wxWindDir >= 0 ? windArrow(wxWindDir) : "";
                items.add(new Object[]{2, 50, wxItem(null, arrow + Math.round(wxWind), "km/h", color, color)});
            }
            if (wxAqi >= 0) {
                int a = wxAqi;
                int c = a <= 50 ? (dk ? 0xFF34C759 : 0xFF1E8E3E) : a <= 100 ? (dk ? 0xFFFFCC00 : 0xFFB38600)
                        : a <= 150 ? (dk ? 0xFFFF9F0A : 0xFFC46A00) : a <= 200 ? (dk ? 0xFFFF453A : 0xFFC4281C) : (dk ? 0xFFBF5AF2 : 0xFF8E44AD);
                items.add(new Object[]{2, 90, wxItem("AQI", String.valueOf(a), null, c, color)});
            }
            // Room: the row minus the system icons, the icon + temperature and what the notifications need.
            int room = row2.getWidth() - row2.getPaddingLeft() - row2.getPaddingRight() - visibleWidth(statusIcons)
                    - Math.min(notifNeeded(), row2.getWidth() * 2 / 5)
                    - visibleWidth(bar.findViewWithTag("dual_wx_col")) - dp(res, 3 + 8)
                    - dp(res, Cfg.i(Cfg.WEATHER_GAP, Cfg.D_WEATHER_GAP));
            android.text.SpannableStringBuilder sb = null;
            while (!items.isEmpty()) {
                sb = new android.text.SpannableStringBuilder();
                for (int line = 1; line <= 2; line++) {
                    boolean first = true;
                    if (line == 2 && sb.length() > 0) sb.append('\n');
                    for (Object[] it : items) {
                        if ((Integer) it[0] != line) continue;
                        if (!first) sb.append("\u2009\u2009");
                        sb.append((CharSequence) it[2]);
                        first = false;
                    }
                }
                if (row2.getWidth() > 0 && spannedWidth(tv, sb) + 1 <= room) break;
                // drop the least important item and try again
                Object[] low = items.get(0);
                for (Object[] it : items) if ((Integer) it[1] < (Integer) low[1]) low = it;
                items.remove(low);
                sb = null;
            }
            String lg = sb == null ? "none" : sb.toString().replace('\n', '/');
            if (!lg.equals(wxShownLog)) {
                wxShownLog = lg;
                XposedBridge.log(TAG + "weather details shown: " + lg + " (room " + room + "px)");
            }
            if (sb == null || sb.length() == 0) {
                if (tv.getVisibility() != View.GONE) tv.setVisibility(View.GONE);
                return;
            }
            if (!sb.toString().equals(String.valueOf(tv.getText())) || wxColor != color) {
                tv.setText(sb);
                wxColor = color;
            }
            fitWidthSpanned(tv, sb);
            if (tv.getVisibility() != View.VISIBLE) tv.setVisibility(View.VISIBLE);
        }

        /** Weather icon + temperature on one small line, today's high / low beneath. */
        void compactWeather(int color) {
            LinearLayout box = (LinearLayout) bar.findViewWithTag("dual_weather");
            if (box == null) return;
            Resources res = bar.getResources();
            LinearLayout col = (LinearLayout) box.findViewWithTag("dual_wx_col");
            TextView hl;
            if (col == null) {
                col = new LinearLayout(ctx);
                col.setTag("dual_wx_col");
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                LinearLayout top = new LinearLayout(ctx);
                top.setOrientation(LinearLayout.HORIZONTAL);
                top.setGravity(Gravity.CENTER_VERTICAL);
                detach(extras.weatherIcon);
                detach(extras.weatherTemp);
                top.addView(extras.weatherIcon, new LinearLayout.LayoutParams(dp(res, 10), dp(res, 10)));
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.setMarginStart(dp(res, 1));
                top.addView(extras.weatherTemp, tlp);
                col.addView(top, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                hl = new TextView(ctx);
                hl.setTag("dual_wx_hl");
                hl.setIncludeFontPadding(false);
                hl.setMaxLines(1);
                hl.setHorizontallyScrolling(true);
                col.addView(hl, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.gravity = Gravity.CENTER_VERTICAL;
                box.addView(col, 0, clp);
                extras.weatherTemp.setIncludeFontPadding(false);
                XposedBridge.log(TAG + "weather made compact with high / low beneath");
            }
            hl = (TextView) col.findViewWithTag("dual_wx_hl");
            // icon follows the temperature's size
            int ic = Math.round(extras.weatherTemp.getTextSize() * 1.05f);
            ViewGroup.LayoutParams ilp = extras.weatherIcon.getLayoutParams();
            if (ilp != null && (ilp.width != ic || ilp.height != ic)) {
                ilp.width = ic;
                ilp.height = ic;
                extras.weatherIcon.setLayoutParams(ilp);
            }
            Hook.setSp(hl, Math.max(5, Cfg.i(Cfg.WEATHER_SP, Cfg.D_WEATHER_SP) - 2));
            Hook.matchClockFont(clock, hl, Cfg.b(Cfg.BOLD, Cfg.D_BOLD));
            hl.setLetterSpacing(-0.03f);
            if (Float.isNaN(wxMax) || Float.isNaN(wxMin) || extras.weatherTemp.getVisibility() != View.VISIBLE) {
                if (hl.getVisibility() != View.GONE) hl.setVisibility(View.GONE);
                return;
            }
            boolean dk = darkBar(color);
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            sb.append("\u2191").append(String.valueOf(Math.round(wxMax))).append('\u00B0');
            sb.setSpan(new android.text.style.ForegroundColorSpan(dk ? 0xFFFF9F0A : 0xFFC46A00), 0, sb.length(), 0);
            sb.append('\u2009');
            int st = sb.length();
            sb.append("\u2193").append(String.valueOf(Math.round(wxMin))).append('\u00B0');
            sb.setSpan(new android.text.style.ForegroundColorSpan(dk ? 0xFF64D2FF : 0xFF0086A8), st, sb.length(), 0);
            if (!sb.toString().equals(String.valueOf(hl.getText())) || hlColor != color) {
                hl.setText(sb);
                hlColor = color;
            }
            if (hl.getVisibility() != View.VISIBLE) hl.setVisibility(View.VISIBLE);
        }

        int hlColor = 0;

        void updateWeather(int color) {
            // Applied here so it works in every layout (combined temperatures included).
            Hook.setSp(extras.weatherTemp, Cfg.i(Cfg.WEATHER_SP, Cfg.D_WEATHER_SP));
            String wt = weatherTemp;
            Drawable wi = weatherIcon;
            if (Cfg.b(Cfg.SHOW_WEATHER, Cfg.D_SHOW_WEATHER) && wt != null) {
                extras.weatherTemp.setTextColor(color);
                Hook.matchClockFont(clock, extras.weatherTemp, false);
                extras.weatherTemp.setText(wt + "°");
                extras.weatherTemp.setVisibility(View.VISIBLE);
            } else {
                extras.weatherTemp.setVisibility(View.GONE);
            }
            if (Cfg.b(Cfg.SHOW_WEATHER, Cfg.D_SHOW_WEATHER) && wt != null && wi != null) {
                if (extras.weatherIcon.getDrawable() != wi) extras.weatherIcon.setImageDrawable(wi);
                if (tintWeatherIcon) extras.weatherIcon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
                else extras.weatherIcon.clearColorFilter();
                extras.weatherIcon.setVisibility(View.VISIBLE);
            } else {
                extras.weatherIcon.setVisibility(View.GONE);
            }
            compactWeather(color);
            updateWeatherDetails(color);
        }

        // ---- Worker thread: temperatures
        final Runnable tempTick = new Runnable() {
            @Override
            public void run() {
                if (stopped) return;
                if (!ScreenState.on()) { worker.postDelayed(this, TEMP_INTERVAL_MS); return; }
                if (Cfg.b(Cfg.SHOW_CPU, Cfg.D_SHOW_CPU)) cpuTemp = readCpuTemp(ctx);
                gpuTemp = readGpuTemp(ctx);
                cpuGhz = readCpuGhz();
                try {
                    android.app.ActivityManager am = ctx.getSystemService(android.app.ActivityManager.class);
                    android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    ramFree = mi.availMem;
                    ramTotal = mi.totalMem;
                } catch (Throwable ignored) { }
                try {
                    android.location.Location fl = TowerDb.myLocation(ctx);
                    if (fl != null) lastFix = new double[]{fl.getLatitude(), fl.getLongitude(),
                            fl.hasAltitude() ? fl.getAltitude() : Double.NaN, fl.hasAccuracy() ? fl.getAccuracy() : Double.NaN};
                } catch (Throwable ignored) { }
                fetchQuake();
                try {
                    double[] me = lastFix != null ? lastFix : lastLatLon;
                    if (Sat.cacheDir == null) Sat.cacheDir = ctx.getCacheDir();
                    try { RideGps.tick(ctx); } catch (Throwable t) { logOnce("ridegps", "ride GPS failed: " + t); }
                    Sat.update(me[0], me[1]);
                    SkyStrip.tick(ctx, me[0], me[1]);
                    // publish the live list for the NetSpeed app's "Satellites now" (every 30 s)
                    long sn = android.os.SystemClock.elapsedRealtime();
                    if (sn - satPubAt > 30_000L) {
                        satPubAt = sn;
                        try {
                            String rep = SkyView.diag(me[0], me[1]) + "  " + ReadableBar.skyDbg + "\n\n" + Hazards.report() + "\nBAND LOCK\n  " + String.valueOf(android.provider.Settings.Global.getString(ctx.getContentResolver(), "netspeed_band_status")).trim().replace("\n", "\n  ") + "\n\n" + TowerArea.report + "\n" + CellDetail.caReport() + "\n" + (QtiNr.report.isEmpty() ? "" : QtiNr.report + "\n")
                                    + "SATELLITES\n" + (Sat.report.isEmpty() ? "  not computed yet\n" : Sat.report) + "\n" + SkyStrip.report();
                            rep = rep + "\nMAHARASHTRA DAM SOURCE\n  " + DamMh.why + "\n";
                            android.provider.Settings.Global.putString(ctx.getContentResolver(), "netspeed_sat_report", rep);
                            try { android.provider.Settings.Global.putString(ctx.getContentResolver(), "netspeed_info", Info.feed(me[0], me[1], System.currentTimeMillis())
                                    + "T|fix|" + me[0] + "," + me[1] + "|" + System.currentTimeMillis() + "\n"); } catch (Throwable ignored) { }
                            if (EarthLine.district != null && !EarthLine.district.isEmpty())
                                android.provider.Settings.Global.putString(ctx.getContentResolver(), "netspeed_district", EarthLine.district);
                        } catch (Throwable t) {
                            logOnce("satpub", "can't publish the satellite list: " + t);
                        }
                    }
                } catch (Throwable t) {
                    logOnce("sat", "satellite update failed: " + t);
                }
                if (Cfg.b(Cfg.SHOW_BAT, Cfg.D_SHOW_BAT)) batTemp = readBatteryTemp(ctx);
                HeatWarn.check(ctx, Float.isNaN(batTemp) ? readBatteryTemp(ctx) : batTemp, cpuTemp);
                long hn = android.os.SystemClock.elapsedRealtime();
                if (hn - healthReadAt > 60_000 || healthReadAt == 0) {   // changes slowly: once a minute
                    healthReadAt = hn;
                    int[] h = readBatteryHealth(ctx);
                    batSoh = h[0];
                    batCycles = h[1];
                }
                worker.postDelayed(this, TEMP_INTERVAL_MS);
            }
        };

        // ---- Worker thread: charging current / power
        volatile List<Bands.Sim> sims = new ArrayList<>();
        String lastBandLog = "";

        final Runnable bandTick = new Runnable() {
            @Override
            public void run() {
                if (stopped) return;
                if (!ScreenState.on()) { worker.postDelayed(this, 5_000); return; }
                if (Cfg.b(Cfg.VO_BADGE, Cfg.D_VO_BADGE)) Voice.watch(ctx);
                if (Cfg.b(Cfg.NET_TYPE, Cfg.D_NET_TYPE)) DataActivity.watch(ctx);
                if (Cfg.b(Cfg.SHOW_BAND, Cfg.D_SHOW_BAND)) {
                    List<Bands.Sim> s = Bands.read(ctx);
                    sims = s;
                    try { TowerArea.refresh(ctx, s); } catch (Throwable t) { logOnce("towerarea", "tower area failed: " + t); }
                    try { SmartCarrier.update(s); } catch (Throwable t) { logOnce("smartcarrier", "smart carrier failed: " + t); }
                    try { QtiNr.tick(ctx); } catch (Throwable t) { logOnce("qtinr", "Qualcomm reader failed: " + t); }
                    try { BandLock.tick(ctx); } catch (Throwable t) { logOnce("bandlock", "band lock failed: " + t); }
                    try { NrFallback.tick(ctx, s, DataActivity.slot()); } catch (Throwable t) { logOnce("nrfallback", "5G fallback failed: " + t); }
                    StringBuilder sb = new StringBuilder("SIMs:");
                    for (Bands.Sim x : s) sb.append(" SIM").append(x.slot).append('=').append(x.band)
                                .append(x.distM < 0 ? " dist?" : (x.guess ? " sig~" : x.approx ? " db~" : " ta=") + x.distM + "m");
                    if (!sb.toString().equals(lastBandLog)) {
                        lastBandLog = sb.toString();
                        XposedBridge.log(TAG + sb);
                    }
                }
                worker.postDelayed(this, 5_000);
            }
        };

        final Runnable powerTick = new Runnable() {
            @Override
            public void run() {
                if (stopped) return;
                if (!ScreenState.on()) { worker.postDelayed(this, POWER_INTERVAL_MS); return; }
                if (Cfg.b(Cfg.SHOW_POWER, Cfg.D_SHOW_POWER)) {
                    float[] ap = readPower(ctx);
                    amps = ap[0];
                    watts = ap[1];
                }
                worker.postDelayed(this, POWER_INTERVAL_MS);
            }
        };

        // ---- Worker thread: weather fallback refresh
        final Runnable weatherTick = new Runnable() {
            @Override
            public void run() {
                if (stopped) return;
                if (!ScreenState.on()) { worker.postDelayed(this, 2 * 60_000L); return; }
                loadWeather();
                fetchWeatherDetails();
                // Short tick so a failed fetch retries soon; fetches themselves are 15 min apart.
                worker.postDelayed(this, 2 * 60_000L);
            }
        };

        private volatile String wxIconPack;
        private volatile int wxIconCode = -2;

        /** Status bar icon: the weather app's, or Open-Meteo's when the two disagree about rain. */
        void refreshWeatherIcon() {
            boolean day = true;
            try { day = Astro.sun(System.currentTimeMillis(), wxLatLon[0], wxLatLon[1]).alt > -0.8; } catch (Throwable ignored) { }
            int ic = SkyView.iconCode(day);
            if (ic == wxIconCode) return;
            wxIconCode = ic;
            weatherIcon = ic >= 0 ? loadWeatherIcon(wxIconPack, ic) : null;
            if (ic != SkyView.wx) logOnce("wx-icon-" + ic, "weather icon from Open-Meteo (code " + ic + "), weather app said " + SkyView.wx);
        }

        void loadWeather() {
            try {
                boolean enabled = true;
                String iconPack = null;
                try (Cursor s = ctx.getContentResolver().query(OMNI_SETTINGS, null, null, null, null)) {
                    if (s != null && s.moveToFirst()) {
                        int e = s.getColumnIndex("enabled");
                        if (e >= 0) enabled = s.getInt(e) != 0;
                        int ip = s.getColumnIndex("icon_pack");
                        if (ip >= 0) iconPack = s.getString(ip);
                    }
                }
                if (!enabled) {
                    logOnce("omni-off", "OmniJaws weather is turned off");
                    weatherTemp = null;
                    return;
                }
                String temp = null;
                int code = -1;
                try (Cursor c = ctx.getContentResolver().query(OMNI_WEATHER, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int ti = c.getColumnIndex("temperature");
                        int ci = c.getColumnIndex("condition_code");
                        if (ti >= 0) temp = c.getString(ti);
                        if (ci >= 0) code = c.getInt(ci);
                    }
                }
                if (temp == null || temp.isEmpty()) {
                    logOnce("omni-empty", "OmniJaws has no weather data yet");
                    weatherTemp = null;
                    return;
                }
                // Drop decimals: "28.4" -> "28".
                try {
                    temp = String.valueOf(Math.round(Float.parseFloat(temp.replace(",", "."))));
                } catch (NumberFormatException ignored) {
                    // Keep OmniJaws' own text.
                }
                SkyView.wx = code;
                wxIconPack = iconPack;
                wxIconCode = -2;
                refreshWeatherIcon();
                weatherTemp = temp;
                logOnce("omni-ok", "OmniJaws weather: " + temp + "°, code " + code
                        + ", icon pack " + iconPack + ", icon " + (weatherIcon != null));
            } catch (Throwable t) {
                logOnce("omni-err", "OmniJaws read failed: " + t);
                weatherTemp = null;
            }
        }

        /** OmniJaws icons are named "<prefix>_<code>" inside the icon pack's package. */
        Drawable loadWeatherIcon(String iconPack, int code) {
            String[][] tries = new String[4][];
            int n = 0;
            if (iconPack != null && iconPack.lastIndexOf('.') > 0) {
                int dot = iconPack.lastIndexOf('.');
                tries[n++] = new String[]{iconPack.substring(0, dot), iconPack.substring(dot + 1)};
            }
            tries[n++] = new String[]{OMNI_PKG, "outline"};
            tries[n++] = new String[]{OMNI_PKG, "google"};
            tries[n++] = new String[]{OMNI_PKG, "weather"};
            for (int i = 0; i < n; i++) {
                try {
                    Resources r = ctx.getPackageManager().getResourcesForApplication(tries[i][0]);
                    int id = r.getIdentifier(tries[i][1] + "_" + code, "drawable", tries[i][0]);
                    if (id == 0) continue;
                    Drawable d = r.getDrawable(id, null);
                    // Outline icons are single-colour: tint them like the clock.
                    // Other packs are full colour: leave them as they are.
                    tintWeatherIcon = tries[i][1].contains("outline");
                    return d;
                } catch (Throwable ignored) {
                    // Try the next one.
                }
            }
            logOnce("omni-icon", "no OmniJaws icon found for pack " + iconPack);
            return null;
        }
    }

    /** VoLTE / VoNR / VoWiFi / IMS icons (replaced by the combined badge). */
    private static boolean voiceSlot(String slot) {
        String s = slot.toLowerCase(Locale.US);
        return s.contains("volte") || s.contains("vonr") || s.contains("vowifi") || s.contains("ims");
    }

    private static boolean isRow1Slot(String slot) {
        String s = slot.toLowerCase(Locale.US);
        for (String k : ROW1_SLOT_KEYWORDS) if (s.contains(k)) return true;
        return false;
    }

    // ------------------------------------------------------------ temperatures

    /**
     * Battery current (A, always positive) and power (W). Kernels differ in units
     * (µA vs mA) and sign (+/- for charging), so the value is normalised here.
     */
    private static float[] readPower(Context ctx) {
        float[] out = {Float.NaN, Float.NaN};
        try {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            long cur = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (cur == Long.MIN_VALUE || cur == 0) {
                cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            }
            if (cur == Integer.MIN_VALUE || cur == Long.MIN_VALUE) return out;
            double a = Math.abs((double) cur);
            a = a > 20_000 ? a / 1_000_000.0 : a / 1_000.0; // µA or mA
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            // Sign from the charging state, not the kernel's number (kernels disagree on it):
            // plus while charging, minus while running on battery.
            int status = i == null ? -1 : i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = i == null ? 0 : i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            boolean charging = plugged != 0 && (status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL);
            if (!charging) a = -a;
            out[0] = (float) a;
            int mv = i == null ? -1 : i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            if (mv > 0) {
                double v = mv > 100_000 ? mv / 1_000_000.0 : mv / 1000.0; // µV or mV
                out[1] = (float) (a * v);
            }
        } catch (Throwable t) {
            logOnce("power", "battery current failed: " + t);
        }
        return out;
    }

    /** {health %, cycle count}, -1 where unknown. Android's own values first, then the battery driver. */
    private static int[] readBatteryHealth(Context ctx) {
        int soh = -1, cyc = -1;
        try {
            BatteryManager bm = ctx.getSystemService(BatteryManager.class);
            if (bm != null) {
                int v = bm.getIntProperty(10);   // BATTERY_PROPERTY_STATE_OF_HEALTH (Android 14+)
                if (v > 0 && v <= 120) soh = v;
            }
        } catch (Throwable ignored) { }
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i != null) {
                int v = i.getIntExtra("android.os.extra.CYCLE_COUNT", -1);
                if (v >= 0) cyc = v;
            }
        } catch (Throwable ignored) { }
        if (soh <= 0) {
            // published by the Ride Cool module (root): System UI can't read the battery driver's files
            try {
                int v = android.provider.Settings.Global.getInt(ctx.getContentResolver(), "netspeed_battery_soh", -1);
                if (v > 0 && v <= 120) soh = v;
            } catch (Throwable ignored) { }
        }
        if (soh <= 0) {
            // Xiaomi / Qualcomm, OPlus (OxygenOS / ColorOS) and generic driver files, first readable one wins
            String[] direct = {"/sys/class/qcom-battery/soh", "/sys/class/power_supply/bms/soh",
                    "/sys/class/power_supply/battery/soh", "/sys/class/power_supply/battery/state_of_health",
                    "/sys/class/oplus_chg/battery/battery_soh", "/sys/class/power_supply/battery/battery_soh",
                    "/sys/class/power_supply/bms/battery_soh"};
            for (String f : direct) {
                int v = parseInt(readLine(new File(f)));
                if (v > 0 && v <= 120) { soh = v; break; }
            }
        }
        if (soh <= 0) {
            // full charge capacity against design capacity
            String[][] pairs = {
                    {"/sys/class/power_supply/battery/charge_full", "/sys/class/power_supply/battery/charge_full_design"},
                    {"/sys/class/power_supply/bms/charge_full", "/sys/class/power_supply/bms/charge_full_design"},
                    {"/sys/class/oplus_chg/battery/battery_fcc", "/sys/class/oplus_chg/battery/design_capacity"}};
            for (String[] pr : pairs) {
                int full = parseInt(readLine(new File(pr[0])));
                int design = parseInt(readLine(new File(pr[1])));
                int v = full > 0 && design > 0 ? Math.round(full * 100f / design) : -1;
                if (v > 0 && v <= 120) { soh = v; break; }
            }
        }
        if (soh <= 0) {
            // System UI can't read the driver's files and Android 14's property is empty on some ROMs: ask root
            // (off the worker thread, so a slow or refused su can never stall the bar). Result shows from the next read.
            rootHealthAsync(ctx);   // the value arrives in Settings.Global "netspeed_battery_soh", read just above on the next pass
        }
        if (cyc < 0) {
            for (String f : new String[]{"/sys/class/power_supply/battery/cycle_count",
                    "/sys/class/power_supply/bms/cycle_count", "/sys/class/oplus_chg/battery/battery_cc"}) {
                int v = parseInt(readLine(new File(f)));
                if (v >= 0) { cyc = v; break; }
            }
        }
        logOnce("health", "battery health " + soh + "%, " + cyc + " cycles");
        return new int[]{soh, cyc};
    }

    private static volatile long rootHealthAt;

    /** Ask the NetSpeed app (it has root, System UI does not) to read the battery health into Settings.Global. */
    private static void rootHealthAsync(Context ctx) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (rootHealthAt != 0 && now - rootHealthAt < 10 * 60_000L) return;
        rootHealthAt = now;
        try {
            ctx.sendBroadcast(new Intent("com.netspeed.xposed.BATT_HEALTH")
                    .setPackage("com.netspeed.xposed")
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES));
        } catch (Throwable t) {
            logOnce("batt-health-req", "battery health request failed: " + t);
        }
    }

    private static int parseInt(String s) {
        try {
            return s == null ? -1 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static float readBatteryTemp(Context ctx) {
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return Float.NaN;
            int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            return t == Integer.MIN_VALUE ? Float.NaN : t / 10f;
        } catch (Throwable t) {
            logOnce("bat", "battery temperature failed: " + t);
            return Float.NaN;
        }
    }

    /** Hottest CPU core. System UI has the DEVICE_POWER permission this needs. */
    private static float readCpuTemp(Context ctx) {
        try {
            HardwarePropertiesManager hpm = ctx.getSystemService(HardwarePropertiesManager.class);
            if (hpm != null) {
                float[] temps = hpm.getDeviceTemperatures(
                        HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU,
                        HardwarePropertiesManager.TEMPERATURE_CURRENT);
                float max = Float.NaN;
                for (float t : temps) {
                    if (t == HardwarePropertiesManager.UNDEFINED_TEMPERATURE || t <= 0 || t > 150) continue;
                    if (Float.isNaN(max) || t > max) max = t;
                }
                if (!Float.isNaN(max)) {
                    logOnce("cpu-hpm", "CPU temperature from HardwarePropertiesManager");
                    return max;
                }
            }
        } catch (Throwable t) {
            logOnce("cpu-hpm-err", "HardwarePropertiesManager failed: " + t);
        }
        return readThermalZones();
    }

    /** Fastest core's current clock in GHz, from cpufreq (NaN if unreadable). */
    private static float readCpuGhz() {
        long max = 0;
        for (int i = 0; i < 16; i++) {
            String v = readLine(new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq"));
            if (v == null) continue;
            try { max = Math.max(max, Long.parseLong(v.trim())); } catch (Throwable ignored) { }
        }
        if (max <= 0) logOnce("ghz-none", "no readable CPU clock");
        return max <= 0 ? Float.NaN : max / 1_000_000f;
    }

    /** GPU temperature: Android's own sensor first, then thermal zones named gpu*. */
    private static float readGpuTemp(Context ctx) {
        try {
            HardwarePropertiesManager hpm = ctx.getSystemService(HardwarePropertiesManager.class);
            if (hpm != null) {
                float max = Float.NaN;
                for (float t : hpm.getDeviceTemperatures(HardwarePropertiesManager.DEVICE_TEMPERATURE_GPU,
                        HardwarePropertiesManager.TEMPERATURE_CURRENT)) {
                    if (t == HardwarePropertiesManager.UNDEFINED_TEMPERATURE || t <= 0 || t > 150) continue;
                    if (Float.isNaN(max) || t > max) max = t;
                }
                if (!Float.isNaN(max)) return max;
            }
        } catch (Throwable ignored) { }
        float max = Float.NaN;
        try {
            File[] zones = new File("/sys/class/thermal").listFiles();
            if (zones == null) return max;
            for (File z : zones) {
                if (!z.getName().startsWith("thermal_zone")) continue;
                String type = readLine(new File(z, "type"));
                if (type == null || !type.toLowerCase(Locale.US).startsWith("gpu")) continue;
                String v = readLine(new File(z, "temp"));
                if (v == null) continue;
                float c = Float.parseFloat(v.trim());
                if (c > 1000) c /= 1000f;
                if (c <= 0 || c > 150) continue;
                if (Float.isNaN(max) || c > max) max = c;
            }
        } catch (Throwable ignored) { }
        if (Float.isNaN(max)) logOnce("gpu-none", "no readable GPU temperature found");
        return max;
    }

    /** Fallback: the kernel's thermal zones (may be blocked by SELinux). */
    private static float readThermalZones() {
        float max = Float.NaN;
        try {
            File[] zones = new File("/sys/class/thermal").listFiles();
            if (zones == null) return max;
            for (File z : zones) {
                if (!z.getName().startsWith("thermal_zone")) continue;
                String type = readLine(new File(z, "type"));
                if (type == null) continue;
                String t = type.toLowerCase(Locale.US);
                if (!(t.startsWith("cpu") || t.contains("cpuss") || t.contains("soc"))) continue;
                String v = readLine(new File(z, "temp"));
                if (v == null) continue;
                float c = Float.parseFloat(v.trim());
                if (c > 1000) c /= 1000f;
                if (c <= 0 || c > 150) continue;
                if (Float.isNaN(max) || c > max) max = c;
            }
            if (!Float.isNaN(max)) logOnce("cpu-sys", "CPU temperature from /sys/class/thermal");
            else logOnce("cpu-none", "no readable CPU temperature found");
        } catch (Throwable t) {
            logOnce("cpu-sys-err", "thermal zones failed: " + t);
        }
        return max;
    }

    private static String readLine(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            return r.readLine();
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ helpers

    private static int dp(Resources res, float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, res.getDisplayMetrics()));
    }

    private static synchronized void logOnce(String key, String msg) {
        if (sLogOnce.put(key, Boolean.TRUE) == null) XposedBridge.log(TAG + msg);
    }
}
