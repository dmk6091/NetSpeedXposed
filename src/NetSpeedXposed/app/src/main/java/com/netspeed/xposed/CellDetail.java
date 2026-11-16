package com.netspeed.xposed;

import android.content.Context;
import android.telephony.CellIdentity;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Everything the modem tells about each SIM's serving cell, for the tower signal icon and the
 * row-2 cell table: signal level, network label, quality numbers and cell identity.
 * Values the modem doesn't report stay at their "unknown" defaults and show as "?".
 */
final class CellDetail {
    private static final String TAG = "NetSpeedXposed: [cell] ";
    static final int NONE = Integer.MIN_VALUE;

    private CellDetail() {}

    /** Fills sim's detail fields. Runs on the worker thread. */
    static void fill(Context ctx, Bands.Sim sim, TelephonyManager tm, SubscriptionInfo si,
                     List<CellInfo> cells, List<SubscriptionInfo> all) {
        ServiceState ss = null;
        try {
            ss = tm.getServiceState();
        } catch (Throwable ignored) { }
        boolean dataIn = false;
        try {
            Object d = ss == null ? null : XposedHelpers.callMethod(ss, "getDataRegistrationState");
            dataIn = d instanceof Integer && (Integer) d == ServiceState.STATE_IN_SERVICE;
        } catch (Throwable ignored) { }
        sim.inService = ss != null && (ss.getState() == ServiceState.STATE_IN_SERVICE || dataIn);

        // --- level 0..4 and quality numbers from this SIM's signal strength
        SignalStrength sig = null;
        try {
            sig = tm.getSignalStrength();
        } catch (Throwable ignored) { }
        if (sig != null) {
            try {
                sim.level = sig.getLevel();
            } catch (Throwable ignored) { }
            boolean nr = sim.band != null && sim.band.startsWith("n");
            for (CellSignalStrength cs : sig.getCellSignalStrengths()) {
                if (cs instanceof CellSignalStrengthLte && (!nr || sim.rsrq == NONE)) {
                    CellSignalStrengthLte l = (CellSignalStrengthLte) cs;
                    int v = l.getRssnr();
                    // Documented as 0.1 dB, but many modems report whole dB (this one does).
                    if (ok(v) && v >= -20 && v <= 30) sim.snr = v;
                    else if (ok(v) && v >= -200 && v <= 300) sim.snr = v / 10f;
                    v = l.getRsrq();
                    if (ok(v) && v >= -34 && v <= 3) sim.rsrq = v;
                    v = l.getRssi();
                    if (ok(v) && v >= -113 && v <= -25) sim.rssi = v;
                    v = l.getCqi();
                    if (ok(v) && v >= 0 && v <= 15) sim.cqi = v;
                    v = l.getTimingAdvance();
                    if (ok(v) && v >= 0 && v <= 1282) sim.ta = v;
                } else if (cs instanceof CellSignalStrengthNr && nr) {
                    CellSignalStrengthNr n = (CellSignalStrengthNr) cs;
                    int v = n.getSsSinr();
                    if (ok(v) && v >= -23 && v <= 40) sim.snr = v;
                    v = n.getSsRsrq();
                    if (ok(v) && v >= -43 && v <= 20) sim.rsrq = v;
                    try {
                        @SuppressWarnings("unchecked")
                        List<Integer> cqi = (List<Integer>) n.getClass().getMethod("getCsiCqiReport").invoke(n);
                        if (cqi != null && !cqi.isEmpty() && cqi.get(0) >= 0 && cqi.get(0) <= 15) sim.cqi = cqi.get(0);
                    } catch (Throwable ignored) { }
                }
            }
        }

        // --- network label (4G / 4G+ / 5G / 5G+ / 3G ...) and carrier aggregation
        sim.net = netLabel(tm, ss);
        try { carrierAgg(ctx, sim, tm, si, ss); } catch (Throwable t) { logOnce("ca", "carrier aggregation read failed: " + t); }

        // --- serving cell identity: registration info first, then the cell list
        CellIdentity id = null;
        if (ss != null) {
            outer:
            for (int domain : new int[]{NetworkRegistrationInfo.DOMAIN_PS, NetworkRegistrationInfo.DOMAIN_CS}) {
                for (NetworkRegistrationInfo nri : ss.getNetworkRegistrationInfoList()) {
                    if (nri.getTransportType() != android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WWAN) continue;
                    if ((nri.getDomain() & domain) == 0 || !nri.isRegistered()) continue;
                    CellIdentity c = nri.getCellIdentity();
                    if (c instanceof CellIdentityLte || c instanceof CellIdentityNr) {
                        id = c;
                        break outer;
                    }
                }
            }
        }
        if (id == null || pciOf(id) < 0) {
            for (CellInfo ci : Bands.simCells(cells, si, sim.band, all)) {
                CellIdentity c = ci instanceof CellInfoLte ? ((CellInfoLte) ci).getCellIdentity()
                        : ci instanceof CellInfoNr ? ((CellInfoNr) ci).getCellIdentity() : null;
                if (c != null && pciOf(c) >= 0) {
                    id = c;
                    break;
                }
            }
        }
        if (id instanceof CellIdentityLte) {
            CellIdentityLte l = (CellIdentityLte) id;
            if (l.getPci() >= 0 && l.getPci() <= 503) sim.pci = l.getPci();
            if (ok(l.getEarfcn()) && l.getEarfcn() >= 0) sim.arfcn = l.getEarfcn();
            if (ok(l.getTac()) && l.getTac() > 0 && l.getTac() < 65536) sim.tac = l.getTac();
            int ci = l.getCi();
            if (ok(ci) && ci > 0 && ci < 268435456) sim.site = (ci >> 8) + "-" + (ci & 0xFF);
            sim.mccMnc = code(l.getMccString(), l.getMncString());
        } else if (id instanceof CellIdentityNr) {
            CellIdentityNr n = (CellIdentityNr) id;
            if (n.getPci() >= 0 && n.getPci() <= 1007) sim.pci = n.getPci();
            if (ok(n.getNrarfcn()) && n.getNrarfcn() >= 0) sim.arfcn = n.getNrarfcn();
            if (ok(n.getTac()) && n.getTac() > 0) sim.tac = n.getTac();
            long nci = n.getNci();
            // gNB id length varies per operator; 24 bits is the most common split.
            if (nci > 0 && nci != Long.MAX_VALUE) sim.site = (nci >> 12) + "-" + (nci & 0xFFF);
            sim.mccMnc = code(n.getMccString(), n.getMncString());
        }
        if (sim.mccMnc == null) sim.mccMnc = code(si.getMccString(), si.getMncString());

        // --- operator name (what the network calls itself, else the SIM's name)
        String op = null;
        try {
            if (ss != null) op = ss.getOperatorAlphaShort();
            if (op == null || op.isEmpty()) op = ss != null ? ss.getOperatorAlphaLong() : null;
        } catch (Throwable ignored) { }
        if (op == null || op.isEmpty()) {
            CharSequence c = si.getCarrierName();
            op = c != null ? c.toString() : null;
        }
        if (op != null) {
            op = op.trim();
            int sp = op.indexOf(' ');
            if (op.length() > 8 && sp > 0) op = op.substring(0, sp);   // "Jio 4G" -> "Jio"
            if (op.length() > 8) op = op.substring(0, 8);
        }
        sim.operator = op;

        try {
            sim.inCall = tm.getCallStateForSubscription() != TelephonyManager.CALL_STATE_IDLE;
        } catch (Throwable t) {
            try {
                @SuppressWarnings("deprecation") int cs = tm.getCallState();
                sim.inCall = cs != TelephonyManager.CALL_STATE_IDLE;
            } catch (Throwable ignored) { }
        }

        logOnce("detail" + sim.slot + sim.band, "SIM" + sim.slot + " " + sim.band + ": level " + sim.level
                + " net " + sim.net + " snr " + sim.snr + " rsrq " + sim.rsrq + " rssi " + sim.rssi
                + " cqi " + sim.cqi + " ta " + sim.ta + " pci " + sim.pci + " arfcn " + sim.arfcn
                + " tac " + sim.tac + " site " + sim.site + " " + sim.mccMnc + " " + sim.operator
                + " data " + sim.dataToday);
    }

