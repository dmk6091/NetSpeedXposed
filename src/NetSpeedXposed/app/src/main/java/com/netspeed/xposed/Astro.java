package com.netspeed.xposed;

/**
 * Where the Sun and the Moon really are in your sky right now (altitude / azimuth), plus the Moon's
 * illuminated fraction and which way its lit side faces. Low-precision formulas (NOAA for the Sun,
 * Meeus chapter 47 main terms for the Moon, with topocentric parallax): good to a fraction of a degree,
 * far finer than one pixel of the status bar.
 */
final class Astro {
    private Astro() {}

    /** Result for one body. Angles in degrees. */
    static class Pos {
        double alt, az;       // altitude above the horizon, azimuth from north clockwise
        double ra, dec;       // apparent right ascension / declination
        double lon;           // ecliptic longitude
        double distKm;
    }

    /** Moon extras. */
    static final class Moon extends Pos {
        double illum;         // 0..1 lit fraction
        boolean waxing;
        double phase;         // 0 new .. 0.5 full .. 1 new (from the elongation)
        double limbFromZenith; // bright limb direction, degrees from "towards the zenith", positive towards east
    }

    private static double rad(double d) { return Math.toRadians(d); }
    private static double deg(double r) { return Math.toDegrees(r); }
    private static double norm(double d) { d %= 360; return d < 0 ? d + 360 : d; }

    static double jd(long ms) { return ms / 86400000.0 + 2440587.5; }

    /** Greenwich mean sidereal time, degrees. */
    static double gmst(double jd) {
        double t = (jd - 2451545.0) / 36525.0;
        return norm(280.46061837 + 360.98564736629 * (jd - 2451545.0) + 0.000387933 * t * t - t * t * t / 38710000.0);
    }

    private static double obliquity(double t) {
        return 23.439291 - 0.0130042 * t;
    }

    /** Fills alt / az from ra / dec for the place. */
    private static void horizon(Pos p, double jd, double lat, double lon) {
        double ha = rad(norm(gmst(jd) + lon - p.ra));
        double la = rad(lat), de = rad(p.dec);
        double sinAlt = Math.sin(la) * Math.sin(de) + Math.cos(la) * Math.cos(de) * Math.cos(ha);
        double alt = Math.asin(Math.max(-1, Math.min(1, sinAlt)));
        double az = Math.atan2(-Math.cos(de) * Math.sin(ha), Math.sin(de) * Math.cos(la) - Math.cos(de) * Math.sin(la) * Math.cos(ha));
        p.alt = deg(alt);
        p.az = norm(deg(az));
    }

    /** Small correction for the atmosphere bending light near the horizon (Bennett), degrees to add. */
    private static double refraction(double alt) {
        if (alt < -1.5) return 0;
        return 1.02 / Math.tan(rad(alt + 10.3 / (alt + 5.11))) / 60.0;
    }

