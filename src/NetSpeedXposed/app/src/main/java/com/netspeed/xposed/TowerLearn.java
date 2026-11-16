package com.netspeed.xposed;

import android.content.Context;
import android.location.Location;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

/**
 * Self-learning tower map, for cells no database knows (mostly 5G). While you move, it
 * records your position and the signal strength (RSRP) of the cell each SIM is on. Once a
 * cell has enough readings spread over an area, it estimates where the tower is by fitting
 * the usual signal-loss curve (signal falls ~30 dB per 10× distance), and the distance to
 * that estimate is shown with "~". Everything stays on the phone.
 */
final class TowerLearn {
    private static final String TAG = "NetSpeedXposed: [learn] ";
    private static final int MAX_SAMPLES = 150;
    private static final int MIN_SAMPLES = 8;
    /** Readings must cover at least this much ground (bounding box diagonal). */
    private static final double MIN_SPREAD_M = 300;
    private static final double MIN_MOVE_M = 40;

    /** Per cell key: samples {lat, lon, rsrp}. */
    private static final Map<String, List<double[]>> SAMPLES = new HashMap<>();
    /** Per cell key: estimated tower {lat, lon} (null = not enough data yet). */
    private static final Map<String, double[]> ESTIMATE = new HashMap<>();
    private static boolean sLoaded = false;
    private static long sLastSave = 0;

    private TowerLearn() {}

    /** Adds a reading for this cell if you've moved since the last one. */
    static void sample(Context ctx, String key, int rsrp) {
        if (key == null || rsrp < -150 || rsrp > -30) return;
        Location me = TowerDb.freshLocation(ctx, 30_000, 60);
        if (me == null) return;
        load(ctx);
        boolean changed = false;
        synchronized (SAMPLES) {
            List<double[]> list = SAMPLES.get(key);
            if (list == null) {
                list = new ArrayList<>();
                SAMPLES.put(key, list);
            }
            if (!list.isEmpty()) {
                double[] last = list.get(list.size() - 1);
                float[] d = new float[1];
                Location.distanceBetween(last[0], last[1], me.getLatitude(), me.getLongitude(), d);
                if (d[0] < MIN_MOVE_M) return;
            }
            list.add(new double[]{me.getLatitude(), me.getLongitude(), rsrp});
            if (list.size() > MAX_SAMPLES) list.remove(0);
            changed = true;
            double[] est = estimate(list);
            if (est != null && ESTIMATE.get(key) == null) {
                XposedBridge.log(TAG + key + " tower position learned from " + list.size() + " readings");
            }
            ESTIMATE.put(key, est);
        }
        if (changed && System.currentTimeMillis() - sLastSave > 60_000) save(ctx);
    }

    /**
     * Learn from every cell this SIM hears, not only the one it's on. Cells with a full ID use the normal
     * key; neighbours usually report only their channel + PCI, so those use "lte~<earfcn>:<pci>" /
     * "nr~<arfcn>:<pci>" (unique within an area), which the serving cell is also filed under.
     */
    static void sampleCells(Context ctx, java.util.List<android.telephony.CellInfo> cells) {
        if (cells == null) return;
        for (android.telephony.CellInfo ci : cells) {
            try {
                if (ci instanceof android.telephony.CellInfoLte) {
                    android.telephony.CellIdentityLte id = ((android.telephony.CellInfoLte) ci).getCellIdentity();
                    int rsrp = ((android.telephony.CellInfoLte) ci).getCellSignalStrength().getRsrp();
                    if (rsrp == Integer.MAX_VALUE) continue;
                    String full = TowerDb.key("lte", id.getMccString(), id.getMncString(), id.getTac(), id.getCi());
                    if (full != null) sample(ctx, full, rsrp);
                    if (id.getEarfcn() != Integer.MAX_VALUE && id.getPci() != Integer.MAX_VALUE)
                        sample(ctx, "lte~" + id.getEarfcn() + ":" + id.getPci(), rsrp);
                } else if (ci instanceof android.telephony.CellInfoNr) {
                    android.telephony.CellIdentityNr id = (android.telephony.CellIdentityNr) ((android.telephony.CellInfoNr) ci).getCellIdentity();
                    android.telephony.CellSignalStrengthNr ss = (android.telephony.CellSignalStrengthNr) ((android.telephony.CellInfoNr) ci).getCellSignalStrength();
                    int rsrp = ss.getSsRsrp();
                    if (rsrp == Integer.MAX_VALUE) continue;
                    String full = TowerDb.key("nr", id.getMccString(), id.getMncString(), id.getTac(), id.getNci());
                    if (full != null) sample(ctx, full, rsrp);
                    if (id.getNrarfcn() != Integer.MAX_VALUE && id.getPci() != Integer.MAX_VALUE)
                        sample(ctx, "nr~" + id.getNrarfcn() + ":" + id.getPci(), rsrp);
                }
            } catch (Throwable ignored) { }
        }
    }

    /** Distance from the phone to this cell's learned tower, or -1 if not learned yet. */
    /**
     * Rough tower area while the fit has too little data: the average of the spots where this cell was
     * strongest (top third of readings, at least 2). Null with no readings.
     */
    static double[] roughArea(Context ctx, String key) {
        if (key == null) return null;
        load(ctx);
        List<double[]> s;
        synchronized (SAMPLES) {
            List<double[]> l = SAMPLES.get(key);
            if (l == null || l.size() < 2) return null;
            s = new java.util.ArrayList<>(l);
        }
        s.sort((a, b) -> Double.compare(b[2], a[2]));          // strongest first
        int n = Math.max(2, s.size() / 3);
        double la = 0, lo = 0;
        for (int i = 0; i < n; i++) { la += s.get(i)[0]; lo += s.get(i)[1]; }
        return new double[]{la / n, lo / n};
    }