    private static boolean ok(int v) {
        return v != CellInfo.UNAVAILABLE && v != Integer.MAX_VALUE;
    }

    private static int pciOf(CellIdentity id) {
        if (id instanceof CellIdentityLte) return ((CellIdentityLte) id).getPci();
        if (id instanceof CellIdentityNr) return ((CellIdentityNr) id).getPci();
        return -1;
    }

    private static String code(String mcc, String mnc) {
        if (mcc == null || mnc == null || mcc.isEmpty() || mnc.isEmpty()) return null;
        return mcc + "-" + mnc;
    }

    // ------------------------------------------------------------ carrier aggregation

    private static final Map<Integer, List<android.telephony.PhysicalChannelConfig>> PCC = new HashMap<>();
    private static final java.util.Set<Integer> PCC_REG = new java.util.HashSet<>();

    private static final Map<Integer, String> CA_LINES = new java.util.TreeMap<>();
    private static final Map<Integer, String> PCC_STATE = new HashMap<>();

    /** For the NetSpeed app's diagnostics: what the modem says about carriers on each SIM. */
    static String caReport() {
        StringBuilder b = new StringBuilder("CARRIER AGGREGATION\n");
        synchronized (CA_LINES) {
            if (CA_LINES.isEmpty()) b.append("  nothing read yet\n");
            for (String l : CA_LINES.values()) b.append(l);
        }
        return b.toString();
    }

