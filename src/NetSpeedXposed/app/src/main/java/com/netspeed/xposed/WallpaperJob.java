package com.netspeed.xposed;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Every 30 min (when the system allows): redraws the map wallpaper only if you have moved far enough. No GPS held on. */
public class WallpaperJob extends JobService {
    private static final int JOB_ID = 4713, SUN_ID = 4715;   // SUN_ID: a one-shot job timed for the next sunrise or sunset

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
            scheduleSun(c);
        } catch (Throwable t) { android.util.Log.w("FieldAtlasWp", "schedule failed: " + t); }
    }

    /** Wakes the wallpaper at the next sunrise or sunset (found by stepping forward 5 minutes at a time), so day and night do not wait for the 30-minute job. */
    static void scheduleSun(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null) return;
            js.cancel(SUN_ID);
            double[] p = DayNight.loc(c);
            long now = System.currentTimeMillis();
            boolean day = DayNight.isDay(p[0], p[1]);
            long t = now;
            for (int i = 0; i < 24 * 12 + 12; i++) {
                t += 5 * 60_000L;
                boolean d2 = Astro.sun(t, p[0], p[1]).alt > -0.83;
                if (d2 != day) break;
            }
            long wait = Math.max(60_000L, t - now + 60_000L);
            js.schedule(new JobInfo.Builder(SUN_ID, new ComponentName(c, WallpaperJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(wait).setOverrideDeadline(wait + 20 * 60_000L)
                    .setPersisted(true).build());
        } catch (Throwable ignored) { }
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
            finally {
                try { if (p.getJobId() == SUN_ID && WallpaperMaker.sp(c).getBoolean("auto", false)) scheduleSun(c); } catch (Throwable ignored) { }
                jobFinished(p, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) { return true; }
}
