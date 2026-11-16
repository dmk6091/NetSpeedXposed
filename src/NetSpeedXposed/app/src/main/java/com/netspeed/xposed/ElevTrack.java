package com.netspeed.xposed;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.util.Calendar;

/** Today's total climb and descent, counted from the barometer (GPS height when there's a fix); resets at midnight. */
final class ElevTrack {
    private ElevTrack() {}

    static volatile float up = 0, down = 0;
    static volatile boolean active;   // a barometer is registered and has reported
    private static boolean started;
    private static volatile boolean baro;   // the barometer is delivering readings
    private static float sm = Float.NaN, lastGps = Float.NaN;
    private static float ref = Float.NaN;
    private static int day = -1;
    private static long saved;
    private static SharedPreferences sp;
    private static final float STEP = 2f;   // metres: smaller wobbles are noise

    static synchronized void start(Context ctx) {
        if (started) return;
        started = true;
        try {
            sp = ctx.getSharedPreferences("netspeed_elev", Context.MODE_PRIVATE);
            day = sp.getInt("day", -1);
            up = sp.getFloat("up", 0);
            down = sp.getFloat("down", 0);
            rollDay();
            SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            Sensor p = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_PRESSURE);
            if (p == null) { de.robv.android.xposed.XposedBridge.log("NetSpeedXposed: [elev] no barometer on this phone"); return; }
            sm.registerListener(new SensorEventListener() {
                @Override public void onSensorChanged(SensorEvent e) {
                    baro = true;
                    add(SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, e.values[0]), STEP);
                }
                @Override public void onAccuracyChanged(Sensor s, int a) { }
            }, p, 1_000_000, new android.os.Handler(android.os.Looper.getMainLooper()));   // about once a second, no batching
            de.robv.android.xposed.XposedBridge.log("NetSpeedXposed: [elev] barometer registered: " + p.getName());
        } catch (Throwable t) { de.robv.android.xposed.XposedBridge.log("NetSpeedXposed: [elev] failed: " + t); }
    }

    /** No barometer (e.g. Poco F7): count from GPS height instead, smoothed, with a bigger step for its vertical noise. */
    static void gps(double alt) {
        if (baro || Double.isNaN(alt) || alt == lastGps) return;
        lastGps = (float) alt;
        sm = Float.isNaN(sm) ? (float) alt : sm * 0.7f + (float) alt * 0.3f;
        add(sm, 6f);
    }

    private static void rollDay() {
        int d = Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
        if (d != day) { day = d; up = 0; down = 0; ref = Float.NaN; save(); }
    }

    private static synchronized void add(float alt, float step) {
        active = true;
        rollDay();
        if (Float.isNaN(ref)) { ref = alt; return; }
        float dlt = alt - ref;
        if (dlt >= step) { up += dlt; ref = alt; }
        else if (dlt <= -step) { down += -dlt; ref = alt; }
        else return;
        long now = System.currentTimeMillis();
        if (now - saved > 60_000L) save();
    }

    private static void save() {
        saved = System.currentTimeMillis();
        try { if (sp != null) sp.edit().putInt("day", day).putFloat("up", up).putFloat("down", down).apply(); } catch (Throwable ignored) { }
    }
}
