package com.netspeed.xposed;

/**
 * Real night sky for the status bar background: the brightest stars (J2000 positions; precession since then is
 * ~0.4°, invisible here) and the bright planets from low-precision Keplerian elements (Standish, JPL; good to a
 * fraction of a degree for 1800-2050). Everything is returned as altitude / azimuth for the place.
 */
final class SkyObjects {
    private SkyObjects() {}

    /** name, RA hours, Dec degrees, magnitude, colour class (B blue-white, W white, Y yellow, O orange, R red). */
    static final Object[][] STARS = {
            {"Sirius", 6.7525, -16.716, -1.46, 'W'}, {"Canopus", 6.3992, -52.696, -0.74, 'W'},
            {"Arcturus", 14.2610, 19.182, -0.05, 'O'}, {"Rigil Kentaurus", 14.6600, -60.835, -0.27, 'Y'},
            {"Vega", 18.6156, 38.784, 0.03, 'W'}, {"Capella", 5.2782, 45.998, 0.08, 'Y'},
            {"Rigel", 5.2423, -8.202, 0.13, 'B'}, {"Procyon", 7.6550, 5.225, 0.34, 'W'},
            {"Achernar", 1.6286, -57.237, 0.46, 'B'}, {"Betelgeuse", 5.9195, 7.407, 0.50, 'R'},
            {"Hadar", 14.0637, -60.373, 0.61, 'B'}, {"Altair", 19.8464, 8.868, 0.76, 'W'},
            {"Acrux", 12.4433, -63.099, 0.76, 'B'}, {"Aldebaran", 4.5987, 16.509, 0.86, 'O'},
            {"Antares", 16.4901, -26.432, 0.96, 'R'}, {"Spica", 13.4199, -11.161, 0.97, 'B'},
            {"Pollux", 7.7553, 28.026, 1.14, 'O'}, {"Fomalhaut", 22.9608, -29.622, 1.16, 'W'},
            {"Deneb", 20.6905, 45.280, 1.25, 'W'}, {"Mimosa", 12.7954, -59.689, 1.25, 'B'},
            {"Regulus", 10.1395, 11.967, 1.35, 'B'}, {"Adhara", 6.9771, -28.972, 1.50, 'B'},
            {"Castor", 7.5767, 31.888, 1.58, 'W'}, {"Shaula", 17.5601, -37.104, 1.62, 'B'},
            {"Gacrux", 12.5194, -57.113, 1.63, 'R'}, {"Bellatrix", 5.4189, 6.350, 1.64, 'B'},
            {"Elnath", 5.4382, 28.608, 1.65, 'B'}, {"Miaplacidus", 9.2200, -69.717, 1.68, 'W'},
            {"Alnilam", 5.6036, -1.202, 1.69, 'B'}, {"Alnair", 22.1372, -46.961, 1.74, 'B'},
            {"Alnitak", 5.6793, -1.943, 1.77, 'B'}, {"Alioth", 12.9005, 55.960, 1.77, 'W'},
            {"Dubhe", 11.0621, 61.751, 1.79, 'O'}, {"Mirfak", 3.4054, 49.861, 1.79, 'Y'},
            {"Wezen", 7.1399, -26.393, 1.83, 'Y'}, {"Sargas", 17.6219, -42.998, 1.86, 'Y'},
            {"Kaus Australis", 18.4029, -34.385, 1.85, 'B'}, {"Avior", 8.3752, -59.510, 1.86, 'O'},
            {"Alkaid", 13.7923, 49.313, 1.86, 'B'}, {"Menkalinan", 5.9921, 44.948, 1.90, 'W'},
            {"Atria", 16.8111, -69.028, 1.92, 'O'}, {"Alhena", 6.6285, 16.399, 1.93, 'W'},
            {"Peacock", 20.4275, -56.735, 1.94, 'B'}, {"Polaris", 2.5303, 89.264, 1.98, 'Y'},
            {"Mirzam", 6.3783, -17.956, 1.98, 'B'}, {"Alphard", 9.4598, -8.659, 1.98, 'O'},
            {"Hamal", 2.1196, 23.462, 2.00, 'O'}, {"Algieba", 10.3329, 19.842, 2.08, 'O'},
            {"Diphda", 0.7265, -17.987, 2.04, 'O'}, {"Nunki", 18.9211, -26.297, 2.05, 'B'},
            {"Menkent", 14.1114, -36.370, 2.06, 'O'}, {"Alpheratz", 0.1398, 29.091, 2.06, 'B'},
            {"Mirach", 1.1622, 35.621, 2.05, 'R'}, {"Saiph", 5.7959, -9.670, 2.07, 'B'},
            {"Kochab", 14.8451, 74.156, 2.08, 'O'}, {"Rasalhague", 17.5822, 12.560, 2.08, 'W'},
            {"Algol", 3.1361, 40.956, 2.10, 'B'}, {"Almach", 2.0650, 42.330, 2.10, 'O'},
            {"Denebola", 11.8177, 14.572, 2.14, 'W'}, {"Navi", 0.9451, 60.717, 2.15, 'B'},
            {"Suhail", 9.1333, -43.433, 2.21, 'O'}, {"Mintaka", 5.5334, -0.299, 2.23, 'B'},
            {"Mizar", 13.3988, 54.925, 2.23, 'W'}, {"Sadr", 20.3705, 40.257, 2.23, 'Y'},
            {"Schedar", 0.6751, 56.537, 2.24, 'O'}, {"Eltanin", 17.9434, 51.489, 2.24, 'O'},
            {"Naos", 8.0597, -40.003, 2.25, 'B'}, {"Caph", 0.1530, 59.150, 2.28, 'W'},
            {"Dschubba", 16.0056, -22.622, 2.29, 'B'}, {"Merak", 11.0307, 56.382, 2.37, 'W'},
            {"Enif", 21.7364, 9.875, 2.39, 'O'}, {"Ankaa", 0.4381, -42.306, 2.40, 'O'},
            {"Scheat", 23.0629, 28.083, 2.42, 'R'}, {"Phecda", 11.8972, 53.695, 2.44, 'W'},
            {"Alderamin", 21.3097, 62.586, 2.45, 'W'}, {"Markab", 23.0793, 15.205, 2.49, 'B'},
    };

