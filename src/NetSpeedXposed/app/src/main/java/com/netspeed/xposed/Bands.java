package com.netspeed.xposed;

import android.content.Context;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * Which band each SIM is on right now ("B3", "n78"), read inside System UI.
 * Only the main (serving) cell's band is reported; for 4G+ that is the primary band.
 */
final class Bands {
    private static final String TAG = "NetSpeedXposed: [band] ";
    private static boolean sLoggedError = false;

    /** No signal value. */
    static final int NO_DBM = Integer.MAX_VALUE;

    /** One SIM: slot (1 or 2), band label (null if unknown) and RSRP in dBm (NO_DBM if unknown). */
    static final class Sim {
        final int slot;
        final String band;
        final int dbm;
        /** Rough distance to the tower in metres from timing advance, or -1 if unknown. */
        final int distM;
        /** True when distM is an estimate from the tower database, not timing advance. */
        final boolean approx;
        /** True when distM is only a guess from signal strength (roughest; shown with ≈). */
        boolean guess = false;
        /** How many cells this SIM hears (serving + neighbours), or -1 if unknown. */
        int cells = -1;
        /** Subscription id (for the voice badge). */
        int sub = -1;

        // Cell details (CellDetail.fill). NONE / null / -1 = not reported.
        int level = -1;
        boolean inService = false;
        boolean dataOn = true;   // this SIM's own mobile-data switch (true when unknown)
        String net;
        float snr = Float.NaN;
        int rsrq = CellDetail.NONE, rssi = CellDetail.NONE, cqi = CellDetail.NONE, ta = CellDetail.NONE;
        int pci = -1, arfcn = -1, tac = -1;
        String site, mccMnc, operator;
        long dataToday = -1;
        boolean inCall = false;
        boolean roaming = false;
        /** Network the SIM is on right now: code ("40469") and broadcast name ("Dolphin"); SIM's own code and name. */
        String netNumeric, netName, simNumeric, simName;
        /** "SA", "NSA·B3" (5G on a 4G anchor), or null (no 5G). */
        String nrMode;
        /** Carrier aggregation: "B8+B3", "n78+n28", "B40+1" (count only), or null for one carrier. */
        String caBands;

        Sim(int slot, String band, int dbm, int distM, boolean approx) {
            this.slot = slot;
            this.band = band;
            this.dbm = dbm;
            this.distM = distM;
            this.approx = approx;
        }
    }

    private Bands() {}

