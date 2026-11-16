package com.netspeed.xposed;

import android.content.Context;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellIdentityNr;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot for the status bar's tower map: you, the towers your SIMs are on and the neighbouring cells whose
 * position is known. Built on the worker thread (TowerArea.refresh); the status bar only reads it.
 */
final class TowerMap {
    private TowerMap() {}

    static final class Pt {
        double lat, lon;
        int rsrp = Integer.MIN_VALUE;
        int kind;          // 0 neighbour, 1 serving SIM1, 2 serving SIM2
        String band;       // serving only
        boolean star;      // strongest signal
        int slot;
        boolean rough;     // position not known: drawn dashed at a fixed spot
    }

    static final class Snap {
        double meLat, meLon;
        final List<Pt> pts = new ArrayList<>();
        int cells;          // cells heard (including ones without a known position)
        long at;
    }

    static volatile Snap SNAP;

    /** Worker thread. servingPos: per SIM {lat, lon, slot, rough?1:0}. */
    static void build(Context ctx, List<double[]> serving, List<String> bands) {
        try {
            android.location.Location me = TowerDb.myLocation(ctx);
            if (me == null) { SNAP = null; return; }
            Snap s = new Snap();
            s.meLat = me.getLatitude();
            s.meLon = me.getLongitude();
            s.at = System.currentTimeMillis();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < serving.size(); i++) {
                double[] p = serving.get(i);
                Pt t = new Pt();
                t.lat = p[0]; t.lon = p[1]; t.slot = (int) p[2];
                t.kind = t.slot <= 1 ? 1 : 2;
                t.band = bands.get(i);
                t.rough = p[3] > 0;
                if (!t.rough) seen.add(String.format(java.util.Locale.US, "%.4f,%.4f", t.lat, t.lon));
                s.pts.add(t);
            }
            int heard = 0;
            for (List<CellInfo> list : Bands.allFresh()) {
                for (CellInfo ci : list) {
                    heard++;
                    try {
                        String key = null, alias = null;
                        int rsrp = Integer.MIN_VALUE;
                        if (ci instanceof CellInfoLte) {
                            android.telephony.CellIdentityLte id = ((CellInfoLte) ci).getCellIdentity();
                            key = TowerDb.key("lte", id.getMccString(), id.getMncString(), id.getTac(), id.getCi());
                            if (id.getPci() >= 0 && id.getEarfcn() >= 0) alias = "lte~" + id.getEarfcn() + ":" + id.getPci();
                            rsrp = ((CellInfoLte) ci).getCellSignalStrength().getRsrp();
                        } else if (ci instanceof CellInfoNr) {
                            CellIdentityNr id = (CellIdentityNr) ((CellInfoNr) ci).getCellIdentity();
                            key = TowerDb.key("nr", id.getMccString(), id.getMncString(), id.getTac(), id.getNci());
                            if (id.getPci() >= 0 && id.getNrarfcn() >= 0) alias = "nr~" + id.getNrarfcn() + ":" + id.getPci();
                            rsrp = ((android.telephony.CellSignalStrengthNr) ((CellInfoNr) ci).getCellSignalStrength()).getSsRsrp();
                        } else continue;
                        double[] p = pos(ctx, key);
                        if (p == null) p = pos(ctx, alias);
                        if (p == null) continue;
                        String k = String.format(java.util.Locale.US, "%.4f,%.4f", p[0], p[1]);
                        if (!seen.add(k)) {
                            // same site as one already drawn: keep the strongest signal on it
                            for (Pt o : s.pts) {
                                if (String.format(java.util.Locale.US, "%.4f,%.4f", o.lat, o.lon).equals(k) && rsrp > o.rsrp && rsrp < 0) o.rsrp = rsrp;
                            }
                            continue;
                        }
                        double[] dd = dist(s.meLat, s.meLon, p[0], p[1]);
                        if (dd[0] > 6000) continue;   // a far guess is more likely wrong than useful
                        Pt t = new Pt();
                        t.lat = p[0]; t.lon = p[1]; t.rsrp = rsrp;
                        s.pts.add(t);
                    } catch (Throwable ignored) { }
                }
            }
            s.cells = heard;
            Pt best = null;
            for (Pt t : s.pts) if (t.rsrp < 0 && t.rsrp > -150 && (best == null || t.rsrp > best.rsrp)) best = t;
            if (best != null && best.kind == 0) best.star = true;
            SNAP = s;
        } catch (Throwable t) {
            SNAP = null;
        }
    }

    private static double[] pos(Context ctx, String key) {
        if (key == null) return null;
        double[] p = TowerLearn.confident(ctx, key) ? TowerLearn.position(ctx, key) : null;
        if (p == null) p = TowerDb.position(ctx, key);
        if (p == null) p = TowerLearn.position(ctx, key);
        return p;
    }

    /** {metres, bearing degrees} from a to b. */
    static double[] dist(double la1, double lo1, double la2, double lo2) {
        float[] r = new float[2];
        android.location.Location.distanceBetween(la1, lo1, la2, lo2, r);
        return new double[]{r[0], (r[1] + 360) % 360};
    }
}