    static int color(char k) {
        switch (k) {
            case 'B': return 0xFFCFDFFF;
            case 'Y': return 0xFFFFF2D2;
            case 'O': return 0xFFFFD09A;
            case 'R': return 0xFFFFAE84;
            default: return 0xFFF6F8FF;
        }
    }

    /** {alt, az} in degrees for RA (degrees) / Dec at the place and time. */
    static double[] altAz(double raDeg, double decDeg, double lst, double lat) {
        double ha = Math.toRadians(lst - raDeg);
        double la = Math.toRadians(lat), de = Math.toRadians(decDeg);
        double sinAlt = Math.sin(la) * Math.sin(de) + Math.cos(la) * Math.cos(de) * Math.cos(ha);
        double alt = Math.asin(Math.max(-1, Math.min(1, sinAlt)));
        double az = Math.atan2(-Math.cos(de) * Math.sin(ha), Math.sin(de) * Math.cos(la) - Math.cos(de) * Math.sin(la) * Math.cos(ha));
        double azd = Math.toDegrees(az) % 360;
        return new double[]{Math.toDegrees(alt), azd < 0 ? azd + 360 : azd};
    }

    static double lst(long ms, double lon) {
        double v = (Astro.gmst(Astro.jd(ms)) + lon) % 360;
        return v < 0 ? v + 360 : v;
    }

    // ---------------------------------------------------------------- planets

    static final String[] PLANETS = {"Venus", "Mars", "Jupiter", "Saturn"};
    static final int[] PLANET_COLOR = {0xFFFFFBEA, 0xFFFF9C6A, 0xFFFFF0D0, 0xFFF4E3B0};
    static final float[] PLANET_MAG = {-4.2f, 0.8f, -2.3f, 0.8f};

    // a, e, I, L, long. perihelion, long. node; and rates per century (Standish 1800-2050)
    private static final double[][] EL = {
            {0.72333566, 0.00677672, 3.39467605, 181.97909950, 131.60246718, 76.67984255,
                    0.00000390, -0.00004107, -0.00078890, 58517.81538729, 0.00268329, -0.27769418},
            {1.52371034, 0.09339410, 1.84969142, -4.55343205, -23.94362959, 49.55953891,
                    0.00001847, 0.00007882, -0.00813131, 19140.30268499, 0.44441088, -0.29257343},
            {5.20288700, 0.04838624, 1.30439695, 34.39644051, 14.72847983, 100.47390909,
                    -0.00011607, -0.00013253, -0.00183714, 3034.74612775, 0.21252668, 0.20469106},
            {9.53667594, 0.05386179, 2.48599187, 49.95424423, 92.59887831, 113.66242448,
                    -0.00125060, -0.00050991, 0.00193609, 1222.49362201, -0.41897216, -0.28867794},
    };
    private static final double[] EARTH = {1.00000261, 0.01671123, -0.00001531, 100.46457166, 102.93768193, 0.0,
            0.00000562, -0.00004392, -0.01294668, 35999.37244981, 0.32327364, 0.0};

    /** Heliocentric ecliptic x, y, z (AU). */
    private static double[] helio(double[] k, double t) {
        double a = k[0] + k[6] * t, e = k[1] + k[7] * t, I = Math.toRadians(k[2] + k[8] * t);
        double L = k[3] + k[9] * t, w = k[4] + k[10] * t, O = k[5] + k[11] * t;
        double M = Math.toRadians(((L - w) % 360 + 540) % 360 - 180);
        double om = Math.toRadians(w - O), Or = Math.toRadians(O);
        double E = M;
        for (int i = 0; i < 10; i++) E -= (E - e * Math.sin(E) - M) / (1 - e * Math.cos(E));
        double xp = a * (Math.cos(E) - e), yp = a * Math.sqrt(1 - e * e) * Math.sin(E);
        double co = Math.cos(om), so = Math.sin(om), cO = Math.cos(Or), sO = Math.sin(Or), cI = Math.cos(I), sI = Math.sin(I);
        return new double[]{
                (co * cO - so * sO * cI) * xp + (-so * cO - co * sO * cI) * yp,
                (co * sO + so * cO * cI) * xp + (-so * sO + co * cO * cI) * yp,
                (so * sI) * xp + (co * sI) * yp};
    }

    /** {ra deg, dec deg} of planet i (index into PLANETS). */
    static double[] planetRaDec(int i, long ms) {
        double t = (Astro.jd(ms) - 2451545.0) / 36525.0;
        double[] p = helio(EL[i], t), e = helio(EARTH, t);
        double x = p[0] - e[0], y = p[1] - e[1], z = p[2] - e[2];
        double eps = Math.toRadians(23.43928);
        double ye = y * Math.cos(eps) - z * Math.sin(eps), ze = y * Math.sin(eps) + z * Math.cos(eps);
        double ra = Math.toDegrees(Math.atan2(ye, x));
        return new double[]{ra < 0 ? ra + 360 : ra, Math.toDegrees(Math.atan2(ze, Math.hypot(x, ye)))};
    }
}