    /** Active SIMs in slot order, each with its band (null when not known). */
    static List<Sim> read(Context ctx) {
        List<Sim> out = new ArrayList<>();
        try {
            SubscriptionManager sm = ctx.getSystemService(SubscriptionManager.class);
            TelephonyManager base = ctx.getSystemService(TelephonyManager.class);
            if (sm == null || base == null) return out;
            List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
            if (subs == null) return out;
            List<SubscriptionInfo> sorted = new ArrayList<>(subs);
            Collections.sort(sorted, (a, b) -> a.getSimSlotIndex() - b.getSimSlotIndex());
            for (SubscriptionInfo si : sorted) {
                String band = null;
                int dbm = NO_DBM;
                int dist = -1;
                boolean approx = false;
                try {
                    TelephonyManager tm = base.createForSubscriptionId(si.getSubscriptionId());
                    // 1) This SIM's own registration (data first, then voice).
                    band = fromServiceState(tm.getServiceState());
                    // 2) Cell list (covers all SIMs): only cells of this SIM's operator.
                    if (band == null) band = fromCells(freshCells(tm, si.getSubscriptionId()), si);
                    android.telephony.SignalStrength ss = tm.getSignalStrength();
                    dbm = rsrp(ss, band);
                    dist = distance(ss, band);
                    // Fallback: this SIM's tower entry in the cell list (some modems only
                    // report timing advance there).
                    if (dist < 0) {
                        List<CellInfo> cells = freshCells(tm, si.getSubscriptionId());
                        dist = distanceFromCells(cells, si, band, sorted);
                        logRawOnce(si.getSimSlotIndex() + 1, band, ss, cells, si);
                    }
                    // Exact distance only exists while the SIM is actively connected. Keep the
                    // last one for a minute after it goes idle (same band), marked with ~.
                    {
                        int sub = si.getSubscriptionId();
                        long now = android.os.SystemClock.elapsedRealtime();
                        synchronized (LAST_TA) {
                            if (dist >= 0) {
                                LAST_TA.put(sub, new Object[]{dist, now, band});
                            } else {
                                Object[] h = LAST_TA.get(sub);
                                if (h != null && now - (Long) h[1] <= 60_000
                                        && band != null && band.equals(h[2])) {
                                    dist = (Integer) h[0];
                                    approx = true;
                                }
                            }
                        }
                    }
                    // Still unknown (idle SIM, 5G): tower position from the database + location.
                    boolean useDb = Cfg.b(Cfg.TOWER_DB, Cfg.D_TOWER_DB), learn = Cfg.b(Cfg.LEARN, Cfg.D_LEARN);
                    if (dist < 0 && (useDb || learn)) {
                        String key = towerKey(tm.getServiceState());
                        // Idle SIMs often leave the tower ID out of their registration info;
                        // take it from this SIM's entry in the cell list instead.
                        if (key == null) key = towerKeyFromCells(freshCells(tm, si.getSubscriptionId()), si, band, sorted);
                        // Not visible now (e.g. the other SIM is on 5G standalone): reuse the
                        // tower this SIM was last seen on, for up to 10 minutes.
                        int sub = si.getSubscriptionId();
                        long now = android.os.SystemClock.elapsedRealtime();
                        boolean keyNow = key != null;
                        if (key != null) {
                            synchronized (LAST_KEY) {
                                LAST_KEY.put(sub, key);
                                LAST_KEY_AT.put(sub, now);
                            }
                        } else {
                            synchronized (LAST_KEY) {
                                Long at = LAST_KEY_AT.get(sub);
                                if (at != null && now - at <= 10 * 60_000) {
                                    key = LAST_KEY.get(sub);
                                    logOnce("reuse" + sub + key, "SIM" + (si.getSimSlotIndex() + 1)
                                            + " using last seen tower " + key);
                                }
                            }
                        }
                        if (key == null) logOnce("nokey" + si.getSimSlotIndex(),
                                "SIM" + (si.getSimSlotIndex() + 1) + " tower ID not available");
                        int d = -1;
                        if (learn) {
                            // Learn only from the tower seen right now, not a remembered one.
                            if (keyNow) TowerLearn.sample(ctx, key, dbm);
                            // a well-fitted self-learned position beats the (often km-off) database entry
                            if (TowerLearn.confident(ctx, key)) d = TowerLearn.distance(ctx, key);
                        }
                        if (d < 0 && useDb) d = TowerDb.distance(ctx, key);
                        if (d < 0 && learn) d = TowerLearn.distance(ctx, key);
                        if (d >= 0) {
                            dist = d;
                            approx = true;
                        }
                    }
                } catch (Throwable t) {
                    if (!sLoggedError) {
                        sLoggedError = true;
                        XposedBridge.log(TAG + "reading cells failed: " + t);
                    }
                }
                // Last resort: a rough guess from signal strength, so there's always a number.
                boolean guess = false;
                if (dist < 0 && valid(dbm)) {
                    dist = signalDistance(dbm, band);
                    approx = true;
                    guess = true;
                }
                Sim sim = new Sim(si.getSimSlotIndex() + 1, band, dbm, dist, approx);
                sim.guess = guess;
                sim.cells = towerCount(si.getSubscriptionId());
                sim.sub = si.getSubscriptionId();
                try {
                    TelephonyManager tm = base.createForSubscriptionId(si.getSubscriptionId());
                    // roaming first, so a failure in the cell details below can't hide it; ask the network
                    // state as well as the TelephonyManager shortcut (some ROMs only fill one of them)
                    boolean rm = false;
                    try { rm = tm.isNetworkRoaming(); } catch (Throwable ignored) { }
                    try {
                        android.telephony.ServiceState ss = tm.getServiceState();
                        if (ss != null && ss.getRoaming()) rm = true;
                    } catch (Throwable ignored) { }
                    try {
                        if (android.telephony.SubscriptionManager.from(ctx).isNetworkRoaming(si.getSubscriptionId())) rm = true;
                    } catch (Throwable ignored) { }
                    sim.roaming = rm;
                    try {
                        android.telephony.ServiceState ss2 = tm.getServiceState();
                        if (ss2 != null) {
                            sim.netNumeric = ss2.getOperatorNumeric();
                            String ln = ss2.getOperatorAlphaLong(), sn = ss2.getOperatorAlphaShort();
                            sim.netName = ln != null && !ln.trim().isEmpty() ? ln.trim() : sn != null ? sn.trim() : null;
                        }
                    } catch (Throwable ignored) { }
                    try { if (sim.netNumeric == null || sim.netNumeric.isEmpty()) sim.netNumeric = tm.getNetworkOperator(); } catch (Throwable ignored) { }
                    try { if (sim.netName == null || sim.netName.isEmpty()) sim.netName = tm.getNetworkOperatorName(); } catch (Throwable ignored) { }
                    try { sim.simNumeric = tm.getSimOperator(); sim.simName = tm.getSimOperatorName(); } catch (Throwable ignored) { }
                    try { sim.nrMode = nrMode(tm.getServiceState()); } catch (Throwable ignored) { }
                    try { sim.dataOn = tm.isDataEnabled(); } catch (Throwable ignored) { }
                    CellDetail.fill(ctx, sim, tm, si, freshCells(tm, si.getSubscriptionId()), sorted);
                    // registration briefly reports no mode (e.g. just after a 5G fallback ends) while the serving band is an NR one: that is standalone
                    if (sim.nrMode == null && sim.band != null && sim.band.startsWith("n") && sim.net != null && sim.net.startsWith("5G")) sim.nrMode = "SA";
                    // the SIM that is not carrying data reports no NR state, yet the phone still shows 5G on an LTE band: that is NSA
                    if (sim.nrMode == null && sim.band != null && sim.band.startsWith("B") && sim.net != null && sim.net.startsWith("5G")) sim.nrMode = "NSA";
                    if (Cfg.b(Cfg.LEARN, Cfg.D_LEARN)) TowerLearn.sampleCells(ctx, freshCells(tm, si.getSubscriptionId()));
                } catch (Throwable t) {
                    logOnce("detailfail", "cell details failed: " + t);
                }
                out.add(sim);
            }
        } catch (Throwable t) {
            if (!sLoggedError) {
                sLoggedError = true;
                XposedBridge.log(TAG + "reading SIMs failed: " + t);
            }
        }
        return out;
    }

