package com.netspeed.xposed;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Checks GitHub every ~30 min in the background and posts a notification when a newer build is out. */
public class UpdateJob extends JobService {
    private static final int JOB_ID = 4711;
    private static final String CHANNEL = "app_update";

    static void schedule(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null) return;
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
            int r = js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, UpdateJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(30 * 60 * 1000L)
                    .setPersisted(true)
                    .build());
            android.util.Log.w("FieldAtlasUpdate", "schedule result " + r);
        } catch (Throwable t) { android.util.Log.w("FieldAtlasUpdate", "schedule failed: " + t); }
    }

    @Override
    public boolean onStartJob(JobParameters p) {
        final Context c = getApplicationContext();
        new Thread(() -> {
            try {
                notifyIfNew(c);
            } catch (Throwable ignored) {
            } finally {
                jobFinished(p, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) { return true; }

    /** Posts the notification once per new build. */
    static void notifyIfNew(Context c) throws Exception {
        AppUpdater.Info info = AppUpdater.check();
        long have = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        if (info.build <= have) return;
        SharedPreferences sp = c.getSharedPreferences("upd", Context.MODE_PRIVATE);
        if (sp.getLong("notified", 0) >= info.build) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(c, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 11, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String first = info.notes.isEmpty() ? "" : info.notes.split("\n")[0];
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Field Atlas build " + info.build + " is available")
                .setContentText(first.isEmpty() ? "Open the app, System page, Update" : first)
                .setStyle(new Notification.BigTextStyle().bigText(info.notes.isEmpty() ? "Open the app, System page, Update" : info.notes))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(4711, n);
        sp.edit().putLong("notified", info.build).apply();
    }

    /** Puts the job back after a reboot or an app update. */
    public static class Boot extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) { schedule(c); }
    }
}
