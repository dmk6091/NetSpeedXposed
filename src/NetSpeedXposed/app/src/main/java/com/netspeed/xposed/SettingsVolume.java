package com.netspeed.xposed;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.media.AudioManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;



/**
 * "Volume steps" in NetSpeed's own settings (opened from LSPosed). Saves the numbers with root
 * straight into the Volume Steps Magisk module's config (no background service watching for
 * changes), and in a global setting so this screen can show them; they apply after a reboot.
 */
final class SettingsVolume {
    private static final String TAG = "NetSpeedXposed: [volsteps] ";
    private static final String KEY = "netspeed_vol_steps";
    private static final String GLOBAL = "netspeed_volume_steps";
    private static final String SETTER = "/data/adb/modules/volumesteps/webui.sh";
    private static final String[] KEYS = {"MEDIA", "CALL", "RING", "ALARM", "NOTIF", "SYSTEM"};

    private static final String[] LABELS = {"Media", "Calls", "Ringtone", "Alarm", "Notification", "System sounds"};
    private static final String[] CODES = {"m", "c", "r", "a", "n", "s"};
    private static final int[] STREAMS = {AudioManager.STREAM_MUSIC, AudioManager.STREAM_VOICE_CALL,
            AudioManager.STREAM_RING, AudioManager.STREAM_ALARM, AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_SYSTEM};

    private SettingsVolume() {}

    private static int[] current(Context ctx) {
        int[] v = new int[6];
        AudioManager am = ctx.getSystemService(AudioManager.class);
        for (int i = 0; i < 6; i++) v[i] = am.getStreamMaxVolume(STREAMS[i]);
        String g = Settings.Global.getString(ctx.getContentResolver(), GLOBAL);
        if (g != null) {
            for (String kv : g.split(";")) {
                String[] p = kv.split("=");
                if (p.length != 2) continue;
                for (int i = 0; i < 6; i++) if (CODES[i].equals(p[0])) {
                    try { v[i] = Integer.parseInt(p[1]); } catch (NumberFormatException ignored) { }
                }
            }
        }
        return v;
    }

    static String summary(Context ctx) {
        try {
            int[] v = current(ctx);
            return "Media " + v[0] + " levels · calls " + v[1] + " · ring " + v[2];
        } catch (Throwable t) {
            return "Levels per volume slider";
        }
    }

    static void show(final Activity act) {
        if (act == null) return;
        final int[] cur = current(act);
        float d = act.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Math.round(24 * d), Math.round(8 * d), Math.round(24 * d), 0);
        TextView hint = new TextView(act);
        hint.setText("Levels per volume slider (3 to 150). More levels = finer control and a quieter lowest step. Applies after a reboot.");
        hint.setTextSize(14);
        hint.setPadding(0, 0, 0, Math.round(8 * d));
        box.addView(hint);
        final EditText[] edits = new EditText[6];
        for (int i = 0; i < 6; i++) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView l = new TextView(act);
            l.setText(LABELS[i]);
            l.setTextSize(16);
            row.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            EditText e = new EditText(act);
            e.setInputType(InputType.TYPE_CLASS_NUMBER);
            e.setText(String.valueOf(cur[i]));
            e.setGravity(Gravity.CENTER);
            e.setMinWidth(Math.round(80 * d));
            e.setSelectAllOnFocus(true);
            edits[i] = e;
            row.addView(e);
            box.addView(row);
        }
        final DialogInterface.OnClickListener save = null;
        AlertDialog.Builder b = new AlertDialog.Builder(act);
        b.setTitle("Volume steps").setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface di, int w) { save(act, edits, false); }
                })
                .setPositiveButton("Save and reboot", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface di, int w) { save(act, edits, true); }
                });
        b.show();
    }

    private static void save(Activity act, EditText[] edits, boolean reboot) {
        StringBuilder sb = new StringBuilder();
        final StringBuilder cmd = new StringBuilder("[ -f " + SETTER + " ] || exit 3");
        for (int i = 0; i < 6; i++) {
            int v;
            try { v = Integer.parseInt(edits[i].getText().toString().trim()); } catch (NumberFormatException e) { v = -1; }
            if (v < 3 || v > 150) {
                Toast.makeText(act, LABELS[i] + ": use 3 to 150", Toast.LENGTH_LONG).show();
                return;
            }
            if (sb.length() > 0) sb.append(';');
            sb.append(CODES[i]).append('=').append(v);
            cmd.append(" && sh ").append(SETTER).append(" set ").append(KEYS[i]).append(' ').append(v);
        }
        final String val = sb.toString();
        final Context app = act.getApplicationContext();
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            final int rc = suRc(cmd + " && settings put global " + GLOBAL + " '" + val + "'");
            ui.post(() -> {
                if (rc == 3) {
                    Toast.makeText(app, "Install the Volume Steps module first", Toast.LENGTH_LONG).show();
                    return;
                }
                if (rc != 0) {
                    Toast.makeText(app, "Couldn't save (is root granted to NetSpeed?)", Toast.LENGTH_LONG).show();
                    return;
                }
                android.util.Log.i("NetSpeedXposed", "[volsteps] saved " + val);
                if (!reboot) {
                    Toast.makeText(app, "Saved. Reboot to apply.", Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(app, "Rebooting in 4 s...", Toast.LENGTH_SHORT).show();
                ui.postDelayed(() -> new Thread(() -> {
                    if (!su("svc power reboot || reboot"))
                        ui.post(() -> Toast.makeText(app, "Reboot by hand to apply", Toast.LENGTH_LONG).show());
                }).start(), 4000);
            });
        }).start();
    }

    private static boolean su(String cmd) {
        return suRc(cmd) == 0;
    }

    private static int suRc(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            return p.waitFor();
        } catch (Exception e) {
            return -1;
        }
    }
}
