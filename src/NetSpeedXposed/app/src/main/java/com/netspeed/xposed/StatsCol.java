package com.netspeed.xposed;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.location.GnssStatus;
import android.location.LocationManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * Three small rows beside the speed (replaces the graph):
 *   cpu23% 12sat      CPU load, GPS satellites used
 *   42ms zz82%        ping, deep-sleep share since boot
 *   gnss_hal 34m      the kernel wakelock held longest (needs root: read every 5 min)
 */
final class StatsCol extends Drawable {
    private static final String TAG = "NetSpeedXposed: [stats] ";

    // ---- data
    private static long cpuTotal, cpuIdle;
    private static int cpuPct = -1;
    private static volatile int sats = -1;
    private static boolean gnssTried;
    private static volatile String wake = "";
    private static long wakeAt;

    static int cpuPct() { return cpuPct; }
    static int sats() { return sats; }
    static String wake() { return wake; }

    static void refresh(Context ctx, boolean dark) {
        readCpu();
        startGnss(ctx);
        long now = SystemClock.elapsedRealtime();
        if (now - wakeAt > 300_000) {            // ask the NetSpeed app (it has root) to refresh the value
            wakeAt = now;
            try {
                android.content.Intent i = new android.content.Intent("com.netspeed.xposed.WAKE_READ")
                        .setPackage("com.netspeed.xposed")
                        .addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                ctx.sendBroadcast(i);
            } catch (Throwable t) {
                XposedBridge.log(TAG + "wake request failed: " + t);
            }
        }
        try {
            String v = android.provider.Settings.Global.getString(ctx.getContentResolver(), "netspeed_wake");
            wake = v == null ? "" : v;
        } catch (Throwable ignored) { }
    }

    private static void readCpu() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/stat"))) {
            String[] f = r.readLine().trim().split("\\s+");
            long total = 0;
            for (int i = 1; i < f.length; i++) total += Long.parseLong(f[i]);
            long idle = Long.parseLong(f[4]) + (f.length > 5 ? Long.parseLong(f[5]) : 0);
            if (cpuTotal > 0 && total > cpuTotal) {
                cpuPct = (int) Math.round(100.0 * (1.0 - (double) (idle - cpuIdle) / (total - cpuTotal)));
                cpuPct = Math.max(0, Math.min(100, cpuPct));
            }
            cpuTotal = total;
            cpuIdle = idle;
            return;
        } catch (Throwable ignored) { }
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/loadavg"))) {
            double l = Double.parseDouble(r.readLine().split(" ")[0]);
            cpuPct = (int) Math.min(100, Math.round(100.0 * l / Runtime.getRuntime().availableProcessors()));
        } catch (Throwable t) {
            cpuPct = freqShare();
        }
    }

    /** Fallback when /proc/stat and /proc/loadavg are closed: average clock speed as a share of the maximum. */
    private static int freqShare() {
        try {
            long cur = 0, max = 0;
            for (int i = 0; i < 16; i++) {
                java.io.File d = new java.io.File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/");
                if (!d.exists()) continue;
                cur += readLong(new java.io.File(d, "scaling_cur_freq"));
                max += readLong(new java.io.File(d, "cpuinfo_max_freq"));
            }
            return max > 0 ? (int) Math.min(100, Math.round(100.0 * cur / max)) : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long readLong(java.io.File f) throws Exception {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            return Long.parseLong(r.readLine().trim());
        }
    }

    private static void startGnss(Context ctx) {
        if (gnssTried) return;
        gnssTried = true;
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            lm.registerGnssStatusCallback(ctx.getMainExecutor(), new GnssStatus.Callback() {
                @Override
                public void onSatelliteStatusChanged(GnssStatus s) {
                    int n = 0;
                    for (int i = 0; i < s.getSatelliteCount(); i++) if (s.usedInFix(i)) n++;
                    sats = n;
                }

                @Override
                public void onStopped() {
                    sats = -1;
                }
            });
            XposedBridge.log(TAG + "GNSS status callback registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "GNSS callback failed: " + t);
        }
    }

    // ---- drawing
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<List<Object[]>> rows = new ArrayList<>();   // each segment: {text, color}
    private float lh;
    private int w, h;

    StatsCol() {
        p.setTypeface(Typeface.DEFAULT_BOLD);
    }

    void build(float textSizePx, int neutral, boolean dark) {
        rows.clear();
        int good = dark ? 0xFF34C759 : 0xFF1E8E3E, mid = dark ? 0xFFFFB020 : 0xFFB86E00, bad = dark ? 0xFFFF453A : 0xFFC4281C;
        int grey = (neutral & 0x00FFFFFF) | 0x99000000;
        // row 1: cpu, satellites
        List<Object[]> r1 = new ArrayList<>();
        if (Cfg.b(Cfg.SH_LOAD, Cfg.D_SH_LOAD)) {
            r1.add(new Object[]{"C", grey});
            r1.add(new Object[]{cpuPct < 0 ? "--" : cpuPct + "%", cpuPct < 0 ? grey : cpuPct < 50 ? good : cpuPct < 80 ? mid : bad});
        }
        r1.add(new Object[]{r1.isEmpty() ? "G" : " G", grey});
        r1.add(new Object[]{sats < 0 ? "--" : String.valueOf(sats), sats < 0 ? grey : sats >= 8 ? good : sats > 0 ? mid : bad});
                rows.add(r1);
        // row 2: ping, deep sleep
        int ms = SysState.pingMs;
        List<Object[]> r2 = new ArrayList<>();
        r2.add(new Object[]{ms < 0 ? "--" : ms + "ms", ms < 0 ? grey : ms < 100 ? good : ms < 300 ? mid : bad});
        if (Cfg.b(Cfg.SH_SLEEP, Cfg.D_SH_SLEEP)) {
            r2.add(new Object[]{" Z", grey});
            long up = SystemClock.uptimeMillis(), el = Math.max(1, SystemClock.elapsedRealtime());
            int deep = (int) Math.round(100.0 * (1.0 - (double) up / el));
            r2.add(new Object[]{deep + "%", deep >= 70 ? good : deep >= 40 ? mid : bad});
        }
        rows.add(r2);
        // row 3: top kernel wakelock
        List<Object[]> r3 = new ArrayList<>();
        r3.add(new Object[]{wake.isEmpty() ? "--" : wake, wake.isEmpty() ? grey : mid});
        rows.add(r3);
        p.setTextSize(textSizePx * 0.72f);
        lh = textSizePx * 0.74f;
        float mw = 0;
        for (List<Object[]> r : rows) {
            float x = 0;
            for (Object[] s : r) x += p.measureText((String) s[0]);
            mw = Math.max(mw, x);
        }
        float maxW = textSizePx * 4.6f;   // the graph's room + the meter icon's
        if (mw > maxW) {
            float k = maxW / mw;
            p.setTextSize(textSizePx * 0.72f * k);
            mw = maxW;
        }
        w = Math.round(mw) + 2;
        h = Math.round(lh * 3);
        setBounds(0, 0, w, h);          // compound drawables are only drawn with bounds set
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        float y = getBounds().top + lh * 0.82f;
        float x0 = getBounds().left;
        for (List<Object[]> r : rows) {
            float x = x0;
            for (Object[] s : r) {
                p.setColor((Integer) s[1]);
                c.drawText((String) s[0], x, y, p);
                x += p.measureText((String) s[0]);
            }
            y += lh;
        }
    }

    @Override public int getIntrinsicWidth() { return w; }
    @Override public int getIntrinsicHeight() { return h; }
    @Override public void setAlpha(int a) { p.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
