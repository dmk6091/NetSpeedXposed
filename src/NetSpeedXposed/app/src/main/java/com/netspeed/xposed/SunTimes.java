package com.netspeed.xposed;

import java.util.Calendar;
import java.util.TimeZone;

/** Sunrise / sunset (NOAA formulas), in local minutes after midnight; NaN when the sun doesn't rise/set. */
final class SunTimes {
    private SunTimes() {}

    static double[] today(double lat, double lon) { return today(lat, lon, 90.833); }

    /** Same for another sun angle: zenith 96 = civil twilight, 84 = golden hour (sun 6 degrees up). */
    static double[] today(double lat, double lon, double zenith) {
        Calendar c = Calendar.getInstance();
        int doy = c.get(Calendar.DAY_OF_YEAR);
        double tzHours = TimeZone.getDefault().getOffset(c.getTimeInMillis()) / 3600_000.0;
        double g = 2 * Math.PI / 365 * (doy - 1);
        double eqTime = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g)
                + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double latR = Math.toRadians(lat);
        double cosH = (Math.cos(Math.toRadians(zenith)) / (Math.cos(latR) * Math.cos(decl)))
                - Math.tan(latR) * Math.tan(decl);
        if (cosH > 1 || cosH < -1) return new double[]{Double.NaN, Double.NaN};
        double ha = Math.toDegrees(Math.acos(cosH));
        double rise = 720 - 4 * (lon + ha) - eqTime + tzHours * 60;
        double set = 720 - 4 * (lon - ha) - eqTime + tzHours * 60;
        return new double[]{rise, set};
    }

    /** "12h39" / "25m" for a number of minutes. */
    static String dur(double m) {
        int t = (int) Math.round(Math.max(0, m));
        return t < 60 ? t + "m" : (t / 60) + "h" + (t % 60 < 10 ? "0" : "") + (t % 60);
    }

    static String hhmm(double minutes) {
        if (Double.isNaN(minutes)) return "--:--";
        int m = (int) Math.round(minutes);
        m = ((m % 1440) + 1440) % 1440;
        return String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60);
    }
}
