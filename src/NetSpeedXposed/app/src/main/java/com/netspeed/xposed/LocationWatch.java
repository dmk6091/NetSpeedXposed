package com.netspeed.xposed;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Every 15 min (when the system allows): if you have moved more than 15 km since the last refresh, redo the dam / river / place lookups for the new spot. */
public class LocationWatch extends JobService {
    private static final int JOB_ID = 4715;
    static final float MOVE_KM = 15f;

    static void schedule(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null) return;
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, LocationWatch.class))
                    .setPeriodic(15 * 60 * 1000L).setPersisted(true).build());
        } catch (Throwable t) { android.util.Log.w("FieldAtlasLoc", "schedule failed: " + t); }
    }

    static String place(double lat, double lon) {
        try {
            org.json.JSONObject a = new org.json.JSONObject(Geo.get(String.format(Locale.US,
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=12&lat=%.5f&lon=%.5f", lat, lon), 12_000)).optJSONObject("address");
            if (a == null) return "";
            String[] ks = {"suburb", "neighbourhood", "village", "town", "city_district", "city", "county"};
            String p1 = "", p2 = "";
            for (String k : ks) { String v = a.optString(k, ""); if (!v.isEmpty()) { if (p1.isEmpty()) p1 = v; else if (p2.isEmpty() && !v.equals(p1)) p2 = v; } }
            return p2.isEmpty() ? p1 : p1 + ", " + p2;
        } catch (Throwable t) { return ""; }
    }

    /** Refreshes when you have moved (or force). Returns a one-line result. */
    static String run(Context c, boolean force) {
        SharedPreferences sp = c.getSharedPreferences(DamJob.PREFS, Context.MODE_PRIVATE);
        try {
            Object[] w = DamJob.where(c);
            if ((Long) w[2] < 0) return "no location fix yet";
            double lat = (Double) w[0], lon = (Double) w[1];
            boolean moved = !sp.contains("lwLat");
            if (!moved) {
                float[] d = new float[1];
                android.location.Location.distanceBetween(sp.getFloat("lwLat", 0), sp.getFloat("lwLon", 0), lat, lon, d);
                moved = d[0] > MOVE_KM * 1000f;
            }
            if (!moved && !force) return "same place";
            String r = DamJob.check(c, false);          // nearest dam and its level for here
            if (r.startsWith("Failed")) return r;       // try again next time; keep the old spot
            sp.edit().putFloat("lwLat", (float) lat).putFloat("lwLon", (float) lon).putString("lwPlace", place(lat, lon))
                    .putLong("lwAt", System.currentTimeMillis()).apply();
            try { if (WallpaperMaker.sp(c).getBoolean("daynight", false)) DayNight.apply(c, false); } catch (Throwable ignored) { }
            return "Refreshed for " + sp.getString("lwPlace", "here") + ": " + r;
        } catch (Throwable t) {
            return "Failed: " + t;
        }
    }

    @Override
    public boolean onStartJob(JobParameters p) {
        final Context c = getApplicationContext();
        new Thread(() -> {
            try { run(c, false); } catch (Throwable ignored) { } finally { jobFinished(p, false); }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) { return true; }
}
