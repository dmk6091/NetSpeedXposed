package com.netspeed.xposed;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** NetSpeed app side of the integrity watcher: notification per check plus a short history. */
public class IntegrityReceiver extends BroadcastReceiver {
    static final String CHANNEL = "integrity_checks";
    private static final String HIST = "integrity_history";
    private static final int MAX = 200;

    @Override
    public void onReceive(Context c, Intent in) {
        if (!IntegrityWatch.ACTION.equals(in.getAction())) return;
        String pkg = in.getStringExtra("pkg");
        if (pkg == null) return;
        String api = in.getStringExtra("api");
        int code = in.getIntExtra("code", -1);
        long at = in.getLongExtra("at", System.currentTimeMillis());
        String name = label(c, pkg);
        String time = new SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).format(new Date(at));
        // history (newest first), kept even when notifications are off
        SharedPreferences sp = c.createDeviceProtectedStorageContext().getSharedPreferences(HIST, 0);
        String line = time + "  " + name + " (" + pkg + ")  " + api + " #" + code;
        String old = sp.getString("log", "");
        String[] rows = old.isEmpty() ? new String[0] : old.split("\n");
        StringBuilder b = new StringBuilder(line);
        for (int i = 0; i < rows.length && i < MAX - 1; i++) b.append('\n').append(rows[i]);
        sp.edit().putString("log", b.toString()).apply();

        if (SettingsProvider.prefs(c).getBoolean(Cfg.INTEGRITY_TOAST, Cfg.D_INTEGRITY_TOAST)) {
            try {
                android.widget.Toast.makeText(c.getApplicationContext(), name + " checked Play Integrity ("
                        + ("standard".equals(api) ? "standard" : "classic") + ")", android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) { }
        }
        if (!SettingsProvider.prefs(c).getBoolean(Cfg.INTEGRITY_NOTIFY, Cfg.D_INTEGRITY_NOTIFY)) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Play Integrity checks", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("An app asked Google Play whether this phone passes Play Integrity");
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(c, SettingsActivity.class).putExtra("show_integrity", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 7, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle(name + " checked Play Integrity")
                .setContentText(("standard".equals(api) ? "Standard" : "Classic") + " API · " + time)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setGroup("integrity")
                .build();
        nm.notify(pkg.hashCode(), n);                              // one notification per app, updated
    }

    static String history(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences(HIST, 0).getString("log", "");
    }

    static void clear(Context c) {
        c.createDeviceProtectedStorageContext().getSharedPreferences(HIST, 0).edit().clear().apply();
    }

    private static String label(Context c, String pkg) {
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            return String.valueOf(c.getPackageManager().getApplicationLabel(ai));
        } catch (Throwable t) {
            return pkg;
        }
    }
}
