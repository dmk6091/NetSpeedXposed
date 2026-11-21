package com.netspeed.xposed;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;

/**
 * "Set wallpaper now" and "Retry the missing names": runs as a system job, so it carries on and applies the wallpaper
 * even if you leave or close the app. A short notification says when it is ready or why it was not made.
 */
public class WallpaperNowJob extends JobService {
    private static final int JOB_ID = 4717;
    private static final String CH = "wallpaper_now";

    /** Starts the build right away; returns false if the system refused the job. */
    static boolean start(Context c, boolean patient) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null) return false;
            PersistableBundle b = new PersistableBundle();
            b.putInt("patient", patient ? 1 : 0);
            ComponentName cn = new ComponentName(c, WallpaperNowJob.class);
            try {   // expedited: starts at once, even in the background
                js.schedule(new JobInfo.Builder(JOB_ID, cn).setExpedited(true).setExtras(b)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
                return true;
            } catch (Throwable expeditedRefused) {
                return js.schedule(new JobInfo.Builder(JOB_ID, cn).setOverrideDeadline(0).setExtras(b)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build()) == JobScheduler.RESULT_SUCCESS;
            }
        } catch (Throwable t) { android.util.Log.w("FieldAtlasWp", "now-job failed: " + t); return false; }
    }

    static boolean running() { return WpStatus.active; }

    @Override
    public boolean onStartJob(JobParameters p) {
        final Context c = getApplicationContext();
        final boolean patient = p.getExtras().getInt("patient", 0) == 1;
        new Thread(() -> {
            String r = "";
            try {
                MapNames.patient = patient;
                r = WallpaperMaker.run(c, true);
            } catch (Throwable t) { r = String.valueOf(t.getMessage()); }
            finally { MapNames.patient = false; }
            notifyDone(c, r);
            jobFinished(p, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) { return false; }

    private static void notifyDone(Context c, String r) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(new NotificationChannel(CH, "Wallpaper ready", NotificationManager.IMPORTANCE_LOW));
            android.content.SharedPreferences sp = WallpaperMaker.sp(c);
            boolean ok = sp.getString("status", "ok").equals("ok");
            String notes = sp.getString("notes", "");
            String title = ok ? "Map wallpaper is ready" : "Map wallpaper was not made";
            String body = ok ? "Took " + sp.getLong("lastMs", 0) / 1000 + " s" + (notes.isEmpty() ? "." : ". Left out: " + notes) : String.valueOf(r);
            PendingIntent pi = PendingIntent.getActivity(c, 17, new Intent(c, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(4717, new Notification.Builder(c, CH).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body)).setContentIntent(pi).setAutoCancel(true).build());
        } catch (Throwable ignored) { }
    }
}
