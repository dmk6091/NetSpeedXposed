package com.netspeed.xposed;

import android.content.Context;
import android.telephony.AccessNetworkConstants;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ImsManager;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsRegistrationAttributes;
import android.telephony.ims.RegistrationManager;
import android.telephony.ims.feature.MmTelFeature;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XposedBridge;

/**
 * Voice-over-data state per SIM, from each SIM's IMS registration (inside System UI):
 * "NR" (VoNR), "LTE" (VoLTE), "WiFi" (VoWiFi), or null when calls on that SIM don't use IMS.
 */
final class Voice {
    private static final String TAG = "NetSpeedXposed: [voice] ";
    private static final Executor EXEC = Executors.newSingleThreadExecutor();

    /** Per subscription: registration (transport + technology) and voice capability. */
    private static final Map<Integer, Integer> TRANSPORT = new HashMap<>();
    private static final Map<Integer, Integer> TECH = new HashMap<>();
    private static final Map<Integer, Boolean> VOICE = new HashMap<>();
    private static final Set<Integer> REGISTERED = new HashSet<>();
    private static final Map<Integer, Integer> FAILS = new java.util.concurrent.ConcurrentHashMap<>();
    private static boolean sLoggedError = false;

    private Voice() {}

    /** Starts listening to any SIM not yet followed. Cheap to call repeatedly. */
    static void watch(Context ctx) {
        try {
            SubscriptionManager sm = ctx.getSystemService(SubscriptionManager.class);
            ImsManager ims = ctx.getSystemService(ImsManager.class);
            if (sm == null || ims == null) return;
            List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
            if (subs == null) return;
            for (SubscriptionInfo si : subs) {
                final int sub = si.getSubscriptionId();
                synchronized (TECH) {
                    if (!REGISTERED.add(sub)) continue;
                }
                try {
                ImsMmTelManager mm = ims.getImsMmTelManager(sub);
                mm.registerImsRegistrationCallback(EXEC, new RegistrationManager.RegistrationCallback() {
                    @Override
                    public void onRegistered(ImsRegistrationAttributes a) {
                        int tech = -1;
                        try {
                            // @SystemApi in the SDK, available to System UI at runtime.
                            tech = (Integer) a.getClass().getMethod("getRegistrationTechnology").invoke(a);
                        } catch (Throwable ignored) { }
                        synchronized (TECH) {
                            TRANSPORT.put(sub, a.getTransportType());
                            TECH.put(sub, tech);
                        }
                        XposedBridge.log(TAG + "sub " + sub + " registered: transport "
                                + a.getTransportType() + ", tech " + tech);
                    }

                    @Override
                    public void onUnregistered(ImsReasonInfo info) {
                        synchronized (TECH) {
                            TRANSPORT.remove(sub);
                            TECH.remove(sub);
                        }
                        XposedBridge.log(TAG + "sub " + sub + " unregistered: " + info);
                    }
                });
                mm.registerMmTelCapabilityCallback(EXEC, new ImsMmTelManager.CapabilityCallback() {
                    @Override
                    public void onCapabilitiesStatusChanged(MmTelFeature.MmTelCapabilities c) {
                        synchronized (TECH) {
                            VOICE.put(sub, c.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE));
                        }
                    }
                });
                XposedBridge.log(TAG + "following voice registration of SIM" + (si.getSimSlotIndex() + 1));
                } catch (Throwable t) {
                    // Right after boot the SIM's IMS service is often not up yet: try again on the
                    // next tick instead of giving up on this SIM for good.
                    synchronized (TECH) {
                        REGISTERED.remove(sub);
                    }
                    Integer n = FAILS.merge(sub, 1, Integer::sum);
                    if (n == 1 || n % 60 == 0) {
                        XposedBridge.log(TAG + "SIM" + (si.getSimSlotIndex() + 1) + " voice not ready yet ("
                                + n + "): " + t);
                    }
                }
            }
        } catch (Throwable t) {
            if (!sLoggedError) {
                sLoggedError = true;
                XposedBridge.log(TAG + "can't read voice registration: " + t);
            }
        }
    }

    /**
     * "NR", "LTE", "WiFi" or null. When the modem doesn't say LTE vs NR, the SIM's current
     * band decides (n… = NR, B… = LTE).
     */
    static String label(int sub, String band) {
        Integer tr, tech;
        Boolean voice;
        synchronized (TECH) {
            tr = TRANSPORT.get(sub);
            tech = TECH.get(sub);
            voice = VOICE.get(sub);
        }
        if (tr == null || Boolean.FALSE.equals(voice)) return null;
        if (tr == AccessNetworkConstants.TRANSPORT_TYPE_WLAN) return "WiFi";
        // ImsRegistrationImplBase: 0 = LTE, 1 = IWLAN, 2 = cross-SIM, 3 = NR.
        if (tech != null && (tech == 1 || tech == 2)) return "WiFi";
        // Over the mobile network, go by the network the SIM is on now (like the stock
        // VoNR icon): modems often keep reporting LTE after moving to 5G standalone.
        if (band != null) return band.startsWith("n") ? "NR" : "LTE";
        if (tech != null && tech == 3) return "NR";
        return "LTE";
    }
}