    /** Self-learned tower position {lat, lon}, or null while there isn't enough data. */
    static double[] position(Context ctx, String key) {
        if (key == null) return null;
        load(ctx);
        synchronized (SAMPLES) {
            double[] e = ESTIMATE.get(key);
            return e == null ? null : new double[]{e[0], e[1]};
        }
    }

    static int distance(Context ctx, String key) {
        if (key == null) return -1;
        load(ctx);
        double[] est;
        synchronized (SAMPLES) {
            est = ESTIMATE.get(key);
        }
        if (est == null) return -1;
        Location me = TowerDb.myLocation(ctx);
        if (me == null) return -1;
        float[] d = new float[1];
        Location.distanceBetween(me.getLatitude(), me.getLongitude(), est[0], est[1], d);
        return Math.round(d[0]);
    }

    /**
     * Grid search for the tower position that best explains the readings with
     * rsrp = A - 30·log10(distance), solving A in closed form for each candidate.
     */
    static double[] estimate(List<double[]> s) {
        if (s.size() < MIN_SAMPLES) return null;
        double lat0 = 0, lon0 = 0;
        for (double[] p : s) {
            lat0 += p[0];
            lon0 += p[1];
        }
        lat0 /= s.size();
        lon0 /= s.size();
        double mLat = 111_320, mLon = 111_320 * Math.cos(Math.toRadians(lat0));
        int n = s.size();
        double[] x = new double[n], y = new double[n], r = new double[n];
        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        for (int i = 0; i < n; i++) {
            x[i] = (s.get(i)[1] - lon0) * mLon;
            y[i] = (s.get(i)[0] - lat0) * mLat;
            r[i] = s.get(i)[2];
            minX = Math.min(minX, x[i]); maxX = Math.max(maxX, x[i]);
            minY = Math.min(minY, y[i]); maxY = Math.max(maxY, y[i]);
        }
        if (Math.hypot(maxX - minX, maxY - minY) < MIN_SPREAD_M) return null;

        // coarse grid for three signal-loss slopes (20 / 30 / 40 dB per decade), then a fine pass around the winner
        double bestErr = Double.MAX_VALUE, bx = 0, by = 0, bk = 30;
        double span = 2_500;
        for (double k : new double[]{20, 30, 40}) {
            for (double gx = minX - span; gx <= maxX + span; gx += 100) {
                for (double gy = minY - span; gy <= maxY + span; gy += 100) {
                    double e = fitErr(x, y, r, gx, gy, k);
                    if (e < bestErr) { bestErr = e; bx = gx; by = gy; bk = k; }
                }
            }
        }
        double cx = bx, cy = by;
        for (double gx = cx - 100; gx <= cx + 100; gx += 10) {
            for (double gy = cy - 100; gy <= cy + 100; gy += 10) {
                double e = fitErr(x, y, r, gx, gy, bk);
                if (e < bestErr) { bestErr = e; bx = gx; by = gy; }
            }
        }
        double rms = Math.sqrt(bestErr / n);   // dB: how well the signal-loss curve explains the readings
        return new double[]{lat0 + by / mLat, lon0 + bx / mLon, rms};
    }

    /** Sum of squared dB errors for a tower at (gx, gy) with k dB per decade (level A solved in closed form). */
    private static double fitErr(double[] x, double[] y, double[] r, double gx, double gy, double k) {
        int n = x.length;
        double sum = 0;
        double[] l = new double[n];
        for (int i = 0; i < n; i++) {
            l[i] = k * Math.log10(Math.max(20, Math.hypot(x[i] - gx, y[i] - gy)));
            sum += r[i] + l[i];
        }
        double a = sum / n, err = 0;
        for (int i = 0; i < n; i++) {
            double e = r[i] - (a - l[i]);
            err += e * e;
        }
        return err;
    }

    /** A learned position good enough to trust over a database entry: 15+ readings and a tight fit (under 7 dB). */
    static boolean confident(Context ctx, String key) {
        if (key == null) return false;
        load(ctx);
        synchronized (SAMPLES) {
            double[] e = ESTIMATE.get(key);
            List<double[]> l = SAMPLES.get(key);
            return e != null && e.length > 2 && e[2] <= 7 && l != null && l.size() >= 15;
        }
    }

    // ------------------------------------------------------------ disk

    private static File file(Context ctx) {
        return new File(ctx.getCacheDir(), "netspeed_learn.txt");
    }

    private static void load(Context ctx) {
        synchronized (SAMPLES) {
            if (sLoaded) return;
            sLoaded = true;
            try (BufferedReader rd = new BufferedReader(new FileReader(file(ctx)))) {
                String line;
                while ((line = rd.readLine()) != null) {
                    String[] p = line.split(" ");
                    if (p.length != 4) continue;
                    List<double[]> list = SAMPLES.get(p[0]);
                    if (list == null) {
                        list = new ArrayList<>();
                        SAMPLES.put(p[0], list);
                    }
                    list.add(new double[]{Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                            Double.parseDouble(p[3])});
                }
            } catch (Throwable ignored) { }
            for (Map.Entry<String, List<double[]>> e : SAMPLES.entrySet()) {
                ESTIMATE.put(e.getKey(), estimate(e.getValue()));
            }
        }
    }

    private static void save(Context ctx) {
        synchronized (SAMPLES) {
            sLastSave = System.currentTimeMillis();
            try (FileWriter w = new FileWriter(file(ctx))) {
                for (Map.Entry<String, List<double[]>> e : SAMPLES.entrySet()) {
                    for (double[] v : e.getValue()) {
                        w.write(e.getKey() + " " + v[0] + " " + v[1] + " " + (int) v[2] + "\n");
                    }
                }
            } catch (Throwable ignored) { }
        }
    }
}
