package com.netspeed.xposed;

import android.app.WallpaperManager;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;

/** Day pictures from sunrise to sunset, night pictures the rest of the time. Pictures live in files/wp/{home,lock}_{day,night}.png. */
public class DayNight extends JobService {
    private static final int JOB_ID = 4714;

    static boolean isDay(Context c) {
        SharedPreferences s = c.getSharedPreferences(DamJob.PREFS, Context.MODE_PRIVATE);
        double lat = s.getFloat("lat", 18.648f), lon = s.getFloat("lon", 73.742f);
        return Astro.sun(System.currentTimeMillis(), lat, lon).alt > -0.83;
    }

    static void schedule(Context c, boolean on) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null) return;
            js.cancel(JOB_ID);
            if (!on) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, DayNight.class))
                    .setPeriodic(15 * 60 * 1000L).setPersisted(true).build());
        } catch (Throwable t) { android.util.Log.w("FieldAtlasWp", "daynight schedule failed: " + t); }
    }

    private static boolean set(Context c, WallpaperManager wm, String name, int flag) throws Exception {
        File f = new File(c.getFilesDir(), "wp/" + name + ".png");
        if (!f.exists()) return false;
        Bitmap b = BitmapFactory.decodeFile(f.getPath());
        if (b == null) return false;
        wm.setBitmap(b, null, true, flag);
        b.recycle();
        return true;
    }

    /** Sets the pictures for the current part of the day. Only when it changed, unless force. */
    static String apply(Context c, boolean force) {
        SharedPreferences sp = WallpaperMaker.sp(c);
        try {
            String mode = isDay(c) ? "day" : "night";
            if (!force && mode.equals(sp.getString("dnMode", ""))) return "already " + mode;
            WallpaperManager wm = WallpaperManager.getInstance(c);
            boolean h = set(c, wm, "home_" + mode, WallpaperManager.FLAG_SYSTEM);
            boolean l = set(c, wm, "lock_" + mode, WallpaperManager.FLAG_LOCK);
            if (!h && !l) return "no " + mode + " pictures found";
            sp.edit().putString("dnMode", mode).putLong("dnAt", System.currentTimeMillis()).apply();
            return mode + " wallpapers set";
        } catch (Throwable t) { return "Failed: " + t; }
    }

    @Override
    public boolean onStartJob(JobParameters p) {
        final Context c = getApplicationContext();
        new Thread(() -> {
            try { if (WallpaperMaker.sp(c).getBoolean("daynight", false)) apply(c, false); }
            catch (Throwable ignored) { }
            finally { jobFinished(p, false); }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) { return true; }
}