    /**
     * RSRP (dBm) for this SIM: the 5G value when the band is 5G, the 4G value when it is 4G,
     * otherwise any valid signal value.
     */
    static int rsrp(android.telephony.SignalStrength ss, String band) {
        if (ss == null) return NO_DBM;
        boolean nr = band != null && band.startsWith("n");
        boolean lte = band != null && band.startsWith("B");
        int any = NO_DBM;
        for (android.telephony.CellSignalStrength cs : ss.getCellSignalStrengths()) {
            int v = NO_DBM;
            if (cs instanceof android.telephony.CellSignalStrengthNr) {
                v = ((android.telephony.CellSignalStrengthNr) cs).getSsRsrp();
                if (!valid(v)) v = ((android.telephony.CellSignalStrengthNr) cs).getCsiRsrp();
                if (nr && valid(v)) return v;
            } else if (cs instanceof android.telephony.CellSignalStrengthLte) {
                v = ((android.telephony.CellSignalStrengthLte) cs).getRsrp();
                if (lte && valid(v)) return v;
            }
            if (!valid(v)) v = cs.getDbm();
            if (any == NO_DBM && valid(v)) any = v;
        }
        return any;
    }

    /**
     * Distance to the serving tower from timing advance. 4G: one step is about 78 m.
     * 5G: only when Android exposes it (getTimingAdvanceMicros, newer versions), in
     * microseconds of round trip, about 150 m each. Returns -1 when not reported.
     */
    static int distance(android.telephony.SignalStrength ss, String band) {
        if (ss == null || band == null) return -1;
        boolean nr = band.startsWith("n");
        for (android.telephony.CellSignalStrength cs : ss.getCellSignalStrengths()) {
            if (!nr && cs instanceof android.telephony.CellSignalStrengthLte) {
                int ta = ((android.telephony.CellSignalStrengthLte) cs).getTimingAdvance();
                if (ta != CellInfo.UNAVAILABLE && ta > 0 && ta <= 1282) return ta * 78;
            } else if (nr && cs instanceof android.telephony.CellSignalStrengthNr) {
                try {
                    Object v = cs.getClass().getMethod("getTimingAdvanceMicros").invoke(cs);
                    if (v instanceof Integer) {
                        int us = (Integer) v;
                        if (us != CellInfo.UNAVAILABLE && us > 0 && us < 2000) return us * 150;
                    }
                } catch (Throwable ignored) { }
            }
        }
        return -1;
    }

