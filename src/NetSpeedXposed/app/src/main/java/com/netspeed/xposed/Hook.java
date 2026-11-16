package com.netspeed.xposed;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Adds a small two-line network speed indicator (download / upload)
 * to the status bar, just before the system icons.
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "NetSpeedXposed: ";
    private static final String SYSTEMUI = "com.android.systemui";

    /** How often the speed is refreshed, in milliseconds. */
    private static final long INTERVAL_MS = 1000;


    /** Below this speed (bytes per second) the connection counts as idle. */
    private static final long IDLE_BPS = 1024;

    /** How long the connection must stay idle before the indicator hides. */
    private static final long IDLE_HIDE_MS = 10_000;

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        if ("android".equals(lp.packageName)) {
            // System framework: makes apps start below the taller status bar.
            DualRow.hookHeight("framework");
            NavImmersive.install("framework");
            Predict.installSystem(lp.classLoader);
            WakePlug.install(lp.classLoader);
            return;
        }
        if ("com.android.vending".equals(lp.packageName)) {
            if (lp.isFirstApplication) IntegrityWatch.install();
            return;
        }
        if ("com.qti.phone".equals(lp.packageName)) {
            QtiNr.installQti(lp.isFirstApplication, lp.processName);
            return;
        }
        if ("com.android.phone".equals(lp.packageName)) {
            if (lp.isFirstApplication) OperatorName.install(lp.classLoader);
            return;
        }
        if ("com.android.settings".equals(lp.packageName)) {
            if (lp.isFirstApplication) {
                SimStatus.install(lp.classLoader);
                MobileSettings.install(lp.classLoader);
            }
            return;
        }
        if (!SYSTEMUI.equals(lp.packageName)) {
            // Any other process in scope is the launcher (it draws the gesture pill on
            // Android 16 ROMs like Lunaris): immersive navigation there.
            if (lp.isFirstApplication) LauncherNav.install(lp.packageName, lp.classLoader);
            return;
        }
        NavImmersive.install("systemui");
        AppInset.install(lp.classLoader);
        QsBatt.install(lp.classLoader);
        SmartCarrier.install(lp.classLoader);

        // Load settings as soon as System UI's Application exists (before the status bar is built).
        try {
            XposedHelpers.findAndHookMethod(android.app.Instrumentation.class, "callApplicationOnCreate",
                    android.app.Application.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                Cfg.init((Context) p.args[0]);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "settings init failed: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "settings hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.systemui.statusbar.phone.PhoneStatusBarView",
                    lp.classLoader,
                    "onFinishInflate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                attach((ViewGroup) param.thisObject);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "attach failed: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + "hooked SystemUI");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook failed: " + t);
        }

        // Show more notification icons before the "•" overflow dot.
        NotifIconLimit.install(lp.classLoader);
        HideSideRoam.install(lp.classLoader);

        // Two-row status bar with temperatures and weather.
        DualRow.install(lp.classLoader);

        // Live Earth on the lock screen.
        LockGlobe.install(lp.classLoader);

        // Pixel-style lock screen exit.
        UnlockAnim.install(lp.classLoader);
    }

    /** Finds a place in the status bar and adds the speed view there. */
    private void attach(ViewGroup bar) {
        Context ctx = bar.getContext();
        Resources res = bar.getResources();
        Cfg.init(ctx);
        ScreenState.init(ctx);

        // Don't add twice if the status bar is re-inflated.
        if (bar.findViewWithTag("netspeed_xposed") != null) return;

        // Different Android versions/ROMs name the right-side container differently,
        // so try a few known ids.
        View anchor = null;
        for (String name : new String[]{"system_icons", "statusIcons", "system_icon_area"}) {
            int id = res.getIdentifier(name, "id", SYSTEMUI);
            if (id != 0) {
                anchor = bar.findViewById(id);
                if (anchor != null) break;
            }
        }
        if (anchor == null || !(anchor.getParent() instanceof ViewGroup)) {
            XposedBridge.log(TAG + "no status bar container found");
            return;
        }
        ViewGroup parent = (ViewGroup) anchor.getParent();

        // Clock view, used to copy its colour so we match light/dark status bar.
        int clockId = res.getIdentifier("clock", "id", SYSTEMUI);
        final View clock = clockId != 0 ? bar.findViewById(clockId) : null;

        final TextView tv = new TextView(ctx);
        tv.setTag("netspeed_xposed");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setMaxLines(2);
        tv.setLineSpacing(0, 0.85f);
        tv.setIncludeFontPadding(false);
        tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        int pad = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 4, res.getDisplayMetrics());
        tv.setPadding(pad, 0, pad, 0);
        // Fixed width, so changing numbers don't push the icons around.
        tv.setMinWidth((int) Math.ceil(tv.getPaint().measureText("↓999K/s")) + pad * 2);
        tv.setText("↓0B/s\n↑0B/s");
        tv.setVisibility(Cfg.b(Cfg.HIDE_IDLE, Cfg.D_HIDE_IDLE) ? View.GONE : View.VISIBLE);

        ViewGroup.LayoutParams lp;
        if (parent instanceof LinearLayout) {
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            llp.gravity = Gravity.CENTER_VERTICAL;
            lp = llp;
        } else {
            lp = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        parent.addView(tv, parent.indexOfChild(anchor), lp);

        startUpdates(tv, clock);
        XposedBridge.log(TAG + "indicator added");
    }

    /** Sets the text size in sp, only when it actually changed (avoids extra layouts). */
    static void setSp(TextView tv, int sp) {
        if (tv == null || sp <= 0) return;
        float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                tv.getResources().getDisplayMetrics());
        if (Math.abs(tv.getTextSize() - px) > 0.5f) tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    }

    /** Use the clock's font (the one picked in Wallpaper & style). Small text gets the bold weight of that font. */
    static void matchClockFont(View clock, TextView tv, boolean bold) {
        if (!(clock instanceof TextView) || tv == null) return;
        Typeface base = ((TextView) clock).getTypeface();
        if (base == null) return;
        Typeface tf = bold ? Typeface.create(base, Typeface.BOLD) : base;
        if (tv.getTypeface() != tf) tv.setTypeface(tf);
    }

    private void startUpdates(final TextView tv, final View clock) {
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            long lastRx = TrafficStats.getTotalRxBytes();
            long lastTx = TrafficStats.getTotalTxBytes();
            long lastTime = SystemClock.elapsedRealtime();
            long lastActive = 0;
            boolean vpnWas;
            final SparkDrawable spark = new SparkDrawable();
            final StatsCol stats = new StatsCol();
            boolean gaugeOff;          // the speedometer didn't fit last time
            long gaugeOffAt;
            int gaugeW;                // its width incl. gap, while shown

            @Override
            public void run() {
                if (!ScreenState.on()) { handler.postDelayed(this, 2000); return; }
                try {
                    Vpn.update(tv.getContext());
                    long rx = TrafficStats.getTotalRxBytes();
                    long tx = TrafficStats.getTotalTxBytes();
                    if (Vpn.active) {                       // the total counts tunnel + network: use the tunnel alone
                        long vr = Vpn.bytes("rx"), vt = Vpn.bytes("tx");
                        if (vr >= 0 && vt >= 0) { rx = vr; tx = vt; }
                        else { rx /= 2; tx /= 2; }
                    }
                    if (Vpn.active != vpnWas) {             // counters switch source: no jump
                        vpnWas = Vpn.active;
                        lastRx = rx; lastTx = tx;
                    }
                    long now = SystemClock.elapsedRealtime();
                    long dt = Math.max(1, now - lastTime);

                    long down = Math.max(0, (rx - lastRx) * 1000 / dt);
                    long up = Math.max(0, (tx - lastTx) * 1000 / dt);
                    lastRx = rx;
                    lastTx = tx;
                    lastTime = now;

                    if (SysState.testing()) {           // test button: a made-up burst so the graph moves
                        double ph = (now % 4000) / 4000.0 * Math.PI * 2;
                        down = (long) (2_000_000 * (1.1 + Math.sin(ph)));
                        up = (long) (200_000 * (1.1 + Math.cos(ph * 1.7)));
                    }
                    spark.push(down, up);
                    boolean active = down >= IDLE_BPS || up >= IDLE_BPS;
                    if (active) lastActive = now;

                    boolean stockHide = !Cfg.b(Cfg.SPEED_STOCK, Cfg.D_SPEED_STOCK) || !(Cfg.b(Cfg.PRO, Cfg.D_PRO) && Cfg.b(Cfg.DUAL, Cfg.D_DUAL));   // 4-row bar off = fully stock: no speed readout either
                    if (stockHide || (Cfg.b(Cfg.HIDE_IDLE, Cfg.D_HIDE_IDLE) && !active && now - lastActive > IDLE_HIDE_MS)) {
                        if (tv.getVisibility() != View.GONE) tv.setVisibility(View.GONE);
                    } else {
                        int color = 0xFFFFFFFF;
                        if (clock instanceof TextView) {
                            color = ((TextView) clock).getCurrentTextColor();
                            tv.setTextColor(color);
                            matchClockFont(clock, tv, Cfg.b(Cfg.BOLD, Cfg.D_BOLD));
                            setSp(tv, Cfg.i(Cfg.SPEED_SP, Cfg.D_SPEED_SP));
                        }
                        // Two rows, ↓ green / ↑ blue, value coloured by speed, small grey unit:
                        //   ↓9.9K/s
                        //   ↑1.2K/s
                        boolean dark = luminance(color) > 0.5f;
                        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
                        speedLine(sb, '↓', down, true, color, dark);
                        if (Vpn.active) vpnTag(sb, "VPN", dark);
                        sb.append('\n');
                        speedLine(sb, '↑', up, false, color, dark);
                        if (Vpn.active) vpnTag(sb, Vpn.age(), dark);
                        tv.setText(sb);
                        // Speedometer before the numbers: download speed, log scale 1 K/s .. 100 M/s.
                        int textW = (int) Math.ceil(tv.getPaint().measureText(Vpn.active ? "↓999K/s 2h14m" : "↓999K/s"))
                                + tv.getPaddingLeft() + tv.getPaddingRight();
                        android.graphics.drawable.Drawable endD = null;
                        if (Cfg.b(Cfg.STATS_COL, Cfg.D_STATS_COL)) {
                            StatsCol.refresh(tv.getContext(), dark);
                            stats.build(tv.getTextSize(), color, dark);
                            endD = stats;
                            textW += stats.getIntrinsicWidth() + Math.round(tv.getTextSize() * 0.2f);
                        } else if (Cfg.b(Cfg.ANIM_SPARK, Cfg.D_ANIM_SPARK) && !Vpn.active) {   // no room for the wave beside the VPN tag
                            int sh = Math.round(tv.getTextSize() * 1.7f), sw = Math.round(tv.getTextSize() * 3.2f);
                            spark.size(sw, sh);
                            spark.colors(dark ? 0xFF34C759 : 0xFF1E8E3E, dark ? 0xFF0A84FF : 0xFF0A60C0,
                                    (color & 0x00FFFFFF) | 0x40000000);
                            endD = spark;
                            textW += sw + Math.round(tv.getTextSize() * 0.2f);
                        }
                        // Speedometer: drop it while it doesn't fit (squeezed, or running into the camera);
                        // try again every 5 min (room can change with the layout).
                        if (gaugeW > 0 && tv.getWidth() > 0 && !gaugeOff && noRoom(tv)) {
                            gaugeOff = true;
                            gaugeOffAt = now;
                            XposedBridge.log(TAG + "speedometer hidden: not enough room");
                        }
                        if (gaugeOff && now - gaugeOffAt > 300_000) gaugeOff = false;
                        gaugeW = 0;
                        if (Cfg.b(Cfg.SPEED_GAUGE, Cfg.D_SPEED_GAUGE) && !gaugeOff && !Cfg.b(Cfg.STATS_COL, Cfg.D_STATS_COL)) {   // the stats column takes its room
                            float frac = down <= 1024 ? 0f : (float) (Math.log10(down / 1024.0) / 5.0);
                            int gh = Math.round(tv.getTextSize() * 1.6f), gw = Math.round(tv.getTextSize() * 1.45f);
                            int prog = speedColor(down, true, color, dark, true);
                            tv.setCompoundDrawablesRelative(new GaugeDrawable(frac, prog, color,
                                    (color & 0x00FFFFFF) | 0x55000000, gw, gh), null, endD, null);
                            int gap = Math.round(tv.getTextSize() * 0.15f);
                            tv.setCompoundDrawablePadding(gap);
                            textW += gw + gap;
                            gaugeW = gw + gap;
                        } else {
                            tv.setCompoundDrawablesRelative(null, null, endD, null);
                            if (endD != null) tv.setCompoundDrawablePadding(Math.round(tv.getTextSize() * 0.2f));
                        }
                        if (tv.getMinWidth() != textW) tv.setMinWidth(textW);
                        if (tv.getVisibility() != View.VISIBLE) tv.setVisibility(View.VISIBLE);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "update failed: " + t);
                }
                handler.postDelayed(this, INTERVAL_MS);
            }
        });
    }

    /** True when the speed view is narrower than it wants, or reaches under the camera cutout. */
    static boolean noRoom(TextView tv) {
        int want = tv.getMinWidth();
        if (want > 0 && tv.getWidth() < want - 1) return true;
        // text wider than the view: clipped
        if (tv.getLayout() != null) {
            float lw = 0;
            for (int i = 0; i < tv.getLineCount(); i++) lw = Math.max(lw, tv.getLayout().getLineWidth(i));
            int room = tv.getWidth() - tv.getCompoundPaddingLeft() - tv.getCompoundPaddingRight();
            if (lw > room + 1) return true;
        }
        try {
            android.view.WindowInsets wi = tv.getRootWindowInsets();
            android.view.DisplayCutout dc = wi == null ? null : wi.getDisplayCutout();
            if (dc != null) {
                int[] p = new int[2];
                tv.getLocationOnScreen(p);
                android.graphics.Rect r = new android.graphics.Rect(p[0], p[1], p[0] + tv.getWidth(), p[1] + tv.getHeight());
                for (android.graphics.Rect cr : dc.getBoundingRects()) if (android.graphics.Rect.intersects(r, cr)) return true;
            }
        } catch (Throwable ignored) { }
        // cut off by the parent
        android.view.ViewParent vp = tv.getParent();
        if (vp instanceof View) {
            View pv = (View) vp;
            if (tv.getRight() > pv.getWidth() - pv.getPaddingRight() + 1 || tv.getLeft() < pv.getPaddingLeft() - 1) return true;
        }
        return false;
    }

    static float luminance(int c) {
        return (0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF)) / 255f;
    }

    /** Idle grey, normal = text colour, fast (1 M/s+) green/blue, very fast (10 M/s+) cyan. */
    static int speedColor(long bps, boolean down, int color, boolean dark, boolean forGauge) {
        if (bps < IDLE_BPS) return (color & 0x00FFFFFF) | 0x66000000;
        if (bps >= 10L << 20) return dark ? 0xFF64D2FF : 0xFF0086A8;
        if (bps >= 1L << 20 || forGauge) return down ? (dark ? 0xFF34C759 : 0xFF1E8E3E) : (dark ? 0xFF0A84FF : 0xFF0A60C0);
        return color;
    }

    /** Small teal tag after a speed row: "VPN" on the first row, the connected time on the second. */
    private static void vpnTag(android.text.SpannableStringBuilder sb, String t, boolean dark) {
        int st = sb.length();
        sb.append(' ').append(t);
        sb.setSpan(new android.text.style.ForegroundColorSpan(dark ? 0xFF30D5C8 : 0xFF00796B), st, sb.length(), 0);
        sb.setSpan(new android.text.style.RelativeSizeSpan(0.72f), st, sb.length(), 0);
        sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), st, sb.length(), 0);
    }

    private static void speedLine(android.text.SpannableStringBuilder sb, char arrow, long bps, boolean down,
                                  int color, boolean dark) {
        boolean idle = bps < IDLE_BPS;
        int arrowCol = idle ? (color & 0x00FFFFFF) | 0x66000000
                : down ? (dark ? 0xFF34C759 : 0xFF1E8E3E) : (dark ? 0xFF0A84FF : 0xFF0A60C0);
        int st = sb.length();
        sb.append(arrow);
        sb.setSpan(new android.text.style.ForegroundColorSpan(arrowCol), st, sb.length(), 0);
        String f = format(bps);
        int cut = f.length() - (f.endsWith("B/s") && !f.endsWith("KB/s") ? 3 : 3); // "K/s", "M/s", "B/s"
        st = sb.length();
        sb.append(f.substring(0, cut));
        sb.setSpan(new android.text.style.ForegroundColorSpan(speedColor(bps, down, color, dark, false)), st, sb.length(), 0);
        st = sb.length();
        sb.append(f.substring(cut));
        sb.setSpan(new android.text.style.RelativeSizeSpan(0.7f), st, sb.length(), 0);
        sb.setSpan(new android.text.style.ForegroundColorSpan((color & 0x00FFFFFF) | 0x99000000), st, sb.length(), 0);
    }

    /** 850 -> "850B/s", 12500 -> "12K/s", 1530000 -> "1.5M/s" */
    private static String format(long bps) {
        if (bps < 1024) return bps + "B/s";
        double kb = bps / 1024.0;
        if (kb < 100) return String.format(Locale.US, "%.1fK/s", kb);
        if (kb < 1024) return String.format(Locale.US, "%.0fK/s", kb);
        double mb = kb / 1024.0;
        if (mb < 100) return String.format(Locale.US, "%.1fM/s", mb);
        return String.format(Locale.US, "%.0fM/s", mb);
    }
}
