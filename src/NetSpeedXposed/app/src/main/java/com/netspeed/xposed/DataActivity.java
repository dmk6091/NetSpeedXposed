package com.netspeed.xposed;

import android.content.Context;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XposedBridge;

/**
 * Mobile data activity of the data SIM, straight from the modem (the same signal phones use
 * for their network-type arrows): none, in, out, in+out, or dormant.
 */
final class DataActivity {
    private static final String TAG = "NetSpeedXposed: [activity] ";
    private static final Executor EXEC = Executors.newSingleThreadExecutor();

    private static volatile int sState = TelephonyManager.DATA_ACTIVITY_NONE;
    private static int sSub = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private static TelephonyManager sTm;
    private static Listener sListener;

    private static final class Listener extends TelephonyCallback implements TelephonyCallback.DataActivityListener {
        @Override
        public void onDataActivity(int direction) {
            sState = direction;
        }
    }

    private DataActivity() {}

    /** Follows the current data SIM; re-attaches when the data SIM changes. Cheap to repeat. */
    static synchronized void watch(Context ctx) {
        try {
            int sub = SubscriptionManager.getDefaultDataSubscriptionId();
            if (sub == sSub && sListener != null) return;
            TelephonyManager base = ctx.getSystemService(TelephonyManager.class);
            if (base == null) return;
            if (sTm != null && sListener != null) sTm.unregisterTelephonyCallback(sListener);
            sSub = sub;
            sTm = SubscriptionManager.isValidSubscriptionId(sub) ? base.createForSubscriptionId(sub) : base;
            sListener = new Listener();
            sTm.registerTelephonyCallback(EXEC, sListener);
            XposedBridge.log(TAG + "following data activity of subscription " + sub);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "can't follow data activity: " + t);
        }
    }

    /** SIM slot (1 or 2) of the mobile data SIM, or 0 if unknown. */
    static int slot() {
        try {
            int idx = SubscriptionManager.getSlotIndex(SubscriptionManager.getDefaultDataSubscriptionId());
            return idx >= 0 ? idx + 1 : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    static boolean in() {
        int s = sState;
        return s == TelephonyManager.DATA_ACTIVITY_IN || s == TelephonyManager.DATA_ACTIVITY_INOUT;
    }

    static boolean out() {
        int s = sState;
        return s == TelephonyManager.DATA_ACTIVITY_OUT || s == TelephonyManager.DATA_ACTIVITY_INOUT;
    }
}
