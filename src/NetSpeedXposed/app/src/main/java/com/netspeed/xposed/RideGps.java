package com.netspeed.xposed;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;

/**
 * Learn while riding: when the phone is on the move (position changed 250 m+ within ~3 min, or speed
 * over 7 km/h), ask for one GPS fix a minute so tower learning gets accurate readings even when no
 * navigation app is running. Standing still = no GPS requests. Switch: Cfg.RIDE_GPS.
 */
final class RideGps {
    private RideGps() {}

    private static final double[][] TRAIL = new double[12][];   // {lat, lon, elapsed ms}, every 20 s
    private static int trailN;
    private static long trailAt, askedAt;
    static volatile boolean moving;
    static volatile int fixes;

    // distance travelled today: sum of the steps between fixes taken every 20 s (jitter, bad fixes and jumps ignored), reset at midnight
    static volatile float dayKm = -1f;
    private static double[] odoP;
    private static long odoT;
    private static String odoDay;

    private static void odo(Context ctx) {
        try {
            if (!Cfg.b(Cfg.SH_TRIP, Cfg.D_SH_TRIP)) return;
            long now = SystemClock.elapsedRealtime();
            if (now - odoT < 20_000 && odoT != 0) return;
            long prevT = odoT;
            odoT = now;
            android.content.SharedPreferences sp = ctx.getSharedPreferences("odo", 0);
            String key = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date());
            if (odoDay == null) { odoDay = sp.getString("day", ""); dayKm = odoDay.equals(key) ? sp.getFloat("km", 0f) : 0f; }
            if (!key.equals(odoDay)) { odoDay = key; dayKm = 0f; odoP = null; }
            Location l = TowerDb.myLocation(ctx);
            if (l == null || (l.hasAccuracy() && l.getAccuracy() > 60f)) return;
            if (odoP != null && now - prevT <= 120_000) {
                float[] d = new float[1];
                Location.distanceBetween(odoP[0], odoP[1], l.getLatitude(), l.getLongitude(), d);
                if (d[0] >= 40f && d[0] <= 900f) {   // under 40 m is jitter, over 900 m in 20 s is a bad fix
                    dayKm += d[0] / 1000f;
                    odoP = new double[]{l.getLatitude(), l.getLongitude()};
                    sp.edit().putString("day", odoDay).putFloat("km", dayKm).apply();
                }
            } else odoP = new double[]{l.getLatitude(), l.getLongitude()};
            if (dayKm < 0f) dayKm = 0f;
        } catch (Throwable ignored) { }
    }

    /** Worker thread, every few seconds. */
    static void tick(Context ctx) {
        odo(ctx);
        if (!Cfg.b(Cfg.RIDE_GPS, Cfg.D_RIDE_GPS) || !Cfg.b(Cfg.LEARN, Cfg.D_LEARN)) { moving = false; return; }
        long now = SystemClock.elapsedRealtime();
        if (now - trailAt < 20_000) return;
        trailAt = now;
        Location l = TowerDb.myLocation(ctx);
        if (l == null) return;
        System.arraycopy(TRAIL, 0, TRAIL, 1, TRAIL.length - 1);
        TRAIL[0] = new double[]{l.getLatitude(), l.getLongitude(), now};
        trailN = Math.min(TRAIL.length, trailN + 1);
        boolean mv = l.hasSpeed() && l.getSpeed() > 2f;
        for (int i = 1; i < trailN && !mv; i++) {
            if (now - TRAIL[i][2] > 200_000) break;
            float[] d = new float[1];
            Location.distanceBetween(TRAIL[i][0], TRAIL[i][1], l.getLatitude(), l.getLongitude(), d);
            if (d[0] > 250) mv = true;
        }
        moving = mv;
        if (!mv || now - askedAt < 60_000) return;
        askedAt = now;
        try {
            LocationManager lm = ctx.getSystemService(LocationManager.class);
            if (lm == null || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return;
            lm.getCurrentLocation(LocationManager.GPS_PROVIDER, null, ctx.getMainExecutor(), loc -> {
                if (loc != null) fixes++;
            });
        } catch (Throwable t) {
            de.robv.android.xposed.XposedBridge.log("NetSpeedXposed: [ridegps] GPS fix not allowed: " + t);
        }
    }
}
