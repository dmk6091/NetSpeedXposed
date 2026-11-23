package com.netspeed.xposed;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Every 15 minutes: keeps a reading of the battery statistics (per part and per app) for the heat history graph. */
public class HeatSnapJob extends JobService {
    private static final int JOB_ID = 4716;

    static void schedule(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null) return;
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, HeatSnapJob.class)).setPeriodic(15 * 60 * 1000L).setPersisted(true).build());
        } catch (Throwable t) { android.util.Log.w("FieldAtlasHeat", "schedule failed: " + t); }
    }

    @Override
    public boolean onStartJob(JobParameters p) {
        final Context c = getApplicationContext();
        new Thread(() -> {
            try { HeatLog.snapshot(c); } catch (Throwable ignored) { } finally { jobFinished(p, false); }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) { return false; }
}
