package com.netspeed.xposed;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Settings screen. Opens from the module's "Settings" in Vector/LSPosed and from the app icon.
 * Most changes apply to the status bar right away; a few need System UI restarted.
 */
public class SettingsActivity extends Activity {
    private static final Uri URI = Uri.parse("content://com.netspeed.xposed.settings/all");

    private SharedPreferences sp;
    private LinearLayout list;
    private int fg, sub, card, accent;

    // ---- pages: a home screen of tiles, then one page per topic
    private static final String[] CAT_NAME = {"Layout", "Network", "Weather & sky", "Earth frames", "Disasters", "Device", "System", "Guides"};
    private static final String[] CAT_ICON = {"📐", "📡", "⛅", "🌍", "🌋", "🔋", "⚙️", "📖"};
    private static final String[] CAT_SUB = {"size, rows, looks, animations", "SIMs, bands, towers, 5G", "forecast, sky, tides, globe",
            "river, soil, land, power, quakes", "quakes, cyclones, alerts", "battery, CPU, speed, heat", "updates, launcher, restart", "UV, heat, SIM boxes, place, sky"};
    private static final int[][] CAT_COL = {{0xFF173A5C, 0xFF102A44}, {0xFF1A4A40, 0xFF112E2E}, {0xFF4A3A1A, 0xFF2E2412},
            {0xFF1F4A2A, 0xFF132E1B}, {0xFF4A2222, 0xFF2E1616}, {0xFF3A2A55, 0xFF241A38}, {0xFF233648, 0xFF162330}, {0xFF4A3A22, 0xFF2E2416}};
    /** The older Readable / two-row layouts are no longer offered: with the 4-row bar off the status bar stays stock. */
    private static final boolean OLD_LAYOUTS = false;
    private LinearLayout home;
    private final LinearLayout[] pages = new LinearLayout[8];
    private final LinearLayout[] pcard = new LinearLayout[8];
    private int curPage = -1, lastCat = 0, secCat = 0;
    private ScrollView scrollRef;

    /** Which page a setting belongs on, from its title; -1 = the same page as the one before it. */
    private static int classify(String raw) {
        String t = raw.toLowerCase(java.util.Locale.ROOT);
        if (t.equals("sim box guide") || t.equals("uv guide and sunscreen timer") || t.equals("heat and power diagnostics") || t.startsWith("heat and power history")
                || t.equals("place guide: soil and plants") || t.equals("sky guide: what is up now")) return 7;
        if (t.contains("satellite strip")) return 2;
        if (t.contains("fault line")) return 3;
        if (t.contains("reservoir")) return 2;
        if (t.contains("frame") || t.contains("soil") || t.contains("river")) return 3;
        if (t.contains("disaster") || t.contains("earthquake") || t.contains("solar storm")) return 4;
        if (t.contains("restart") || t.contains("reset") || t.contains("volume") || t.contains("launcher") || t.contains("update")
                || t.contains("integrity") || t.contains("diagnostic") || t.contains("immersive") || t.contains("gesture")
                || t.contains("apps' top") || t.contains("installed build") || t.contains("predict")) return 6;
        if (t.contains(" size") || t.contains("animation") || t.contains("layout") || t.contains("row") || t.contains("gap")
                || t.contains("height") || t.contains("look") || t.contains("chip") || t.contains("dashboard") || t.contains("tint")
                || t.contains("bold") || t.contains("contrast") || t.contains("dimming") || t.contains("clock") || t.contains("margin")
                || t.contains("test the")) return 0;
        if (t.contains("battery") || t.contains("cpu") || t.contains("gpu") || t.contains("temperature") || t.contains("amps")
                || t.contains("throttl") || t.contains("heat") || t.contains("speed") || t.contains("bluetooth") || t.contains("charging")
                || t.contains("brightness") || t.contains("stats column") || t.contains("warn when") || t.contains("camera")
                || t.contains("sparkline") || t.contains("hide net")) return 5;
        if (t.contains("weather") || t.contains("tide") || t.contains("coast") || t.contains("sky") || t.contains("world map")
                || t.contains("globe") || t.contains("unlock") || t.contains("haze")) return 2;
        if (t.contains("tower") || t.contains("5g") || t.contains("4g") || t.contains("sim") || t.contains("band") || t.contains("dbm")
                || t.contains("snr") || t.contains("sinr") || t.contains("volte") || t.contains("vonr") || t.contains("network")
                || t.contains("ping") || t.contains("qualcomm") || t.contains("roaming") || t.contains("carrier") || t.contains("operator")
                || t.contains("mobile data") || t.contains("internet") || t.contains("signal") || t.contains("beacon") || t.contains("learn")
                || t.contains("corner")) return 1;
        return -1;
    }

