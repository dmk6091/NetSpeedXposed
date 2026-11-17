package com.dmk.ostweaks;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Settings + diagnostics for OxygenOS Tweaks. Settings live in Settings.System and are written with root. */
public class MainActivity extends Activity {
    static final int BG = 0xFF101114, CARD = 0xFF1B1D22, TXT = 0xFFE8E8EA, DIM = 0xFF8A8D94,
            ACC = 0xFF7FB2FF, OK = 0xFF5FD38D, WARN = 0xFFFFB454, BAD = 0xFFFF6B6B;

    final Map<String, Boolean> flags = new LinkedHashMap<>();
    float padL = 20, padR = 20, padT = 0;
    boolean romTop, carrierOn;
    int sugN = 4;
    LinearLayout diagBox;
    final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        load();
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        root.setPadding(p, dp(36), p, dp(24));
        sv.addView(root);
        root.addView(text("OxygenOS Tweaks", 26, TXT, true));
        root.addView(text("Changes apply after System UI restarts", 13, DIM, false));

        LinearLayout c = card(root, "STATUS BAR PADDING");
        seek(c, "Left", 0, 60, padL, v -> padL = v);
        seek(c, "Right", 0, 60, padR, v -> padR = v);
        seek(c, "Top", 0, 30, padT < 0 ? 0 : padT, v -> padT = v);
        toggle(c, "Use ROM default top", "Ignore the top value", romTop, v -> romTop = v);

        c = card(root, "STATUS BAR");
        toggle(c, "Carrier name", "System UI carrier label", carrierOn, v -> carrierOn = v);
        flag(c, "ostweaks_vonr", "VoNR / VoLTE icons", "Instead of HD");

        c = card(root, "IMMERSIVE NAVIGATION");
        flag(c, "ostweaks_inav_apps", "In apps", "Transparent nav bar and gesture area");
        flag(c, "ostweaks_inav_home", "Home screen", "OxygenOS launcher");
        flag(c, "ostweaks_inav_kb", "Keyboard", "Gboard");
        flag(c, "ostweaks_kb_gap", "Remove gap below keyboard", "Gboard, restarts keyboard");

        c = card(root, "HOME SCREEN");
        flag(c, "ostweaks_dock5", "5-icon dock (experimental)", "OxygenOS launcher, restarts it");
        flag(c, "ostweaks_dock_sug", "Dock suggestions", "Recent apps in a row above the dock");
        flag(c, "ostweaks_dock_anim", "Close animation into row (experimental)", "Leaving an app shrinks it into its icon");
        seek(c, "Suggestions shown", 1, 5, sugN, v -> sugN = (int) v);

        c = card(root, "SETTINGS & PHONE");
        flag(c, "ostweaks_settings", "IMS status, IMSI, DC dimming, HDR", "Extra options in Settings");
        flag(c, "ostweaks_phone", "Vo5G calls switch", "Phone / SIM settings");
        flag(c, "ostweaks_eyecare", "Eye Care visual comfort", null);

        c = card(root, "QUICK PANEL");
        flag(c, "ostweaks_slider", "Percentage on brightness & volume sliders", null);
        c.addView(text("Screen timeout and Network mode tiles: add them from the tile editor", 12, DIM, false));

        c = card(root, "DIAGNOSTICS");
        diagBox = new LinearLayout(this);
        diagBox.setOrientation(LinearLayout.VERTICAL);
        c.addView(diagBox);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.addView(button("View module log", v -> showLog()), lp(1));
        btns.addView(button("Copy report", v -> copyReport()), lp(1));
        c.addView(btns);

