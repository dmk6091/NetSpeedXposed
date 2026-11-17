package com.dmk.ostweaks;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick settings tile: tap to step the screen timeout through a fixed list.
 * Needs "Modify system settings" (WRITE_SETTINGS); the first tap opens that permission page if missing.
 */
public class TimeoutTile extends TileService {
    private static final int[] STEPS_MS = {15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000};

    @Override
    public void onStartListening() {
        refresh();
    }

    private static final String TAG = "OSTweaksTile";

    @Override
    public void onClick() {
        android.util.Log.i(TAG, "onClick");
        if (!Settings.System.canWrite(this)) {
            Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            open(i);
            return;
        }
        int cur = current();
        int next = STEPS_MS[0];
        for (int s : STEPS_MS) {
            if (s > cur) { next = s; break; }
        }
        boolean ok = false;
        try {
            ok = Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, next);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "putInt failed", t);
        }
        int after = current();
        android.util.Log.i(TAG, "click: " + cur + " -> " + next + ", putInt=" + ok + ", now=" + after);
        if (after != next) {
            // Some ROM builds silently ignore third-party writes to this key: fall back to root.
            final int target = next;
            new Thread(() -> {
                try {
                    Process pr = Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "settings put system screen_off_timeout " + target});
                    int rc = pr.waitFor();
                    android.util.Log.i(TAG, "su fallback rc=" + rc + ", now=" + current());
                } catch (Throwable t) {
                    android.util.Log.w(TAG, "su fallback failed", t);
                }
                refresh();
            }).start();
        }
        refresh();
    }

    private void open(Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pi);
            } else {
                startActivityAndCollapse(i);
            }
        } catch (Throwable ignored) {
        }
    }

    private int current() {
        return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 30_000);
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setLabel("Screen timeout");
        boolean ok = Settings.System.canWrite(this);
        t.setSubtitle(ok ? label(current()) : "Tap to allow");
        if (ok) t.setIcon(textIcon(shortLabel(current())));
        t.setState(Tile.STATE_ACTIVE);
        t.updateTile();
    }

    /** "30s", "1m", "10m", "1h": drawn as the tile icon (OxygenOS tiles don't show subtitles). */
    private static String shortLabel(int ms) {
        int s = ms / 1000;
        if (s < 60) return s + "s";
        int m = s / 60;
        if (m < 60) return m + "m";
        return (m / 60) + "h";
    }

    private Icon textIcon(String text) {
        int size = Math.round(48 * getResources().getDisplayMetrics().density);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        float ts = size * 0.62f;
        p.setTextSize(ts);
        float w = p.measureText(text);
        if (w > size * 0.96f) { ts *= size * 0.96f / w; p.setTextSize(ts); }
        Rect r = new Rect();
        p.getTextBounds(text, 0, text.length(), r);
        c.drawText(text, size / 2f, size / 2f - r.exactCenterY(), p);
        return Icon.createWithBitmap(b);
    }

    private static String label(int ms) {
        int s = ms / 1000;
        if (s < 60) return s + " s";
        int m = s / 60;
        if (m < 60) return m + " min";
        return (m / 60) + " h";
    }
}