    /** Android's display type per SIM (TelephonyDisplayInfo override): 1 LTE CA, 2 LTE Adv Pro, 3 NR NSA, 5 NR Advanced. */
    private static final Map<Integer, Integer> DISP = new HashMap<>();

    private static String dispName(Integer o) {
        if (o == null) return "no update yet";
        switch (o) {
            case 0: return "plain (no override)";
            case 1: return "LTE CA (4G+)";
            case 2: return "LTE Advanced Pro";
            case 3: return "5G NSA";
            case 4: case 5: return "5G Advanced (5G+)";
            default: return "type " + o;
        }
    }

    private static final class PccCb extends android.telephony.TelephonyCallback
            implements android.telephony.TelephonyCallback.PhysicalChannelConfigListener,
            android.telephony.TelephonyCallback.DisplayInfoListener {
        final int sub;
        PccCb(int sub) { this.sub = sub; }
        @Override public void onDisplayInfoChanged(android.telephony.TelephonyDisplayInfo i) {
            synchronized (PCC) { DISP.put(sub, i == null ? null : i.getOverrideNetworkType()); }
        }
        @Override public void onPhysicalChannelConfigChanged(List<android.telephony.PhysicalChannelConfig> l) {
            synchronized (PCC) {
                PCC.put(sub, l == null ? null : new java.util.ArrayList<>(l));
                PCC_STATE.put(sub, "updated, " + (l == null ? "null" : l.size() + " carrier(s)"));
            }
        }
    }