    /**
     * Distance guess from RSRP with a city path-loss curve (signal drops ~30 dB per 10x
     * distance; -90 dBm is about 850 m on 1800 MHz). Higher bands fade faster, so the same
     * signal means a closer tower. Rounded (100 m below 1 km, 0.5 km above) — it's a guess.
     */
    static int signalDistance(int rsrp, String band) {
        double f = bandMhz(band);
        double a = -2 - 20 * Math.log10(f / 1800.0);
        double d = Math.pow(10, (a - rsrp) / 30.0);
        d = Math.max(100, Math.min(15_000, d));
        return d < 1000 ? (int) (Math.round(d / 100) * 100) : (int) (Math.round(d / 500) * 500);
    }

    /** Downlink frequency (MHz) of an LTE "B40" / NR "n78" band; 1800 if unknown. */
    static double bandMhz(String band) {
        if (band == null || band.length() < 2) return 1800;
        int n;
        try {
            n = Integer.parseInt(band.substring(1).replaceAll("[^0-9].*", ""));
        } catch (Throwable t) {
            return 1800;
        }
        switch (n) {
            case 1: return 2100;
            case 3: return 1800;
            case 5: return 850;
            case 8: return 900;
            case 28: return 700;
            case 40: return 2300;
            case 41: return 2500;
            case 77: return 3700;
            case 78: return 3500;
            default: return 1800;
        }
    }

    private static boolean valid(int v) {
        return v != CellInfo.UNAVAILABLE && v != NO_DBM && v <= -30 && v >= -160;
    }

    /** Serving cell from this SIM's registration info: data first, then voice. */
    static String fromServiceState(android.telephony.ServiceState ss) {
        if (ss == null) return null;
        for (int domain : new int[]{android.telephony.NetworkRegistrationInfo.DOMAIN_PS,
                android.telephony.NetworkRegistrationInfo.DOMAIN_CS}) {
            for (android.telephony.NetworkRegistrationInfo nri : ss.getNetworkRegistrationInfoList()) {
                if (nri.getTransportType() != android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WWAN) continue;
                if ((nri.getDomain() & domain) == 0 || !nri.isRegistered()) continue;
                android.telephony.CellIdentity id = nri.getCellIdentity();
                String b = id instanceof CellIdentityNr ? nrBand((CellIdentityNr) id)
                        : id instanceof CellIdentityLte ? lteBand((CellIdentityLte) id) : null;
                if (b != null) return b;
            }
        }
        return null;
    }

