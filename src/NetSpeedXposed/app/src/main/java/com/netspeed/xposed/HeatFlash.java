package com.netspeed.xposed;

import android.content.Context;
import android.os.SystemClock;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flashing warning on the status bar's top strip when power draw or heat is abnormal. It flashes for the chosen time
 * (10 / 30 / 60 s), then stays quiet for 10 minutes unless the power gets clearly worse.
 */
final class HeatFlash {
    private HeatFlash() {}

    private static boolean active;
    private static String text = "";
    private static long until, lastStart = -3600_000L;
    private static float lastW;

    /** The warning text while it is flashing, else null. Called every second from the status bar's update. */
    static String update(Context ctx, float watts, float bat, float cpu, boolean charging, String live) {
        try {
            if (!Cfg.b(Cfg.FLASH, Cfg.D_FLASH)) { active = false; return null; }
            long now = SystemClock.elapsedRealtime();
            if (active && now < until) return text;
            active = false;
            float limitBat = Cfg.i(Cfg.FLASH_BAT, Cfg.D_FLASH_BAT), limitCpu = Cfg.i(Cfg.FLASH_CPU, Cfg.D_FLASH_CPU), limitW = Cfg.i(Cfg.FLASH_W, Cfg.D_FLASH_W);
            float w = Float.isNaN(watts) ? 0f : Math.abs(watts);
            boolean hotT = (!Float.isNaN(bat) && bat >= limitBat) || (!Float.isNaN(cpu) && cpu >= limitCpu);
            boolean hotW = !charging && w >= limitW;
            if (!hotT && !hotW) return null;
            if (now - lastStart < 10 * 60_000L && w < lastW + 3f) return null;
            // what is probably behind it, from things System UI can see
            StringBuilder why = new StringBuilder();
            try {
                int br = android.provider.Settings.System.getInt(ctx.getContentResolver(), "screen_brightness", 0);
                if (br > 0 && br >= 0.7 * 2047 && w >= 3f) add(why, "screen");
            } catch (Throwable ignored) { }
            try {
                Matcher m = Pattern.compile("dbm=(-?\\d+)").matcher(live == null ? "" : live);
                while (m.find()) if (Integer.parseInt(m.group(1)) <= -100) { add(why, "weak signal"); break; }
            } catch (Throwable ignored) { }
            if (charging) add(why, "charging");
            StringBuilder t = new StringBuilder("⚠ ");
            if (hotT) {
                boolean b = !Float.isNaN(bat) && bat >= limitBat;
                t.append("HOT ").append(b ? "B" : "C").append(Math.round(b ? bat : cpu)).append('°');
                if (hotW) t.append(" · ");
            }
            if (hotW) t.append(String.format(Locale.US, "%.1f W", w));
            t.append(" · ").append(why.length() > 0 ? why.toString() : "see Heat report");
            text = t.toString();
            int secs = new int[]{10, 30, 60}[Math.max(0, Math.min(2, Cfg.i(Cfg.FLASH_SEC, Cfg.D_FLASH_SEC)))];
            until = now + secs * 1000L;
            lastStart = now; lastW = w; active = true;
            return text;
        } catch (Throwable t) { return null; }
    }

    private static void add(StringBuilder sb, String s) { if (sb.length() > 0) sb.append(" + "); sb.append(s); }
}
