package com.netspeed.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Heat throttle, run with root by this app (System UI has none). System UI sends
 * com.netspeed.xposed.THROTTLE with on=true/false and pct (CPU cap as a share of each cluster's top speed).
 * ON: caps every CPU cluster and the GPU, drops the screen to 60 Hz and clears background apps.
 * OFF: puts the CPU limits and refresh rate back to what they were.
 */
public final class ThrottleReceiver extends BroadcastReceiver {
    private static final String ON =
            "S=/data/local/tmp/throttle.state; PCT=%PCT%; GPCT=%GPCT%; G=/sys/class/kgsl/kgsl-3d0/devfreq\n"
            + "if [ ! -f $S ]; then { echo \"peak $(settings get system peak_refresh_rate)\"; echo \"min $(settings get system min_refresh_rate)\"; echo \"gpu $(cat $G/max_freq)\"; } > $S; fi\n"
            + "for p in /sys/devices/system/cpu/cpufreq/policy*; do\n"
            + "  max=$(cat $p/cpuinfo_max_freq); lim=$((max*PCT/100)); best=0\n"
            + "  for f in $(cat $p/scaling_available_frequencies); do [ $f -le $lim ] && [ $f -gt $best ] && best=$f; done\n"
            + "  [ $best -gt 0 ] && echo $best > $p/scaling_max_freq\n"
            + "done\n"
            + "gmax=$(grep '^gpu ' $S | cut -d' ' -f2); glim=$((gmax/100*GPCT)); gb=0\n"
            + "for f in $(cat $G/available_frequencies); do [ $f -le $glim ] && [ $f -gt $gb ] && gb=$f; done\n"
            + "[ $gb -gt 0 ] && echo $gb > $G/max_freq\n"
            + "settings put system peak_refresh_rate 60.0; settings put system min_refresh_rate 60.0\n"
            + "DIM=%DIM%; B=/data/local/tmp/throttle.bright\n"
            + "if [ $DIM -gt 0 ]; then\n"
            + "  if [ ! -f $B ]; then { echo \"mode $(settings get system screen_brightness_mode)\"; echo \"val $(settings get system screen_brightness)\"; } > $B; fi\n"
            + "  mx=$(dumpsys power | grep -o 'mScreenBrightnessSettingMaximum=[0-9]*' | head -1 | cut -d= -f2); [ -z \"$mx\" ] && mx=255\n"
            + "  cap=$((mx*DIM/100)); cur=$(grep '^val ' $B | cut -d' ' -f2)\n"
            + "  case $cur in ''|*[!0-9]*) cur=$mx;; esac\n"
            + "  v=$cur; [ $cur -gt $cap ] && v=$cap\n"
            + "  settings put system screen_brightness_mode 0; settings put system screen_brightness $v\n"
            + "fi\n"
            + "am kill-all\n";
    private static final String OFF =
            "S=/data/local/tmp/throttle.state\n"
            + "for p in /sys/devices/system/cpu/cpufreq/policy*; do cat $p/cpuinfo_max_freq > $p/scaling_max_freq; done\n"
            + "if [ -f $S ]; then\n"
            + "  while read k v; do\n"
            + "    if [ $k = gpu ]; then echo $v > /sys/class/kgsl/kgsl-3d0/devfreq/max_freq; continue; fi\n"
            + "    n=peak_refresh_rate; [ $k = min ] && n=min_refresh_rate\n"
            + "    if [ \"$v\" = null ] || [ -z \"$v\" ]; then settings delete system $n; else settings put system $n $v; fi\n"
            + "  done < $S; rm -f $S\n"
            + "fi\n"
            + "B=/data/local/tmp/throttle.bright\n"
            + "if [ -f $B ]; then\n"
            + "  while read k v; do\n"
            + "    case $v in ''|null) continue;; esac\n"
            + "    [ $k = mode ] && settings put system screen_brightness_mode $v\n"
            + "    [ $k = val ] && settings put system screen_brightness $v\n"
            + "  done < $B; rm -f $B\n"
            + "fi\n";

    /** Only the brightness part of OFF: puts the saved brightness and auto mode back, if we saved any. */
    private static final String BRIGHT_ONLY =
            "B=/data/local/tmp/throttle.bright\n"
            + "if [ -f $B ]; then\n"
            + "  while read k v; do\n"
            + "    case $v in ''|null) continue;; esac\n"
            + "    [ $k = mode ] && settings put system screen_brightness_mode $v\n"
            + "    [ $k = val ] && settings put system screen_brightness $v\n"
            + "  done < $B; rm -f $B\n"
            + "fi\n";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        final boolean on = intent.getBooleanExtra("on", false);
        final boolean brightOnly = intent.getBooleanExtra("bright", false);
        final int pct = Math.max(30, Math.min(100, intent.getIntExtra("pct", 60)));
        final int gpct = Math.max(30, Math.min(100, intent.getIntExtra("gpct", 55)));
        final int dim = Math.max(0, Math.min(100, intent.getIntExtra("dim", 0)));
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String script = brightOnly ? BRIGHT_ONLY : on ? ON.replace("%PCT%", String.valueOf(pct)).replace("%GPCT%", String.valueOf(gpct)).replace("%DIM%", String.valueOf(dim)) : OFF;
                    Process p = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
                    p.waitFor();
                } catch (Throwable ignored) {
                } finally {
                    pr.finish();
                }
            }
        }, "netspeed-throttle").start();
    }
}
