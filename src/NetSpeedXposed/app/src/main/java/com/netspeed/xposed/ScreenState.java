package com.netspeed.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;

/**
 * Screen on / off, kept up to date by a broadcast receiver (no binder call per check).
 * Status bar work pauses while the screen is off: nobody sees it, and the CPU and modem can rest.
 */
final class ScreenState {
    private static volatile boolean sOn = true;
    private static boolean sInit;

    private ScreenState() {}

    static synchronized void init(Context ctx) {
        if (sInit) return;
        sInit = true;
        try {
            PowerManager pm = ctx.getSystemService(PowerManager.class);
            if (pm != null) sOn = pm.isInteractive();
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    sOn = Intent.ACTION_SCREEN_ON.equals(i.getAction());
                }
            }, f, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            sOn = true;
        }
    }

    static boolean on() {
        return sOn;
    }
}
