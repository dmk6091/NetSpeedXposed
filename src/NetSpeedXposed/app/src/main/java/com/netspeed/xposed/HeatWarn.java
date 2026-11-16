package com.netspeed.xposed;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;

/**
 * "Device too hot" popup, styled like the system's Critical Battery Level dialog. Called from the
 * temperature tick in System UI (screen on only). Shows once, comes back after 10 minutes if still hot,
 * and closes itself when the phone has cooled down.
 */
final class HeatWarn {
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static AlertDialog dlg;
    private static boolean showing;
    private static long dismissedAt = -10 * 60_000L;

    private HeatWarn() {}

    /** True while the heat throttle is on (read by the status bar to show it). */
    static volatile boolean throttled;

    /** Cap the CPU (and drop to 60 Hz) when hot, release when cooled. The NetSpeed app does the root work. */
    private static boolean inited, brightChecked, dimOffSent;

    private static void throttle(Context ctx, float bat, float cpu) {
        try {
            if (!inited) {
                // System UI restarts forget the state, but the caps stay on: read the flag we left behind
                inited = true;
                try {
                    throttled = android.provider.Settings.Global.getInt(ctx.getContentResolver(), "netspeed_throttled", 0) == 1;
                } catch (Throwable ignored) { }
            }
            // never leave the screen on manual brightness: put the saved brightness / auto mode back
            //  - once after every System UI start while not throttled (a missed restore from earlier)
            //  - when the dim option is switched off while throttled
            boolean dimOn = Cfg.b(Cfg.THR, Cfg.D_THR) && Cfg.b(Cfg.THR_DIM, Cfg.D_THR_DIM);
            if (!throttled && !brightChecked) { brightChecked = true; sendBright(ctx); }
            if (throttled && !dimOn && !dimOffSent) { dimOffSent = true; sendBright(ctx); }
            if (dimOn || !throttled) dimOffSent = false;
            if (!Cfg.b(Cfg.THR, Cfg.D_THR)) {
                if (throttled) send(ctx, false, 100);
                return;
            }
            float tb = Cfg.i(Cfg.THR_BAT, Cfg.D_THR_BAT), tc = Cfg.i(Cfg.THR_CPU, Cfg.D_THR_CPU);
            boolean hot = (!Float.isNaN(bat) && bat >= tb) || (!Float.isNaN(cpu) && cpu >= tc);
            boolean cool = (Float.isNaN(bat) || bat < tb - 3) && (Float.isNaN(cpu) || cpu < tc - 8);
            if (!throttled && hot) send(ctx, true, Cfg.i(Cfg.THR_PCT, Cfg.D_THR_PCT));
            else if (throttled && cool) send(ctx, false, 100);
        } catch (Throwable ignored) { }
    }

    private static void sendBright(Context ctx) {
        try {
            ctx.sendBroadcast(new Intent("com.netspeed.xposed.THROTTLE").setPackage("com.netspeed.xposed")
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES).putExtra("bright", true));
        } catch (Throwable ignored) { }
    }

    private static void send(Context ctx, boolean on, int pct) {
        throttled = on;
        try {
            android.provider.Settings.Global.putInt(ctx.getContentResolver(), "netspeed_throttled", on ? 1 : 0);
        } catch (Throwable ignored) { }
        Intent i = new Intent("com.netspeed.xposed.THROTTLE").setPackage("com.netspeed.xposed")
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES).putExtra("on", on).putExtra("pct", pct).putExtra("gpct", Cfg.i(Cfg.THR_GPU, Cfg.D_THR_GPU))
                .putExtra("dim", on && Cfg.b(Cfg.THR_DIM, Cfg.D_THR_DIM) ? Cfg.i(Cfg.THR_DIM_PCT, Cfg.D_THR_DIM_PCT) : 0);
        ctx.sendBroadcast(i);
    }

    static void check(final Context ctx, final float bat, final float cpu) {
        throttle(ctx, bat, cpu);
        try {
            if (!Cfg.b(Cfg.HEAT, Cfg.D_HEAT)) return;
            final float bl = Cfg.i(Cfg.HEAT_BAT, Cfg.D_HEAT_BAT), cl = Cfg.i(Cfg.HEAT_CPU, Cfg.D_HEAT_CPU);
            boolean hotB = !Float.isNaN(bat) && bat >= bl, hotC = !Float.isNaN(cpu) && cpu >= cl;
            if (showing) {
                boolean cool = (Float.isNaN(bat) || bat < bl - 3) && (Float.isNaN(cpu) || cpu < cl - 8);
                if (cool) UI.post(new Runnable() { @Override public void run() { try { if (dlg != null) dlg.dismiss(); } catch (Throwable ignored) { } } });
                return;
            }
            if (!hotB && !hotC) return;
            if (SystemClock.elapsedRealtime() - dismissedAt < 10 * 60_000L) return;
            showing = true;
            UI.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        show(ctx, bat, cpu);
                    } catch (Throwable t) {
                        showing = false;
                        dismissedAt = SystemClock.elapsedRealtime();
                        de.robv.android.xposed.XposedBridge.log("NetSpeedXposed: [heat] popup failed: " + t);
                    }
                }
            });
        } catch (Throwable ignored) { }
    }

    private static void show(final Context ctx, float bat, float cpu) {
        Context t = new ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        StringBuilder m = new StringBuilder();
        if (!Float.isNaN(bat)) m.append("Battery: ").append(Math.round(bat)).append("°C\n");
        if (!Float.isNaN(cpu)) m.append("CPU: ").append(Math.round(cpu)).append("°C\n");
        m.append("\nYour device is running hot. Stop charging or heavy use, take it out of the sun and any case, "
                + "and let it cool down to avoid slowdowns and battery damage.");
        AlertDialog.Builder b = new AlertDialog.Builder(t)
                .setTitle("⚠️ Device Too Hot")
                .setMessage(m.toString())
                .setNeutralButton("Battery Saver", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                            PowerManager.class.getMethod("setPowerSaveModeEnabled", boolean.class).invoke(pm, true);
                        } catch (Throwable e) {
                            try { android.provider.Settings.Global.putInt(ctx.getContentResolver(), "low_power", 1); } catch (Throwable ignored) { }
                        }
                    }
                })
                .setPositiveButton("OK", null);
        dlg = b.create();
        dlg.setCanceledOnTouchOutside(false);
        dlg.getWindow().setType(2008);   // TYPE_SYSTEM_DIALOG: allowed from System UI
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                showing = false;
                dismissedAt = SystemClock.elapsedRealtime();
                dlg = null;
            }
        });
        dlg.show();
        try {
            ((android.os.Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE))
                    .vibrate(android.os.VibrationEffect.createWaveform(new long[]{0, 200, 120, 200}, -1));
        } catch (Throwable ignored) { }
    }
}
