package com.netspeed.xposed;

import java.util.Calendar;
import java.util.Locale;

/**
 * Climate patterns that sit beside El Nino in the earth slot: the Indian Ocean Dipole, the MJO, the Arabian Sea temperature against its normal,
 * the NAO, the PDO, the El Nino trend and the Indian monsoon season. Values come from NOAA CPC / BoM text files and the Open-Meteo marine model;
 * everything is approximate and the month of each value is shown, because the monthly indices arrive weeks late.
 */
final class ClimIdx {
    private ClimIdx() {}

    static final String URL_IOD = "https://www.cpc.ncep.noaa.gov/products/international/ocean_monitoring/indian/IODMI/mnth.ersstv6.clim19912020.dmi_current.txt";
    static final String URL_PDO = "https://www.cpc.ncep.noaa.gov/products/GODAS/multiora/index/mnth.ersstv5.clim19912020.pdo_current.txt";
    static final String URL_NAO = "https://www.cpc.ncep.noaa.gov/products/precip/CWlink/pna/norm.nao.monthly.b5001.current.ascii";
    static final String URL_MJO = "https://www.bom.gov.au/climate/mjo/graphics/rmm.74toRealtime.txt";
    static final String URL_SST = "https://marine-api.open-meteo.com/v1/marine?latitude=15&longitude=68&current=sea_surface_temperature";

    static volatile long at = 0, triedAt = 0;
    static volatile double iod = Double.NaN, pdo = Double.NaN, nao = Double.NaN, mjoAmp = Double.NaN, sst = Double.NaN, trend = Double.NaN;
    static volatile int iodM = 0, pdoM = 0, naoM = 0, mjoPhase = 0;   // month 1..12 of the value

    static boolean due(long now) { return now - triedAt > 10 * 60_000L && now - at > 12 * 3600_000L; }

