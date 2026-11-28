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

    // Distance travelled today. Two sources: (1) while riding, our own GPS updates every ~3 s (best accuracy, switched off when
    // standing still); (2) otherwise the phone's latest known location. Bad fixes, jitter and standing-still drift are ignored.
    // Every day's total is kept in Settings.Global "netspeed_odo_hist" ("yyyyMMdd=km;...", newest last, 400 days) so the app can show it.
    static volatile float dayKm = -1f;
    static final String HIST_KEY = "netspeed_odo_hist";
    private static double[] odoP;
    private static long odoT, odoFixNs, histAt;
    private static String odoDay;
    private static boolean histDirty;
    private static final java.util.TreeMap<String, Float> HIST = new java.util.TreeMap<>();
    private static boolean histLoaded;
    private static android.content.SharedPreferences odoSp;
    private static Context odoCtx;
    private static LocationManager gpsLm;
    private static android.location.LocationListener gpsL;
    private static long gpsStillSince;
    static volatile boolean gpsOn;

    private static void loadHist(Context ctx) {
        if (histLoaded) return;
        histLoaded = true;
        try {
            String g = android.provider.Settings.Global.getString(ctx.getContentResolver(), HIST_KEY);
            if (g != null) for (String e : g.split(";")) {
                int q = e.indexOf('=');
                if (q > 0) try { HIST.put(e.substring(0, q), Float.parseFloat(e.substring(q + 1))); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    private static void flushHist(boolean force) {
        try {
            if (odoCtx == null || (!histDirty && !force)) return;
            long now = SystemClock.elapsedRealtime();
            if (!force && now - histAt < 60_000) return;
            histAt = now;
            while (HIST.size() > 400) HIST.pollFirstEntry();
            StringBuilder b = new StringBuilder();
            for (java.util.Map.Entry<String, Float> e : HIST.entrySet()) {
                if (b.length() > 0) b.append(';');
                b.append(e.getKey()).append('=').append(String.format(java.util.Locale.US, "%.2f", e.getValue()));
            }
            android.provider.Settings.Global.putString(odoCtx.getContentResolver(), HIST_KEY, b.toString());
            histDirty = false;
        } catch (Throwable ignored) { }
    }

    /** Sets up the day (midnight rollover keeps yesterday in the history). */
    private static boolean odoDay(Context ctx) {
        odoCtx = ctx;
        loadHist(ctx);
        if (odoSp == null) odoSp = ctx.getSharedPreferences("odo", 0);
        String key = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date());
        if (odoDay == null) {
            odoDay = odoSp.getString("day", "");
            dayKm = odoDay.equals(key) ? odoSp.getFloat("km", 0f) : 0f;
            if (!odoDay.isEmpty() && !odoDay.equals(key)) { HIST.put(odoDay, odoSp.getFloat("km", 0f)); histDirty = true; }
        }
        if (!key.equals(odoDay)) {
            if (!odoDay.isEmpty()) HIST.put(odoDay, Math.max(0f, dayKm));
            odoDay = key; dayKm = 0f; odoP = null; histDirty = true;
            flushHist(true);
        }
        return true;
    }

    /** One position fix into the day's total. Returns nothing; safe to call from any thread. */
    private static synchronized void odoFix(Location l, boolean gps) {
        try {
            if (l == null || odoSp == null) return;
            if (l.hasAccuracy() && l.getAccuracy() > (gps ? 30f : 100f)) return;
            long fixNs = l.getElapsedRealtimeNanos();
            if (odoP != null && fixNs <= odoFixNs) return;   // the same fix again, or an older one
            if (odoP == null) { odoP = new double[]{l.getLatitude(), l.getLongitude()}; odoFixNs = fixNs; return; }
            float[] d = new float[1];
            Location.distanceBetween(odoP[0], odoP[1], l.getLatitude(), l.getLongitude(), d);
            double dt = (fixNs - odoFixNs) / 1e9;   // seconds between the two FIXES (can be minutes apart)
            if (gps && l.hasSpeed() && l.getSpeed() < 1.0f) return;   // standing still: GPS drift is not distance
            float jitter = gps ? Math.max(6f, (l.hasAccuracy() ? l.getAccuracy() : 0f) * 0.5f) : Math.max(40f, l.hasAccuracy() ? l.getAccuracy() : 0f);
            if (dt > 0 && dt <= 1200 && d[0] / dt <= 45.0) {   // up to 160 km/h: believable
                if (d[0] >= jitter) {
                    dayKm += d[0] / 1000f;
                    odoP = new double[]{l.getLatitude(), l.getLongitude()}; odoFixNs = fixNs;
                    odoSp.edit().putString("day", odoDay).putFloat("km", dayKm).apply();
                    HIST.put(odoDay, dayKm); histDirty = true;
                    flushHist(false);
                }
            } else { odoP = new double[]{l.getLatitude(), l.getLongitude()}; odoFixNs = fixNs; }   // a jump or a long gap: restart here
        } catch (Throwable ignored) { }
    }

    private static void odo(Context ctx) {
        try {
            if (!Cfg.b(Cfg.SH_TRIP, Cfg.D_SH_TRIP)) return;
            odoDay(ctx);
            flushHist(false);
            if (gpsOn) return;   // our own GPS updates are doing the counting
            long now = SystemClock.elapsedRealtime();
            if (now - odoT < 20_000 && odoT != 0) return;
            odoT = now;
            odoFix(TowerDb.myLocation(ctx), false);
            if (dayKm < 0f) dayKm = 0f;
        } catch (Throwable ignored) { }
    }

    /** Switches our own GPS updates on while riding (when the setting is on) and off again after ~2 minutes of standing still. */
    private static void gpsControl(Context ctx, boolean mv) {
        try {
            boolean want = Cfg.b(Cfg.RIDE_ACC, Cfg.D_RIDE_ACC) && Cfg.b(Cfg.SH_TRIP, Cfg.D_SH_TRIP);
            long now = SystemClock.elapsedRealtime();
            if (mv) gpsStillSince = 0; else if (gpsStillSince == 0) gpsStillSince = now;
            if (want && mv && !gpsOn) {
                LocationManager lm = ctx.getSystemService(LocationManager.class);
                if (lm == null || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return;
                gpsL = loc -> odoFix(loc, true);
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 0f, ctx.getMainExecutor(), gpsL);
                gpsLm = lm; gpsOn = true;
            } else if (gpsOn && (!want || (gpsStillSince != 0 && now - gpsStillSince > 120_000))) {
                try { gpsLm.removeUpdates(gpsL); } catch (Throwable ignored) { }
                gpsOn = false; gpsL = null; odoT = 0;
            }
        } catch (Throwable t) {
            de.robv.android.xposed.XposedBridge.log("NetSpeedXposed: [ridegps] own GPS not allowed: " + t);
        }
    }

    /** Worker thread, every few seconds. */
    static void tick(Context ctx) {
        odo(ctx);
        boolean learnOn = Cfg.b(Cfg.RIDE_GPS, Cfg.D_RIDE_GPS) && Cfg.b(Cfg.LEARN, Cfg.D_LEARN);
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
        moving = learnOn && mv;
        gpsControl(ctx, mv);
        if (!learnOn) return;
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
