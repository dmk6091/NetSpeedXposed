package com.netspeed.xposed;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Every 30 min (when the system allows): redraws the map wallpaper only if you have moved far enough. No GPS held on. */
public class WallpaperJob extends JobService {
    private static final int JOB_ID = 4713;

    static void schedule(Context c, boolean on) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null) return;
            js.cancel(JOB_ID);
            if (!on) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, WallpaperJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setRequiresBatteryNotLow(true)
                    .setPeriodic(30 * 60 * 1000L)
                    .setPersisted(true)
                    .build());
        } catch (Throwable t) { android.util.Log.w("FieldAtlasWp", "schedule failed: " + t); }
    }

    static boolean scheduled(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js != null) for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    @Override
    public boolean onStartJob(JobParameters p) {
        final Context c = getApplicationContext();
        new Thread(() -> {
            try { if (WallpaperMaker.sp(c).getBoolean("auto", false)) WallpaperMaker.run(c, false); }
            catch (Throwable ignored) { }
            finally { jobFinished(p, false); }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) { return true; }
}
