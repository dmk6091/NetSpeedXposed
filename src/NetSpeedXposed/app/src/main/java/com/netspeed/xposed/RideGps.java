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

    /** Worker thread, every few seconds. */
    static void tick(Context ctx) {
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