    /** Serving cell from the combined cell list, matched to this SIM by operator code. */
    static String fromCells(List<CellInfo> cells, SubscriptionInfo si) {
        if (cells == null) return null;
        String mcc = si.getMccString(), mnc = si.getMncString();
        for (int pass = 0; pass < 2; pass++) {
            for (CellInfo ci : cells) {
                if (!ci.isRegistered()) continue;
                if (pass == 0 && ci instanceof CellInfoNr) {
                    CellIdentityNr id = (CellIdentityNr) ((CellInfoNr) ci).getCellIdentity();
                    if (sameOperator(id.getMccString(), id.getMncString(), mcc, mnc)) {
                        String b = nrBand(id);
                        if (b != null) return b;
                    }
                } else if (pass == 1 && ci instanceof CellInfoLte) {
                    CellIdentityLte id = ((CellInfoLte) ci).getCellIdentity();
                    if (sameOperator(id.getMccString(), id.getMncString(), mcc, mnc)) {
                        String b = lteBand(id);
                        if (b != null) return b;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Timing advance from this SIM's registered cell in the cell list. The cell is the one
     * whose operator code matches the SIM; if none does (operators like Vi broadcast a
     * different code than the SIM card has), the one registered cell of the same country
     * that belongs to no other SIM. A value of 0 is what idle SIMs report, so it counts as
     * unknown.
     */
    /** Database key of the tower this SIM is registered on (data first, then voice), or null. */
    static String towerKey(android.telephony.ServiceState ss) {
        if (ss == null) return null;
        for (int domain : new int[]{android.telephony.NetworkRegistrationInfo.DOMAIN_PS,
                android.telephony.NetworkRegistrationInfo.DOMAIN_CS}) {
            for (android.telephony.NetworkRegistrationInfo nri : ss.getNetworkRegistrationInfoList()) {
                if (nri.getTransportType() != android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WWAN) continue;
                if ((nri.getDomain() & domain) == 0 || !nri.isRegistered()) continue;
                android.telephony.CellIdentity id = nri.getCellIdentity();
                try {
                    if (id instanceof CellIdentityLte) {
                        CellIdentityLte l = (CellIdentityLte) id;
                        String k = TowerDb.key("lte", l.getMccString(), l.getMncString(), l.getTac(), l.getCi());
                        if (k != null) return k;
                    } else if (id instanceof CellIdentityNr) {
                        CellIdentityNr n = (CellIdentityNr) id;
                        String k = TowerDb.key("nr", n.getMccString(), n.getMncString(), n.getTac(), n.getNci());
                        if (k != null) return k;
                    }
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    /**
     * 5G mode from the data registration: "SA" when data runs on NR itself, "NSA·B3" when it runs on an LTE
     * anchor (band B3) with 5G added on top, null when there's no 5G.
     */
    static String nrMode(android.telephony.ServiceState ss) {
        if (ss == null) return null;
        for (android.telephony.NetworkRegistrationInfo nri : ss.getNetworkRegistrationInfoList()) {
            if (nri.getTransportType() != android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WWAN) continue;
            if ((nri.getDomain() & android.telephony.NetworkRegistrationInfo.DOMAIN_PS) == 0 || !nri.isRegistered()) continue;
            int tech = nri.getAccessNetworkTechnology();
            if (tech == android.telephony.TelephonyManager.NETWORK_TYPE_NR) return "SA";
            if (tech != android.telephony.TelephonyManager.NETWORK_TYPE_LTE) return null;
            boolean nr = false;
            try {
                Object st = de.robv.android.xposed.XposedHelpers.callMethod(ss, "getNrState");
                nr = st instanceof Integer && (Integer) st == 3;           // NR_STATE_CONNECTED
            } catch (Throwable ignored) { }
            if (!nr) return null;
            String b = null;
            try {
                if (nri.getCellIdentity() instanceof CellIdentityLte) b = lteBand((CellIdentityLte) nri.getCellIdentity());
            } catch (Throwable ignored) { }
            return b == null ? "NSA" : "NSA·" + b;
        }
        return null;
    }

    /** Database key of this SIM's registered cell in the cell list (same matching as below). */
    static String towerKeyFromCells(List<CellInfo> cells, SubscriptionInfo si, String band,
                                    List<SubscriptionInfo> all) {
        for (CellInfo ci : simCells(cells, si, band, all)) {
            try {
                if (ci instanceof CellInfoLte) {
                    CellIdentityLte l = ((CellInfoLte) ci).getCellIdentity();
                    String k = TowerDb.key("lte", l.getMccString(), l.getMncString(), l.getTac(), l.getCi());
                    if (k != null) return k;
                } else if (ci instanceof CellInfoNr) {
                    CellIdentityNr n = (CellIdentityNr) ((CellInfoNr) ci).getCellIdentity();
                    String k = TowerDb.key("nr", n.getMccString(), n.getMncString(), n.getTac(), n.getNci());
                    if (k != null) return k;
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /**
     * This SIM's registered cells of the band's radio type: the ones whose operator code
     * matches the SIM, or else the one same-country cell no other SIM owns.
     */
    static List<CellInfo> simCells(List<CellInfo> cells, SubscriptionInfo si, String band,
                                   List<SubscriptionInfo> all) {
        List<CellInfo> mine = new ArrayList<>(), unclaimed = new ArrayList<>();
        if (cells == null || band == null) return mine;
        boolean nr = band.startsWith("n");
        for (CellInfo ci : cells) {
            if (!ci.isRegistered()) continue;
            if (nr ? !(ci instanceof CellInfoNr) : !(ci instanceof CellInfoLte)) continue;
            String cMcc, cMnc;
            if (ci instanceof CellInfoLte) {
                CellIdentityLte id = ((CellInfoLte) ci).getCellIdentity();
                cMcc = id.getMccString();
                cMnc = id.getMncString();
            } else {
                CellIdentityNr id = (CellIdentityNr) ((CellInfoNr) ci).getCellIdentity();
                cMcc = id.getMccString();
                cMnc = id.getMncString();
            }
            if (sameOperator(cMcc, cMnc, si.getMccString(), si.getMncString())) {
                mine.add(ci);
                continue;
            }
            boolean other = false;
            for (SubscriptionInfo o : all) {
                if (o.getSubscriptionId() != si.getSubscriptionId()
                        && sameOperator(cMcc, cMnc, o.getMccString(), o.getMncString())) other = true;
            }
            if (!other && cMcc != null && cMcc.equals(si.getMccString())) unclaimed.add(ci);
        }
        return !mine.isEmpty() ? mine : unclaimed.size() == 1 ? unclaimed : mine;
    }

    private static final java.util.Set<String> sLogged = new java.util.HashSet<>();
    /** Per subscription: {distance m, time, band} of the last exact (timing advance) distance. */
    private static final java.util.Map<Integer, Object[]> LAST_TA = new java.util.HashMap<>();

    // Mobile data activity, to scan more often while data is flowing.
    private static long sLastBytes = -1, sLastBytesAt = 0;
    private static volatile boolean sDataActive = false;

    /** True while mobile data is moving (more than ~2 KB/s since the last check). */
    static boolean dataActive() {
        long now = android.os.SystemClock.elapsedRealtime();
        long b = android.net.TrafficStats.getMobileRxBytes() + android.net.TrafficStats.getMobileTxBytes();
        if (b < 0) b = android.net.TrafficStats.getTotalRxBytes() + android.net.TrafficStats.getTotalTxBytes();
        synchronized (LAST_TA) {
            if (sLastBytes >= 0 && now - sLastBytesAt >= 2_000) {
                long rate = (b - sLastBytes) * 1000 / Math.max(1, now - sLastBytesAt);
                sDataActive = rate > 2_048;
                sLastBytes = b;
                sLastBytesAt = now;
            } else if (sLastBytes < 0) {
                sLastBytes = b;
                sLastBytesAt = now;
            }
        }
        return sDataActive;
    }

    /** Fresh cell lists per SIM, from requestCellInfoUpdate (getAllCellInfo is often stale). */
    private static final java.util.Map<Integer, List<CellInfo>> FRESH = new java.util.HashMap<>();
    private static final java.util.Map<Integer, Long> FRESH_REQ = new java.util.HashMap<>();
    private static final java.util.concurrent.Executor CELL_EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /**
     * This SIM's cell list: asks the modem for a fresh scan every 30 seconds and returns
     * the newest result, falling back to Android's cached list until the first one arrives.
     */
    /** Cells in this SIM's latest fresh scan (its own modem stack only), or -1 if none yet. */
    static int cellCount(int sub) {
        synchronized (FRESH) {
            List<CellInfo> f = FRESH.get(sub);
            return f == null ? -1 : f.size();
        }
    }

    /**
     * Estimated towers (sites) among the cells this SIM hears. Operators give the (usually 3)
     * sectors of one tower physical cell ids in the same group of three (PCI / 3), so each
     * distinct group per technology counts as one tower. 2G / 3G cells count one each.
     */
    static int towerCount(int sub) {
        List<CellInfo> f;
        synchronized (FRESH) {
            f = FRESH.get(sub);
        }
        if (f == null) return -1;
        java.util.Set<String> sites = new java.util.HashSet<>();
        int i = 0;
        for (CellInfo c : f) {
            i++;
            int pci = -1;
            String tech = "x";
            try {
                if (c instanceof android.telephony.CellInfoLte) {
                    pci = ((android.telephony.CellInfoLte) c).getCellIdentity().getPci();
                    tech = "L";
                } else if (c instanceof android.telephony.CellInfoNr) {
                    pci = ((android.telephony.CellIdentityNr) ((android.telephony.CellInfoNr) c).getCellIdentity()).getPci();
                    tech = "N";
                }
            } catch (Throwable ignored) { }
            if (pci >= 0 && pci < 1008) sites.add(tech + (pci / 3));
            else sites.add("o" + i);
        }
        return sites.size();
    }

    /** Every SIM's latest fresh cell scan (for the status bar's tower map). */
    static List<List<CellInfo>> allFresh() {
        synchronized (FRESH) {
            return new ArrayList<>(FRESH.values());
        }
    }

    static List<CellInfo> freshCells(TelephonyManager tm, int sub) {
        long now = android.os.SystemClock.elapsedRealtime();
        boolean ask;
        synchronized (FRESH) {
            Long last = FRESH_REQ.get(sub);
            ask = ScreenState.on() && (last == null || now - last > (dataActive() ? 30_000 : 60_000));
            if (ask) FRESH_REQ.put(sub, now);
        }
        if (ask) {
            try {
                tm.requestCellInfoUpdate(CELL_EXEC, new TelephonyManager.CellInfoCallback() {
                    @Override
                    public void onCellInfo(List<CellInfo> cells) {
                        synchronized (FRESH) {
                            FRESH.put(sub, cells);
                        }
                    }
                });
            } catch (Throwable t) {
                logOnce("cellreq", "fresh cell scan not allowed: " + t);
            }
        }
        List<CellInfo> f;
        synchronized (FRESH) {
            f = FRESH.get(sub);
        }
        if (f != null && !f.isEmpty()) return f;
        try {
            return tm.getAllCellInfo();
        } catch (Throwable t) {
            return null;
        }
    }
    /** Last tower each SIM (by subscription id) was seen on, and when. */
    private static final java.util.Map<Integer, String> LAST_KEY = new java.util.HashMap<>();

    /** The tower ID last seen for this subscription (null if none). */
    static String lastKey(int sub) {
        synchronized (LAST_KEY) {
            return LAST_KEY.get(sub);
        }
    }
    private static final java.util.Map<Integer, Long> LAST_KEY_AT = new java.util.HashMap<>();

    private static void logOnce(String key, String msg) {
        synchronized (sLogged) {
            if (!sLogged.add(key)) return;
        }
        XposedBridge.log(TAG + msg);
    }

    static int distanceFromCells(List<CellInfo> cells, SubscriptionInfo si, String band,
                                 List<SubscriptionInfo> all) {
        if (cells == null || band == null) return -1;
        boolean nr = band.startsWith("n");
        List<CellInfo> mine = new ArrayList<>(), unclaimed = new ArrayList<>();
        for (CellInfo ci : cells) {
            if (!ci.isRegistered()) continue;
            if (nr ? !(ci instanceof CellInfoNr) : !(ci instanceof CellInfoLte)) continue;
            String cMcc, cMnc;
            if (ci instanceof CellInfoLte) {
                CellIdentityLte id = ((CellInfoLte) ci).getCellIdentity();
                cMcc = id.getMccString();
                cMnc = id.getMncString();
            } else {
                CellIdentityNr id = (CellIdentityNr) ((CellInfoNr) ci).getCellIdentity();
                cMcc = id.getMccString();
                cMnc = id.getMncString();
            }
            if (sameOperator(cMcc, cMnc, si.getMccString(), si.getMncString())) {
                mine.add(ci);
                continue;
            }
            boolean other = false;
            for (SubscriptionInfo o : all) {
                if (o.getSubscriptionId() != si.getSubscriptionId()
                        && sameOperator(cMcc, cMnc, o.getMccString(), o.getMncString())) other = true;
            }
            if (!other && cMcc != null && cMcc.equals(si.getMccString())) unclaimed.add(ci);
        }
        List<CellInfo> use = !mine.isEmpty() ? mine : unclaimed.size() == 1 ? unclaimed : mine;
        for (CellInfo ci : use) {
            if (ci instanceof CellInfoLte) {
                int ta = ((CellInfoLte) ci).getCellSignalStrength().getTimingAdvance();
                if (ta != CellInfo.UNAVAILABLE && ta > 0 && ta <= 1282) return ta * 78;
            } else if (ci instanceof CellInfoNr) {
                try {
                    Object cs = ((CellInfoNr) ci).getCellSignalStrength();
                    Object v = cs.getClass().getMethod("getTimingAdvanceMicros").invoke(cs);
                    if (v instanceof Integer) {
                        int us = (Integer) v;
                        if (us != CellInfo.UNAVAILABLE && us > 0 && us < 2000) return us * 150;
                    }
                } catch (Throwable ignored) { }
            }
        }
        return -1;
    }

    private static final java.util.Set<String> sRawLogged = new java.util.HashSet<>();

    /** Logs the raw timing-advance values once per SIM + band, to see what the modem reports. */
    private static void logRawOnce(int slot, String band, android.telephony.SignalStrength ss,
                                   List<CellInfo> cells, SubscriptionInfo si) {
        String key = slot + ":" + band;
        synchronized (sRawLogged) {
            if (!sRawLogged.add(key)) return;
        }
        StringBuilder sb = new StringBuilder("SIM" + slot + " " + band + " raw TA:");
        try {
            if (ss != null) for (android.telephony.CellSignalStrength cs : ss.getCellSignalStrengths()) {
                if (cs instanceof android.telephony.CellSignalStrengthLte) {
                    sb.append(" signal.lte=").append(((android.telephony.CellSignalStrengthLte) cs).getTimingAdvance());
                } else {
                    sb.append(" signal.").append(cs.getClass().getSimpleName());
                }
            }
            int n = 0;
            if (cells != null) for (CellInfo ci : cells) {
                if (!ci.isRegistered()) continue;
                n++;
                if (ci instanceof CellInfoLte) {
                    CellIdentityLte id = ((CellInfoLte) ci).getCellIdentity();
                    sb.append(" cell.lte[").append(id.getMccString()).append('-').append(id.getMncString())
                            .append("]=").append(((CellInfoLte) ci).getCellSignalStrength().getTimingAdvance());
                } else {
                    sb.append(" cell.").append(ci.getClass().getSimpleName());
                }
            }
            sb.append(" (registered cells ").append(n).append(", SIM ")
                    .append(si.getMccString()).append('-').append(si.getMncString()).append(')');
        } catch (Throwable t) {
            sb.append(" error ").append(t);
        }
        XposedBridge.log(TAG + sb);
    }

    private static boolean sameOperator(String cMcc, String cMnc, String mcc, String mnc) {
        if (cMcc == null || cMnc == null || mcc == null || mnc == null) return false;
        try {
            if (Integer.parseInt(cMcc) == Integer.parseInt(mcc) && Integer.parseInt(cMnc) == Integer.parseInt(mnc)) return true;
        } catch (NumberFormatException e) {
            if (cMcc.equals(mcc) && cMnc.equals(mnc)) return true;
        }
        // same operator, same circle, different code (Vi SIM 404-27 on Vi's 404-22 network)
        String[] a = Circles.of(cMcc + cMnc), b = Circles.of(mcc + mnc);
        return a != null && b != null && a[0].equals(b[0]) && a[1].equals(b[1]);
    }

    static String lteBand(CellIdentityLte id) {
        try {
            int[] b = id.getBands();
            if (b != null && b.length > 0 && b[0] > 0) return "B" + b[0];
        } catch (Throwable ignored) { }
        int band = lteBandFromEarfcn(id.getEarfcn());
        return band > 0 ? "B" + band : null;
    }

    static String nrBand(CellIdentityNr id) {
        try {
            int[] b = id.getBands();
            if (b != null && b.length > 0 && b[0] > 0) return "n" + b[0];
        } catch (Throwable ignored) { }
        int band = nrBandFromArfcn(id.getNrarfcn());
        return band > 0 ? "n" + band : null;
    }

    /** Downlink EARFCN to LTE band (3GPP TS 36.101). */
    static int lteBandFromEarfcn(int e) {
        if (e < 0 || e == CellInfo.UNAVAILABLE) return -1;
        int[][] t = {
                {1, 0, 599}, {2, 600, 1199}, {3, 1200, 1949}, {4, 1950, 2399}, {5, 2400, 2649},
                {7, 2750, 3449}, {8, 3450, 3799}, {12, 5010, 5179}, {13, 5180, 5279},
                {14, 5280, 5379}, {17, 5730, 5849}, {18, 5850, 5999}, {19, 6000, 6149},
                {20, 6150, 6449}, {25, 8040, 8689}, {26, 8690, 9039}, {28, 9210, 9659},
                {29, 9660, 9769}, {30, 9770, 9869}, {32, 9920, 10359}, {34, 36200, 36349},
                {38, 37750, 38249}, {39, 38250, 38649}, {40, 38650, 39649}, {41, 39650, 41589},
                {42, 41590, 43589}, {43, 43590, 45589}, {46, 46790, 54539}, {48, 55240, 56739},
                {66, 66436, 67335}, {71, 68586, 68935},
        };
        for (int[] r : t) if (e >= r[1] && e <= r[2]) return r[0];
        return -1;
    }

    /** NR-ARFCN to the usual NR band for that frequency (3GPP TS 38.104). */
    static int nrBandFromArfcn(int a) {
        if (a < 0 || a == CellInfo.UNAVAILABLE) return -1;
        if (a >= 151600 && a <= 160600) return 28;
        if (a >= 173800 && a <= 178800) return 5;
        if (a >= 185000 && a <= 192000) return 8;
        if (a >= 361000 && a <= 376000) return 3;
        if (a >= 422000 && a <= 434000) return 1;
        if (a >= 460000 && a <= 480000) return 40;
        if (a >= 499200 && a <= 537999) return 41;
        if (a >= 620000 && a <= 653333) return 78;
        if (a >= 653334 && a <= 680000) return 77;
        if (a >= 693334 && a <= 733333) return 79;
        return -1;
    }

}
