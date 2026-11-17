package com.netspeed.xposed;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings tile: shows the screen timeout (15s ... 10m) and steps to the next one on each tap. Writes the setting directly when the app has
 * "Modify system settings", otherwise with root.
 */
public final class ScreenTimeoutTile extends TileService {
    private static final int LONG_MS = 10 * 60_000;
    private static final int DEFAULT_MS = 30_000;
    private static final String PREFS = "tile_timeout", KEY_NORMAL = "normal";

    @Override
    public void onStartListening() {
        refresh();
    }

    private static final int[] STEPS = {15_000, 30_000, 60_000, 120_000, 300_000, 600_000};

    @Override
    public void onClick() {
        Context c = this;
        int cur = current(c), target = STEPS[0];
        for (int s : STEPS) if (s > cur) { target = s; break; }
        if (!write(c, target)) {
            // neither "Modify system settings" nor root: ask for the permission
            try {
                startActivityAndCollapse(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + c.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) { }
        }
        refresh();
    }

    private static int current(Context c) {
        return Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, DEFAULT_MS);
    }

    private static boolean write(Context c, int ms) {
        try {
            if (Settings.System.canWrite(c)) {
                return Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, ms);
            }
        } catch (Throwable ignored) { }
        try {
            Process pr = new ProcessBuilder("su", "-c", "settings put system screen_off_timeout " + ms)
                    .redirectErrorStream(true).start();
            return pr.waitFor() == 0 && current(c) == ms;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String label(int ms) {
        if (ms < 60_000) return (ms / 1000) + " s";
        if (ms % 60_000 == 0) return (ms / 60_000) + " min";
        return String.format(java.util.Locale.US, "%.1f min", ms / 60_000f);
    }

    private static String shortLabel(int ms) {
        return ms < 60_000 ? (ms / 1000) + "s" : (ms / 60_000) + "m";
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        int cur = current(this);
        t.setLabel("Screen timeout");
        t.setSubtitle(label(cur));
        t.setIcon(ValueIcon.icon(shortLabel(cur)));
        t.setState(cur != DEFAULT_MS ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }
}