    static Pos sun(long ms, double lat, double lon) {
        double jd = jd(ms), t = (jd - 2451545.0) / 36525.0;
        double l0 = norm(280.46646 + t * (36000.76983 + t * 0.0003032));
        double m = rad(357.52911 + t * (35999.05029 - 0.0001537 * t));
        double c = Math.sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) + Math.sin(2 * m) * (0.019993 - 0.000101 * t)
                + Math.sin(3 * m) * 0.000289;
        double om = rad(125.04 - 1934.136 * t);
        double lam = l0 + c - 0.00569 - 0.00478 * Math.sin(om);
        double eps = rad(obliquity(t) + 0.00256 * Math.cos(om));
        double e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);
        double v = m + rad(c);
        Pos p = new Pos();
        p.lon = norm(lam);
        p.distKm = 149597870.7 * 1.000001018 * (1 - e * e) / (1 + e * Math.cos(v));
        p.ra = norm(deg(Math.atan2(Math.cos(eps) * Math.sin(rad(lam)), Math.cos(rad(lam)))));
        p.dec = deg(Math.asin(Math.sin(eps) * Math.sin(rad(lam))));
        horizon(p, jd, lat, lon);
        p.alt += refraction(p.alt);
        return p;
    }

    /**
     * Next moonrise or moonset after ms: {time ms, 1 = rise / 0 = set}, or null if none within 30 h.
     * The Moon's upper edge on the horizon (centre at -0.27°, refraction and parallax already in alt).
     */
    static long[] nextMoonEvent(long ms, double lat, double lon) {
        final double H = -0.27;
        final long step = 10 * 60_000L;
        double prev = moon(ms, lat, lon).alt - H;
        for (long t = ms + step; t <= ms + 30 * 3600_000L; t += step) {
            double cur = moon(t, lat, lon).alt - H;
            if ((prev < 0) != (cur < 0)) {
                long lo = t - step, hi = t;
                boolean rising = cur > 0;
                while (hi - lo > 30_000L) {                    // refine to half a minute
                    long mid = (lo + hi) / 2;
                    double m = moon(mid, lat, lon).alt - H;
                    if ((m > 0) == rising) hi = mid; else lo = mid;
                }
                return new long[]{hi, rising ? 1 : 0};
            }
            prev = cur;
        }
        return null;
    }

    static Moon moon(long ms, double lat, double lon) {
        double jd = jd(ms), t = (jd - 2451545.0) / 36525.0;
        double lp = norm(218.3164477 + 481267.88123421 * t);
        double d = rad(norm(297.8501921 + 445267.1114034 * t));
        double m = rad(norm(357.5291092 + 35999.0502909 * t));
        double mp = rad(norm(134.9633964 + 477198.8675055 * t));
        double f = rad(norm(93.2720950 + 483202.0175233 * t));
        double e = 1 - 0.002516 * t;

        double sl = 6.288774 * Math.sin(mp)
                + 1.274027 * Math.sin(2 * d - mp)
                + 0.658314 * Math.sin(2 * d)
                + 0.213618 * Math.sin(2 * mp)
                - 0.185116 * e * Math.sin(m)
                - 0.114332 * Math.sin(2 * f)
                + 0.058793 * Math.sin(2 * d - 2 * mp)
                + 0.057066 * e * Math.sin(2 * d - m - mp)
                + 0.053322 * Math.sin(2 * d + mp)
                + 0.045758 * e * Math.sin(2 * d - m)
                - 0.040923 * e * Math.sin(m - mp)
                - 0.034720 * Math.sin(d)
                - 0.030383 * e * Math.sin(m + mp)
                + 0.015327 * Math.sin(2 * d - 2 * f)
                - 0.012528 * Math.sin(mp + 2 * f)
                + 0.010980 * Math.sin(mp - 2 * f)
                + 0.010675 * Math.sin(4 * d - mp)
                + 0.010034 * Math.sin(3 * mp)
                + 0.008548 * Math.sin(4 * d - 2 * mp);
        double sb = 5.128122 * Math.sin(f)
                + 0.280602 * Math.sin(mp + f)
                + 0.277693 * Math.sin(mp - f)
                + 0.173237 * Math.sin(2 * d - f)
                + 0.055413 * Math.sin(2 * d - mp + f)
                + 0.046271 * Math.sin(2 * d - mp - f)
                + 0.032573 * Math.sin(2 * d + f)
                + 0.017198 * Math.sin(2 * mp + f)
                + 0.009266 * Math.sin(2 * d + mp - f)
                + 0.008822 * Math.sin(2 * mp - f);
        double sr = -20905.355 * Math.cos(mp)
                - 3699.111 * Math.cos(2 * d - mp)
                - 2955.968 * Math.cos(2 * d)
                - 569.925 * Math.cos(2 * mp)
                + 48.888 * e * Math.cos(m)
                - 3.149 * Math.cos(2 * f)
                + 246.158 * Math.cos(2 * d - 2 * mp)
                - 152.138 * e * Math.cos(2 * d - m - mp)
                - 170.733 * Math.cos(2 * d + mp)
                - 204.586 * e * Math.cos(2 * d - m)
                - 129.620 * e * Math.cos(m - mp)
                + 108.743 * Math.cos(d)
                + 104.755 * e * Math.cos(m + mp)
                + 10.321 * Math.cos(2 * d - 2 * f)
                + 79.661 * Math.cos(mp - 2 * f);

        double lam = rad(norm(lp + sl)), bet = rad(sb);
        double eps = rad(obliquity(t));
        Moon p = new Moon();
        p.lon = deg(lam);
        p.distKm = 385000.56 + sr;
        p.ra = norm(deg(Math.atan2(Math.sin(lam) * Math.cos(eps) - Math.tan(bet) * Math.sin(eps), Math.cos(lam))));
        p.dec = deg(Math.asin(Math.sin(bet) * Math.cos(eps) + Math.cos(bet) * Math.sin(eps) * Math.sin(lam)));
        horizon(p, jd, lat, lon);
        // topocentric: the Moon is close enough that your place on Earth lowers it by up to ~1 degree
        p.alt -= deg(Math.asin(6378.14 / p.distKm * Math.cos(rad(p.alt))));
        p.alt += refraction(p.alt);

        // phase from the Sun-Moon elongation
        Pos s = sun(ms, lat, lon);
        double as = rad(s.ra), ds = rad(s.dec), am = rad(p.ra), dm = rad(p.dec);
        double cosPsi = Math.sin(ds) * Math.sin(dm) + Math.cos(ds) * Math.cos(dm) * Math.cos(as - am);
        double psi = Math.acos(Math.max(-1, Math.min(1, cosPsi)));
        double i = Math.atan2(s.distKm * Math.sin(psi), p.distKm - s.distKm * Math.cos(psi));
        p.illum = (1 + Math.cos(i)) / 2;
        double el = norm(p.lon - s.lon);
        p.waxing = el < 180;
        p.phase = el / 360.0;
        // bright limb position angle (from celestial north, through east), then turned to the zenith frame
        double chi = Math.atan2(Math.cos(ds) * Math.sin(as - am),
                Math.sin(ds) * Math.cos(dm) - Math.cos(ds) * Math.sin(dm) * Math.cos(as - am));
        double ha = rad(norm(gmst(jd) + lon - p.ra));
        double q = Math.atan2(Math.sin(ha), Math.tan(rad(lat)) * Math.cos(dm) - Math.sin(dm) * Math.cos(ha));
        p.limbFromZenith = deg(chi - q);
        return p;
    }
}
