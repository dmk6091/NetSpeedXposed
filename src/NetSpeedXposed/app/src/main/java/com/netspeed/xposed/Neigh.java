package com.netspeed.xposed;

import android.content.Context;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The strongest cells the phone hears besides the serving ones (any SIM), for the right column of the bar. Worker thread. */
final class Neigh {
    private Neigh() {}

    static final class N {
        String band;      // "n78" / "B3" / "?"
        int pci, rsrp;
    }

    static volatile List<N> LIST = new ArrayList<>();

    static void refresh() {
        try {
            List<N> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (List<CellInfo> list : Bands.allFresh()) {
                for (CellInfo ci : list) {
                    try {
                        if (ci.isRegistered()) continue;
                        N n = new N();
                        int arfcn;
                        if (ci instanceof CellInfoLte) {
                            android.telephony.CellIdentityLte id = ((CellInfoLte) ci).getCellIdentity();
                            n.pci = id.getPci();
                            arfcn = id.getEarfcn();
                            n.rsrp = ((CellInfoLte) ci).getCellSignalStrength().getRsrp();
                            int b = Bands.lteBandFromEarfcn(arfcn);
                            n.band = b > 0 ? "B" + b : "?";
                        } else if (ci instanceof CellInfoNr) {
                            android.telephony.CellIdentityNr id = (android.telephony.CellIdentityNr) ((CellInfoNr) ci).getCellIdentity();
                            n.pci = id.getPci();
                            arfcn = id.getNrarfcn();
                            n.rsrp = ((android.telephony.CellSignalStrengthNr) ((CellInfoNr) ci).getCellSignalStrength()).getSsRsrp();
                            int b = Bands.nrBandFromArfcn(arfcn);
                            n.band = b > 0 ? "n" + b : "?";
                        } else continue;
                        if (n.pci < 0 || n.pci == Integer.MAX_VALUE || n.rsrp >= 0 || n.rsrp < -140) continue;
                        if (!seen.add(arfcn + ":" + n.pci)) continue;
                        out.add(n);
                    } catch (Throwable ignored) { }
                }
            }
            Collections.sort(out, (a, b) -> b.rsrp - a.rsrp);
            LIST = out.size() > 4 ? new ArrayList<>(out.subList(0, 4)) : out;
        } catch (Throwable ignored) { }
    }
}