    /**
     * Carriers the modem is using right now. The modem's channel list gives each carrier's band
     * ("B8+B3"); without it, the cell bandwidth list still gives how many ("B8+1").
     * Several carriers of the serving kind turn the label into "4G+" / "5G+".
     */
    private static void carrierAgg(Context ctx, Bands.Sim sim, TelephonyManager tm, SubscriptionInfo si, ServiceState ss) {
        sim.caBands = null;
        if (sim.band == null || !sim.inService) return;
        int sub = si.getSubscriptionId();
        boolean reg;
        synchronized (PCC) { reg = PCC_REG.contains(sub); }
        if (!reg) {
            synchronized (PCC) { PCC_REG.add(sub); }
            try {
                tm.registerTelephonyCallback(ctx.getMainExecutor(), new PccCb(sub));
                synchronized (PCC) { PCC_STATE.put(sub, "listening, no update yet"); }
                logOnce("pcc" + sub, "channel list listener on sub " + sub);
            } catch (Throwable t) {
                synchronized (PCC) { PCC_STATE.put(sub, "refused: " + t.getClass().getSimpleName() + " " + t.getMessage()); }
                logOnce("pccf" + sub, "channel list not available on sub " + sub + ": " + t);
            }
        }
        boolean nr = sim.band.startsWith("n");
        int want = nr ? TelephonyManager.NETWORK_TYPE_NR : TelephonyManager.NETWORK_TYPE_LTE;
        List<android.telephony.PhysicalChannelConfig> l;
        synchronized (PCC) { l = PCC.get(sub); }
        java.util.LinkedHashSet<String> bands = new java.util.LinkedHashSet<>();
        int n = 0;
        if (l != null) {
            bands.add(sim.band);                               // serving band first
            for (android.telephony.PhysicalChannelConfig p : l) {
                int t = p.getNetworkType();
                if (t != want && !(t == 19 && !nr)) continue;
                n++;
                int b = pccBand(p, nr);
                if (b > 0) bands.add((nr ? "n" : "B") + b);
            }
        }
        int[] bw = null;
        try { bw = ss == null ? null : ss.getCellBandwidths(); } catch (Throwable ignored) { }
        StringBuilder r = new StringBuilder();
        r.append("  SIM ").append(sim.slot).append(" (").append(sim.net).append(' ').append(sim.band).append(")\n");
        String st;
        synchronized (PCC) { st = PCC_STATE.get(sub); }
        r.append("    channel list: ").append(st == null ? "?" : st).append('\n');
        if (l != null) for (android.telephony.PhysicalChannelConfig p : l) {
            int t = p.getNetworkType();
            r.append("      ").append(t == TelephonyManager.NETWORK_TYPE_NR ? "NR n" : t == TelephonyManager.NETWORK_TYPE_LTE || t == 19 ? "LTE B" : "type " + t + " band ")
                    .append(pccBand(p, t == TelephonyManager.NETWORK_TYPE_NR))
                    .append(p.getConnectionStatus() == 1 ? " primary" : p.getConnectionStatus() == 2 ? " secondary" : " ?")
                    .append(", ").append(p.getCellBandwidthDownlinkKhz() / 1000).append(" MHz down, pci ").append(p.getPhysicalCellId()).append('\n');
        }
        Integer disp;
        synchronized (PCC) { disp = DISP.get(sub); }
        r.append("    Android display type: ").append(dispName(disp)).append('\n');
        int qi = QtiNr.iconFor(sim.slot - 1);
        if (qi >= 0) r.append("    Qualcomm icon: ").append(QtiNr.name(qi)).append('\n');
        r.append("    bandwidth list: ");
        if (bw == null || bw.length == 0) r.append("empty\n");
        else { for (int i = 0; i < bw.length; i++) r.append(i > 0 ? " + " : "").append(bw[i] / 1000).append(" MHz"); r.append('\n'); }
        synchronized (CA_LINES) { CA_LINES.put(sim.slot, r.toString()); }
        if (n < 2) {                                           // fall back to the bandwidth list
            if (bw != null && bw.length > n) { n = bw.length; bands.clear(); }
        }
        if (n < 2) {
            // one carrier, but Android itself rates the connection 5G Advanced / 4G+ (what stock ROMs show as "+")
            if (((disp != null && (disp == 4 || disp == 5)) || qi == 2) && nr && "5G".equals(sim.net)) sim.net = "5G+";
            else if (disp != null && disp == 1 && !nr && "4G".equals(sim.net)) sim.net = "4G+";
            return;
        }
        if (bands.size() >= 2) sim.caBands = String.join("+", bands);
        else sim.caBands = sim.band + "+" + (n - 1);
        if ("4G".equals(sim.net) && !nr) sim.net = "4G+";
        else if ("5G".equals(sim.net) && nr) sim.net = "5G+";
    }