    private static String firstText(View v) {
        if (v instanceof TextView) { CharSequence c = ((TextView) v).getText(); return c == null ? "" : c.toString(); }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) { String r = firstText(g.getChildAt(i)); if (!r.isEmpty()) return r; }
        }
        return "";
    }

    /** What the old sections add their rows to: each row is sent on to the card of the page it belongs to. */
    private final class RoutedCard extends LinearLayout {
        RoutedCard() { super(SettingsActivity.this); setOrientation(VERTICAL); }

        @Override
        public void addView(View child, int index, ViewGroup.LayoutParams params) {
            int cat = classify(firstText(child));
            if (cat < 0) cat = lastCat;
            lastCat = cat;
            pcard[cat].addView(child, params);
        }
    }

    /** Faint map contour lines behind everything. */
    private static final class ContourDrawable extends android.graphics.drawable.Drawable {
        private final int bg;
        private final android.graphics.Paint line = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        ContourDrawable(int bg, int accent) {
            this.bg = bg;
            line.setStyle(android.graphics.Paint.Style.STROKE);
            line.setStrokeWidth(2f);
            line.setColor((accent & 0x00FFFFFF) | 0x16000000);
        }

        @Override
        public void draw(Canvas c) {
            c.drawColor(bg);
            int w = getBounds().width(), h = getBounds().height();
            android.graphics.Path p = new android.graphics.Path();
            for (int k = 0; k < 7; k++) {
                float y0 = h * 0.08f + k * h * 0.035f, y1 = h * 0.86f + k * h * 0.02f;
                for (float yy : new float[]{y0, y1}) {
                    p.reset();
                    p.moveTo(-20, yy);
                    p.quadTo(w * 0.3f, yy - h * 0.045f, w * 0.55f, yy + h * 0.01f);
                    p.quadTo(w * 0.8f, yy + h * 0.05f, w + 20, yy - h * 0.015f);
                    c.drawPath(p, line);
                }
            }
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = SettingsProvider.prefs(this);
        // integrity watcher notifications (Android 13+ asks once)
        try {
            UpdateJob.schedule(this);
            DamJob.schedule(this);
            HeatSnapJob.schedule(getApplicationContext());
            LocationWatch.schedule(this);
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 41);
        } catch (Throwable ignored) { }
        if (getIntent() != null && getIntent().getBooleanExtra("show_dam", false))
            getWindow().getDecorView().post(this::showDam);
        if (getIntent() != null && getIntent().getBooleanExtra("show_integrity", false))
            getWindow().getDecorView().post(this::showIntegrity);

        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int bg = dark ? 0xFF0B1220 : 0xFFEAF2F7;
        card = dark ? 0xFF111C2E : 0xFFFFFFFF;
        fg = dark ? 0xFFDFE8F2 : 0xFF10202F;
        sub = dark ? 0xFF7F93AB : 0xFF5A6B7D;
        accent = dark ? 0xFF7AE7C7 : 0xFF0B7A66;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new ContourDrawable(bg, accent));
        scrollRef = scroll;
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        list.setPadding(p, p, p, p + dp(72));   // room for the footer menu
        scroll.addView(list);
        // Edge-to-edge: keep content clear of the status and navigation bars.
        scroll.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets sys = in.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(0, sys.top, 0, sys.bottom);
            return in;
        });
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.addView(scroll, new android.widget.FrameLayout.LayoutParams(-1, -1));
        LinearLayout jump = new LinearLayout(this);
        jump.setOrientation(LinearLayout.VERTICAL);
        jump.addView(jumpButton("↑", () -> scroll.smoothScrollTo(0, 0)));
        jump.addView(jumpButton("↓", () -> scroll.smoothScrollTo(0, list.getHeight())));
        android.widget.FrameLayout.LayoutParams jlp = new android.widget.FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.END);
        jlp.setMargins(0, 0, dp(16), dp(24) + dp(64));
        root.addView(jump, jlp);
        // footer menu: Home, Search, Updates, Diagnostics
        final LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setBackgroundColor(card);
        foot.setElevation(dp(8));
        String[][] fi = {{"\uD83C\uDFE0", "Home"}, {"\uD83D\uDD0D", "Search"}, {"\u2B06\uFE0F", "Updates"}, {"\uD83E\uDE7A", "Diagnostics"}, {"\u2139\uFE0F", "Info"}};
        Runnable[] fa = {
                () -> { showPage(-1); scroll.smoothScrollTo(0, 0); },
                () -> searchDialog(scroll),
                () -> { showPage(6); if (updStatus != null && updStatus.getParent() instanceof View) scroll.post(() -> jumpTo(scroll, (View) updStatus.getParent())); },
                this::showSatellites,
                () -> showPage(8)};
        for (int i = 0; i < 5; i++) {
            LinearLayout it = new LinearLayout(this);
            it.setOrientation(LinearLayout.VERTICAL);
            it.setGravity(Gravity.CENTER);
            it.setPadding(0, dp(8), 0, dp(8));
            TextView ic = text(fi[i][0], 20, accent);
            ic.setGravity(Gravity.CENTER);
            TextView lb = text(fi[i][1], 11, i == 0 ? accent : sub);
            lb.setGravity(Gravity.CENTER);
            it.addView(ic);
            it.addView(lb);
            if (i == 2) { footDot = lb; }
            final int k = i;
            it.setOnClickListener(v -> fa[k].run());
            foot.addView(it, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        final android.widget.FrameLayout.LayoutParams flp = new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(foot, flp);
        // permission gate: covers everything until the needed permissions are allowed
        gate = new LinearLayout(this);
        gate.setOrientation(LinearLayout.VERTICAL);
        gate.setBackgroundColor(bg);
        gate.setPadding(dp(20), dp(80), dp(20), dp(20));
        gate.setVisibility(View.GONE);
        gate.setClickable(true);
        root.addView(gate, new android.widget.FrameLayout.LayoutParams(-1, -1));
        root.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets sys = in.getInsets(WindowInsets.Type.systemBars());
            jlp.bottomMargin = dp(24) + dp(64) + sys.bottom;
            jump.setLayoutParams(jlp);
            foot.setPadding(0, 0, 0, sys.bottom);
            scroll.dispatchApplyWindowInsets(in);
            return in;
        });
        setContentView(root);
        // Light background: ask for dark status/navigation bar icons, so they stay readable.
        if (!dark) {
            try {
                android.view.WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    c.setSystemBarsAppearance(light, light);
                }
            } catch (Throwable ignored) { }
        }
        build();
    }

    private void build() {
        list.removeAllViews();
        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        list.addView(home);
        for (int i = 0; i < 8; i++) {
            pages[i] = new LinearLayout(this);
            pages[i].setOrientation(LinearLayout.VERTICAL);
            pages[i].setVisibility(View.GONE);
            TextView back = text("\u2039  Field Atlas", 14, accent);
            back.setPadding(dp(4), dp(4), dp(12), dp(10));
            final int idx = i;
            back.setOnClickListener(v -> showPage(-1));
            pages[i].addView(back);
            TextView ttl = text(CAT_ICON[i] + "  " + CAT_NAME[i], 26, fg);
            ttl.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            ttl.setPadding(dp(4), 0, 0, dp(2));
            pages[i].addView(ttl);
            TextView st = text(CAT_SUB[i], 13, sub);
            st.setPadding(dp(4), 0, 0, dp(10));
            pages[i].addView(st);
            pcard[i] = styledCard();
            pages[i].addView(pcard[i]);
            list.addView(pages[i]);
        }
        infoPage = new LinearLayout(this);
        infoPage.setOrientation(LinearLayout.VERTICAL);
        infoPage.setVisibility(View.GONE);
        list.addView(infoPage);
        lastCat = 0;
        secCat = 0;
        // with the 4-row bar on, options that only shaped the old layouts are left out
        final boolean pro = sp.getBoolean(Cfg.PRO, Cfg.D_PRO);
        title("Field Atlas");
        note("Signal \u00b7 towers \u00b7 weather \u00b7 sky \u00b7 earth. Changes apply to the status bar right away; items marked \u27f3 need System UI restarted.");

        section("Layout");
        LinearLayout c = card();
        if (OLD_LAYOUTS) {
            toggle(c, "Readable layout", "Four lines of large text: clock, CPU / battery temperature, watts, speed, "
                    + "notifications, Bluetooth; per-SIM network, signal, band and distance (the SIM carrying mobile data "
                    + "sits in a highlighted pill with live \u2193\u2191); weather and air quality; portrait battery. "
                    + "Turn off for the old two-row layout", Cfg.READABLE, Cfg.D_READABLE);
        }
        toggle(c, "New 4-row status bar", "Everything in four rows: clock with time zone, speed and temperatures; sunrise/sunset, alarm, notifications and a frame for the system icons; power, CPU load and GPS; ping, deep sleep, wakelock and Bluetooth. Battery health and cycles under the camera; both SIMs (data SIM outlined) against the right edge, full weather under them. Off = the original stock status bar. Restart SystemUI to apply", Cfg.PRO, Cfg.D_PRO);
        slider(c, "4-row bar extra height \u27f3", "dp", Cfg.PRO_EXTRA, Cfg.D_PRO_EXTRA, 0, 24);
        e2eToggle(c);
        if (OLD_LAYOUTS) {
            slider(c, "Gap between rows", "dp", Cfg.ROW_GAP, Cfg.D_ROW_GAP, 0, 12);
            slider(c, "Row 2 height", "dp", Cfg.ROW2_H, Cfg.D_ROW2_H, 14, 32);
            slider(c, "Gap before weather", "dp", Cfg.WEATHER_GAP, Cfg.D_WEATHER_GAP, 0, 16);
            slider(c, "Status bar height trim / extra ⟳", "dp", Cfg.EXTRA, Cfg.D_EXTRA, -20, 24);
        }
        slider(c, "Apps' top space: move app content up", "dp", Cfg.APP_TOP_TRIM, Cfg.D_APP_TOP_TRIM, 0, 60);
        c.addView(text("0 = apps start below the whole bar. Higher moves every app's header up behind the "
                + "lower rows. Taps there go to the status bar, so keep buttons clear. Applies live", 13, sub));
        propToggle(c, "Immersive navigation", "Apps draw to the bottom edge; the gesture pill "
                + "floats over them. Tick your launcher (and System Framework) in the LSPosed/Vector scope",
                NavImmersive.PROP);
        slider(c, "Gesture pill drop: 0 = default height, higher = lower", "dp", Cfg.PILL_DY, Cfg.D_PILL_DY, 0, 12);
        LinearLayout pillRow = new LinearLayout(this);
        pillRow.setPadding(0, dp(4), 0, dp(8));
        button(pillRow, "Save gesture-pill dump (8 s)", false, v -> savePillDump());
        button(pillRow, "Restart launcher", false, v -> new Thread(() -> {
            boolean ok = runSu("am force-stop $(cmd package resolve-activity --brief "
                    + "-a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1 | cut -d/ -f1)");
            runOnUiThread(() -> Toast.makeText(this, ok ? "Launcher restarted" : "Couldn't restart (root?)",
                    Toast.LENGTH_SHORT).show());
        }).start());
        c.addView(pillRow);

        if (OLD_LAYOUTS) {
            section("Text");
            c = card();
            slider(c, "Temperatures size", "sp", Cfg.TEMP_SP, Cfg.D_TEMP_SP, 6, 12);
            slider(c, "Weather size", "sp", Cfg.WEATHER_SP, Cfg.D_WEATHER_SP, 8, 16);
            slider(c, "Net speed size", "sp", Cfg.SPEED_SP, Cfg.D_SPEED_SP, 6, 12);
            slider(c, "Amps / watts size", "sp", Cfg.POWER_SP, Cfg.D_POWER_SP, 6, 12);
            slider(c, "Network band size", "sp", Cfg.BAND_SP, Cfg.D_BAND_SP, 6, 12);
            slider(c, "Tower mast size", "%", Cfg.MAST_PCT, Cfg.D_MAST_PCT, 80, 200);
            slider(c, "Clock size", "sp", Cfg.CLOCK_SP, Cfg.D_CLOCK_SP, 9, 18);
            slider(c, "Row 2 right margin", "dp", Cfg.ROW2_END, Cfg.D_ROW2_END, 0, 24);
            slider(c, "Signal tower icon size", "%", Cfg.TOWER_PCT, Cfg.D_TOWER_PCT, 70, 150);
            toggle(c, "Bold small text", "Temperatures and net speed", Cfg.BOLD, Cfg.D_BOLD);
        }

        section("Show");
        c = card();
        toggle(c, "CPU temperature", null, Cfg.SHOW_CPU, Cfg.D_SHOW_CPU);
        toggle(c, "Battery temperature", null, Cfg.SHOW_BAT, Cfg.D_SHOW_BAT);
        toggle(c, "Amps and watts", "Battery current and power, after the net speed",
                Cfg.SHOW_POWER, Cfg.D_SHOW_POWER);
        toggle(c, "Network band per SIM", "e.g. n78₁ over B3₂, before VoLTE / VoNR",
                Cfg.SHOW_BAND, Cfg.D_SHOW_BAND);
        if (OLD_LAYOUTS) {
            toggle(c, "Signal strength (dBm)", "e.g. n78₁ -92. Hidden by itself if there's no room",
                    Cfg.SHOW_DBM, Cfg.D_SHOW_DBM);
        }
        toggle(c, "Distance to tower", "Next to the tower counts: how far your serving tower is (~ estimate, ? unknown)",
                Cfg.SHOW_DIST, Cfg.D_SHOW_DIST);
        if (OLD_LAYOUTS) {
            toggle(c, "Signal tower icon", "One tower for both SIMs: SIM 1 waves left, SIM 2 right (4 = full), "
                    + "network + VoLTE/VoNR per SIM, ↓↑ on the data SIM. Replaces the stock bars, type and Vo badge",
                    Cfg.TOWER_ICON, Cfg.D_TOWER_ICON);
        }
        toggle(c, "Internet speed meter", "The download / upload speed in the 4-row bar. With the 4-row bar off, the status bar is fully stock and shows no speed. Restart System UI to apply", Cfg.SPEED_STOCK, Cfg.D_SPEED_STOCK);
        toggle(c, "Battery health and cycles", "Battery health % and charge cycles next to the SIM boxes", Cfg.SH_HEALTH, Cfg.D_SH_HEALTH);
        toggle(c, "CPU load", "CPU load % after the temperatures", Cfg.SH_LOAD, Cfg.D_SH_LOAD);
        toggle(c, "Free RAM", "Free memory after the system icons", Cfg.SH_RAM, Cfg.D_SH_RAM);
        toggle(c, "CPU clock", "Current CPU frequency in GHz", Cfg.SH_GHZ, Cfg.D_SH_GHZ);
        toggle(c, "Deep sleep share", "Time the phone spent in deep sleep, as %", Cfg.SH_SLEEP, Cfg.D_SH_SLEEP);
        toggle(c, "Next alarm", "Time of the next alarm", Cfg.SH_ALARM, Cfg.D_SH_ALARM);
        toggle(c, "Notification count", "Bell with the number of notifications", Cfg.SH_NOTIF, Cfg.D_SH_NOTIF);
        toggle(c, "Bluetooth device battery", "Battery % of the connected Bluetooth device", Cfg.SH_BTBAT, Cfg.D_SH_BTBAT);
        toggle(c, "Own row 2 icons", "Bell with the notification count (silent ones in brackets), Bluetooth "
                + "devices (W watch / H headphones / P phone + battery + codec), vibrate / silent / DND, next alarm, "
                + "Wi-Fi router (generation, band, dBm, link speed, activity) and a small tray for the rest. "
                + "Replaces the stock notification and system icons", Cfg.SYS_ICONS, Cfg.D_SYS_ICONS);
        toggle(c, "Lock screen globe", "Live Earth under the lock screen clock: day/night, your location as a red dot. "
                + "Drag or flick to turn it, double-tap to come back to your location",
                Cfg.LOCK_GLOBE, Cfg.D_LOCK_GLOBE);
        toggle(c, "Pixel unlock animation", "When you unlock, the lock screen drifts up slightly and fades out while the "
                + "home screen comes in behind it, like on a Pixel. Needs System UI in NetSpeed's LSPosed scope; "
                + "restart System UI to apply", Cfg.UNLOCK_ANIM, Cfg.D_UNLOCK_ANIM);
        toggle(c, "Globe turns by itself", "When you aren't touching it the Earth turns slowly (one turn in "
                + "90 s) with the live day / night line", Cfg.GLOBE_SPIN, Cfg.D_GLOBE_SPIN);
        if (OLD_LAYOUTS) {
            toggle(c, "Portrait battery", "Upright battery with the % inside, coloured by level (green while charging)",
                    Cfg.BATT_PORTRAIT, Cfg.D_BATT_PORTRAIT);
            toggle(c, "Battery % inside the icon", "Stock battery icon with the percentage written inside it",
                    Cfg.BATT_PCT_IN, Cfg.D_BATT_PCT_IN);
            toggle(c, "Li-ion battery cell", "Battery drawn as a cell with a liquid fill and % inside, coloured "
                    + "by level (green while charging). Replaces the stock icon", Cfg.BATT_CELL, Cfg.D_BATT_CELL);
            toggle(c, "Battery details beside the cell", "Voltage, charge left / total (Ah), time to empty "
                    + "(↓) or to full (ϟ)", Cfg.BATT_TEXT, Cfg.D_BATT_TEXT);
            toggle(c, "Clock seconds + timezone", "Seconds and IST beside the time, and a thin line under it "
                    + "that fills over each minute", Cfg.CLOCK_SECONDS, Cfg.D_CLOCK_SECONDS);
            toggle(c, "Next sunrise / sunset under the clock", "Small clock moved up; under it the next sun event "
                    + "(↑ sunrise or ↓ sunset) with its time", Cfg.CLOCK_SUN, Cfg.D_CLOCK_SUN);
        }
        toggle(c, "Auto contrast", "Measures the brightness under the status bar and switches to dark "
                + "text on light screens (and white on dark), even when an app asks for the wrong one",
                Cfg.AUTO_CONTRAST, Cfg.D_AUTO_CONTRAST);
        if (OLD_LAYOUTS) {
            toggle(c, "Text around the camera", "Battery health % curved over the camera hole (amber under 90 %, red under 80 %), "
                    + "charge cycles under it (the lower line steps aside when notification icons reach under the camera)",
                    Cfg.CAM_TEXT, Cfg.D_CAM_TEXT);
        }
        toggle(c, "Weather details", "Beside the weather icon: rain chance (next 3 h), UV, "
                + "humidity, wind, AQI (Open-Meteo, every 15 min). Least important drop out when space is short. The AQI pill shows the number and a letter: "
                + "G good (0-50), M moderate (51-100), S unhealthy for sensitive groups (101-150), U unhealthy (151-200), "
                + "V very unhealthy (201-300), H hazardous (301+). Colour goes green, yellow, orange, red, purple, maroon",
                Cfg.WX_DETAILS, Cfg.D_WX_DETAILS);
        slider(c, "Earthquakes shown from", "mag", Cfg.QUAKE_MIN, Cfg.D_QUAKE_MIN, 10, 70);
        slider(c, "Earthquakes within", "km100", Cfg.QUAKE_KM, Cfg.D_QUAKE_KM, 1, 200);
        button(c, "SIM box guide", false, v -> showSimGuide(-1));
        button(c, "Save status bar picture (layout test)", false, v -> {
            Toast.makeText(this, "Taking the picture…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                final StatusShot.Result r = StatusShot.take(getApplicationContext());
                runOnUiThread(() -> {
                    android.app.AlertDialog.Builder bd = new android.app.AlertDialog.Builder(this).setMessage(r.msg).setPositiveButton("OK", null);
                    if (r.uri != null) bd.setNeutralButton("Share", (d, w) -> {
                        android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_SEND);
                        it.setType("image/png"); it.putExtra(android.content.Intent.EXTRA_STREAM, r.uri);
                        it.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(android.content.Intent.createChooser(it, "Share status bar picture"));
                    });
                    bd.show();
                });
            }).start();
        });
        button(c, "Live diagnostics", false, v -> showSatellites());
        button(c, "Heat and power diagnostics", false, v -> showHeatReport());
        button(c, "Heat and power history (diagnostics graph)", false, v -> showHeatHistory(3 * 3600_000L));
        button(c, "UV guide and sunscreen timer", false, v -> showUvGuide());
        button(c, "Place guide: soil and plants", false, v -> showPlaceGuide());
        button(c, "Sky guide: what is up now", false, v -> showSkyGuide());
        button(c, "Play Integrity checks (history)", false, v -> showIntegrity());
        if (OLD_LAYOUTS) {
            toggle(c, "Speedometer with the net speed", "Small gauge for download speed (1 K/s to 100 M/s); "
                    + "arrows green ↓ / blue ↑, speeds coloured idle grey → fast green/blue → very fast cyan",
                    Cfg.SPEED_GAUGE, Cfg.D_SPEED_GAUGE);
            toggle(c, "SINR with the tower counts", "Nodes symbol coloured by signal quality (green good, yellow fair, "
                    + "red poor) and the SINR in dB after the distance; the number hides first when space is short",
                    Cfg.SINR_MAST, Cfg.D_SINR_MAST);
            toggle(c, "Temperatures + current together", "Thermometer per line, temperature coloured by heat, then "
                    + "amps / watts in the same block", Cfg.COMBINE_TEMPS, Cfg.D_COMBINE_TEMPS);
            toggle(c, "SNR next to dBm", "Signal-to-noise per SIM (dB), left of the camera. Hidden by itself if there's no room",
                    Cfg.SHOW_SNR, Cfg.D_SHOW_SNR);
        }
        toggle(c, "Combined VoLTE / VoNR badge", "One \"Vo\" badge with each SIM's voice type "
                + "(NR / LTE / WiFi) instead of the two stock icons", Cfg.VO_BADGE, Cfg.D_VO_BADGE);
        toggle(c, "Network type with activity arrows", "5G / 4G+ with ↓↑ underneath that light up "
                + "while data flows (like Samsung)", Cfg.NET_TYPE, Cfg.D_NET_TYPE);
        section("Animations");
        c = card();
        toggle(c, "Low heat mode", "Turns off the always-moving animations (heat pulse, shine, moving "
                + "arrows, charging flow) and the ping dot, so the status bar isn't redrawn many times a "
                + "second. Recommended", Cfg.LOW_HEAT, Cfg.D_LOW_HEAT);
        choice(c, "Sky motion", Cfg.SKY_MOTION, Cfg.D_SKY_MOTION, new String[]{"Smooth: clouds and rain move 10 times a second (uses the most power)", "Slow: 2 times a second (recommended)", "Still: the sky holds still and changes every 5 seconds"});
        toggle(c, "Animation: heat warning", "Warm or hot temperatures pulse orange / red (cool ones stay calm)",
                Cfg.ANIM_HEAT, Cfg.D_ANIM_HEAT);
        if (OLD_LAYOUTS) {
            toggle(c, "Speed sparkline", "A tiny live graph of the last 30 seconds after the speed numbers "
                    + "(↓ green, ↑ blue)", Cfg.ANIM_SPARK, Cfg.D_ANIM_SPARK);
        }
        toggle(c, "Idle dimming", "After a few quiet seconds the status bar softens; an item lights up "
                + "again when its value changes", Cfg.ANIM_DIM, Cfg.D_ANIM_DIM);
        if (OLD_LAYOUTS) {
            toggle(c, "Colourful network type", "5G purple-cyan, 4G+ orange-pink, 4G green, 3G yellow, 2G red "
                    + "(on the tower view)", Cfg.NET_COLOR, Cfg.D_NET_COLOR);
            toggle(c, "Animation: network type shine", "A light sweeps across 5G / 4G+ every few seconds",
                    Cfg.ANIM_SHIMMER, Cfg.D_ANIM_SHIMMER);
            toggle(c, "Animation: moving arrows", "The ↓↑ arrows move while data flows (faster look, still only "
                    + "while there is traffic)", Cfg.ANIM_ARROWS, Cfg.D_ANIM_ARROWS);
            toggle(c, "Animation: 5G / 4G switch flash", "The network type pops once when it changes, so you see "
                    + "when NetSwitch acted", Cfg.ANIM_SWITCH, Cfg.D_ANIM_SWITCH);
        }
        toggle(c, "Animation: charging flow", "A light runs along the battery fill while charging",
                Cfg.ANIM_CHARGE, Cfg.D_ANIM_CHARGE);
        if (OLD_LAYOUTS) {
            toggle(c, "Animation: bell wiggle + count roll", "The bell shakes and the number rolls up when a "
                    + "new notification arrives", Cfg.ANIM_BELL, Cfg.D_ANIM_BELL);
        }
        toggle(c, "Ping dot", "A small dot beside the network type, ticks every few seconds: green fast, "
                + "amber slow, red no connection (a tiny TCP connect to 1.1.1.1)", Cfg.ANIM_PING, Cfg.D_ANIM_PING);
        button(c, "▶  Test the animations now", true, v -> {
            try {
                sendBroadcast(new android.content.Intent("com.netspeed.xposed.ANIM_TEST"));
                android.widget.Toast.makeText(this, "Playing for 8 seconds - look at the status bar",
                        android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) { }
        });
        section("Towers");
        c = card();
        toggle(c, "Towers around per SIM", "Dish + estimated number of towers each SIM can hear (from "
                + "the cells' ids). Hidden by itself if there's no room before the camera", Cfg.SHOW_CELLS, Cfg.D_SHOW_CELLS);
        toggle(c, "Look up towers online", "For idle SIMs and 5G: tower position from BeaconDB + your "
                + "location, shown with ~. Sends only tower IDs", Cfg.TOWER_DB, Cfg.D_TOWER_DB);
        toggle(c, "Learn tower positions", "For towers no database knows (mostly 5G): records your "
                + "position + signal while you move and estimates the tower. Stays on this phone",
                Cfg.LEARN, Cfg.D_LEARN);
        toggle(c, "Learn while riding", "When you're on the move (not standing still), one GPS fix a minute so towers get "
                + "learned even without a navigation app. Also learns the nearby towers your phone hears. A little battery "
                + "while moving, none while still", Cfg.RIDE_GPS, Cfg.D_RIDE_GPS);
        toggle(c, "Weak 5G → 4G (quota-guarded)", "When the data SIM's 5G is weak for 20 s, switch it to 4G, retry 5G after "
                + "3 min (longer if it keeps failing). Stops once 4G data today reaches the limit below, so a 5G-unlimited / "
                + "4G-capped plan isn't eaten. The bar shows 4G·used while it's on. Needs \"Phone Services\" in LSPosed; reboot once",
                Cfg.NR_FALLBACK, Cfg.D_NR_FALLBACK);
        choice(c, "5G / 4G mode", Cfg.NR_MODE, Cfg.D_NR_MODE, new String[]{"Automatic",
                "Always allow 5G (fallback off)", "Force 4G (5G off)"});
        toggle(c, "Check the internet, not just the signal", "Weak 5G also counts when the connection carries no data: "
                + "when nothing is coming in, a tiny request is sent over mobile data about once a minute (3 failures in a row = "
                + "bad). The automatic switching also pauses on Wi-Fi, in airplane mode and with mobile data off",
                Cfg.NR_NETCHECK, Cfg.D_NR_NETCHECK);
        toggle(c, "Move data to the other SIM when this one is weak", "Turns on Android's own \"switch mobile data automatically\" "
                + "for both SIMs: when the data SIM is weak the phone uses the other SIM's data, and goes back when it recovers "
                + "(the phone checks the other SIM really has internet). Turning this off puts the setting back as it was. "
                + "Needs \"Phone Services\" in LSPosed; reboot once", Cfg.AUTO_SIM, Cfg.D_AUTO_SIM);
        toggle(c, "Notify on Play Integrity checks", "A notification each time an app asks Google Play whether this "
                + "phone passes Play Integrity (watch only: nothing is blocked or changed). Needs Google Play Store "
                + "ticked in LSPosed; force-stop Play Store or reboot once", Cfg.INTEGRITY_NOTIFY, Cfg.D_INTEGRITY_NOTIFY);
        toggle(c, "Toast on Play Integrity checks", "A short pop-up message with the app's name each time it checks",
                Cfg.INTEGRITY_TOAST, Cfg.D_INTEGRITY_TOAST);
        toggle(c, "Qualcomm 5G+ reader (experimental)", "Asks Qualcomm's own phone helper (the one stock HyperOS uses) "
                + "whether each SIM's 5G counts as 5G+, and shows 5G+ when it does. What it finds is listed in "
                + "\"Live diagnostics\". Needs com.qti.phone ticked in LSPosed; reboot after switching. "
                + "Turn off if calls or data act up", Cfg.QTI_NR, Cfg.D_QTI_NR);
        toggle(c, "Tide at the nearest coast", "Row 3 after AQI: rising ↑ / falling ↓, height now, next high (H) or "
                + "low (L) water and the place. Model data (Open-Meteo Marine), about ±20 min. Amber = spring tide",
                Cfg.TIDE, Cfg.D_TIDE);
        toggle(c, "Satellite strip: navigation satellites", "Rotates in the strip above the clock when no satellite is up: how many GPS, "
                + "NavIC, BeiDou, Galileo and GLONASS satellites your phone is using. Shows only while the phone's GPS is working", Cfg.SKY_NAV, Cfg.D_SKY_NAV);
        toggle(c, "Satellite strip: people in space", "How many people are in space now (a community list of the ISS and Tiangong crews, checked daily)",
                Cfg.SKY_PEOPLE, Cfg.D_SKY_PEOPLE);
        toggle(c, "Satellite strip: stargazing score", "1 to 10 from cloud cover, Moon brightness and how dark it is. Only after dark",
                Cfg.SKY_STAR, Cfg.D_SKY_STAR);
        toggle(c, "Satellite strip: planet pairings", "When the Moon is within 4 degrees of a bright planet, or two planets within 3, "
                + "and they are above the horizon in a dark sky", Cfg.SKY_PAIR, Cfg.D_SKY_PAIR);
        toggle(c, "Satellite strip: close asteroids", "An asteroid passing within 4 Moon-distances in the next 2 weeks (NASA/JPL), with a rough size guess. "
                + "Most days nothing shows", Cfg.SKY_AST, Cfg.D_SKY_AST);
        toggle(c, "Satellite strip: Sun distance", "How far the Sun is from you now in AU and km, with the light travel time. "
                + "Worked out on the phone; changes through the year (closest in January, farthest in July)", Cfg.SKY_SUN, Cfg.D_SKY_SUN);
        toggle(c, "Satellite strip: Voyager distance", "How far Voyager 1 and Voyager 2 are from the Sun in AU and km, with the light travel time. "
                + "Position from NASA/JPL Horizons once a day, moved on at the probe's speed in between", Cfg.SKY_VOY, Cfg.D_SKY_VOY);
        slider(c, "Satellite strip: seconds per frame", "s", Cfg.SKY_SPEED, Cfg.D_SKY_SPEED, 3, 8);
        toggle(c, "Satellite strip: next rocket launch", "The next launch in the coming days: rocket, mission, site and time. "
                + "From Launch Library 2 (free), checked every 3 hours. Launch times can move; \"live now\" uses the webcast flag", Cfg.SKY_LAUNCH, Cfg.D_SKY_LAUNCH);
        slider(c, "Launches: show within", "days", Cfg.LCH_DAYS, Cfg.D_LCH_DAYS, 1, 30);
        choice(c, "Launches: name shown", Cfg.LCH_NAME, Cfg.D_LCH_NAME, new String[]{"Mission name (Amazon Leo, Starlink...)", "Rocket name (Vulcan, Falcon 9...)", "Both"});
        toggle(c, "Launches: agency", "SpaceX, ISRO, ULA... (short name)", Cfg.LCH_AGENCY, Cfg.D_LCH_AGENCY);
        toggle(c, "Launches: country of the launch site", "USA, India, China...", Cfg.LCH_COUNTRY, Cfg.D_LCH_COUNTRY);
        toggle(c, "Launches: spaceport name", "Cape Canaveral SFS, Satish Dhawan Space Centre...", Cfg.LCH_SITE, Cfg.D_LCH_SITE);
        toggle(c, "Launches: pad name", "SLC-40, Second Launch Pad...", Cfg.LCH_PAD, Cfg.D_LCH_PAD);
        choice(c, "Launches: time shown as", Cfg.LCH_TIME, Cfg.D_LCH_TIME, new String[]{"Countdown (in 2d 4h)", "Date and time (21 Nov 23:10)"});
        toggle(c, "Launches: times in UTC", "Off = your phone's time zone", Cfg.LCH_UTC, Cfg.D_LCH_UTC);
        choice(c, "Launches: only from", Cfg.LCH_REGION, Cfg.D_LCH_REGION, new String[]{"Everywhere", "India", "USA", "China", "Europe (Kourou)", "Russia", "Japan"});
        toggle(c, "Launches: say \"live now\"", "Adds \"live\" while a webcast is on around the launch time", Cfg.LCH_LIVE, Cfg.D_LCH_LIVE);
        toggle(c, "Show the bar on the lock screen", "Keeps the readable status bar (clock, strip, place line) visible while the phone is locked, "
                + "instead of hiding it. The stock lock-screen bar may still show underneath", Cfg.LOCK_BAR, Cfg.D_LOCK_BAR);
        toggle(c, "Reservoir watch chip", "Rotates in the place line with the river, rain and soil frames: the nearest dam (from your GPS, "
                + "among the 178 CWC reservoirs), how full it is, distance and hydro MW, with a dot for the state: red danger low (under 20%), orange low (20-40), green normal (40-90), "
                + "blue high (90-95), purple danger high (over 95). CWC updates once a day. \"Danger\" is % full, not a flood warning",
                Cfg.DAM, Cfg.D_DAM);
        toggle(c, "Reservoir watch notifications", "A notification once a day when the nearest dam changes state, or when "
                + "its level has gone steady (0.5 points a day or less over 3 days)", Cfg.DAM_NOTIF, Cfg.D_DAM_NOTIF);
        button(c, "Reservoir details", false, v -> showDam());
        toggle(c, "Disasters & solar storms", "Row 4: a current GDACS orange / red alert (cyclone, flood, volcano, "
                + "wildfire, drought, quake) replaces the quake line, with distance and direction from you. A magnetic "
                + "storm (NOAA Kp 5 or more) adds 🧲Kp·G at the end. Quiet most days", Cfg.HAZARDS, Cfg.D_HAZARDS);
        toggle(c, "River & dam frame", "On the place line: the nearest river with width and distance, the nearest dam within 10 km, and the flow of the nearest river to wherever you are, in m\u00b3/s, with \u25B2 rising / "
                + "\u25BC falling since yesterday and \"high\" or \"low\" against the last 30 days. A computer model (Open-Meteo "
                + "flood service), about 5 km grid, not a gauge. Hidden where there is no river", Cfg.RIVER, Cfg.D_RIVER);
        toggle(c, "Rain & soil frame", "Place line frame: rain in the last 24 h and the next 24 h, soil moisture. Model data (Open-Meteo)",
                Cfg.EL_RAIN, Cfg.D_EL_RAIN);
        toggle(c, "Soil frame", "Place line frame: soil type (clay, loam, sandy...) from the clay, silt and sand share of the top soil (ISRIC SoilGrids), plus moisture",
                Cfg.EL_SOIL, Cfg.D_EL_SOIL);
        toggle(c, "Rock frame", "Place line frame: the kind of bedrock under you (basalt, granite, sandstone...) with its age, from Macrostrat's world geology map. Coarse: a regional rock type, not a local survey",
                Cfg.EL_ROCK, Cfg.D_EL_ROCK);
        toggle(c, "Habitat and plants frames", "Place line frames: the forest region you are in, what the map shows within 5 km (built-up, farmland, forest, grassland...) and the plants people have logged within 10 km (iNaturalist). Records are what was logged, not a full survey",
                Cfg.EL_HABITAT, Cfg.D_EL_HABITAT);
        toggle(c, "Wetland frame", "Place line frames: the nearest wetland or lake within 25 km, and the nearest Ramsar site within 200 km. From OpenStreetMap, so only what mappers have drawn; a Ramsar site shows only if OpenStreetMap has it under its official name",
                Cfg.EL_WETLAND, Cfg.D_EL_WETLAND);
        toggle(c, "Power frame", "Place line frame: nearest power line (voltage, operator if mapped) and substation within 5 km, from OpenStreetMap. Only what mappers have drawn",
                Cfg.EL_POWER, Cfg.D_EL_POWER);
        toggle(c, "Land frame", "Place line frame: height, climb, nearest named hill or peak with distance and direction",
                Cfg.EL_LAND, Cfg.D_EL_LAND);
        toggle(c, "Quake frame", "Place line frame: the latest M2.5+ quake within 300 km in the last 30 days (USGS), or that there was none",
                Cfg.EL_QUAKE, Cfg.D_EL_QUAKE);
        toggle(c, "Earth frame", "Place line frame: full place: taluka, district and state",
                Cfg.EL_EARTH, Cfg.D_EL_EARTH);
        slider(c, "Seconds per frame", "s", Cfg.EL_SPEED, Cfg.D_EL_SPEED, 3, 8);
        choice(c, "Frame order", Cfg.EL_ORDER, Cfg.D_EL_ORDER, new String[]{"River, rain, soil, power, land, quake, place",
                "Place and land first, then water and power", "Water first, then land, power, quake", "Quake and rain first (alerts first)"});
        slider(c, "Dam search radius", "km", Cfg.EL_DAM_KM, Cfg.D_EL_DAM_KM, 3, 20);
        toggle(c, "Fault lines frame", "Rotates in the place line: the nearest mapped fault and the nearest named one, "
                + "with distance, direction and type. India: GSI atlas (via AFDI); South / Central Asia: GEM. Mapped faults only, "
                + "not a hazard warning", Cfg.FAULT, Cfg.D_FAULT);
        slider(c, "Fault line search radius", "km", Cfg.FAULT_KM, Cfg.D_FAULT_KM, 50, 1000);
        button(c, "Fault line details", false, v -> showFaults());
        slider(c, "Power line search radius", "km", Cfg.EL_POWER_KM, Cfg.D_EL_POWER_KM, 2, 10);
        slider(c, "Peak search radius", "km", Cfg.EL_PEAK_KM, Cfg.D_EL_PEAK_KM, 10, 60);
        toggle(c, "Weather extras on the sky", "Haze over the sky when the air quality is poor (AQI), a cool-blue or warm-red tint when it is cold or "
                + "hot. Gauge-line look only", Cfg.WX_FX, Cfg.D_WX_FX);
        toggle(c, "Cloud and wind frames (row 4)", "Takes turns with the quake / disaster item in row 4: cloud type with its base height and cover, "
                + "which way the low, mid and high clouds drift, and the kind of wind with gusts. Estimates from the weather model. "
                + "A disaster alert always stays on top", Cfg.WX_SKY, Cfg.D_WX_SKY);
        toggle(c, "Weather on row 4", "Puts the temperature, high / low, air quality, visibility, cloud and rain on row 4. Row 3 then holds "
                + "the tide, the sea temperature and the disaster / quake item", Cfg.WX_ROW4, Cfg.D_WX_ROW4);
        toggle(c, "Earth: plate, boundary, El Ni\u00f1o", "Row 4 (right) shows the El Ni\u00f1o state; the place line (Ravet \u00b7 \u2026) takes turns showing the tectonic plate you are on and the nearest plate boundary with its type, distance, direction and rate. Plate data: Bird (2003) PB2002; El Ni\u00f1o: NOAA. Approximate", Cfg.EARTH_SLOT, Cfg.D_EARTH_SLOT);
        toggle(c, "Row 3: tide and sea side by side", "With weather on row 4: row 3 shows the tide, the sea temperature, the wave height and period, the sea "
                + "state and the ocean current all at once; the disaster item moves to row 4. When row 3 is too tight the current, then the sea state "
                + "drop out, and below that the sea frames take turns in the tide slot instead", Cfg.SEA_ROW3, Cfg.D_SEA_ROW3);
        toggle(c, "Sea frames in the tide slot", "The tide slot takes turns showing the tide, the wave height and period, the sea state and the ocean "
                + "current. Estimates from the marine model at the tide place; shown only where the sea is near", Cfg.SEA_FRAMES, Cfg.D_SEA_FRAMES);
        toggle(c, "World map (day and night)", "A faint world map over the weather sky: the night side is shaded, big cities light up "
                + "there, a yellow dot marks where the sun is overhead and a white ring marks you", Cfg.BG_MAP, Cfg.D_BG_MAP);
        
        textField(c, "ReliefWeb app name (optional)", "Adds UN OCHA ReliefWeb disasters (floods, epidemics, landslides…) to the "
                + "row-4 rotation. Request an app name at apidoc.reliefweb.int, paste it here once approved", Cfg.RELIEFWEB_APP, "e.g. dmk-netspeed", false, "https://apidoc.reliefweb.int/");
        toggle(c, "Dim status bar with screen brightness", "The whole bar follows the brightness you set: full from half "
                + "brightness up, easing down to about 55 % at the lowest so it isn't glaring in a dark room", Cfg.DIM_NIGHT, Cfg.D_DIM_NIGHT);
        choice(c, "Status bar layout", Cfg.LAYOUT, Cfg.D_LAYOUT, new String[]{"Classic (data and Q in a column at the far right)",
                "Folded (data and Q under the data SIM box, more room on the left)",
                "Stretched (as Folded, SIM boxes also take the width of the old column)",
                "Tower column (stretched, boxes from camera to edge, tower details on the right)"});
        toggle(c, "Moon phase and day type by the clock", "Initials after the weekday, e.g. \"D\u00b7WxG\" = Day, Waxing gibbous (needs \"SIM boxes up\")", Cfg.SEA_MOON, Cfg.D_SEA_MOON);
        toggle(c, "SIM boxes up (moon line off)", "Drops the moon phase and time-name line above the SIM boxes and moves the boxes up into its space", Cfg.SIM_UP, Cfg.D_SIM_UP);
        toggle(c, "Q for the second SIM", "The SIM that is not carrying data also gets its own signal quality Q in the bottom row of its box (folded layouts)", Cfg.Q_SECOND, Cfg.D_Q_SECOND);
        toggle(c, "Tower column: site and sector", "Tower number and the side of it you are on (Tower column layout only)", Cfg.TC_SITE, Cfg.D_TC_SITE);
        toggle(c, "Channel width", "10 / 20 / 100 MHz of the serving channel, after the cells-heard number on the band line; ~ when estimated", Cfg.TC_BW, Cfg.D_TC_BW);
        toggle(c, "Tower column: strongest neighbour", "Band and signal of the best other tower the phone hears", Cfg.TC_NB, Cfg.D_TC_NB);
        toggle(c, "Tint behind the SIM boxes", "A faint colour behind each SIM box, from its signal quality (Tower column layout only)", Cfg.TC_TINT, Cfg.D_TC_TINT);
        toggle(c, "Tower icon", "A small cell-tower mast before the distance to the tower. Off: the satellite-dish symbol as before", Cfg.TOWER_MAST, Cfg.D_TOWER_MAST);
        choice(c, "Data used in the SIM box", Cfg.DATA_PERIOD, Cfg.D_DATA_PERIOD, new String[]{"Daily (D)", "Monthly (M)", "Both at once (D and M)", "D and M taking turns (5 s)"});
        toggle(c, "Boxed Q for signal quality", "The data SIM's signal quality figure gets a small box with a Q in it", Cfg.Q_BOX, Cfg.D_Q_BOX);
        toggle(c, "4G / 5G labels in the data box", "Off: 4G and 5G are told apart by colour only (4G green to red by how close it is to the limit, 5G teal)", Cfg.DATA_LABELS, Cfg.D_DATA_LABELS);
        choice(c, "Coast distance and tide place", Cfg.COAST_MODE, Cfg.D_COAST_MODE, new String[]{"Nearest water (creeks too)", "Open sea"});
        choice(c, "Tide place chosen by", Cfg.TIDE_BY, Cfg.D_TIDE_BY, new String[]{"Nearest tide point (straight line)", "Nearest beach (shows beach name)"});
        choice(c, "Tide place", Cfg.TIDE_PLACE, Cfg.D_TIDE_PLACE, Tide.choices());
        textField(c, "SIM 1 bands (lock)", "Only these bands, e.g. \"n78 B3 B40\" (B = 4G, n = 5G). Only B given: 5G stays free; "
                + "only n given: 4G stays free. Empty = all bands. Not saved in the modem: a reboot or emptying the field undoes it. "
                + "Needs \"Phone Services\" in LSPosed. Result shows in \"Live diagnostics\"", Cfg.BANDS1, "e.g. n78 B3 B40", false);
        textField(c, "SIM 2 bands (lock)", "Same for SIM 2", Cfg.BANDS2, "e.g. B3 B40", false);
        toggle(c, "Group tints", "Faint coloured backgrounds that group the bar: time, device, speed, mobile network, "
                + "sky, weather, Earth & hazards. No extra space used", Cfg.ZONES, Cfg.D_ZONES);
        choice(c, "Corner beside the SIMs", Cfg.CORNER, Cfg.D_CORNER, new String[]{"Off",
                "5G share today (5G over %)", "Signal quality now (Q over %)"});
        slider(c, "4G data limit for the fallback", "MB", Cfg.NR_QUOTA_MB, Cfg.D_NR_QUOTA_MB, 200, 2000);
        textField(c, "OpenCelliD token (optional)", "Backup database with better coverage. Paste the "
                + "token from my.opencellid.org (starts with pk.). Stays on this phone.", Cfg.OCID_KEY, "pk.…", true, "https://my.opencellid.org/");
        textField(c, "WeatherAPI.com key (optional)", "Free key from weatherapi.com. When set, temperature, condition, "
                + "rain chance, wind, UV, cloud and high/low come from WeatherAPI (corrected with real stations) instead "
                + "of the forecasts. Stays on this phone.", Cfg.WAPI_KEY, "key…", true, "https://www.weatherapi.com/signup.aspx");
        toggle(c, "Weather", "From OmniJaws", Cfg.SHOW_WEATHER, Cfg.D_SHOW_WEATHER);
        if (OLD_LAYOUTS) {
            toggle(c, "Hide net speed when idle", "After 10 s below 1 KB/s", Cfg.HIDE_IDLE, Cfg.D_HIDE_IDLE);
        }
        toggle(c, "Heat throttle (root)", "When hot: caps every CPU cluster and the GPU to the speeds below, drops the screen to 60 Hz and clears background apps. Releases when cooled and restores your refresh rate. Stands down (does nothing) while another app such as Rider Cool already caps the CPU at or below these speeds, and only gives back the limits it set itself. Needs root for NetSpeed. Restart SystemUI to apply", Cfg.THR, Cfg.D_THR);
        slider(c, "Throttle when battery reaches", "\u00B0C", Cfg.THR_BAT, Cfg.D_THR_BAT, 36, 50);
        slider(c, "Throttle when CPU reaches", "\u00B0C", Cfg.THR_CPU, Cfg.D_THR_CPU, 55, 95);
        slider(c, "GPU speed while throttled", "% of max", Cfg.THR_GPU, Cfg.D_THR_GPU, 30, 90);
        slider(c, "CPU speed while throttled", "% of max", Cfg.THR_PCT, Cfg.D_THR_PCT, 30, 90);
        toggle(c, "Dim the screen while throttled", "Also caps the brightness (switches to manual brightness while hot, never raises it) and puts your brightness and auto mode back when cooled. Off by default: it can make the screen hard to read in sun", Cfg.THR_DIM, Cfg.D_THR_DIM);
        slider(c, "Brightness while throttled", "% of max", Cfg.THR_DIM_PCT, Cfg.D_THR_DIM_PCT, 20, 90);
        toggle(c, "Heat warning popup", "A Device Too Hot popup (like the Critical Battery one) when the battery or CPU passes the limits below. Comes back after 10 minutes if still hot, closes itself when cooled. Restart SystemUI to apply", Cfg.HEAT, Cfg.D_HEAT);
        toggle(c, "Flash a warning on the status bar", "When power draw or heat is high, the top strip flashes a red tag with the cause for the time below, then stays quiet for 10 minutes unless it gets clearly worse", Cfg.FLASH, Cfg.D_FLASH);
        slider(c, "Flash when power reaches", "W", Cfg.FLASH_W, Cfg.D_FLASH_W, 3, 15);
        slider(c, "Flash when battery reaches", "\u00B0C", Cfg.FLASH_BAT, Cfg.D_FLASH_BAT, 36, 50);
        slider(c, "Flash when CPU reaches", "\u00B0C", Cfg.FLASH_CPU, Cfg.D_FLASH_CPU, 55, 95);
        choice(c, "Flash for", Cfg.FLASH_SEC, Cfg.D_FLASH_SEC, new String[]{"10 seconds", "30 seconds", "60 seconds"});
        slider(c, "Warn when battery reaches", "\u00B0C", Cfg.HEAT_BAT, Cfg.D_HEAT_BAT, 38, 50);
        slider(c, "Warn when CPU reaches", "\u00B0C", Cfg.HEAT_CPU, Cfg.D_HEAT_CPU, 60, 100);
        slider(c, "Freeze sky at or below battery % (0 = never)", "%", Cfg.SKY_LOW, Cfg.D_SKY_LOW, 0, 50);
        skyChoice(c);
        slider(c, "Sky opacity", "%", Cfg.SKY_OPACITY, Cfg.D_SKY_OPACITY, 30, 100);
        toggle(c, "Fade the sky into the wallpaper", "The lower part of the sky thins out so the status bar blends into "
                + "the wallpaper or app below instead of ending in a hard edge", Cfg.SKY_FADE, Cfg.D_SKY_FADE);
        toggle(c, "Stats column beside the speed", "Three small rows instead of the graph: CPU load and GPS satellites, ping and deep-sleep share, and the kernel wakelock held longest (root, refreshed every 5 min)", Cfg.STATS_COL, Cfg.D_STATS_COL);
        toggle(c, "Hide battery % in quick settings", "The percentage in the pulled-down shade header; the status bar is unchanged. Restart SystemUI to apply", Cfg.QS_BATT_HIDE, Cfg.D_QS_BATT_HIDE);

        section("Operator name");
        c = card();
        c.addView(text("Shown everywhere on this phone (status bar, lock screen, settings, dialer, apps). "
                + "Network, calls and operator codes stay real. Needs \"Phone Services\" "
                + "(com.android.phone) ticked in the Vector scope. Applies after airplane mode on/off.", 13, sub));
        toggle(c, "Smart carrier name", "Lock screen, quick settings, shade and Settings: the network's own fuller name "
                + "at home (e.g. \"Jio True5G\" instead of \"Jio\"), and your SIM's name plus the network's in another state "
                + "(\"Airtel(IND airtel)\", \"BSNL(Dolphin)\"). A name typed below wins. Needs \"Phone Services\" in "
                + "NetSpeed's LSPosed scope; reboot or toggle airplane mode to refresh", Cfg.SMART_CARRIER, Cfg.D_SMART_CARRIER);
        toggle(c, "Roaming between circles", "When your SIM's circle and the tower's circle differ (e.g. a Maharashtra SIM on a Mumbai "
                + "tower), apps, System UI and Settings are told the phone is roaming. Only what leaves the phone service changes: "
                + "the modem, calls, SMS and the phone's own data handling stay real. Off by default; turn it off if calls or 5G act "
                + "strangely. Needs \"Phone Services\" in NetSpeed's LSPosed scope; applies after airplane mode on/off", Cfg.CIRCLE_ROAM, Cfg.D_CIRCLE_ROAM);
        toggle(c, "Hide side R icon", "Hides the big R beside the signal bars while roaming; the small raised R stays. "
                + "Needs System UI in NetSpeed's LSPosed scope; restart System UI to apply", Cfg.HIDE_SIDE_R, Cfg.D_HIDE_SIDE_R);
        textField(c, "SIM 1 name", "Empty = real name", Cfg.OP_NAME1, "e.g. Jio", false);
        textField(c, "SIM 2 name", "Empty = real name", Cfg.OP_NAME2, "e.g. Vi", false);

        if (OLD_LAYOUTS) {
            section("Layout mode ⟳");
            c = card();
            toggle(c, "Two-row status bar", "Off = original one-row layout", Cfg.DUAL, Cfg.D_DUAL);
            toggle(c, "Net speed after weather", "Off = on the right, before the icons",
                    Cfg.SPEED_LEFT, Cfg.D_SPEED_LEFT);
        }

        section("Launcher");
        c = card();
        predictToggle(c);

        section("Volume steps");
        c = card();
        c.addView(text(SettingsVolume.summary(this) + ". Needs the Volume Steps Magisk module; "
                + "applies after a reboot.", 13, sub));
        button(c, "Change volume steps", false, v -> SettingsVolume.show(this));

        updatesSection();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(20);
        pages[6].addView(row, rlp);
        button(row, "Restart System UI", true, v -> restartSystemUi());
        button(row, "Reset", false, v -> new AlertDialog.Builder(this)
                .setMessage("Reset all settings to defaults?")
                .setPositiveButton("Reset", (d, w) -> {
                    sp.edit().clear().commit();
                    changed();
                    build();
                })
                .setNegativeButton("Cancel", null)
                .show());
        buildHome();
        showPage(curPage);
    }

    private void buildHome() {
        LinearLayout row = null;
        for (int i = 0; i < 8; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                home.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setPadding(dp(14), dp(14), dp(14), dp(14));
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, CAT_COL[i]);
            g.setCornerRadius(dp(20));
            t.setBackground(g);
            TextView ic = text(CAT_ICON[i], 26, 0xFFFFFFFF);
            t.addView(ic);
            TextView nm = text(CAT_NAME[i], 16, 0xFFFFFFFF);
            nm.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            nm.setPadding(0, dp(6), 0, 0);
            t.addView(nm);
            t.addView(text(CAT_SUB[i], 12, 0xFFB8C7D8));
            final int idx = i;
            t.setOnClickListener(v -> showPage(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(128), 1f);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            if (i == 6) { lp.weight = 1f; }
            row.addView(t, lp);
        }
        if (row != null && row.getChildCount() == 1) {   // odd tile: pad the row so it keeps half width
            View pad = new View(this);
            row.addView(pad, new LinearLayout.LayoutParams(0, 1, 1f));
        }
    }

    private void showPage(int p) {
        curPage = p;
        home.setVisibility(p < 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < 8; i++) pages[i].setVisibility(i == p ? View.VISIBLE : View.GONE);
        if (infoPage != null) { infoPage.setVisibility(p == 8 ? View.VISIBLE : View.GONE); if (p == 8) fillInfo(); }
        if (scrollRef != null) scrollRef.scrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        if (curPage >= 0) showPage(-1);
        else super.onBackPressed();
    }

    // ------------------------------------------------------------ updates

    private TextView updStatus, footDot;
    private LinearLayout gate;
    private volatile int rootOk = -1;   // -1 not asked yet, 0 no, 1 yes

    private static final String[] ROOT_APPS = {"com.resukisu.resukisu", "me.weishu.kernelsu", "com.rifsxd.ksunext", "com.sukisu.ultra",
            "me.bmax.apatch", "com.topjohnwu.magisk", "io.github.vvb2060.magisk", "io.github.huskydg.magisk", "com.dergoogler.mmrl"};

    /** The installed root manager app (ReSukiSU, KernelSU, APatch, Magisk...), or null. */
    private String rootManager() {
        for (String p : ROOT_APPS) {
            try { getPackageManager().getPackageInfo(p, 0); return p; } catch (Exception ignored) { }
        }
        return null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshGate();
        new Thread(() -> {
            int ok = 0;
            try {
                Process pr = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
                if (pr.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) ok = pr.exitValue() == 0 ? 1 : 0;
                else pr.destroy();
            } catch (Exception ignored) { }
            rootOk = ok;
            runOnUiThread(this::refreshGate);
        }).start();
    }

    private boolean usageOk() {
        try {
            android.app.AppOpsManager a = getSystemService(android.app.AppOpsManager.class);
            return a.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName())
                    == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) { return true; }
    }

    private void gateRow(String title, String why, boolean ok, String button, Runnable open) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(card);
        r.setBackground(g);
        r.addView(text((ok ? "\u2705  " : "\u274C  ") + title, 17, fg));
        r.addView(text(ok ? "allowed" : why, 13, sub));
        if (!ok && open != null) {
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText(button);
            b.setTextColor(isDark() ? 0xFF06251C : Color.WHITE);
            GradientDrawable bg2 = new GradientDrawable();
            bg2.setCornerRadius(dp(24));
            bg2.setColor(accent);
            b.setBackground(bg2);
            b.setOnClickListener(v -> open.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(44));
            lp.topMargin = dp(8);
            r.addView(b, lp);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.bottomMargin = dp(10);
        gate.addView(r, rl);
    }

    private void refreshGate() {
        if (gate == null) return;
        final String pkg = getPackageName();
        boolean notif = getSystemService(android.app.NotificationManager.class).areNotificationsEnabled();
        boolean usage = usageOk();
        boolean write = android.provider.Settings.System.canWrite(this);
        boolean root = rootOk != 0;   // unknown counts as fine until su answers
        if (notif && usage && write && root) { gate.setVisibility(View.GONE); return; }
        gate.removeAllViews();
        TextView t = text("\uD83D\uDD12  Permissions needed", 26, fg);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        gate.addView(t);
        TextView st = text("Field Atlas can't run until these are allowed.", 14, sub);
        st.setPadding(0, dp(6), 0, dp(20));
        gate.addView(st);
        final String mgr = rootManager();
        gateRow("Root access", mgr == null ? "No root manager app found (ReSukiSU, KernelSU, APatch or Magisk). Install one and grant Field Atlas root"
                : "Needed for updates, System UI restart and battery health. Allow Field Atlas in " + mgr, root,
                mgr == null ? "Rescan" : "Open root manager", () -> {
                    Intent i = mgr == null ? null : getPackageManager().getLaunchIntentForPackage(mgr);
                    if (i != null) startActivity(i); else onResume();
                });
        gateRow("Notifications", "For update alerts and Play Integrity notifications", notif, "Open setting", () -> startActivity(
                new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, pkg)));
        gateRow("Usage access", "For launcher app suggestions", usage, "Open setting", () -> startActivity(
                new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + pkg))));
        gateRow("Modify system settings", "Lets Field Atlas write the settings the status bar reads", write, "Open setting", () -> startActivity(
                new Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + pkg))));
        if (!usage || !write) {
            TextView hint = text("Switch greyed out? Open App info \u2192 \u22EE (top right) \u2192 Allow restricted settings, then try again.", 13, sub);
            hint.setPadding(dp(4), dp(6), dp(4), 0);
            gate.addView(hint);
        }
        gate.setVisibility(View.VISIBLE);
    }
    private Button updButton;
    private AppUpdater.Info latest;

    private long installedBuild() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    private void updatesSection() {
        section("Updates");
        LinearLayout c = card();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, dp(12));
        box.addView(text("Installed build " + installedBuild(), 16, fg));
        updStatus = text("Checking…", 13, sub);
        box.addView(updStatus);
        updButton = new Button(this);
        updButton.setAllCaps(false);
        updButton.setTextColor(isDark() ? 0xFF06251C : Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(24));
        g.setColor(accent);
        updButton.setBackground(g);
        updButton.setVisibility(View.GONE);
        updButton.setOnClickListener(v -> doUpdate());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.topMargin = dp(10);
        box.addView(updButton, lp);
        c.addView(box);
        box.setOnClickListener(v -> checkUpdate());   // the card itself is never on screen (RoutedCard sends the box to its page)
        checkUpdate();
    }

    private void checkUpdate() {
        updStatus.setText("Checking…");
        updButton.setVisibility(View.GONE);
        new Thread(() -> {
            try {
                AppUpdater.Info info = AppUpdater.check();
                runOnUiThread(() -> showUpdate(info));
            } catch (Exception e) {
                runOnUiThread(() -> updStatus.setText("Couldn't check: " + e.getMessage() + ". Tap to retry."));
            }
        }).start();
    }

    private void showUpdate(AppUpdater.Info info) {
        latest = info;
        long have = installedBuild();
        if (info.build > have) {
            String notes = info.notes.isEmpty() ? "" : "\n\n" + info.notes;   // the whole change list, not just its first line
            if (footDot != null) { footDot.setText("Updates \u25CF"); footDot.setTextColor(0xFFFF6B6B); }
            updStatus.setText("Build " + info.build + " is available." + notes);
            updButton.setText("Update to build " + info.build);
            updButton.setVisibility(View.VISIBLE);
        } else {
            updStatus.setText("Up to date (checked " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + "). Tap to check again.");
            updButton.setVisibility(View.GONE);
        }
    }

    private void doUpdate() {
        updButton.setEnabled(false);
        updStatus.setText("Downloading…");
        new Thread(() -> {
            String msg;
            try {
                java.io.File apk = AppUpdater.download(getCacheDir());
                runOnUiThread(() -> updStatus.setText("Installing… the status bar restarts in a few seconds."));
                boolean ok = AppUpdater.installAndRestart(apk);
                msg = ok ? null : "Root wasn't granted. Allow root for this app in ReSukiSU and try again.";
            } catch (Exception e) {
                msg = "Update failed: " + e.getMessage();
            }
            final String m = msg;
            if (m != null) runOnUiThread(() -> {
                updStatus.setText(m);
                updButton.setEnabled(true);
            });
        }).start();
    }

    // ------------------------------------------------------------ widgets

    private void title(String t) {
        TextView v = text(t, 26, fg);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setPadding(dp(4), dp(8), 0, dp(4));
        home.addView(v);
    }

    private void note(String t) {
        TextView v = text(t, 13, sub);
        v.setPadding(dp(4), 0, dp(4), dp(12));
        home.addView(v);
    }

    /** One setting found by the search: its title, the words under it, and the row to jump to. */
    private static final class Hit {
        String title, desc;
        View row;
    }

    /** Every setting title (16 sp text) in the page, with the description beside it. */
    private java.util.List<Hit> collect() {
        java.util.List<Hit> out = new java.util.ArrayList<>();
        walk(list, out);
        return out;
    }

    private void walk(View v, java.util.List<Hit> out) {
        if (v instanceof TextView && !(v instanceof android.widget.Button) && !(v instanceof android.widget.EditText)) {
            TextView t = (TextView) v;
            float sz = t.getTextSize() / getResources().getDisplayMetrics().scaledDensity;
            if (Math.abs(sz - 16) < 0.6f && t.getText().length() > 0 && t.getVisibility() == View.VISIBLE) {
                Hit h = new Hit();
                h.title = t.getText().toString();
                StringBuilder d = new StringBuilder();
                if (t.getParent() instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) t.getParent();
                    for (int i = 0; i < g.getChildCount(); i++) {
                        View o = g.getChildAt(i);
                        if (o != t && o instanceof TextView) d.append(((TextView) o).getText()).append(' ');
                    }
                    View row = g;
                    // the clickable row is the title's parent, or its grandparent (title + description in a column)
                    if (!g.isClickable() && g.getParent() instanceof View && ((View) g.getParent()).isClickable()) row = (View) g.getParent();
                    h.row = row;
                } else h.row = t;
                h.desc = d.toString().trim();
                out.add(h);
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), out);
        }
    }

    private void searchDialog(ScrollView scroll) {
        java.util.List<Hit> all = collect();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        android.widget.EditText q = new android.widget.EditText(this);
        q.setHint("Search settings (e.g. tide, ping, sky)");
        q.setSingleLine(true);
        box.addView(q);
        LinearLayout res = new LinearLayout(this);
        res.setOrientation(LinearLayout.VERTICAL);
        ScrollView rs = new ScrollView(this);
        rs.addView(res);
        box.addView(rs, new LinearLayout.LayoutParams(-1, dp(360)));
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Search").setView(box)
                .setNegativeButton("Close", null).create();
        Runnable fill = () -> {
            res.removeAllViews();
            String[] words = q.getText().toString().toLowerCase(java.util.Locale.ROOT).trim().split("\\s+");
            int n = 0;
            for (Hit h : all) {
                String hay = (h.title + " " + h.desc).toLowerCase(java.util.Locale.ROOT);
                boolean ok = true;
                for (String w : words) if (!w.isEmpty() && !hay.contains(w)) { ok = false; break; }
                if (!ok) continue;
                LinearLayout it = new LinearLayout(this);
                it.setOrientation(LinearLayout.VERTICAL);
                it.setPadding(0, dp(10), 0, dp(10));
                it.addView(text(h.title, 16, fg));
                if (!h.desc.isEmpty()) {
                    TextView dv = text(h.desc, 13, sub);
                    dv.setMaxLines(2);
                    dv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    it.addView(dv);
                }
                it.setOnClickListener(x -> {
                    dlg.dismiss();
                    jumpTo(scroll, h.row);
                });
                res.addView(it);
                if (++n >= 60) break;
            }
            if (n == 0) res.addView(text("Nothing found", 14, sub));
        };
        q.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c, int d) { }
            public void onTextChanged(CharSequence a, int b, int c, int d) { }
            public void afterTextChanged(android.text.Editable e) { fill.run(); }
        });
        fill.run();
        dlg.show();
        q.requestFocus();
        dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    /** Scroll the page to a row and flash it. */
    private void jumpTo(ScrollView scroll, View row) {
        for (int i = 0; i < 8; i++) {
            for (View v = row; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null)
                if (v == pages[i]) { showPage(i); break; }
        }
        int y = 0;
        for (View v = row; v != null && v != list; v = v.getParent() instanceof View ? (View) v.getParent() : null) y += v.getTop();
        final int fy = y;
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, fy - dp(80))));
        android.graphics.drawable.Drawable old = row.getBackground();
        row.setBackgroundColor((accent & 0x00FFFFFF) | 0x40000000);
        row.postDelayed(() -> row.setBackground(old), 1600);
    }

    private void section(String t) {
        int c = "Towers".equals(t) || "Operator name".equals(t) ? 1
                : "Launcher".equals(t) || "Volume steps".equals(t) || "Updates".equals(t) ? 6 : 0;
        secCat = c;
        lastCat = c;
    }

    private LinearLayout card() {
        return new RoutedCard();
    }

    private LinearLayout styledCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(card);
        g.setCornerRadius(dp(20));
        g.setStroke(dp(1), (accent & 0x00FFFFFF) | 0x22000000);
        c.setBackground(g);
        c.setPadding(dp(16), dp(6), dp(16), dp(6));
        return c;
    }

    /** "mag" sliders store tenths: 25 shows as "M2.5+". */
    private static String sliderText(int v, String unit) {
        if ("mag".equals(unit)) return String.format(java.util.Locale.US, "M%.1f+", v / 10f);
        if ("km100".equals(unit)) return v >= 200 ? "anywhere" : String.format(java.util.Locale.US, "%,d km of you", v * 100);
        return v + " " + unit;
    }

    private void slider(LinearLayout parent, String label, String unit, String key, int def, int min, int max) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(6));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = text(label, 16, fg);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView val = text("", 15, accent);
        top.addView(val);
        box.addView(top);

        SeekBar s = new SeekBar(this);
        s.setMin(min);
        s.setMax(max);
        int cur = sp.getInt(key, def);
        s.setProgress(cur);
        val.setText(sliderText(cur, unit) + (cur == def ? "  (default)" : ""));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int v, boolean user) {
                val.setText(sliderText(v, unit) + (v == def ? "  (default)" : ""));
            }

            @Override public void onStartTrackingTouch(SeekBar bar) { }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                sp.edit().putInt(key, bar.getProgress()).commit();
                changed();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(4);
        box.addView(s, lp);
        parent.addView(box);
    }

    private void textField(LinearLayout parent, String label, String desc, String key) {
        textField(parent, label, desc, key, "pk.…", true, null);
    }

    private void textField(LinearLayout parent, String label, String desc, String key, String hint, boolean token) {
        textField(parent, label, desc, key, hint, token, null);
    }

    private void textField(LinearLayout parent, String label, String desc, String key, String hint, boolean token, String url) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, dp(12));
        box.addView(text(label, 16, fg));
        if (desc != null) box.addView(text(desc, 13, sub));
        if (url != null) {
            TextView lk = text("Open " + Uri.parse(url).getHost() + " \u2197", 14, accent);
            lk.setPadding(0, dp(8), 0, dp(4));
            lk.setOnClickListener(v -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show(); } });
            box.addView(lk);
        }
        android.widget.EditText et = new android.widget.EditText(this);
        et.setSingleLine(true);
        et.setTextColor(fg);
        et.setHintTextColor(sub);
        et.setHint(hint);
        et.setInputType(token ? android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                : android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        et.setText(sp.getString(key, ""));
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override
            public void afterTextChanged(android.text.Editable e) {
                sp.edit().putString(key, e.toString().trim()).commit();
                changed();
            }
        });
        box.addView(et, new LinearLayout.LayoutParams(-1, -2));
        parent.addView(box);
    }

    /** Round floating button for jumping to the top / bottom of the list. */
    private View jumpButton(String t, Runnable r) {
        TextView b = new TextView(this);
        b.setText(t);
        b.setTextSize(20);
        b.setTextColor(0xFFFFFFFF);
        b.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor((accent & 0x00FFFFFF) | 0xE0000000);
        b.setBackground(bg);
        b.setElevation(dp(4));
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.setMargins(0, dp(6), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void choice(LinearLayout parent, String label, String key, int def, String[] opts) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, dp(12), 0, dp(12));
        r.addView(text(label, 16, fg));
        int cur = Math.max(0, Math.min(opts.length - 1, sp.getInt(key, def)));
        TextView v = text(opts[cur], 13, sub);
        r.addView(v);
        r.setOnClickListener(x -> new AlertDialog.Builder(this).setTitle(label)
                .setSingleChoiceItems(opts, Math.max(0, Math.min(opts.length - 1, sp.getInt(key, def))), (d, w) -> {
                    sp.edit().putInt(key, w).commit();
                    v.setText(opts[w]);
                    changed();
                    d.dismiss();
                }).show());
        parent.addView(r);
    }

    /** One choice for the animated background instead of two switches. */
    private void skyChoice(LinearLayout parent) {
        final String[] opts = {"Off — plain status bar",
                "Weather — sky follows the weather (sun, moon, clouds, rain…)",
                "Weather + stars — same, but clear nights show stars, planets and satellites"};
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, dp(12), 0, dp(12));
        r.addView(text("Status bar background", 16, fg));
        TextView v = text("", 13, sub);
        Runnable show = () -> {
            boolean on = sp.getBoolean(Cfg.SKY, Cfg.D_SKY), st = sp.getBoolean(Cfg.NIGHT, Cfg.D_NIGHT);
            v.setText(!on ? opts[0] : st ? opts[2] : opts[1]);
        };
        show.run();
        r.addView(v);
        r.setOnClickListener(x -> {
            boolean on = sp.getBoolean(Cfg.SKY, Cfg.D_SKY), st = sp.getBoolean(Cfg.NIGHT, Cfg.D_NIGHT);
            int cur = !on ? 0 : st ? 2 : 1;
            new AlertDialog.Builder(this).setTitle("Status bar background")
                    .setSingleChoiceItems(opts, cur, (d, w) -> {
                        sp.edit().putBoolean(Cfg.SKY, w > 0).putBoolean(Cfg.NIGHT, w == 2).commit();
                        show.run();
                        changed();
                        d.dismiss();
                    }).show();
        });
        parent.addView(r);
    }

    private void toggle(LinearLayout parent, String label, String desc, String key, boolean def) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(12), 0, dp(12));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(label, 16, fg));
        if (desc != null) texts.addView(text(desc, 13, sub));
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(sp.getBoolean(key, def));
        sw.setOnCheckedChangeListener((v, on) -> {
            sp.edit().putBoolean(key, on).commit();
            changed();
            if (Cfg.PRO.equals(key)) list.post(this::build);
        });
        r.addView(sw);
        r.setOnClickListener(v -> sw.toggle());
        parent.addView(r);
    }

    /** Apps that asked for Play Integrity, newest first (recorded by IntegrityReceiver). */
    /** Nearest dam: what we know about it, plus the checks behind it. Refresh runs the daily check now. */
    private void showDam() {
        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION,
                        android.Manifest.permission.ACCESS_FINE_LOCATION}, 42);
        } catch (Throwable ignored) { }
        TextView tv = new TextView(this);
        tv.setText(damReport());
        tv.setTextSize(13);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(18), dp(12), dp(18), dp(4));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Reservoir watch")
                .setView(sv)
                .setPositiveButton("Check now", (dd, w) -> {
                    android.widget.Toast.makeText(this, "Checking…", android.widget.Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        String r = DamJob.check(getApplicationContext(), true);
                        runOnUiThread(() -> { android.widget.Toast.makeText(this, r, android.widget.Toast.LENGTH_LONG).show(); showDam(); });
                    }).start();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /** Fault lines: what the frame is using, as text for Live diagnostics. */
    private String faultReport() {
        android.content.SharedPreferences d = getSharedPreferences(DamJob.PREFS, MODE_PRIVATE);
        StringBuilder b = new StringBuilder();
        b.append("Frame in the place line: ").append(sp.getBoolean(Cfg.FAULT, Cfg.D_FAULT) ? "on" : "off")
                .append(", radius ").append(sp.getInt(Cfg.FAULT_KM, Cfg.D_FAULT_KM)).append(" km\n");
        if (!d.contains("lat")) { b.append("Position: none saved yet (open Reservoir details once, or let the daily check run)\n"); return b.toString(); }
        double lat = d.getFloat("lat", 0), lon = d.getFloat("lon", 0);
        b.append(String.format(java.util.Locale.US, "Position used here: %.3f, %.3f (the bar uses its own live fix)\n", lat, lon));
        long t0 = System.nanoTime();
        java.util.List<Faults.Hit> hs = Faults.nearest(lat, lon, 4);
        b.append(String.format(java.util.Locale.US, "Lookup took %d ms\n", (System.nanoTime() - t0) / 1_000_000L));
        if (hs.isEmpty()) b.append("Nearest: none in the data within 1500 km\n");
        for (Faults.Hit h : hs)
            b.append(String.format(java.util.Locale.US, "Nearest: %s %d km %s, %s [%s]\n", h.label(), Math.round(h.km), h.dir, Faults.detail(h), h.src.equals("A") ? "AFDI" : "GEM"));
        b.append("Data: AFDI (India) + GEM (South/Central Asia), built into the app\n");
        return b.toString();
    }

    /** Reservoir info and its checks, as text (the details dialog and Live diagnostics). */
    private String damReport() {
        android.content.SharedPreferences d = getSharedPreferences(DamJob.PREFS, MODE_PRIVATE);
        java.text.SimpleDateFormat tf = new java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.US);
        StringBuilder b = new StringBuilder();
        if (d.contains("name")) {
            int st = d.getInt("state", 2);
            boolean mh = d.getString("uid", "").startsWith("MH:");
            float pct = d.getFloat("pct", 0);
            b.append(d.getString("name", "")).append("\n");
            b.append(String.format(java.util.Locale.US, "%.0f%% full: %s\n", pct, Dam.STATE_NAMES[st]));
            if (d.getString("uid", "").startsWith("ALL:")) b.append(d.getString("src", "")).append(" (snapshot)\n");
            else b.append(String.format(java.util.Locale.US, "Live storage %.3f of %.3f BCM\n", d.getFloat("storage", 0), d.getFloat("liveCap", 0)));
            if (!d.getString("river", "").isEmpty()) b.append("River: ").append(d.getString("river", "")).append("\n");
            else b.append(mh ? "River: not given by the Maharashtra source\n" : "River: not given by CWC for this dam\n");
            if (d.getFloat("frl", 0) > 0) b.append(String.format(java.util.Locale.US, "Full reservoir level %.2f m\n", d.getFloat("frl", 0)));
            b.append(d.getFloat("mw", 0) > 0 ? String.format(java.util.Locale.US, "Hydro power installed %.0f MW\n", d.getFloat("mw", 0)) : "No hydro power station listed\n");
            if (d.getString("district", "").length() > 0) b.append("District: ").append(d.getString("district", "")).append("\n");
            b.append("Distance ").append(Dam.kmText(d.getFloat("km", 0))).append(d.getBoolean("approx", false) ? " (dam position approximate)" : "").append("\n");
            if (!Float.isNaN(d.getFloat("ly", Float.NaN))) b.append(String.format(java.util.Locale.US, "Same date last year: %.0f%% full\n", d.getFloat("ly", 0)));
            b.append(d.getString("uid", "").startsWith("MH:wrd/") ? "Maharashtra WRD report dated " : mh ? "Maharashtra WRD reading (via mumbailakewaterlevel.in) dated " : "CWC reading dated ").append(d.getString("date", "?")).append(d.getString("rtime", "").isEmpty() ? "" : " " + d.getString("rtime", "")).append("\n");
            try {
                org.json.JSONObject h = new org.json.JSONObject(d.getString("hist", "{}"));
                double pd = DamJob.trend(h);
                b.append(Double.isNaN(pd) ? "Trend: needs 3 days of readings\n"
                        : String.format(java.util.Locale.US, "Trend %+.1f points a day%s\n", pd, Math.abs(pd) <= 0.5 ? " (steady)" : pd > 0 ? " (rising)" : " (falling)"));
            } catch (Throwable ignored) { }
        } else {
            b.append("No reading yet. Tap Check now.\n");
        }
        b.append("\n\"Danger\" means how full the reservoir is, not a flood warning. These sources give no river or water level, "
                + "and no live power output.\n\n--- Checks ---\n");
        long ok = d.getLong("okAt", 0), tr = d.getLong("tryAt", 0);
        boolean loc = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        b.append("Location permission: ").append(loc ? "yes" : "NO (allow it, then Check now)").append("\n");
        b.append("Position used: ").append(d.getString("where", "not checked yet"));
        if (d.getLong("whereAge", -1) > 0) b.append(" (").append(d.getLong("whereAge", 0)).append(" min old)");
        b.append("\n");
        String mhPub = "";
        try {
            String pr = android.provider.Settings.Global.getString(getContentResolver(), "netspeed_sat_report");
            int k = pr == null ? -1 : pr.indexOf("MAHARASHTRA DAM SOURCE\n");
            if (k >= 0) { String t = pr.substring(k + 23); int e = t.indexOf('\n'); mhPub = (e < 0 ? t : t.substring(0, e)).trim(); }
        } catch (Throwable ignored) { }
        b.append("Maharashtra source (WRD daily report + mumbailakewaterlevel.in), status bar: ").append(mhPub.isEmpty() ? "no report yet" : mhPub)
                .append("\nMaharashtra source, this app's own check: ").append(DamMh.why).append("\n");
        b.append("CWC columns: ").append(Dam.riverKeys.isEmpty() ? "(list not loaded this run)" : Dam.riverKeys).append("\n");
        b.append("Reservoir list: ").append(Dam.list != null ? Dam.list.length + " dams in memory" : new java.io.File(getCacheDir(), "dam_list2.json").exists() ? "cached on phone" : "not downloaded yet").append("\n");
        b.append("CWC storage call: ").append(d.getString("err", "").isEmpty() ? (ok > 0 ? "OK " + d.getString("http", "") : "not run yet") : d.getString("err", "")).append("\n");
        b.append("Last good check: ").append(ok > 0 ? tf.format(new java.util.Date(ok)) : "never").append("\n");
        b.append("Last attempt: ").append(tr > 0 ? tf.format(new java.util.Date(tr)) : "never").append("\n");
        try {
            org.json.JSONObject h = new org.json.JSONObject(d.getString("hist", "{}"));
            b.append("Readings kept for the trend: ").append(h.length()).append("\n");
        } catch (Throwable ignored) { }
        b.append("Daily job: ").append(DamJob.scheduled(this) ? "scheduled" : "NOT scheduled").append("\n");
        b.append("Notifications: ").append(getSystemService(android.app.NotificationManager.class).areNotificationsEnabled() ? "allowed" : "blocked in Android settings").append("\n");
        try {
            boolean free = getSystemService(android.os.PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
            b.append("Battery: ").append(free ? "unrestricted" : "optimised (Android may delay the daily check)").append("\n");
        } catch (Throwable ignored) { }
        b.append("Chip in the status bar: ").append(sp.getBoolean(Cfg.DAM, Cfg.D_DAM) ? "on" : "off").append("\n");
        return b.toString();
    }

    // ------------------------------------------------------------ Info tab

    private LinearLayout infoPage;

    private LinearLayout infoCard(String icon, String title, String sub) {
        LinearLayout c = styledCard();
        c.setPadding(dp(16), dp(14), dp(16), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(12));
        TextView t = text(icon + "  " + title, 17, fg);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        c.addView(t);
        if (sub != null && !sub.isEmpty()) { TextView s2 = text(sub, 12, this.sub); s2.setPadding(0, dp(2), 0, dp(6)); c.addView(s2); }
        infoPage.addView(c, lp);
        return c;
    }

    private void infoRow(LinearLayout c, String k, String v) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, dp(7), 0, dp(7));
        TextView a = text(k, 11, sub);
        TextView b2 = text(v, 14, fg);
        r.addView(a);
        r.addView(b2);
        c.addView(r);
    }

    private void fillInfo() {
        infoPage.removeAllViews();
        TextView back = text("‹  Field Atlas", 14, accent);
        back.setPadding(dp(4), dp(4), dp(12), dp(10));
        back.setOnClickListener(v -> showPage(-1));
        infoPage.addView(back);
        TextView ttl = text("ℹ️  Info", 26, fg);
        ttl.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ttl.setPadding(dp(4), 0, 0, dp(2));
        infoPage.addView(ttl);
        TextView st = text("what the app knows about your place and sky right now", 13, sub);
        st.setPadding(dp(4), 0, 0, dp(10));
        infoPage.addView(st);
        {
            final android.content.SharedPreferences lw = getSharedPreferences(DamJob.PREFS, MODE_PRIVATE);
            long at = lw.getLong("lwAt", 0);
            final TextView pl = text(at == 0 ? "Dam, river and place data refresh when you move more than 15 km. Tap to refresh for here now."
                    : "Showing: " + lw.getString("lwPlace", "your place") + " \u00b7 refreshed " + Math.max(0, (System.currentTimeMillis() - at) / 60000L)
                    + " min ago \u00b7 refreshes itself when you move 15 km. Tap to refresh now.", 12, sub);
            pl.setPadding(dp(4), 0, 0, dp(10));
            pl.setOnClickListener(v -> {
                pl.setText("Refreshing for your place\u2026");
                new Thread(() -> {
                    String r = LocationWatch.run(getApplicationContext(), true);
                    runOnUiThread(() -> { android.widget.Toast.makeText(this, r, android.widget.Toast.LENGTH_LONG).show(); fillInfo(); });
                }).start();
            });
            infoPage.addView(pl);
        }
        java.util.List<String[]> A = new java.util.ArrayList<>(), E = new java.util.ArrayList<>(), D = new java.util.ArrayList<>(), LY = new java.util.ArrayList<>();
        double lat = Double.NaN, lon = Double.NaN;
        long feedAt = 0;
        try {
            String f = android.provider.Settings.Global.getString(getContentResolver(), "netspeed_info");
            if (f != null) for (String ln : f.split("\n")) {
                String[] p = ln.split("\\|", 3);
                if (p.length < 3) continue;
                if (p[0].equals("A")) A.add(new String[]{p[1], p[2]});
                else if (p[0].equals("E")) E.add(new String[]{p[1], p[2]});
                else if (p[0].equals("D")) D.add(new String[]{p[1], p[2]});
                else if (p[0].equals("L")) LY.add(new String[]{p[1], p[2]});
                else if (p[0].equals("T")) {
                    String[] q = p[2].split("\\|");
                    String[] ll = q[0].split(",");
                    lat = Double.parseDouble(ll[0]); lon = Double.parseDouble(ll[1]);
                    feedAt = q.length > 1 ? Long.parseLong(q[1]) : 0;
                }
            }
        } catch (Throwable ignored) { }
        android.content.SharedPreferences d = getSharedPreferences(DamJob.PREFS, MODE_PRIVATE);
        if (Double.isNaN(lat) && d.contains("lat")) { lat = d.getFloat("lat", 0); lon = d.getFloat("lon", 0); }
        long now = System.currentTimeMillis();
        java.util.Locale us = java.util.Locale.US;

        // ---- Dam / reservoir
        LinearLayout c = infoCard("🌊", "Dam / reservoir", d.contains("name") ? d.getString("name", "") : "no reading yet – open Reservoir watch and tap Check now");
        if (d.contains("name")) {
            int s = Math.max(0, Math.min(4, d.getInt("state", 2)));
            float pct = d.getFloat("pct", 0);
            TextView hero = text(String.format(us, "%.0f%%", pct), 44, Dam.STATE_COLORS[s]);
            hero.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            c.addView(hero);
            LinearLayout bar = new LinearLayout(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor((fg & 0x00FFFFFF) | 0x22000000);
            bg.setCornerRadius(dp(5));
            bar.setBackground(bg);
            View fill = new View(this);
            GradientDrawable fg2 = new GradientDrawable();
            fg2.setColor(Dam.STATE_COLORS[s]);
            fg2.setCornerRadius(dp(5));
            fill.setBackground(fg2);
            bar.addView(fill, new LinearLayout.LayoutParams(0, dp(10), Math.max(1f, Math.min(100f, pct))));
            bar.addView(new View(this), new LinearLayout.LayoutParams(0, dp(10), Math.max(0f, 100f - pct)));
            c.addView(bar, new LinearLayout.LayoutParams(-1, dp(10)));
            boolean snap = d.getString("uid", "").startsWith("ALL:");
            TextView sl = text(Dam.STATE_NAMES[s] + (snap ? " · " + d.getString("src", "") + " (state report snapshot, not live)" + (d.getString("flow", "").isEmpty() ? "" : "\ncusecs: " + d.getString("flow", ""))
                    : String.format(us, " · %.3f of %.3f BCM live storage", d.getFloat("storage", 0), d.getFloat("liveCap", 0))), 13, sub);
            sl.setPadding(0, dp(6), 0, dp(4));
            c.addView(sl);
            try {
                double pd = DamJob.trend(new org.json.JSONObject(d.getString("hist", "{}")));
                if (!Double.isNaN(pd)) infoRow(c, "Trend", String.format(us, "%+.1f points a day%s", pd, Math.abs(pd) <= 0.5 ? " (steady)" : pd > 0 ? " (rising)" : " (falling)"));
            } catch (Throwable ignored) { }
            if (!Float.isNaN(d.getFloat("ly", Float.NaN))) infoRow(c, "Same date last year", String.format(us, "%.0f%% full", d.getFloat("ly", 0)));
            if (!d.getString("river", "").isEmpty()) infoRow(c, "River", d.getString("river", ""));
            if (!d.getString("district", "").isEmpty()) infoRow(c, "District", d.getString("district", ""));
            infoRow(c, "Distance", Dam.kmText(d.getFloat("km", 0)) + (d.getBoolean("approx", false) ? " (position approximate)" : ""));
            String uid = d.getString("uid", "");
            infoRow(c, "Reading", d.getString("date", "") + (d.getString("rtime", "").isEmpty() ? "" : " " + d.getString("rtime", "")) + " · "
                    + (uid.startsWith("MH:wrd/") ? "Maharashtra WRD report" : uid.startsWith("MH:") ? "Maharashtra WRD via mumbailakewaterlevel.in" : "CWC"));
            if (!Double.isNaN(lat)) {
                java.util.List<Object[]> nb = DamWrd.nearby(lat, lon, 5);
                String own = d.getString("name", "").toLowerCase(us);
                int shown = 0;
                for (Object[] o : nb) {
                    DamWrd.Row r = (DamWrd.Row) o[0];
                    if (own.contains(r.name.toLowerCase(us)) || r.name.toLowerCase(us).contains(own)) continue;
                    if (shown == 0) { TextView h = text("Other dams nearby", 12, accent); h.setPadding(0, dp(10), 0, dp(2)); c.addView(h); }
                    if (shown++ >= 4) break;
                    infoRow(c, r.name + " · " + Dam.kmText(((Double) o[1]).floatValue()),
                            String.format(us, "%.0f%% full", r.pct) + (r.ly > 0 ? String.format(us, " (last year %.0f%%)", r.ly) : "") + (r.date.isEmpty() ? "" : " · " + r.date));
                }
            }
        }

        if (!Double.isNaN(lat)) {
            try {
                DamAll.useFeed(getCacheDir());
                java.util.List<DamAll.Hit> rh = DamAll.nearest(lat, lon, 5, 150, false);
                if (!rh.isEmpty()) {
                    TextView h = text("Nearest dams anywhere in India", 12, accent);
                    h.setPadding(0, dp(10), 0, dp(2));
                    c.addView(h);
                    for (DamAll.Hit x : rh) {
                        StringBuilder sb = new StringBuilder();
                        sb.append(x.state);
                        if (!x.river.isEmpty()) sb.append(" · ").append(x.river).append(x.riverConf.equals("L") ? " (river uncertain)" : "");
                        if (x.fresh()) sb.append(String.format(us, "\n%.0f%% full (%s) · %s · %s", x.pct, x.basis, x.date, x.source));
                        else if (!Double.isNaN(x.pct)) sb.append(String.format(us, "\nlevel %.0f%% on %s is too old, not shown", x.pct, x.date));
                        if (!Double.isNaN(x.inflow) || !Double.isNaN(x.outRiver) || !Double.isNaN(x.outCanal)) {
                            sb.append("\ncusecs:");
                            if (!Double.isNaN(x.inflow)) sb.append(String.format(us, " in %.0f", x.inflow));
                            if (!Double.isNaN(x.outRiver)) sb.append(String.format(us, " out river %.0f", x.outRiver));
                            if (!Double.isNaN(x.outCanal)) sb.append(String.format(us, " out canal %.0f", x.outCanal));
                        }
                        infoRow(c, x.name + " · " + Dam.kmText(x.km) + " " + x.dir, sb.toString());
                    }
                    TextView n = text("National dam register with rivers; levels and flows only where a state or CWC report gave them (levels file: " + DamAll.feedNote + "; built-in snapshot " + DamAll.SNAPSHOT + "; hidden after " + DamAll.MAX_AGE_DAYS + " days). Positions can be a few km out.", 11, sub);
                    c.addView(n);
                }
            } catch (Throwable ignored) { }
        }

        // ---- Astronomy
        c = infoCard("🔭", "Astronomy", Double.isNaN(lat) ? "waiting for a position" : null);
        if (!Double.isNaN(lat)) {
            try {
                double[] sun = SunTimes.today(lat, lon);
                infoRow(c, "Sun", "rises " + SunTimes.hhmm(sun[0]) + " · sets " + SunTimes.hhmm(sun[1]) + (Double.isNaN(sun[0]) ? "" : " · day " + SunTimes.dur(sun[1] - sun[0])));
            } catch (Throwable ignored) { }
            try {
                Astro.Moon m = Astro.moon(now, lat, lon);
                String[] ph = {"new", "waxing crescent", "first quarter", "waxing gibbous", "full", "waning gibbous", "last quarter", "waning crescent"};
                String nm = ph[(int) Math.floor(((m.phase % 1 + 1) % 1) * 8 + 0.5) % 8];
                java.text.SimpleDateFormat tf = new java.text.SimpleDateFormat("HH:mm", us);
                long[] ev = Astro.nextMoonEvent(now, lat, lon);
                infoRow(c, "Moon", nm + String.format(us, " · %.0f%% lit", m.illum * 100) + (m.alt > 0 ? String.format(us, " · %.0f° up", m.alt) : " · below horizon")
                        + (ev == null ? "" : " · " + (ev[1] == 1 ? "rises " : "sets ") + tf.format(new java.util.Date(ev[0]))));
            } catch (Throwable ignored) { }
        }
        for (String[] a : A) infoRow(c, a[0], a[1]);
        if (A.isEmpty()) infoRow(c, "Status bar feed", "nothing published yet – wait a minute with the status bar on");

        // ---- Earth
        c = infoCard("🌍", "Earth", null);
        for (String[] e : E) infoRow(c, e[0], e[1]);
        if (E.isEmpty()) infoRow(c, "Status bar feed", "nothing published yet – the Earth line fills in after the first fetch");

        // ---- Disasters
        c = infoCard("🌋", "Disasters", null);
        for (String[] e : D) infoRow(c, e[0], e[1]);
        try {
            if (!Double.isNaN(lat)) {
                java.util.List<Faults.Hit> hs = Faults.nearest(lat, lon, 1);
                if (!hs.isEmpty()) infoRow(c, "Nearest fault", hs.get(0).label() + " · " + Math.round(hs.get(0).km) + " km " + hs.get(0).dir);
            }
        } catch (Throwable ignored) { }
        if (D.isEmpty()) infoRow(c, "Alerts", "none reported near you");
        c = infoCard("\uD83E\uDDED", "Status bar diagnostics", null);
        for (String[] e : LY) infoRow(c, e[0], e[1]);
        if (LY.isEmpty()) infoRow(c, "Layout", "nothing published yet \u2013 wait a minute with the status bar on");
        button(c, "Refresh data now", false, v -> {
            sp.edit().putInt(Cfg.REFRESH_REQ, sp.getInt(Cfg.REFRESH_REQ, 0) + 1).commit();
            changed();
            android.widget.Toast.makeText(this, "Asked the status bar to refetch weather, tide and El Ni\u00f1o (at most once every 10 minutes)", android.widget.Toast.LENGTH_LONG).show();
        });
        infoLat = lat; infoLon = lon;
        beachCard();
        wetlandCard();
        habitatCard();
        riverCard(E);
        mapCard();
        soilCard();
        TextView ft = text(feedAt > 0 ? "Updated " + new java.text.SimpleDateFormat("HH:mm", us).format(new java.util.Date(feedAt)) + " · sources: CWC, Maharashtra WRD, GDACS, USGS, NOAA, Open-Meteo, Launch Library"
                : "Sources: CWC, Maharashtra WRD, GDACS, USGS, NOAA, Open-Meteo, Launch Library", 11, sub);
        ft.setPadding(dp(4), 0, 0, dp(70));
        infoPage.addView(ft);
    }

    // ------------------------------------------------------------ soil at another place

    private String soilQ = "", soilPlace = "", soilType = "", soilErr = "";
    private double soilSand = Double.NaN, soilSilt, soilClay, soilKm = Double.NaN;
    private LinearLayout soilOut;
    private double infoLat = Double.NaN, infoLon = Double.NaN;

    private static final double[][][] TRI = {
            {{100, 0, 0}, {90, 10, 0}, {85, 0, 15}},
            {{90, 10, 0}, {70, 30, 0}, {85, 0, 15}},
            {{85, 0, 15}, {70, 30, 0}, {50, 50, 0}, {43, 50, 7}, {52, 41, 7}, {52, 28, 20}, {80, 0, 20}},
            {{52, 41, 7}, {43, 50, 7}, {23, 50, 27}, {45, 28, 27}, {52, 28, 20}},
            {{50, 50, 0}, {20, 80, 0}, {8, 80, 12}, {0, 88, 12}, {0, 73, 27}, {23, 50, 27}, {43, 50, 7}},
            {{20, 80, 0}, {0, 100, 0}, {0, 88, 12}, {8, 80, 12}},
            {{80, 0, 20}, {65, 0, 35}, {45, 20, 35}, {45, 28, 27}, {52, 28, 20}},
            {{45, 28, 27}, {20, 53, 27}, {20, 40, 40}, {45, 15, 40}},
            {{20, 53, 27}, {0, 73, 27}, {0, 60, 40}, {20, 40, 40}},
            {{65, 0, 35}, {45, 0, 55}, {45, 15, 40}, {45, 20, 35}},
            {{20, 40, 40}, {0, 60, 40}, {0, 40, 60}},
            {{45, 0, 55}, {45, 15, 40}, {20, 40, 40}, {0, 40, 60}, {0, 0, 100}}};
    private static final String[] TRI_NAME = {"sand", "loamy sand", "sandy loam", "loam", "silt loam", "silt", "sandy clay loam", "clay loam",
            "silty clay loam", "sandy clay", "silty clay", "clay"};
    private static final String[] TRI_NOTE = {"Loose grains you can see. Drains fast and holds very little water.",
            "Mostly sand. Drains fast, holds little water.", "Gritty but holds some water. Easy to dig.", "Balanced mix. Easy to work, drains and holds water well.",
            "Smooth and soft when dry, slippery when wet.", "Fine powder. Slippery when wet, can wash away.", "Sandy with some clay. Holds water better than loam.",
            "Holds water. Sticky when wet, hard when dry.", "Smooth, sticky when wet. Drains slowly.", "Gritty and sticky. Hard when dry.",
            "Very fine and sticky. Drains slowly, cracks when dry.", "Very sticky when wet, cracks when dry. Drains slowly."};

    /** The soil triangle (sand, silt, clay) with the matching class lit and the soil marked. */
    private final class TriView extends View {
        final String cls;
        final double sa, si, cl;
        TriView(String cls, double sa, double si, double cl) { super(SettingsActivity.this); this.cls = cls; this.sa = sa; this.si = si; this.cl = cl; }
        @Override protected void onMeasure(int w, int h) {
            int ww = MeasureSpec.getSize(w);
            setMeasuredDimension(ww, (int) (ww * 0.78));
        }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            float W = getWidth(), side = Math.min(W - dp(16), (getHeight() - dp(24)) / 0.866f), hh = side * 0.866f;
            float ox = (W - side) / 2f, oy = getHeight() - dp(8);
            android.graphics.Paint f = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG), t = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            t.setTextSize(dp(9));
            t.setTextAlign(android.graphics.Paint.Align.CENTER);
            for (int i = 0; i < TRI.length; i++) {
                android.graphics.Path p = new android.graphics.Path();
                float cx = 0, cy = 0;
                for (int j = 0; j < TRI[i].length; j++) {
                    float x = ox + side * (float) (TRI[i][j][0] / 100 + TRI[i][j][2] / 200), y = oy - hh * (float) (TRI[i][j][2] / 100);
                    if (j == 0) p.moveTo(x, y); else p.lineTo(x, y);
                    cx += x; cy += y;
                }
                p.close();
                cx /= TRI[i].length; cy /= TRI[i].length;
                boolean on = TRI_NAME[i].equals(cls);
                f.setStyle(android.graphics.Paint.Style.FILL);
                f.setColor(on ? 0xFFC9763A : (sub & 0x00FFFFFF) | 0x22000000);
                cv.drawPath(p, f);
                f.setStyle(android.graphics.Paint.Style.STROKE);
                f.setStrokeWidth(dp(1));
                f.setColor((sub & 0x00FFFFFF) | 0x88000000);
                cv.drawPath(p, f);
                if (on) { t.setColor(0xFFFFFFFF); cv.drawText(TRI_NAME[i], cx, cy + dp(3), t); }
            }
            float mx = ox + side * (float) (sa / 100 + cl / 200), my = oy - hh * (float) (cl / 100);
            f.setStyle(android.graphics.Paint.Style.FILL);
            f.setColor(0xFFFFFFFF);
            cv.drawCircle(mx, my, dp(6), f);
            f.setStyle(android.graphics.Paint.Style.STROKE);
            f.setStrokeWidth(dp(2));
            f.setColor(0xFF2F7FD1);
            cv.drawCircle(mx, my, dp(6), f);
        }
    }

    /** A drawn picture of the grains: big pale dots are sand, medium silt, tiny dark clay, in the soil's proportions. */
    private final class GrainView extends View {
        final double sa, si, cl;
        GrainView(double sa, double si, double cl) { super(SettingsActivity.this); this.sa = sa; this.si = si; this.cl = cl; }
        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), dp(110)); }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFF7A4A2A);
            cv.drawRoundRect(0, 0, getWidth(), getHeight(), dp(10), dp(10), p);
            java.util.Random r = new java.util.Random(7);
            double area = getWidth() * getHeight();
            int ns = (int) (sa * 0.9), nt = (int) (si * 2.2), nc = (int) (cl * 7);
            p.setColor(0xFFD9B98A);
            for (int i = 0; i < ns; i++) cv.drawCircle(dp(6) + r.nextFloat() * (getWidth() - dp(12)), dp(6) + r.nextFloat() * (getHeight() - dp(12)), dp(3) + r.nextFloat() * dp(2), p);
            p.setColor(0xFFB08A62);
            for (int i = 0; i < nt; i++) cv.drawCircle(dp(3) + r.nextFloat() * (getWidth() - dp(6)), dp(3) + r.nextFloat() * (getHeight() - dp(6)), (dp(3) / 2f) + r.nextFloat() * (dp(3) / 2f), p);
            p.setColor(0xFF8C3F1D);
            for (int i = 0; i < nc; i++) cv.drawCircle(r.nextFloat() * getWidth(), r.nextFloat() * getHeight(), dp(1) * 0.6f + 0.5f, p);
        }
    }

    /** {sand, silt, clay} in % for the top 5 cm at a point, or null when SoilGrids has no data there. */
    private double[] soilAt(double lat, double lon) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(String.format(java.util.Locale.US,
                "https://rest.isric.org/soilgrids/v2.0/properties/query?lon=%.4f&lat=%.4f&property=clay&property=sand&property=silt&depth=0-5cm&value=mean",
                lon, lat)).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(25_000);
        c.setRequestProperty("User-Agent", "NetSpeedXposed/1.0 (github.com/dmk6091/NetSpeedXposed)");
        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String ln;
            while ((ln = r.readLine()) != null) sb.append(ln);
        }
        org.json.JSONArray ly = new org.json.JSONObject(sb.toString()).getJSONObject("properties").getJSONArray("layers");
        double clay = Double.NaN, sand = Double.NaN, silt = Double.NaN;
        for (int i = 0; i < ly.length(); i++) {
            org.json.JSONObject l = ly.getJSONObject(i);
            org.json.JSONObject v = l.getJSONArray("depths").getJSONObject(0).getJSONObject("values");
            if (v.isNull("mean")) continue;
            double pc = v.getDouble("mean") / 10.0;
            String nm = l.getString("name");
            if (nm.equals("clay")) clay = pc; else if (nm.equals("sand")) sand = pc; else if (nm.equals("silt")) silt = pc;
        }
        return Double.isNaN(clay) || Double.isNaN(sand) || Double.isNaN(silt) ? null : new double[]{sand, silt, clay};
    }

    /** Several points at once (the server is slow, so wait for them together). Result per point: soil, or null; the first error is rethrown if none answered. */
    private double[][] soilMany(double[][] pts) throws Exception {
        final double[][] out = new double[pts.length][];
        final Throwable[] err = new Throwable[1];
        final int[] ok = {0};
        Thread[] th = new Thread[pts.length];
        for (int i = 0; i < pts.length; i++) {
            final int k = i;
            th[i] = new Thread(() -> {
                try { out[k] = soilAt(pts[k][0], pts[k][1]); synchronized (ok) { ok[0]++; } } catch (Throwable t) { err[0] = t; }
            });
            th[i].start();
        }
        for (Thread t : th) t.join();
        if (ok[0] == 0 && err[0] != null) throw new Exception(err[0].getMessage() == null ? err[0].getClass().getSimpleName() : err[0].getMessage());
        return out;
    }

    private void soilLookup(String q) {
        soilQ = q;
        soilErr = "";
        soilType = "";
        soilSand = Double.NaN;
        showSoil();
        new Thread(() -> {
            String err = "";
            try {
                double lat, lon;
                String place;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*[,\\s]\\s*(-?\\d+(?:\\.\\d+)?)").matcher(q);
                if (m.find() && Math.abs(Double.parseDouble(m.group(1))) <= 90 && Math.abs(Double.parseDouble(m.group(2))) <= 180) {
                    lat = Double.parseDouble(m.group(1)); lon = Double.parseDouble(m.group(2));
                    place = String.format(java.util.Locale.US, "%.4f, %.4f", lat, lon);
                } else {
                    org.json.JSONArray a = new org.json.JSONArray(EarthLine.slowGet("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&accept-language=en&q="
                            + java.net.URLEncoder.encode(q, "UTF-8")));
                    if (a.length() == 0) throw new Exception("place not found");
                    org.json.JSONObject o = a.getJSONObject(0);
                    lat = Double.parseDouble(o.getString("lat")); lon = Double.parseDouble(o.getString("lon"));
                    String[] dn = o.optString("display_name", q).split(",\\s*");
                    place = dn.length > 3 ? dn[0] + ", " + dn[1] + ", " + dn[dn.length - 3] : o.optString("display_name", q);
                }
                // the centre of a town is often built-up land with no soil data: step outwards to the nearest spot that has some
                double[] sc = null;
                String moved = "";
                double[][] ring = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
                double[] rounds = {0, 2, 5, 10};
                for (double km : rounds) {
                    double[][] pts = km == 0 ? new double[][]{{lat, lon}, {lat + 2 / 111.0, lon}, {lat, lon + 2 / (111.0 * Math.cos(Math.toRadians(lat)))},
                            {lat - 2 / 111.0, lon}, {lat, lon - 2 / (111.0 * Math.cos(Math.toRadians(lat)))}} : new double[ring.length][];
                    if (km > 0) for (int i = 0; i < ring.length; i++)
                        pts[i] = new double[]{lat + ring[i][0] * km / 111.0, lon + ring[i][1] * km / (111.0 * Math.cos(Math.toRadians(lat)))};
                    double[][] res = soilMany(pts);
                    int pick = -1;
                    for (int i = 0; i < res.length && pick < 0; i++) if (res[i] != null) pick = i;
                    if (pick >= 0) {
                        sc = res[pick];
                        if (km == 0 && pick == 0) break;
                        moved = String.format(java.util.Locale.US, " (no soil data in town: used land about %.0f km away)", km == 0 ? 2 : km);
                        break;
                    }
                }
                if (sc == null) throw new Exception("no soil data within 10 km (water, rock or built-up land)");
                double sand = sc[0], silt = sc[1], clay = sc[2];
                place = place + moved;
                soilPlace = place;
                soilSand = sand; soilSilt = silt; soilClay = clay;
                soilType = EarthLine.texture(sand, silt, clay);
                soilKm = Double.isNaN(infoLat) ? Double.NaN : Dam.km(infoLat, infoLon, lat, lon);
            } catch (Throwable t) {
                err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            }
            soilErr = err;
            runOnUiThread(this::showSoil);
        }).start();
    }

    private void showSoil() {
        if (soilOut == null) return;
        soilOut.removeAllViews();
        if (soilQ.isEmpty()) return;
        if (!soilErr.isEmpty()) { soilOut.addView(text("Could not look that up: " + soilErr, 13, 0xFFD93A2B)); return; }
        if (Double.isNaN(soilSand)) { soilOut.addView(text("Looking up… (SoilGrids can take up to 30 s)", 13, sub)); return; }
        TextView pl = text(soilPlace + (Double.isNaN(soilKm) ? "" : " · " + Dam.kmText(soilKm) + " from you"), 13, fg);
        pl.setPadding(0, dp(8), 0, dp(2));
        soilOut.addView(pl);
        TextView ty = text(soilType.isEmpty() ? "" : Character.toUpperCase(soilType.charAt(0)) + soilType.substring(1), 22, 0xFFE0894A);
        soilOut.addView(ty);
        soilOut.addView(text(String.format(java.util.Locale.US, "sand %.0f%% · silt %.0f%% · clay %.0f%%", soilSand, soilSilt, soilClay), 14, fg));
        soilOut.addView(new TriView(soilType, soilSand, soilSilt, soilClay), new LinearLayout.LayoutParams(-1, -2));
        TextView c1 = text("The white dot shows where this soil sits between sand, silt and clay.", 11, sub);
        soilOut.addView(c1);
        TextView c2 = text("What the grains look like (drawn, not to scale)", 11, sub);
        c2.setPadding(0, dp(8), 0, dp(4));
        soilOut.addView(c2);
        soilOut.addView(new GrainView(soilSand, soilSilt, soilClay), new LinearLayout.LayoutParams(-1, -2));
        String note = "";
        for (int i = 0; i < TRI_NAME.length; i++) if (TRI_NAME[i].equals(soilType)) note = TRI_NOTE[i];
        TextView nt = text("Sand = big grains you can see · silt = fine powder · clay = tiny sticky particles. " + note, 12, fg);
        nt.setPadding(0, dp(8), 0, 0);
        soilOut.addView(nt);
        TextView src = text("SoilGrids, 250 m map, top 5 cm: modelled, not a ground survey.", 11, sub);
        src.setPadding(0, dp(8), 0, 0);
        soilOut.addView(src);
    }

    /** Nearest named beaches (OpenStreetMap), filled in after the lookup. */
    private void beachCard() {
        final LinearLayout c = infoCard("\uD83C\uDFD6\uFE0F", "Nearest beaches", "straight-line distance from you (OpenStreetMap, or the built-in list)");
        final TextView t = text("Looking up\u2026", 13, sub);
        c.addView(t);
        final double la = infoLat, lo = infoLon;
        if (Double.isNaN(la)) { t.setText("No position yet"); return; }
        new Thread(() -> {
            String r;
            try {
                java.util.List<Beach.Hit> hs = Beach.nearest(getApplicationContext(), la, lo, 8);
                StringBuilder sb = new StringBuilder();
                for (Beach.Hit h : hs) sb.append(h.name).append("  \u00b7  ").append(Math.round(h.km)).append(" km ").append(h.dir).append('\n');
                r = hs.isEmpty() ? "No named beach within 150 km" : sb.toString().trim();
            } catch (Throwable e) {
                r = "Couldn't reach the map server: " + e.getClass().getSimpleName();
            }
            final String fr = r;
            runOnUiThread(() -> { t.setText(fr); t.setTextSize(14); t.setTextColor(fg); });
        }).start();
    }

    /** River systems: the river beside you, then each river it joins on the way to the sea (facts from RiverInfo). */
    private void riverCard(java.util.List<String[]> E) {
        final LinearLayout c = infoCard("\uD83C\uDF0A", "River systems", "your river and the systems it joins");
        String rv = "";
        for (String[] e : E) if (e[0].equals("River flow")) rv = e[1];
        final String flow = rv;
        final double la = infoLat, lo = infoLon;
        final TextView t = text("Looking up\u2026", 13, sub);
        c.addView(t);
        new Thread(() -> {
            String name = Watershed.riverName(flow);
            if (name.isEmpty() && !Double.isNaN(la)) {
                try { name = Watershed.riverName(RiverFlow.waterName(la, lo)); } catch (Throwable ignored) { }
            }
            final String[] path = name.isEmpty() ? null : Watershed.path(name);
            final String nm = name;
            runOnUiThread(() -> {
                c.removeView(t);
                if (nm.isEmpty()) { c.addView(text("No named river within about 3 km of you", 13, sub)); return; }
                if (!flow.isEmpty()) infoRow(c, "Flow now at your spot (model, about 5 km grid)", (flow.matches("(?s).*[0-9.]+k?\\s*m\u00b3/s.*") ? flow.replaceFirst("(?s)^.*?([0-9.]+k?\\s*m\u00b3/s.*)$", "$1") : flow));
                if (path == null) { infoRow(c, "River", nm + " \u00b7 not in my river table yet, so no source or mouth details"); return; }
                StringBuilder ch = new StringBuilder();
                for (String p : path) ch.append(ch.length() > 0 ? " \u2192 " : "").append(p);
                infoRow(c, "Where the water goes", ch.toString());
                for (int i = 0; i < path.length - 1; i++) {
                    RiverInfo.R ri = RiverInfo.get(path[i]);
                    TextView h = text(path[i] + (i == path.length - 2 ? "  (the basin you are in)" : ""), 15, accent);
                    h.setPadding(0, dp(10), 0, 0);
                    c.addView(h);
                    if (ri == null) { c.addView(text("no details in my table", 12, sub)); continue; }
                    if (!ri.source.isEmpty()) infoRow(c, "Source", ri.source);
                    if (!ri.length.isEmpty() || !ri.basin.isEmpty()) infoRow(c, ri.basin.isEmpty() ? "Length" : ri.length.isEmpty() ? "Basin" : "Length \u00b7 basin",
                            ri.length.isEmpty() ? ri.basin : ri.basin.isEmpty() ? ri.length : ri.length + " \u00b7 " + ri.basin);
                    if (!ri.ends.isEmpty()) infoRow(c, i == path.length - 2 ? "River mouth" : "Joins / ends", ri.ends);
                    if (!ri.meets.isEmpty()) infoRow(c, "Tributary meetings", ri.meets);
                    if (!ri.discharge.isEmpty()) infoRow(c, "Mean discharge", ri.discharge);
                    if (!ri.states.isEmpty()) infoRow(c, "States", ri.states);
                }
                TextView f = text("Facts from Wikipedia river pages; blank means the page did not say. Live flow is a model, not a gauge.", 11, sub);
                f.setPadding(0, dp(8), 0, 0);
                c.addView(f);
            });
        }).start();
    }

    /** Nearest wetland / marsh / Ramsar site (OpenStreetMap), filled in after the lookup. */
    /** Forest region, land cover within 5 km and the plants people have logged within 10 km. */
    private void habitatCard() {
        final LinearLayout c = infoCard("\uD83C\uDF33", "Habitat and flora", "ecoregion, map land cover and iNaturalist plant records");
        final double la = infoLat, lo = infoLon;
        final TextView t = text(Double.isNaN(la) ? "No position yet" : "Looking up\u2026", 13, sub);
        c.addView(t);
        if (Double.isNaN(la)) return;
        new Thread(() -> {
            String r;
            try {
                Habitat.Data d = Habitat.load(getApplicationContext(), la, lo, false);
                StringBuilder sb = new StringBuilder();
                if (!d.region().isEmpty()) sb.append("REGION\n").append(d.region()).append(d.biome.isEmpty() ? "" : "\nBiome: " + d.biome).append(d.eco.isEmpty() ? "" : "\nEcoregion on the map: " + d.eco).append("\nBroad type for the area, not the patch next to you. The ecoregion name can sound odd for your place; the biome is the safer reading.\n\n");
                if (d.coverOk) {
                    sb.append("MAP LAND COVER WITHIN 5 KM\n");
                    Integer[] ix = {0, 1, 2, 3, 4, 5, 6};
                    java.util.Arrays.sort(ix, (a, b) -> Float.compare(d.cover[b], d.cover[a]));
                    for (int i : ix) if (d.cover[i] >= 1) sb.append(Habitat.COVER[i]).append(' ').append(Math.round(d.cover[i])).append("%   ");
                    sb.append("\nColours of the OpenStreetMap tiles; \"open land\" means nothing mapped there.\n");
                    if (!Double.isNaN(d.leafKm) && !d.leaf.isEmpty()) sb.append("Nearest tagged forest: ").append(Math.round(d.leafKm)).append(" km ").append(d.leafDir).append(" · ").append(d.leaf).append('\n');
                    sb.append('\n');
                }
                if (!d.names.isEmpty()) {
                    sb.append("PLANTS LOGGED WITHIN 10 KM (").append(d.species).append(" species)\n");
                    for (int i = 0; i < d.names.size(); i++) sb.append(d.names.get(i)).append("  ·  ").append(d.sci.get(i)).append("  ·  ").append(d.counts.get(i)).append(" records\n");
                    sb.append("What people logged, not a full survey. One species is capped so a single project cannot dominate.");
                }
                r = sb.length() == 0 ? "No data from the servers just now. Try again later." : sb.toString().trim();
            } catch (Throwable e) {
                r = "Couldn't reach the servers: " + e.getClass().getSimpleName();
            }
            final String fr = r;
            runOnUiThread(() -> { t.setText(fr); t.setTextSize(14); t.setTextColor(fg); });
        }).start();
    }

    private void wetlandCard() {
        final LinearLayout c = infoCard("\uD83E\uDD86", "Wetlands near you", "OpenStreetMap, plus the Ramsar list of India");
        final double la = infoLat, lo = infoLon;
        final TextView t = text(Double.isNaN(la) ? "No position yet" : "Looking up\u2026", 13, sub);
        c.addView(t);
        if (Double.isNaN(la)) return;
        new Thread(() -> {
            String r;
            try {
                long now = System.currentTimeMillis();
                double[] a = Wetland.at;
                if (a == null || Beach.km(la, lo, a[0], a[1]) > 5 || now - Wetland.okAt > 12 * 3600_000L) Wetland.lookup(la, lo, now);
                Wetland.Hit w = Wetland.wetland, m = Wetland.marsh, rs = Wetland.ramsar;
                r = "Nearest wetland / lake\n" + (w == null ? "none within 25 km" : w.text())
                        + "\n\nNearest marsh / swamp\n" + (m == null ? "none within 25 km" : m.text())
                        + "\n\nNearest Ramsar site\n" + (rs == null ? "none found within 220 km (or not mapped under its official name)" : rs.text());
            } catch (Throwable e) {
                r = "Couldn't reach the map server: " + e.getClass().getSimpleName();
            }
            final String fr = r;
            runOnUiThread(() -> { t.setText(fr); t.setTextSize(14); t.setTextColor(fg); });
        }).start();
    }

    private TextView wpButton(String label, boolean filled, Runnable r) {
        TextView go = text(label, 15, filled ? 0xFFFFFFFF : accent);
        go.setGravity(Gravity.CENTER);
        go.setPadding(0, dp(11), 0, dp(11));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(12));
        if (filled) g.setColor(accent); else g.setStroke(dp(1), accent);
        go.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        go.setLayoutParams(lp);
        go.setOnClickListener(v -> r.run());
        return go;
    }

    /** Map screen and map wallpaper (own settings in the "wp" file; the wallpaper job runs in this app, not in System UI). */
    private void mapCard() {
        final android.content.SharedPreferences wp = WallpaperMaker.sp(this);
        if (wp.getBoolean("daynight", false)) DayNight.schedule(getApplicationContext(), true);
        LinearLayout c = infoCard("\uD83D\uDDFA\uFE0F", "Map and wallpaper", "your place on a map with rock, soil, faults and relief");
        c.addView(wpButton("Open live map", true, () -> startActivity(new android.content.Intent(this, MapActivity.class))));
        TextView st = text("", 12, sub);
        st.setPadding(0, dp(8), 0, 0);
        Runnable show = () -> {
            long at = wp.getLong("lastAt", 0);
            String base = at == 0 ? "No map wallpaper made yet" : "Last picture " + new java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US).format(new java.util.Date(at))
                    + (wp.getLong("lastMs", 0) > 0 ? " \u00b7 took " + wp.getLong("lastMs", 0) / 1000 + " s" : "");
            String why = wp.getString("status", "ok").equals("ok") ? "" : "\nLast try failed: " + reason(wp.getString("status", ""));
            String nt = wp.getString("notes", "");
            st.setText(base + (nt.isEmpty() ? "" : "\nLeft out: " + nt) + why);
        };
        show.run();
        c.addView(wpButton("Set wallpaper now", false, () -> {
            st.setText("Starting\u2026");
            final android.os.Handler hd = new android.os.Handler(android.os.Looper.getMainLooper());
            final Runnable[] tick = new Runnable[1];
            if (!WallpaperNowJob.start(getApplicationContext(), false)) { st.setText("Android refused to start the job. Try again."); return; }
            watch(st, show, hd, tick);
            android.widget.Toast.makeText(this, "Making it in the background. You can leave the app; a notification says when it is ready.", android.widget.Toast.LENGTH_LONG).show();
        }));
        c.addView(wpButton("Retry the missing names (waits longer)", false, () -> {
            st.setText("Starting\u2026");
            final android.os.Handler hd = new android.os.Handler(android.os.Looper.getMainLooper());
            final Runnable[] tick = new Runnable[1];
            if (!WallpaperNowJob.start(getApplicationContext(), true)) { st.setText("Android refused to start the job. Try again."); return; }
            watch(st, show, hd, tick);
            android.widget.Toast.makeText(this, "Retrying in the background. You can leave the app; a notification says when it is ready.", android.widget.Toast.LENGTH_LONG).show();
        }));
        c.addView(st);
        LinearLayout opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.VERTICAL);
        c.addView(opts);
        Switch dn = new Switch(this);
        dn.setText("Day and night wallpapers, automatic (Sahyadri pictures near Pune, live terrain map elsewhere)");
        dn.setTextColor(fg);
        dn.setTextSize(15);
        dn.setPadding(0, dp(12), 0, dp(4));
        dn.setChecked(wp.getBoolean("daynight", false));
        dn.setOnCheckedChangeListener((v, on) -> {
            wp.edit().putBoolean("daynight", on).commit();
            DayNight.schedule(getApplicationContext(), on);
            if (on) {
                wp.edit().putBoolean("auto", false).commit();
                WallpaperJob.schedule(getApplicationContext(), false);
                new Thread(() -> {
                    String r = DayNight.apply(getApplicationContext(), true);
                    runOnUiThread(() -> android.widget.Toast.makeText(this, r, android.widget.Toast.LENGTH_LONG).show());
                }).start();
            }
        });
        opts.addView(dn);
        Switch sw = new Switch(this);
        sw.setText("Keep the wallpaper on where I am");
        sw.setTextColor(fg);
        sw.setTextSize(15);
        sw.setPadding(0, dp(12), 0, dp(4));
        sw.setChecked(wp.getBoolean("auto", false));
        sw.setOnCheckedChangeListener((v, on) -> {
            wp.edit().putBoolean("auto", on).commit();
            WallpaperJob.schedule(getApplicationContext(), on);
            if (on) { wp.edit().putBoolean("daynight", false).commit(); DayNight.schedule(getApplicationContext(), false); dn.setChecked(false); }
        });
        opts.addView(sw);
        wpChoice(opts, wp, "Redraw after I move", "km", 1, new String[]{"1 km", "2 km", "5 km", "10 km"});
        wpChoice(opts, wp, "Map size", "zoom", 1, WallpaperMaker.ZOOM_NAMES);
        wpChoice(opts, wp, "Map style", "style", 0, WallpaperMaker.STYLES);
        wpChoice(opts, wp, "Put it on", "target", 0, WallpaperMaker.TARGETS);
        wpChoice(opts, wp, "Lock screen", "lockSky", 0, new String[]{"Live sky: Sun by day, Moon, stars and planets by night, over a mountain", "Same map as the home screen"});
        wpChoice(opts, wp, "Names on the map", "names", 0, new String[]{"Show peaks, dams, lakes, wetlands, faults", "Hide"});
        wpChoice(opts, wp, "Faults", "faults", 0, new String[]{"Show the fault lines", "Hide"});
        wpChoice(opts, wp, "Nearest coast, fault, forest, wetland", "coast", 0, new String[]{"Show lines with km and direction", "Hide"});
        wpChoice(opts, wp, "Cities, big peaks, rivers, region names (Region and Far sizes)", "cities", 0, new String[]{"Show", "Hide"});
        wpChoice(opts, wp, "Forest and grass colour", "forest", 0, new String[]{"Show", "Hide (plain terrain)"});
        wpChoice(opts, wp, "Colour key (legend)", "legend", 0, new String[]{"Show", "Hide"});
        wpChoice(opts, wp, "Info card on the picture", "card", 0, new String[]{"Show rock, soil, fault, relief", "Hide"});
        TextView n = text("The job runs about every 30 min but only draws when you have moved. Needs location permission and the phone not on low battery. Maps: OpenStreetMap.", 11, sub);
        n.setPadding(0, dp(8), 0, 0);
        opts.addView(n);
    }

    /** Keeps the status line moving while a background wallpaper job runs, then shows the result. */
    private void watch(TextView st, Runnable show, android.os.Handler hd, Runnable[] tick) {
        final long t0 = System.currentTimeMillis();
        tick[0] = () -> {
            if (WpStatus.active) { st.setText(WpStatus.progress()); hd.postDelayed(tick[0], 1000); }
            else if (System.currentTimeMillis() - t0 < 6000) hd.postDelayed(tick[0], 500);   // job not started yet
            else show.run();
        };
        hd.postDelayed(tick[0], 500);
    }

    /** A failure message in plain words. */
    private static String reason(String m) {
        String l = m.toLowerCase(java.util.Locale.ROOT);
        if (l.contains("no terrain tiles")) return "the elevation map server could not be reached (check the internet, then try again)";
        if (l.contains("no map tiles")) return "the map server could not be reached (check the internet, then try again)";
        if (l.contains("unknownhost") || l.contains("unable to resolve")) return "no internet connection";
        if (l.contains("timeout")) return "a server took too long to answer";
        if (l.contains("outofmemory")) return "the phone ran out of memory while drawing";
        if (l.contains("security") || l.contains("permission")) return "a permission is missing: " + m;
        return m;
    }

    /** km stores the index into KMS; card stores 0 = show, 1 = hide. */
    private void wpChoice(LinearLayout parent, final android.content.SharedPreferences wp, String label, String key, int def, String[] o) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, dp(10), 0, dp(6));
        r.addView(text(label, 15, fg));
        final java.util.function.IntSupplier cur = () -> {
            if (key.equals("km")) { int km = wp.getInt("km", 2); for (int i = 0; i < WallpaperMaker.KMS.length; i++) if (WallpaperMaker.KMS[i] == km) return i; return def; }
            if (key.equals("card")) return wp.getBoolean("card", false) ? 0 : 1;
            if (key.equals("names")) return wp.getBoolean("names", true) ? 0 : 1;
            return Math.max(0, Math.min(o.length - 1, wp.getInt(key, def)));
        };
        final TextView v = text(o[cur.getAsInt()], 13, sub);
        r.addView(v);
        r.setOnClickListener(x -> new AlertDialog.Builder(this).setTitle(label).setSingleChoiceItems(o, cur.getAsInt(), (d, w) -> {
            if (key.equals("km")) wp.edit().putInt("km", WallpaperMaker.KMS[w]).commit();
            else if (key.equals("card")) wp.edit().putBoolean("card", w == 0).commit();
            else if (key.equals("names")) wp.edit().putBoolean("names", w == 0).commit();
            else wp.edit().putInt(key, w).commit();
            v.setText(o[w]);
            d.dismiss();
        }).show());
        parent.addView(r);
    }

    private void soilCard() {
        LinearLayout c = infoCard("🌱", "Soil at another place", "type a place name, or paste coordinates from Google Maps");
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setHint("e.g. Lonavala  or  18.7546, 73.4062");
        in.setText(soilQ);
        in.setSingleLine(true);
        in.setTextColor(fg);
        in.setHintTextColor(sub);
        in.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        c.addView(in, new LinearLayout.LayoutParams(-1, -2));
        TextView go = text("Look up soil", 15, 0xFFFFFFFF);
        go.setGravity(Gravity.CENTER);
        go.setPadding(0, dp(11), 0, dp(11));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF2F7FD1);
        g.setCornerRadius(dp(12));
        go.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(8), 0, dp(4));
        c.addView(go, lp);
        final Runnable run = () -> {
            String q = in.getText().toString().trim();
            if (!q.isEmpty()) {
                android.view.inputmethod.InputMethodManager im = getSystemService(android.view.inputmethod.InputMethodManager.class);
                if (im != null) im.hideSoftInputFromWindow(in.getWindowToken(), 0);
                soilLookup(q);
            }
        };
        go.setOnClickListener(v -> run.run());
        in.setOnEditorActionListener((v, a, e) -> { run.run(); return true; });
        soilOut = new LinearLayout(this);
        soilOut.setOrientation(LinearLayout.VERTICAL);
        c.addView(soilOut);
        showSoil();
    }

    /** Nearest mapped faults to where the phone last was. */
    private void showFaults() {
        Toast.makeText(this, "Locating…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            android.content.SharedPreferences d = getSharedPreferences(DamJob.PREFS, MODE_PRIVATE);
            double lat, lon;
            String where;
            if (d.contains("lat")) { lat = d.getFloat("lat", 0); lon = d.getFloat("lon", 0); where = "last known position"; }
            else { Object[] w = DamJob.where(getApplicationContext()); lat = (Double) w[0]; lon = (Double) w[1]; where = (String) w[3]; }
            java.util.List<Faults.Hit> hs = Faults.nearest(lat, lon, 6);
            StringBuilder b = new StringBuilder();
            b.append(String.format(java.util.Locale.US, "Position used: %.3f, %.3f (%s)\n\n", lat, lon, where));
            b.append("Nearest mapped faults\n");
            if (hs.isEmpty()) b.append("  none in the data within 1500 km (the data covers India, South and Central Asia)\n");
            for (Faults.Hit h : hs)
                b.append(String.format(java.util.Locale.US, "  %s  %d km %s\n      %s\n", h.label(), Math.round(h.km), h.dir, Faults.detail(h)));
            b.append("\n").append(Faults.credit());
            final String txt = b.toString();
            runOnUiThread(() -> {
                TextView tv = new TextView(this);
                tv.setText(txt);
                tv.setTextSize(13);
                tv.setTextIsSelectable(true);
                tv.setPadding(dp(18), dp(12), dp(18), dp(4));
                android.widget.ScrollView sv = new android.widget.ScrollView(this);
                sv.addView(tv);
                new AlertDialog.Builder(this).setTitle("Fault lines").setView(sv).setNegativeButton("Close", null).show();
            });
        }).start();
    }

    private void showIntegrity() {
        String r = IntegrityReceiver.history(this);
        if (r.isEmpty()) r = "No checks seen yet.\n\nTick Google Play Store in LSPosed for this module, then force-stop "
                + "Play Store (or reboot). Each check an app makes shows up here.";
        TextView tv = new TextView(this);
        tv.setText(r);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(12);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(18), dp(12), dp(18), dp(4));
        tv.setHorizontallyScrolling(true);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.addView(tv);
        sv.addView(hs);
        new AlertDialog.Builder(this)
                .setTitle("Play Integrity checks")
                .setView(sv)
                .setPositiveButton("Refresh", (d, w) -> showIntegrity())
                .setNeutralButton("Clear", (d, w) -> IntegrityReceiver.clear(this))
                .setNegativeButton("Close", null)
                .show();
    }

    /**
     * Live diagnostics, one topic at a time. System UI publishes one long report every 30 s; it is split at its
     * headings (lines in capitals at the left edge) so each topic opens on its own, and the lines about the river, the
     * earth line and the weather place move out of "TOWERS" into a section of their own.
     */
    private java.util.LinkedHashMap<String, String> diagSections() {
        String r = null;
        try { r = android.provider.Settings.Global.getString(getContentResolver(), "netspeed_sat_report"); } catch (Throwable ignored) { }
        java.util.LinkedHashMap<String, StringBuilder> m = new java.util.LinkedHashMap<>();
        if (r == null || r.isEmpty()) {
            m.put("STATUS", new StringBuilder("No report from the status bar yet.\n\nIt is published every 30 s once the module is "
                    + "running in System UI (restart System UI after an update) and the screen is on.\n"));
        } else {
            String cur = "STATUS";
            StringBuilder extra = new StringBuilder();
            for (String ln : r.split("\n", -1)) {
                if (ln.matches("[A-Z][A-Z0-9 &/+()'-]{2,}")) { cur = ln.trim(); if (!m.containsKey(cur)) m.put(cur, new StringBuilder()); continue; }
                String t = ln.trim();
                if (cur.equals("TOWERS") && (t.startsWith("river flow:") || t.startsWith("earth line:") || t.startsWith("WEATHER place:"))) { extra.append(ln).append('\n'); continue; }
                if (!m.containsKey(cur)) m.put(cur, new StringBuilder());
                m.get(cur).append(ln).append('\n');
            }
            if (extra.length() > 0) m.put("RIVER, EARTH LINE AND WEATHER PLACE", extra);
        }
        try { m.put("RESERVOIR WATCH", new StringBuilder(damReport()).append('\n')); } catch (Throwable ignored) { }
        try { m.put("FAULT LINES", new StringBuilder(faultReport()).append('\n')); } catch (Throwable ignored) { }
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        try {   // one line per strip of the place line, first in the list: does it have data, and if not, why
            String f = android.provider.Settings.Global.getString(getContentResolver(), "netspeed_info");
            StringBuilder sb = new StringBuilder();
            int ok = 0, bad = 0;
            if (f != null) for (String ln : f.split("\n")) {
                String[] p = ln.split("\\|", 3);
                if (p.length < 3 || !p[0].equals("S")) continue;
                sb.append(p[1]).append("\n  ").append(p[2]).append("\n\n");
                if (p[2].startsWith("\u2705")) ok++; else if (p[2].startsWith("\u26A0")) bad++;
            }
            if (sb.length() > 0) out.put("PLACE LINE STRIPS", (ok + " working, " + bad + " without data right now. Updated every 30 s.\n"
                    + "A strip with data still has to come round in the rotation.\n\n") + sb);
            else out.put("PLACE LINE STRIPS", "No strip report from the status bar yet. Install this build, restart System UI and wait a minute.");
        } catch (Throwable ignored) { }
        for (java.util.Map.Entry<String, StringBuilder> e : m.entrySet()) {
            String v = e.getValue().toString().replaceAll("^\\s+|\\s+$", "");
            if (!v.isEmpty()) out.put(e.getKey(), v);
        }
        return out;
    }


    // ------------------------------------------------------------------ SIM box guide
    private static String g(java.util.Map<String, String> m, String k) { String v = m.get(k); return v == null || v.isEmpty() ? null : v; }

    /** One line per item on the SIM boxes: how it looks on the bar, what it means, your value right now, and a plain-words note for the hard terms. */
    private void showSimGuide(int pick) {
        java.util.List<java.util.Map<String, String>> sims = new java.util.ArrayList<>();
        try {
            String f = android.provider.Settings.Global.getString(getContentResolver(), "netspeed_info");
            if (f != null) for (String ln : f.split("\n")) {
                String[] p = ln.split("\\|", 3);
                if (p.length < 3 || !p[0].equals("SIMLIVE")) continue;
                java.util.Map<String, String> m = new java.util.HashMap<>();
                m.put("slot", p[1]);
                for (String kv : p[2].split(";")) { int i = kv.indexOf('='); if (i > 0) m.put(kv.substring(0, i), kv.substring(i + 1)); }
                sims.add(m);
            }
        } catch (Throwable ignored) { }
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (java.util.Map<String, String> x : sims) if (pick < 0 ? "1".equals(x.get("data")) : String.valueOf(pick).equals(x.get("slot"))) m = x;
        if (m.isEmpty() && !sims.isEmpty()) m = sims.get(0);
        final boolean live = !m.isEmpty();
        final java.util.Map<String, String> cur = m;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(10), dp(16), dp(4));
        TextView top = new TextView(this);
        top.setTextSize(12);
        top.setTextColor(sub);
        top.setText(live ? "Blue = what the status bar shows for SIM " + Integer.parseInt(m.get("slot")) + ("1".equals(m.get("data")) ? " (the data SIM)" : "") + " right now. A ~ in front means a rough estimate, not exact."
                : "No live values yet. Install this build, restart System UI and wait a minute. The meanings below still apply. A ~ in front of a value means a rough estimate.");
        col.addView(top);
        String rat = g(m, "rat"), nr = g(m, "nr"), dbm = g(m, "dbm"), snr = g(m, "snr"), q = g(m, "q"), tr = g(m, "trend");
        int qn = -1; try { qn = Integer.parseInt(q); } catch (Throwable ignored) { }
        String qc = qn < 0 ? null : qn >= 70 ? "green" : qn >= 45 ? "yellow" : qn >= 25 ? "orange" : "red";
        String dir = g(m, "dir"), h = g(m, "h"), ang = g(m, "angle"), cl = g(m, "clear"), los = g(m, "los");
        String est = "1".equals(m.get("est")) || (dir != null && dir.endsWith("~")) ? "~" : "";
        String[][] rows = {
            {"HEADER"},
            {"5G+ / 4G+ / 5G / 4G", "Network type your SIM is on. The + means several channels are joined for more speed.", live ? (rat == null ? "no network" : rat) : null, ""},
            {"\u2582\u2584\u2586\u2588 bars", "Signal strength as bars, same as the phone's own signal icon.", null, ""},
            {"SA / NSA", "How 5G is carried.", nr, "NSA = 5G speed on top of a 4G anchor connection. SA = pure 5G that needs no 4G."},
            {"VoNR / VoLTE", "What your calls run on: data, not old 2G/3G.", live ? g(m, "voice") : null, "VoNR = calls on 5G. VoLTE = calls on 4G. WiFi = calls over WiFi."},
            {"SIGNAL LINE"},
            {"-92 dBm", "How strong the tower's signal is when it reaches you.", dbm == null ? null : dbm + " dBm", "dBm is signal power. Closer to 0 is stronger: -70 is great, -90 is okay, -110 is poor."},
            {"\u2191 / \u2193  +4dB", "Signal getting better or worse over the last minute.", tr == null ? (live ? "steady" : null) : tr, ""},
            {"SINR", "How clean the signal is, compared with noise and other towers.", snr == null ? null : snr + " dB", "SINR = signal to interference plus noise. Higher is cleaner. Above 13 is good, below 3 is bad."},
            {"N", "You just moved to a different tower (last 2 minutes).", live ? ("1".equals(m.get("new")) ? "yes, new cell" : "no") : null, "A cell is one antenna sector on a tower. A tower has several."},
            {"Q 68%", "Quality score from dBm (60%) and SINR (40%). Green 70+, yellow 45+, orange 25+, red below.", q == null ? null : q + "% " + qc, ""},
            {"BAND LINE"},
            {"n78 \u00b7 2.0km", "Channel group (band) in use and the distance to the tower.", g(m, "band") == null ? null : g(m, "band") + (g(m, "dist") == null ? "" : " \u00b7 " + g(m, "dist")), "Band = the radio frequency slot. n78 is 3.5 GHz: fast but short range. Lower bands such as B5 or B28 reach farther but are slower."},
            {"cells heard", "How many cells the phone can hear at the moment.", g(m, "cells"), ""},
            {"\u00b7 100 MHz", "Width of the channel, after the cells-heard number. More MHz, more speed.", g(m, "bw") == null ? null : g(m, "bw") + " MHz", "~ in front means the modem gave no width and this is the usual value for that band (100 MHz on n78, 10 MHz on n28)."},
            {"TOWER AND CELL LINE"},
            {"12m \u00d73", "Time on this cell, and how many times you changed cells today.", g(m, "oncell") == null ? null : g(m, "oncell") + " on it, " + g(m, "ho") + " changes today", "A change of cell as you move is called a handover."},
            {"\u2197 arrow", "Direction from you to the tower.", dir, "Yellow = exact tower position. Grey with ~ = rough guess."},
            {"LOS clear / LOS +6m", "Line of sight from you to the tower: clear, or how many metres of ground stick up in the way.", los == null || "0".equals(los) ? (live ? "clear" : null) : los + " m in the way", "LOS = line of sight, looked at from your place toward the tower."},
            {"\u2220+1\u00b0", "Angle you look up (+) or down (-) to the antenna.", ang == null ? null : est + (Integer.parseInt(ang) >= 0 ? "+" : "\u2212") + Math.abs(Integer.parseInt(ang)) + "\u00b0", ""},
            {"28 km", "Radio horizon: how far a signal can reach before the Earth's curve hides it. Uses the tower's real mast height when OpenStreetMap lists it, else an assumed 30 m (shown with ~).", live ? (g(m, "hz") != null ? g(m, "hz") : est + "28") + " km" : null, "Radio waves bend slightly with the air, so the horizon is a bit farther than what the eye sees."},
            {"F 85%", "Clearance, shown beside the direction arrow: how free the signal's oval path is of hills.", cl == null ? null : est + cl + "%", "F is for Fresnel zone, the oval corridor around the straight line to the tower. Colour: green 80% or more, yellow 50 to 79, orange below 50. Less free means ground is eating signal. Buildings and trees are not counted."},
            {"mast 45m", "Blue tag: the tower's real mast height, found in OpenStreetMap. Only some towers have it.", g(m, "mast") == null ? (live ? "none listed, 30 m assumed" : null) : g(m, "mast") + " m", "With a real height the ~ goes away on the angle, horizon and F. Without it the app assumes 30 m and keeps the ~."},
            {"B3 \u221284 (with icon)", "Best neighbouring tower: its band and signal. Orange = weaker than your tower, green = stronger, so a switch may come. It sits at the right end of the first free line, under the band line.", g(m, "nbr"), "A neighbour is another tower the phone can hear and may hand over to."},
            {"BOTTOM ROW"},
            {"\u2227 \u2196NW 14m", "A line for the shape of the land around you, then the direction and height of the highest ground 300 m away.", g(m, "land") == null ? null : g(m, "land") + (g(m, "shade") == null ? "" : " " + g(m, "shade")), "A line that draws the shape of the ground. \u2227 = Ridge: you stand higher than the land around. \u2571 = Slope: ground rises one way and falls the other. V = Valley: you stand lower than most of the land around. \u2500 = Flat: under 6 m of difference. Green when nothing blocks the path to this tower, orange when ground does. ~ = rough tower position."},
            {"\u25b214m@40%", "The highest ground on the path from you to the tower: how far above you (\u25b2) or below you (\u25bc), and how far along the path it is.", g(m, "peak"), "@40% means 40% of the way to the tower. This is the ground itself, not how far it sticks into the signal line, which the LOS line already tells you. Colour follows the line-of-sight check."},
            {"Mountain box +6m", "Tower's ground compared with yours. Not the mast height, a phone cannot know that.", h, "Green = tower ground higher than yours, orange = lower, grey = rough. It sits after the arrow and F on the line above; if a neighbour tower takes the end of that line, it moves to the landform line at the bottom of the box."},
        };
        for (String[] r : rows) {
            if (r.length == 1) {
                TextView hd = new TextView(this);
                hd.setText(r[0]);
                hd.setTextSize(12);
                hd.setTextColor(sub);
                hd.setTypeface(null, android.graphics.Typeface.BOLD);
                hd.setPadding(0, dp(16), 0, dp(2));
                col.addView(hd);
                continue;
            }
            TextView a = new TextView(this);
            a.setText(r[0]);
            a.setTextSize(16);
            a.setTypeface(null, android.graphics.Typeface.BOLD);
            a.setTextColor(accent);
            a.setPadding(0, dp(8), 0, 0);
            col.addView(a);
            TextView b = new TextView(this);
            b.setText(r[1]);
            b.setTextSize(14);
            b.setTextColor(fg);
            col.addView(b);
            if (r[2] != null) {
                TextView v = new TextView(this);
                v.setText("now: " + r[2]);
                v.setTextSize(14);
                v.setTextColor(0xFF4FA3FF);
                col.addView(v);
            }
            if (!r[3].isEmpty()) {
                TextView e = new TextView(this);
                e.setText("\u24D8 " + r[3]);
                e.setTextSize(12);
                e.setTextColor(sub);
                col.addView(e);
            }
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(col);
        new AlertDialog.Builder(this).setTitle("SIM box guide").setView(sv)
                .setPositiveButton("Refresh", (d, w) -> showSimGuide(live ? Integer.parseInt(cur.get("slot")) : -1))
                .setNeutralButton(sims.size() > 1 ? "Other SIM" : null, (d, w) -> {
                    for (java.util.Map<String, String> x : sims) if (!x.get("slot").equals(cur.get("slot"))) { showSimGuide(Integer.parseInt(x.get("slot"))); return; }
                })
                .setNegativeButton("Close", null).show();
    }

    private void showHeatHistory(final long win) {
        final android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(8), dp(12), dp(8));
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        String[] names = {"3 h", "12 h", "24 h", "7 days"};
        final long[] wins = {3 * 3600_000L, 12 * 3600_000L, 24 * 3600_000L, 7 * 86400_000L};
        for (int i = 0; i < 4; i++) {
            final int k = i;
            android.widget.Button b = new android.widget.Button(this);
            b.setText(names[i]); b.setAllCaps(false); b.setTextSize(12);
            b.setEnabled(wins[i] != win);
            b.setOnClickListener(v -> { dismissHistory(); showHeatHistory(wins[k]); });
            row.addView(b, new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        col.addView(row);
        final HeatGraph g = new HeatGraph(this);
        col.addView(g);
        final TextView sum = new TextView(this);
        sum.setTextSize(12); sum.setTextColor(fg); sum.setText("Reading…"); sum.setPadding(0, dp(8), 0, 0);
        col.addView(sum);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(col);
        histDlg = new AlertDialog.Builder(this).setTitle("Heat and power history").setView(sv).setNegativeButton("Close", null).show();
        new Thread(() -> {
            try { HeatLog.snapshot(getApplicationContext()); } catch (Throwable ignored) { }
            final HeatLog.Data dd = HeatLog.build(getApplicationContext(), win);
            StringBuilder sb = new StringBuilder();
            double tot = Math.max(1, dd.totalMah);
            sb.append(String.format(java.util.Locale.US, "Used in this period: %.0f mAh\n\nBY CAUSE\n", dd.totalMah));
            for (int k = 0; k < 6; k++) sb.append(String.format(java.util.Locale.US, "  %-18s %5.0f mAh  (%.0f%%)\n", HeatLog.CAT[k], dd.catMah[k], dd.catMah[k] / tot * 100));
            sb.append("\nBY APP (including system ones)\n");
            java.util.List<java.util.Map.Entry<String, Double>> es = new java.util.ArrayList<>(dd.apps.entrySet());
            es.sort((a, b2) -> Double.compare(b2.getValue(), a.getValue()));
            for (int i = 0; i < Math.min(12, es.size()); i++) sb.append(String.format(java.util.Locale.US, "  %5.0f mAh  (%.0f%%)  %s\n", es.get(i).getValue(), es.get(i).getValue() / tot * 100, es.get(i).getKey()));
            if (es.isEmpty()) sb.append("  nothing yet: readings are taken every 15 minutes\n");
            runOnUiThread(() -> { g.setData(dd); sum.setText(sb.toString()); sum.setTypeface(android.graphics.Typeface.MONOSPACE); });
        }).start();
    }

    private static final float[] UV_MED = {200, 250, 300, 450, 600, 900};   // J/m2 of sunburn-causing UV for skin types I to VI (typical)

    private void showUvGuide() {
        final android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(8), dp(14), dp(8));
        String more = "";
        try { more = android.provider.Settings.Global.getString(getContentResolver(), "netspeed_wx_more"); } catch (Throwable ignored) { }
        String[] mo = (more == null ? "" : more).split("\\|", -1);
        float[] uvh = new float[24];
        boolean haveSeries = false;
        try {
            String[] u = mo[5].split(",");
            if (u.length >= 24) { for (int i = 0; i < 24; i++) uvh[i] = Float.parseFloat(u[i]); haveSeries = true; }
        } catch (Throwable ignored) { }
        float uvNow = Float.NaN;
        try { uvNow = Float.parseFloat(mo[8]); } catch (Throwable ignored) { }
        final int skin = Math.max(1, Math.min(6, sp.getInt("uv_skin", 5)));
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        float med = UV_MED[skin - 1];

        TextView head = text("UV right now", 13, sub);
        col.addView(head);
        String lvl = Float.isNaN(uvNow) ? "unknown" : uvNow < 3 ? "LOW" : uvNow < 6 ? "MODERATE" : uvNow < 8 ? "HIGH" : uvNow < 11 ? "VERY HIGH" : "EXTREME";
        TextView big = text(Float.isNaN(uvNow) ? "No reading yet (needs the status bar's weather)" : String.format(java.util.Locale.US, "%.0f  %s", uvNow, lvl), Float.isNaN(uvNow) ? 14 : 30, Float.isNaN(uvNow) ? fg : UvBars.colour(uvNow));
        col.addView(big);
        if (!Float.isNaN(uvNow) && uvNow >= 0.5f) {
            int burn = (int) Math.round(med / (1.5 * uvNow) / 5.0) * 5;
            int tan = Math.max(5, (int) Math.round(burn * 0.4 / 5.0) * 5);
            col.addView(text("Unprotected skin (type " + new String[]{"I", "II", "III", "IV", "V", "VI"}[skin - 1] + "): starts to tan in about " + tan + " min and can burn in about " + burn + " min.", 13, fg));
        }
        if (haveSeries) {
            float peak = 0; int ph = 0;
            for (int h = 6; h < 19; h++) if (uvh[h] > peak) { peak = uvh[h]; ph = h; }
            col.addView(text(String.format(java.util.Locale.US, "Today's peak: %.1f at %d:00. Hours with UV under 3 (safe): %s", peak, ph, safeHours(uvh)), 13, sub));
            UvBars bars = new UvBars(this);
            bars.set(uvh, hour);
            col.addView(bars);
            double dose = 0;
            for (int h = 0; h <= hour && h < 24; h++) dose += uvh[h] * 90.0 * (h == hour ? (java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE) / 60.0) : 1.0);
            col.addView(text(String.format(java.util.Locale.US, "Sun so far today, if you were outside the whole time with nothing on: %.0f%% of what burns your skin type. Shade, a cap and sunscreen cut this a lot.", dose / med * 100), 12, sub));
        }
        col.addView(text("Your skin type (sets the tan and burn times)", 13, sub));
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        final String[] rn = {"I", "II", "III", "IV", "V", "VI"};
        for (int i = 0; i < 6; i++) {
            final int k = i + 1;
            android.widget.Button b = new android.widget.Button(this);
            b.setText(rn[i]); b.setAllCaps(false); b.setEnabled(k != skin);
            b.setOnClickListener(v -> { sp.edit().putInt("uv_skin", k).apply(); if (uvDlg != null) uvDlg.dismiss(); showUvGuide(); });
            row.addView(b, new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        col.addView(row);
        col.addView(text("Type V is brown skin that tans easily and rarely burns. The lock screen picture uses this too.", 12, sub));
        final TextView sun = text("", 14, fg);
        long at = sp.getLong("uv_applied", 0);
        sun.setText(at == 0 ? "Sunscreen: not logged. Tap the button when you put it on, and I show when to redo it."
                : System.currentTimeMillis() - at < 2 * 3600_000L ? "Sunscreen on for " + ((System.currentTimeMillis() - at) / 60000) + " min. Redo in " + (120 - (System.currentTimeMillis() - at) / 60000) + " min."
                : "Applied " + ((System.currentTimeMillis() - at) / 60000) + " min ago. Time to put more on.");
        col.addView(sun);
        android.widget.Button ap = new android.widget.Button(this);
        ap.setText("I put sunscreen on just now"); ap.setAllCaps(false);
        ap.setOnClickListener(v -> { sp.edit().putLong("uv_applied", System.currentTimeMillis()).apply(); sun.setText("Logged. Redo in 2 h, or sooner after heavy sweat."); });
        col.addView(ap);
        col.addView(text("WHAT TO DO IN THE FIELD", 13, accent));
        String[] tips = {"SPF 50+ on face, ears, neck and hands: those four get missed", "Cap with a neck flap, UV sunglasses, long sleeves", "Work in shade between 11:00 and 15:00 when you can",
                "Clouds do not stop UV: up to 80% still gets through", "Concrete, water and metal reflect more UV onto you", "Redo sunscreen every 2 h and after sweating a lot",
                "UVB burns the surface, UVA goes deeper and ages and darkens skin", "A tan is the skin's injury response, not protection"};
        for (String t : tips) col.addView(text("• " + t, 13, fg));
        col.addView(text("Times are standard estimates for each skin type, not medical advice.", 11, sub));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(col);
        uvDlg = new AlertDialog.Builder(this).setTitle("UV guide").setView(sv).setNegativeButton("Close", null).show();
    }

    private AlertDialog uvDlg;

    private double[] guideLatLon() {
        try {
            android.location.Location l = TowerDb.myLocation(this);
            if (l != null) return new double[]{l.getLatitude(), l.getLongitude()};
            double[] lk = TowerDb.lastKnown(this);
            if (lk != null) return new double[]{lk[0], lk[1]};
        } catch (Throwable ignored) { }
        return new double[]{DayNight.HOME_LAT, DayNight.HOME_LON};
    }

    private void showGuideText(String title, String body) {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        TextView t = text(body, 14, fg);
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        sv.addView(t);
        new AlertDialog.Builder(this).setTitle(title).setView(sv).setPositiveButton("Close", null).show();
    }

    private void showPlaceGuide() {
        Toast.makeText(this, "Looking up the place…", Toast.LENGTH_SHORT).show();
        final double[] ll = guideLatLon();
        new Thread(() -> {
            PlaceInfo.Info in = PlaceInfo.lookup(getApplicationContext(), sp, ll[0], ll[1]);
            StringBuilder sb = new StringBuilder();
            sb.append(in.where().isEmpty() ? "Place name not found (needs data once; then it is saved).\n" : in.where() + "\n");
            sb.append(String.format(java.util.Locale.US, "(%.4f, %.4f)\n\n", ll[0], ll[1]));
            sb.append("SOIL\n  ").append(in.soil.isEmpty() ? "no guide entry for this state yet" : in.soil).append("\n\nPLANTS YOU WILL COMMONLY SEE\n  ").append(in.plants.isEmpty() ? "-" : in.plants).append('\n');
            try {
                Habitat.Data h = Habitat.cached(getApplicationContext(), ll[0], ll[1]);
                if (h != null && h.any()) {
                    if (!h.region().isEmpty()) sb.append("\nREGION\n  ").append(h.region()).append('\n');
                    java.util.List<String> top = h.top(8);
                    if (!top.isEmpty()) sb.append("\nPLANTS PEOPLE HAVE LOGGED WITHIN 10 KM (iNaturalist)\n  ").append(android.text.TextUtils.join(", ", top)).append('\n');
                    String cv = h.coverText(5);
                    if (!cv.isEmpty()) sb.append("\nLAND AROUND YOU (about 5 km)\n  ").append(cv).append('\n');
                }
            } catch (Throwable ignored) { }
            sb.append("\nThe soil and plant lines come from a built-in guide for each state and zone of India: a general picture, not a lab test of your exact spot.");
            final String out = sb.toString();
            runOnUiThread(() -> showGuideText("Place guide", out));
        }).start();
    }

    private void showSkyGuide() {
        Toast.makeText(this, "Working out the sky…", Toast.LENGTH_SHORT).show();
        final double[] ll = guideLatLon();
        new Thread(() -> {
            String body;
            try { body = SkyScene.guideText(getApplicationContext(), ll[0], ll[1], System.currentTimeMillis()); } catch (Throwable t) { body = "Could not work out the sky: " + t; }
            final String out = new java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.US).format(new java.util.Date()) + "\n\n" + body;
            runOnUiThread(() -> showGuideText("Sky guide", out));
        }).start();
    }

    private static String safeHours(float[] uvh) {
        StringBuilder sb = new StringBuilder();
        for (int h = 6; h < 20; h++) if (uvh[h] < 3) { if (sb.length() > 0) sb.append(", "); sb.append(h); }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private AlertDialog histDlg;
    private void dismissHistory() { try { if (histDlg != null) histDlg.dismiss(); } catch (Throwable ignored) { } }

    private void showHeatReport() {
        Toast.makeText(this, "Measuring for about 10 seconds…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String live = "";
            try { live = String.valueOf(android.provider.Settings.Global.getString(getContentResolver(), "netspeed_info")); } catch (Throwable ignored) { }
            final String rep = HeatReport.build(getApplicationContext(), live);
            runOnUiThread(() -> {
                TextView tv = new TextView(this);
                tv.setText(rep); tv.setTextSize(12); tv.setTextColor(fg); tv.setTypeface(android.graphics.Typeface.MONOSPACE);
                tv.setPadding(dp(16), dp(8), dp(16), dp(8)); tv.setTextIsSelectable(true);
                android.widget.ScrollView sv = new android.widget.ScrollView(this); sv.addView(tv);
                new AlertDialog.Builder(this).setTitle("Heat and power").setView(sv)
                        .setPositiveButton("Measure again", (d, w) -> showHeatReport())
                        .setNeutralButton("Copy", (d, w) -> { ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("heat", rep)); Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show(); })
                        .setNegativeButton("Close", null).show();
            });
        }).start();
    }

    /** The topic list; tap one to see only its diagnostics. */
    private void showSatellites() {
        final java.util.LinkedHashMap<String, String> secs = diagSections();
        final String[] keys = secs.keySet().toArray(new String[0]);
        String[] labels = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            String k = keys[i];
            labels[i] = k.charAt(0) + k.substring(1).toLowerCase(java.util.Locale.ROOT);
        }
        new AlertDialog.Builder(this)
                .setTitle("Live diagnostics")
                .setItems(labels, (d, which) -> showDiagSection(keys[which], labels[which]))
                .setNegativeButton("Close", null)
                .show();
    }

    private void showDiagSection(String key, String label) {
        String r = diagSections().get(key);
        if (r == null) r = "Nothing for this topic yet.";
        TextView tv = new TextView(this);
        tv.setText(r);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(11);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(14), dp(12), dp(14), dp(4));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        tv.setFocusable(false);
        sv.addView(tv);
        sv.post(() -> sv.scrollTo(0, 0));
        new AlertDialog.Builder(this)
                .setTitle(label)
                .setView(sv)
                .setPositiveButton("Refresh", (d, w) -> {
                    if (!key.equals("RESERVOIR WATCH") || !sp.getBoolean(Cfg.DAM, Cfg.D_DAM)) { showDiagSection(key, label); return; }
                    Toast.makeText(this, "Refreshing, checking the dam…", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        String res = DamJob.check(getApplicationContext(), false);
                        runOnUiThread(() -> { Toast.makeText(this, res, Toast.LENGTH_LONG).show(); showDiagSection(key, label); });
                    }).start();
                })
                .setNeutralButton("All topics", (d, w) -> showSatellites())
                .setNegativeButton("Close", null)
                .show();
    }

    private void button(LinearLayout parent, String label, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(primary ? (isDark() ? 0xFF06251C : Color.WHITE) : accent);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(24));
        if (primary) g.setColor(accent);
        else {
            g.setColor(Color.TRANSPARENT);
            g.setStroke(dp(1), accent);
        }
        b.setBackground(g);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp;
        if (parent.getOrientation() == LinearLayout.VERTICAL) {
            lp = new LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
            lp.setMargins(0, dp(8), 0, dp(8));
        } else {
            lp = new LinearLayout.LayoutParams(0, dp(48), primary ? 2f : 1f);
            lp.setMarginEnd(primary ? dp(10) : 0);
        }
        parent.addView(b, lp);
    }

    private TextView text(String t, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        return v;
    }

    // ------------------------------------------------------------ actions

    /** Tells System UI to reload settings. */
    private void changed() {
        getContentResolver().notifyChange(URI, null);
    }

    /** A switch backed by a system property (set with root), for settings the system server reads. */
    private void propToggle(LinearLayout parent, String label, String desc, String prop) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(12), 0, dp(12));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(label, 16, fg));
        if (desc != null) texts.addView(text(desc, 13, sub));
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(sp.getBoolean(Cfg.NAV_IMMERSIVE, Cfg.D_NAV_IMMERSIVE)
                || "1".equals(getProp(prop)) || "true".equals(getProp(prop)));
        sw.setOnCheckedChangeListener((v, on) -> new Thread(() -> {
            // Saved for System UI (applies on Restart System UI), and as a property for the
            // system server (applies after a reboot).
            sp.edit().putBoolean(Cfg.NAV_IMMERSIVE, on).commit();
            changed();
            String val = on ? "1" : "0";
            boolean ok = runSu("setprop " + prop + " " + val)
                    || runSu("resetprop -p " + prop + " " + val)
                    || runSu("/data/adb/ksu/bin/resetprop -p " + prop + " " + val);
            boolean took = val.equals(getProp(prop));
            runOnUiThread(() -> {
                new AlertDialog.Builder(this)
                        .setMessage((on ? "Immersive navigation on." : "Immersive navigation off.")
                                + " Restart System UI + launcher to apply.")
                        .setPositiveButton("Restart System UI + launcher", (d, w) -> {
                            new Thread(() -> runSu("am force-stop $(cmd package resolve-activity --brief "
                                    + "-a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1 "
                                    + "| cut -d/ -f1)")).start();
                            restartSystemUi();
                        })
                        .setNegativeButton("Later", null)
                        .show();
            });
        }).start());
        r.addView(sw);
        r.setOnClickListener(v -> sw.toggle());
        parent.addView(r);
    }

    /**
     * Edge-to-edge for every app. Android forces it on apps whose target SDK is at least
     * persist.wm.debug.default_e2e_since_sdk (read once at boot); 0 = every app. Apps draw behind the
     * status bar and around the camera and pad their content by the bar's height.
     */
    private static final String E2E_PROP = "persist.wm.debug.default_e2e_since_sdk";

    private void e2eToggle(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(12), 0, dp(12));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text("Apps draw to the screen edges", 16, fg));
        texts.addView(text("Every app draws behind the status bar and around the camera, like apps made for "
                + "Android 15+. If one app's top bar ends up under the status bar, turn this off. Needs a reboot", 13, sub));
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(this);
        sw.setChecked("0".equals(getProp(E2E_PROP)));
        sw.setOnCheckedChangeListener((v, on) -> new Thread(() -> {
            String val = on ? "0" : "2147483647";
            boolean ok = runSu("setprop " + E2E_PROP + " " + val)
                    || runSu("resetprop -p " + E2E_PROP + " " + val)
                    || runSu("/data/adb/ksu/bin/resetprop -p " + E2E_PROP + " " + val);
            boolean took = val.equals(getProp(E2E_PROP));
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setMessage(took ? (on ? "Edge-to-edge for all apps is on." : "Edge-to-edge for all apps is off.")
                            + " Reboot to apply." : "Couldn't set it (root denied?).")
                    .setPositiveButton(took ? "Reboot now" : "OK", (d, w) -> {
                        if (took) new Thread(() -> runSu("svc power reboot || reboot")).start();
                    })
                    .setNegativeButton("Later", null)
                    .show());
        }).start());
        r.addView(sw);
        r.setOnClickListener(v -> sw.toggle());
        parent.addView(r);
    }

    /** App suggestions (launcher dock + app drawer). Needs a reboot: the system reads it at boot. */
    private void predictToggle(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(12), 0, dp(12));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text("App suggestions ⟳ reboot", 16, fg));
        texts.addView(text("Pixel-style suggested apps in empty dock slots and at the top of the app "
                + "drawer, from your own usage history (stays on this phone). Tick System Framework "
                + "in the scope", 13, sub));
        TextView st = text(predictStatus(), 12, sub);
        texts.addView(st);
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(this);
        String prop = Predict.PROP;
        sw.setChecked(sp.getBoolean(Cfg.PREDICT, Cfg.D_PREDICT));
        sw.setOnCheckedChangeListener((v, on) -> new Thread(() -> {
            sp.edit().putBoolean(Cfg.PREDICT, on).commit();
            String val = on ? "1" : "0";
            runSu("setprop " + prop + " " + val);
            String f = Predict.FLAG;
            boolean ok = on
                    ? runSu("echo 1 > " + f + " && chmod 644 " + f + " && chown system:system " + f
                            + " && (chcon u:object_r:system_data_file:s0 " + f + " || true)")
                    : runSu("rm -f " + f + " && setprop " + prop + " 0; true");
            // Usage history access, so suggestions follow what you actually use.
            if (on) runSu("appops set " + getPackageName() + " GET_USAGE_STATS allow");
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setMessage((on ? "App suggestions on." : "App suggestions off.")
                            + (ok ? " Reboot to apply." : "\n\nCouldn't save the setting with root. "
                            + "Allow root for this app in ReSukiSU and try again."))
                    .setPositiveButton("Reboot now", (d, w) -> new Thread(() -> runSu("svc power reboot || reboot")).start())
                    .setNegativeButton("Later", null)
                    .show());
        }).start());
        r.addView(sw);
        r.setOnClickListener(v -> sw.toggle());
        parent.addView(r);
    }

    private String predictStatus() {
        try {
            java.util.Map<String, ?> m = createDeviceProtectedStorageContext()
                    .getSharedPreferences(PredictionService.STATUS_PREFS, 0).getAll();
            if (m.isEmpty()) return "Status: the system hasn't started the suggestions service yet";
            StringBuilder b = new StringBuilder("Status (now "
                    + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + "):");
            for (String k : new String[]{"service", "destroyed", "alive", "tick_error", "request", "event",
                    "session_hotseat", "session_home", "usage",
                    "last_hotseat", "last_home", "rootlist", "debug", "error"}) {
                Object v = m.get(k);
                if (v != null) b.append("\n• ").append(k.replace('_', ' ')).append(": ").append(v);
            }
            return b.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String getProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class).invoke(null, key);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Wait 8 s (go to the app where the pill is too high), then save the window / inset state. */
    private void savePillDump() {
        Toast.makeText(this, "Open the app where the pill is too high - saving in 8 s", Toast.LENGTH_LONG).show();
        new Thread(() -> {
            try { Thread.sleep(8000); } catch (InterruptedException ignored) { }
            boolean ok = runSu("f=/sdcard/Download/netspeed_pill_dump_$(date +%H%M%S).txt; { date; "
                    + "echo '== focus'; dumpsys window | grep -E 'mCurrentFocus|mFocusedApp|imeControlTarget'; "
                    + "echo '== taskbar window'; dumpsys window windows | grep -i -A22 'Window{.* Taskbar}'; "
                    + "echo '== navigation insets'; dumpsys window displays | grep -i -E 'type=(navigationBars|mandatorySystemGestures|tappableElement|ime) '; "
                    + "echo '== launcher taskbar state'; dumpsys activity service com.android.launcher3/com.android.quickstep.TouchInteractionService 2>&1 "
                    + "| grep -i -E 'taskbar|stash|handle|margin|translation|inset|isInApp|transient|ime' | head -n 250; "
                    + "echo '== module log'; for x in $(ls -t /data/adb/lspd/log/modules_* /data/adb/lspd/log/verbose_* 2>/dev/null | head -n 2); do echo $x; grep -F '[nav]' $x | tail -n 60; done; "
                    + "logcat -d 2>/dev/null | grep -F '[nav]' | tail -n 40; "
                    + "} > $f 2>&1; chmod 666 $f");
            runOnUiThread(() -> Toast.makeText(this, ok ? "Saved: Download/netspeed_pill_dump_*.txt - send it to me"
                    : "Couldn't save (is root granted?)", Toast.LENGTH_LONG).show());
        }).start();
    }

    private static boolean runSu(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void restartSystemUi() {
        new Thread(() -> {
            String err = null;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "killall com.android.systemui"});
                if (p.waitFor() != 0) err = "Root command failed (exit " + p.exitValue() + ")";
            } catch (Exception e) {
                err = "Root not available: " + e.getMessage();
            }
            final String msg = err;
            runOnUiThread(() -> {
                if (msg != null) Toast.makeText(this, msg + ". Grant root to this app in ReSukiSU, or reboot.",
                        Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private boolean isDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
