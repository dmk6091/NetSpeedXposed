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
 * Quick Settings tile: one tap switches the screen timeout between your normal value and a long one
 * (10 minutes). Lit while the long timeout is on. Your normal value is remembered when you switch to
 * the long one and put back when you switch off. Writes the setting directly when the app has
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

    @Override
    public void onClick() {
        Context c = this;
        int cur = current(c);
        SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        int target;
        if (cur >= LONG_MS) {
            target = p.getInt(KEY_NORMAL, DEFAULT_MS);
            if (target >= LONG_MS) target = DEFAULT_MS;
        } else {
            p.edit().putInt(KEY_NORMAL, cur).apply();
            target = LONG_MS;
        }
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

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        int cur = current(this);
        t.setLabel("Screen timeout");
        t.setSubtitle(label(cur));
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_tile_timeout));
        t.setState(cur >= LONG_MS ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }
}
