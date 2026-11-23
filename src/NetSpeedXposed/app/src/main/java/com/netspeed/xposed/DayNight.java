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

    static final double HOME_LAT = 18.648, HOME_LON = 73.742;
    static final float HOME_KM = 60f;      // inside this: the hand-made Sahyadri pictures; outside: the live terrain map
    static final float REDRAW_KM = 15f;

    /** Where the phone is: a fresh fix if there is one, else the last one, else Ravet. {lat, lon}. */
    static double[] loc(Context c) {
        try {
            Object[] w = DamJob.where(c);
            if ((Long) w[2] >= 0) return new double[]{(Double) w[0], (Double) w[1]};
        } catch (Throwable ignored) { }
        SharedPreferences s = c.getSharedPreferences(DamJob.PREFS, Context.MODE_PRIVATE);
        return new double[]{s.getFloat("lat", (float) HOME_LAT), s.getFloat("lon", (float) HOME_LON)};
    }

    static boolean isDay(double lat, double lon) {
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

    /** Sets the pictures for the current part of the day and place. Only when something changed, unless force. */
    static String apply(Context c, boolean force) {
        SharedPreferences sp = WallpaperMaker.sp(c);
        try {
            double[] p = loc(c);
            String mode = isDay(p[0], p[1]) ? "day" : "night";
            float[] d = new float[1];
            android.location.Location.distanceBetween(p[0], p[1], HOME_LAT, HOME_LON, d);
            boolean pic = d[0] < HOME_KM * 1000f;
            String kind = pic ? "pic" : "live";
            boolean sky = mode.equals("night") && sp.getInt("lockSky", 0) == 0;
            boolean moved = false;
            if (!pic && sp.contains("dnLat")) {
                float[] m = new float[1];
                android.location.Location.distanceBetween(sp.getFloat("dnLat", 0), sp.getFloat("dnLon", 0), p[0], p[1], m);
                moved = m[0] > REDRAW_KM * 1000f;
            }
            if (!force && mode.equals(sp.getString("dnMode", "")) && kind.equals(sp.getString("dnKind", "")) && !moved
                    && (pic || sp.contains("dnLat"))) {
                if (sky && System.currentTimeMillis() - sp.getLong("skyAt", 0) > 25 * 60_000L) return SkyScene.applyLock(c, p[0], p[1]);
                return "already " + mode + " " + kind;
            }
            WallpaperManager wm = WallpaperManager.getInstance(c);
            String msg;
            if (pic) {
                boolean h = set(c, wm, "home_" + mode, WallpaperManager.FLAG_SYSTEM);
                boolean l = sky || set(c, wm, "lock_" + mode, WallpaperManager.FLAG_LOCK);
                if (!h && !l) return "no " + mode + " pictures found";
                msg = mode + " wallpapers set (Sahyadri pictures)";
            } else {
                boolean day = mode.equals("day");
                Bitmap home = TerrainMaker.draw(c, p[0], p[1], day, 10, WallpaperMaker.sp(c).getBoolean("card", false), null);
                wm.setBitmap(home, null, true, WallpaperManager.FLAG_SYSTEM);
                home.recycle();
                if (!sky) {
                    Bitmap lock = TerrainMaker.draw(c, p[0], p[1], day, 9, false, null);
                    wm.setBitmap(lock, null, true, WallpaperManager.FLAG_LOCK);
                    lock.recycle();
                }
                msg = mode + " live terrain wallpapers set";
            }
            if (sky) msg += "; " + SkyScene.applyLock(c, p[0], p[1]);
            sp.edit().putString("dnMode", mode).putString("dnKind", kind).putFloat("dnLat", (float) p[0]).putFloat("dnLon", (float) p[1])
                    .putLong("dnAt", System.currentTimeMillis()).apply();
            return msg;
        } catch (Throwable t) {
            String m = "Failed: " + t;
            sp.edit().putString("dnErr", m).apply();
            return m;
        }
    }

    /** Test hook: adb shell su -c "am broadcast -n com.netspeed.xposed/.DayNight\$Cmd --ef lat 12.97 --ef lon 77.59 --ez day true --ei z 10 --ez card true". Saves files/live_prev.png. */
    public static class Cmd extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context c, android.content.Intent i) {
            final android.content.BroadcastReceiver.PendingResult pr = goAsync();
            final Context ctx = c.getApplicationContext();
            final double lat = i.getFloatExtra("lat", 18.648f), lon = i.getFloatExtra("lon", 73.742f);
            final boolean day = i.getBooleanExtra("day", true), card = i.getBooleanExtra("card", true), apply = i.getBooleanExtra("apply", false);
            final int z = i.getIntExtra("z", 10);
            new Thread(() -> {
                try {
                    long t0 = System.currentTimeMillis();
                    TerrainMaker.Info info = new TerrainMaker.Info();
                    Bitmap b = TerrainMaker.draw(ctx, lat, lon, day, z, card, info);
                    try (java.io.FileOutputStream fo = new java.io.FileOutputStream(new File(ctx.getFilesDir(), "live_prev.jpg"))) {
                        b.compress(Bitmap.CompressFormat.JPEG, 88, fo);
                    }
                    if (apply) {
                        WallpaperManager wm = WallpaperManager.getInstance(ctx);
                        wm.setBitmap(b, null, true, WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
                    }
                    android.util.Log.e("FieldAtlasWp", "live preview ok " + (System.currentTimeMillis() - t0) + " ms forest=" + info.forestKm + " wet=" + info.wetKm + " (" + info.wetCount + ") relief=" + info.reliefMin + ".." + info.reliefMax);
                    b.recycle();
                } catch (Throwable t) { android.util.Log.e("FieldAtlasWp", "live preview failed: " + t, t); }
                finally { pr.finish(); }
            }).start();
        }
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