    static void fetch(long now) {
        boolean any = false;
        try { String s = EarthSci.get(URL_IOD); String[] l = lastRow(s, 5); if (l != null) { iod = Double.parseDouble(l[4]); iodM = Integer.parseInt(l[1]); any = true; } } catch (Throwable ignored) { }
        try { String s = EarthSci.get(URL_PDO); String[] l = lastRow(s, 3); if (l != null) { pdo = Double.parseDouble(l[2]); pdoM = Integer.parseInt(l[1]); any = true; } } catch (Throwable ignored) { }
        try { String s = EarthSci.get(URL_NAO); String[] l = lastRow(s, 3); if (l != null) { nao = Double.parseDouble(l[2]); naoM = Integer.parseInt(l[1]); any = true; } } catch (Throwable ignored) { }
        try { mjo(tail(URL_MJO, 3000)); any = true; } catch (Throwable ignored) { }
        try {
            String s = EarthSci.get(URL_SST);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"sea_surface_temperature\"\\s*:\\s*(-?[0-9.]+)").matcher(s);
            if (m.find()) { sst = Double.parseDouble(m.group(1)); any = true; }
        } catch (Throwable ignored) { }
        if (any) at = now;
    }

    /** Last line whose first two fields are a year and a month and whose value fields are numbers (stars and other fill are skipped). */
    private static String[] lastRow(String s, int minFields) {
        String[] ls = s.split("\n");
        for (int i = ls.length - 1; i >= 0; i--) {
            String[] f = ls[i].trim().split("\\s+");
            if (f.length < minFields || !f[0].matches("\\d{4}") || !f[1].matches("\\d{1,2}")) continue;
            try { Double.parseDouble(f[f.length - 1]); } catch (Exception e) { continue; }
            if (f[f.length - 1].contains("*")) continue;
            return f;
        }
        return null;
    }

    /** The end of a big file: asks for the last bytes only; a server that sends it all is read to its end keeping just the tail. */
    private static String tail(String url, int n) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", "NetSpeedXposed/1.0");
        c.setRequestProperty("Range", "bytes=-" + n);
        int code = c.getResponseCode();
        if (code != 200 && code != 206) throw new java.io.IOException("HTTP " + code);
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) { sb.append(line).append('\n'); if (sb.length() > 4 * n) sb.delete(0, sb.length() - 2 * n); }
        }
        return sb.toString();
    }

    private static void mjo(String s) {
        String[] ls = s.split("\n");
        for (int i = ls.length - 1; i >= 0; i--) {
            String[] f = ls[i].trim().split("\\s+");
            if (f.length < 7) continue;
            try {
                int ph = Integer.parseInt(f[5]);
                double amp = Double.parseDouble(f[6]);
                if (ph < 1 || ph > 8 || amp > 50) continue;
                mjoPhase = ph; mjoAmp = amp;
                return;
            } catch (Exception ignored) { }
        }
        throw new IllegalStateException("no MJO row");
    }

    /** Sets the El Nino trend from the last ONI seasons: the change over two seasons (positive = warming). */
    static void trendFrom(String oniTxt) {
        try {
            double a = Double.NaN, b = Double.NaN;
            int n = 0;
            String[] ls = oniTxt.split("\n");
            for (int i = ls.length - 1; i >= 0 && n < 3; i--) {
                String[] f = ls[i].trim().split("\\s+");
                if (f.length < 4 || !f[0].matches("[A-Z]{3}") || !f[1].matches("\\d{4}")) continue;
                double v = Double.parseDouble(f[3]);
                if (n == 0) a = v;
                n++;
                b = v;
            }
            if (n == 3) trend = a - b;
        } catch (Throwable ignored) { }
    }

    static final String[] MON = {"", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    /** A pattern index: "positive" / "negative" / "neutral" around +-0.4 (DMI) or +-0.5 (the others). */
    static String sign(double v, double th, String pos, String neg, String neu) { return v >= th ? pos : v <= -th ? neg : neu; }

    static String mjoWhere() {
        switch (mjoPhase) {
            case 2: case 3: return "Indian Ocean";
            case 4: case 5: return "Maritime Continent";
            case 6: case 7: return "W Pacific";
            default: return "Africa / W Hemisphere";
        }
    }

    /** Arabian Sea (15N 68E) usual surface temperature by month, deg C; rough. */
    private static final double[] NORM = {0, 26.6, 26.6, 27.8, 29.2, 30.0, 28.8, 27.6, 27.2, 27.5, 28.2, 28.0, 27.0};
    static double sstAnom() { return Double.isNaN(sst) ? Double.NaN : sst - NORM[Calendar.getInstance().get(Calendar.MONTH) + 1]; }

    /** {season, extra} for Pune / western India, by calendar date (approximate; the real onset and withdrawal move from year to year). */
    static String[] monsoon() {
        Calendar c = Calendar.getInstance();
        int m = c.get(Calendar.MONTH) + 1, d = c.get(Calendar.DAY_OF_MONTH), doy = c.get(Calendar.DAY_OF_YEAR);
        Calendar j1 = (Calendar) c.clone(); j1.set(c.get(Calendar.MONTH) >= 5 ? c.get(Calendar.YEAR) : c.get(Calendar.YEAR), Calendar.JUNE, 1);
        int jun1 = j1.get(Calendar.DAY_OF_YEAR);
        if (m >= 6 && m <= 9) return new String[]{"SW monsoon", "day " + (doy - jun1 + 1), "Pune rains"};
        if (m == 10 && d < 15) return new String[]{"Monsoon withdrawing", "", "SW monsoon retreating"};
        if (m >= 10 && m <= 12) {
            Calendar o = (Calendar) c.clone(); o.set(Calendar.MONTH, Calendar.OCTOBER); o.set(Calendar.DAY_OF_MONTH, 15);
            return new String[]{"NE monsoon", "day " + (doy - o.get(Calendar.DAY_OF_YEAR) + 1), "SE India rains, Pune dry"};
        }
        if (m <= 2) return new String[]{"Winter", "", "dry season"};
        int daysTo = jun1 + 9 - doy;
        return new String[]{"Pre-monsoon", daysTo > 0 ? "onset ~" + daysTo + "d" : "", "hot, dry"};
    }

    static String fmt(double v) { return String.format(Locale.US, "%+.1f", v).replace('-', '−'); }
}