        Button apply = button("Apply & restart System UI", v -> apply());
        apply.setBackground(round(0xFF3A6DF0, 24));
        apply.setTextColor(Color.WHITE);
        root.addView(apply, new LinearLayout.LayoutParams(-1, dp(52)));
        setContentView(sv);
        refreshDiag();
    }

    // ---- state ----
    void load() {
        String[] keys = {"ostweaks_vonr", "ostweaks_inav_apps", "ostweaks_inav_home", "ostweaks_inav_kb", "ostweaks_kb_gap",
                "ostweaks_settings", "ostweaks_phone", "ostweaks_eyecare", "ostweaks_slider", "ostweaks_dock5", "ostweaks_dock_sug", "ostweaks_dock_anim"};
        for (String k : keys) flags.put(k, Settings.System.getInt(getContentResolver(), k, (k.equals("ostweaks_dock5") || k.equals("ostweaks_dock_sug") || k.equals("ostweaks_dock_anim")) ? 0 : 1) != 0);
        String v = Settings.System.getString(getContentResolver(), "ostweaks_sb_padding");
        if (v != null) {
            try {
                String[] a = v.split(",");
                padL = Float.parseFloat(a[0].trim());
                padR = Float.parseFloat(a[1].trim());
                if (a.length > 2) {
                    float t = Float.parseFloat(a[2].trim());
                    if (t < 0) romTop = true; else padT = t;
                } else romTop = true;
            } catch (Throwable ignored) {}
        } else { padL = 2; padR = 2; romTop = true; }
        sugN = Settings.System.getInt(getContentResolver(), "ostweaks_dock_sug_n", 4);
        carrierOn = !"-1".equals(Settings.Secure.getString(getContentResolver(), "carrier_info_settings_show"));
    }

    void apply() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Boolean> e : flags.entrySet())
            sb.append("settings put system ").append(e.getKey()).append(' ').append(e.getValue() ? 1 : 0).append("; ");
        sb.append("settings put system ostweaks_dock_sug_n ").append(sugN).append("; ");
        sb.append("settings put system ostweaks_sb_padding '").append((int) padL).append(',').append((int) padR)
                .append(',').append(romTop ? -1 : (int) padT).append("'; ");
        sb.append(carrierOn ? "settings delete secure carrier_info_settings_show; "
                : "settings put secure carrier_info_settings_show -1; ");
        sb.append(Boolean.TRUE.equals(flags.get("ostweaks_dock_sug")) ? watcherOn() : watcherOff());
        sb.append("am force-stop com.google.android.inputmethod.latin; am force-stop com.android.launcher; kill $(pidof com.android.systemui)");
        final String cmd = sb.toString();
        new Thread(() -> {
            String out = su(cmd);
            ui.post(() -> {
                Toast.makeText(this, out == null ? "Root not available" : "Applied", Toast.LENGTH_SHORT).show();
                ui.postDelayed(this::refreshDiag, 9000);
            });
        }).start();
    }

    // ---- system-wide recent apps watcher (root script reading the event log) ----
    static final String SCRIPT = "/data/adb/service.d/ostweaks-recent.sh";

    static String watcherOn() {
        String sh = "#!/system/bin/sh\\n"
                + "# OxygenOS Tweaks: records the foreground app for the launcher's dock suggestions\\n"
                + "while [ \"$(getprop sys.boot_completed)\" != 1 ]; do sleep 5; done\\n"
                + "last=\"$(settings get system ostweaks_recent </dev/null 2>/dev/null)\"; case \"$last\" in null|*' '*|*:*) last=\"\";; esac\\n"
                + "logcat -b events -T 1 -v brief wm_set_resumed_activity:I '*:S' | while read -r l; do\\n"
                + "  c=\"${l#*[0,}\"; p=\"${c%%/*}\"\\n"
                + "  case \"$p\" in ''|*[!A-Za-z0-9_.]*|com.android.launcher|com.android.systemui|android) continue;; esac\\n"
                + "  new=\"$p\"; for x in $(echo \"$last\" | tr ',' ' '); do [ \"$x\" != \"$p\" ] && new=\"$new,$x\"; done\\n"
                + "  last=\"$(echo \"$new\" | cut -d, -f1-12)\"\\n"
                + "  settings put system ostweaks_recent \"$last\" </dev/null >/dev/null 2>&1\\n"
                + "done\\n";
        return "mkdir -p /data/adb/service.d; printf '" + sh.replace("%", "%%").replace("'", "'\\''") + "' > " + SCRIPT
                + "; chmod 755 " + SCRIPT + "; pkill -f 'ostweaks-recen[t].sh'; (setsid sh " + SCRIPT + " </dev/null >/dev/null 2>&1 &); ";
    }

    static String watcherOff() {
        return "pkill -f 'ostweaks-recen[t].sh'; rm -f " + SCRIPT + "; ";
    }

    // ---- diagnostics ----
    static final String LOGS = "ls -t /data/adb/lspd/log/verbose_*.log 2>/dev/null | head -1";

    String lastLog() {
        return su("f=$(" + LOGS + "); [ -n \"$f\" ] && grep -aE 'OSTweaks:|ImmersiveNav:' \"$f\"");
    }

    void refreshDiag() {
        new Thread(() -> {
            String id = su("id");
            String log = lastLog();
            ui.post(() -> fillDiag(id, log));
        }).start();
    }

    List<String[]> report = new ArrayList<>();

    void fillDiag(String id, String log) {
        diagBox.removeAllViews();
        report.clear();
        boolean root = id != null && id.contains("uid=0");
        row("Root access", root ? "Granted" : "Not granted", root ? OK : BAD, null);
        boolean active = log != null && log.contains("OSTweaks:");
        row("Module active in Vector", active ? "Yes" : "No", active ? OK : BAD,
                active ? null : "Enable it in Vector and tick System UI, Launcher, Settings, Phone, Gboard");
        String[][] checks = {
                {"System UI: padding", "padding: status bar padding hooks \\((\\d+)\\)", null},
                {"System UI: carrier name", "carrier name in status bar allowed \\((\\d+)\\)", null},
                {"System UI: VoNR / VoLTE icons", "VoNR/VoLTE icons on \\((\\d+)", "ostweaks_vonr"},
                {"System UI: immersive nav", "ImmersiveNav: active in com\\.android\\.systemui", "ostweaks_inav_apps"},
                {"Launcher: immersive nav", "ImmersiveNav: active in com\\.android\\.launcher", "ostweaks_inav_home"},
                {"Launcher: 5-icon dock", "dock: 5 icons \\((\\d+) hooks\\)", "ostweaks_dock5"},
                {"Launcher: dock suggestions", "dock suggestions on \\((\\d+) hooks\\)", "ostweaks_dock_sug"},
                {"Settings app", "Settings: IMS status[^(]*\\((\\d+)\\)", "ostweaks_settings"},
                {"Phone: Vo5G switch", "Phone: Vo5G calls switch shown \\((\\d+)\\)", "ostweaks_phone"},
                {"Eye Care", "Eye Care: features on", "ostweaks_eyecare"},
        };
        for (String[] ck : checks) {
            boolean enabled = ck[2] == null || Boolean.TRUE.equals(flags.get(ck[2]));
            String hit = log == null ? null : lastMatch(log, ck[1]);
            if (!enabled) row(ck[0], "Off", DIM, null);
            else if (hit == null) row(ck[0], "Not seen", WARN, "Not loaded since boot: restart the app's process");
            else row(ck[0], "OK", OK, hit);
        }
        String rec = su("settings get system ostweaks_recent");
        boolean watching = rec != null && !rec.trim().isEmpty() && !rec.trim().equals("null");
        if (Boolean.TRUE.equals(flags.get("ostweaks_dock_sug")))
            row("Recent-apps watcher", watching ? "Running" : "No data yet", watching ? OK : WARN,
                    watching ? rec.trim() : "Open an app, then reopen this screen");
        String pad = log == null ? null : lastMatch(log, "padding dp left/right/top/bottom = ([^\\n]*)");
        if (pad != null) row("Applied padding", pad.replace(".0", ""), OK, "left / right / top / bottom (dp)");
    }

    String lastMatch(String log, String re) {
        Matcher m = Pattern.compile(re).matcher(log);
        String last = null;
        while (m.find()) last = m.groupCount() > 0 && m.group(1) != null ? m.group(1) : "loaded";
        return last;
    }

    void row(String name, String status, int color, String sub) {
        report.add(new String[]{name, status, sub == null ? "" : sub});
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(7), 0, dp(7));
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text(name, 15, TXT, false));
        if (sub != null) left.addView(text(sub, 12, DIM, false));
        r.addView(left, lp(1));
        TextView s = text(status, 14, color, false);
        r.addView(s);
        diagBox.addView(r);
    }

    void showLog() {
        new Thread(() -> {
            String log = lastLog();
            if (log == null) log = "No Vector log readable (root needed)";
            String[] lines = log.split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, lines.length - 60); i < lines.length; i++)
                sb.append(lines[i].replaceFirst("^\\[ (\\S+) +\\d+: *\\d+: *\\d+ \\S+/\\S+ \\] ", "$1 ")).append('\n');
            final String t = sb.toString();
            ui.post(() -> {
                TextView tv = text(t, 11, TXT, false);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextIsSelectable(true);
                ScrollView s = new ScrollView(this);
                s.addView(tv);
                new AlertDialog.Builder(this).setTitle("Module log").setView(s).setPositiveButton("Close", null).show();
            });
        }).start();
    }

    void copyReport() {
        StringBuilder sb = new StringBuilder("OxygenOS Tweaks diagnostics\n");
        for (String[] r : report) {
            sb.append(r[0]).append(": ").append(r[1]);
            if (!r[2].isEmpty()) sb.append(" (").append(r[2]).append(')');
            sb.append('\n');
        }
        ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("report", sb.toString()));
        Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show();
    }

    static String su(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- ui helpers ----
    interface F { void set(float v); }
    interface B { void set(boolean v); }

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    LinearLayout.LayoutParams lp(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    LinearLayout card(LinearLayout root, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 18));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.addView(text(title, 13, ACC, true));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(12);
        root.addView(c, p);
        return c;
    }

    Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(TXT);
        b.setBackground(round(0xFF2A2D34, 20));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(44));
        p.setMargins(dp(4), dp(10), dp(4), dp(4));
        b.setLayoutParams(p);
        return b;
    }

    void toggle(LinearLayout c, String name, String sub, boolean on, B cb) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(8), 0, dp(8));
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text(name, 15, TXT, false));
        if (sub != null) left.addView(text(sub, 12, DIM, false));
        r.addView(left, lp(1));
        Switch sw = new Switch(this);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((CompoundButton b, boolean v) -> cb.set(v));
        r.addView(sw);
        c.addView(r);
    }

    void flag(LinearLayout c, String key, String name, String sub) {
        toggle(c, name, sub, Boolean.TRUE.equals(flags.get(key)), v -> flags.put(key, v));
    }

    void seek(LinearLayout c, String name, int min, int max, float val, F cb) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setPadding(0, dp(8), 0, 0);
        h.addView(text(name, 14, TXT, false), lp(1));
        final TextView v = text((int) val + " dp", 14, ACC, true);
        h.addView(v);
        c.addView(h);
        SeekBar s = new SeekBar(this);
        s.setMax(max - min);
        s.setProgress((int) val - min);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean u) {
                v.setText((p + min) + " dp");
                cb.set(p + min);
            }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) {}
        });
        c.addView(s);
    }
}