    /** The carrier's band: the modem's own number, or worked out from its channel number when the modem reports 0. */
    private static int pccBand(android.telephony.PhysicalChannelConfig p, boolean nr) {
        int b = p.getBand();
        if (b > 0) return b;
        try {
            int ch = (Integer) p.getClass().getMethod("getDownlinkChannelNumber").invoke(p);
            return nr ? Bands.nrBandFromArfcn(ch) : Bands.lteBandFromEarfcn(ch);
        } catch (Throwable ignored) { }
        return b;
    }

    /** "5G", "4G+", "4G", "3G", "H+", "2G"... like the system's own type icon. */
    static String netLabel(TelephonyManager tm, ServiceState ss) {
        int t = TelephonyManager.NETWORK_TYPE_UNKNOWN;
        try {
            t = tm.getDataNetworkType();
            if (t == TelephonyManager.NETWORK_TYPE_UNKNOWN) t = tm.getVoiceNetworkType();
        } catch (Throwable ignored) { }
        switch (t) {
            case TelephonyManager.NETWORK_TYPE_NR:
                return "5G";
            case TelephonyManager.NETWORK_TYPE_LTE:
            case 19: // LTE_CA
                try {
                    Object nrState = ss == null ? null : XposedHelpers.callMethod(ss, "getNrState");
                    if (nrState instanceof Integer && (Integer) nrState == 3) return "5G"; // NSA, connected
                } catch (Throwable ignored) { }
                try {
                    Object ca = ss == null ? null : XposedHelpers.callMethod(ss, "isUsingCarrierAggregation");
                    if (t == 19 || Boolean.TRUE.equals(ca)) return "4G+";
                } catch (Throwable ignored) { }
                return "4G";
            case TelephonyManager.NETWORK_TYPE_HSPAP:
                return "H+";
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
                return "H";
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
                return "3G";
            case TelephonyManager.NETWORK_TYPE_EDGE:
                return "E";
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_GSM:
                return "2G";
            case TelephonyManager.NETWORK_TYPE_IWLAN:
                return "WiFi";
            default:
                return null;
        }
    }

    // ------------------------------------------------------------ data used today

    private static final Map<Integer, long[]> DATA = new HashMap<>(); // sub -> {bytes, checkedAt}

    /** Mobile data used since midnight on this SIM, in bytes; -1 if not readable. Cached 60 s. */
    static long dataToday(Context ctx, TelephonyManager tm, int sub) {
        long now = System.currentTimeMillis();
        synchronized (DATA) {
            long[] c = DATA.get(sub);
            if (c != null && now - c[1] < 60_000) return c[0];
        }
        long bytes = -1;
        try {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cal.set(java.util.Calendar.MINUTE, 0);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            String imsi = null;
            try {
                imsi = tm.getSubscriberId();
            } catch (Throwable t) {
                logOnce("imsi", "SIM id for data usage not readable: " + t);
            }
            android.app.usage.NetworkStatsManager nsm = ctx.getSystemService(android.app.usage.NetworkStatsManager.class);
            @SuppressWarnings("deprecation")
            android.app.usage.NetworkStats.Bucket b = nsm.querySummaryForDevice(
                    android.net.ConnectivityManager.TYPE_MOBILE, imsi, cal.getTimeInMillis(), now);
            if (b != null) bytes = b.getRxBytes() + b.getTxBytes();
        } catch (Throwable t) {
            logOnce("datausage", "data usage not readable: " + t);
        }
        synchronized (DATA) {
            DATA.put(sub, new long[]{bytes, now});
        }
        return bytes;
    }

    private static final java.util.Set<String> LOGGED = new java.util.HashSet<>();

    private static void logOnce(String key, String msg) {
        synchronized (LOGGED) {
            if (!LOGGED.add(key)) return;
        }
        XposedBridge.log(TAG + msg);
    }
}
