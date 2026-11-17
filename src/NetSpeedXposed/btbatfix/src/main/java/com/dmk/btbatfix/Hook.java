package com.dmk.btbatfix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * The status bar sets the Bluetooth headset battery icon only when the headset connects or its battery
 * level changes. After a reboot the headset is often already connected (or its level already known)
 * before System UI has drawn that icon, so nothing appears until it is reconnected.
 *
 * This finds System UI's status bar policy (the class that puts the Bluetooth icons in the status bar)
 * and asks it to refresh its Bluetooth icons: a few times after System UI starts, after the first
 * unlock, and shortly after any Bluetooth connect / battery-level event.
 */
public class Hook implements IXposedHookLoadPackage {
    static final String TAG = "BtBatFix: ";
    static final String[] POLICY_CLASSES = {
            "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarPolicy",
            "com.android.systemui.statusbar.phone.PhoneStatusBarPolicy",
            "com.android.systemui.statusbar.policy.MiuiPhoneStatusBarPolicy",
            "com.miui.systemui.statusbar.phone.MiuiPhoneStatusBarPolicy",
    };
    static final long[] START_DELAYS = {5_000, 20_000, 60_000, 180_000};

    final List<Object> policies = new ArrayList<>();
    final List<Method> refreshers = new ArrayList<>();
    Handler main;
    boolean receiverOn, logged;

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        if (!"com.android.systemui".equals(lp.packageName)) return;
        if (lp.processName != null && !"com.android.systemui".equals(lp.processName)) return;
        int found = 0;
        for (String n : POLICY_CLASSES) {
            Class<?> c = XposedHelpers.findClassIfExists(n, lp.classLoader);
            if (c == null) continue;
            found++;
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try { onPolicy(p.thisObject, p.args); } catch (Throwable t) { XposedBridge.log(TAG + "setup: " + t); }
                }
            });
        }
        XposedBridge.log(TAG + (found == 0 ? "no status bar policy class found" : "watching " + found + " status bar policy class(es)"));
        try { StockArrows.install(lp.classLoader); } catch (Throwable t) { XposedBridge.log(TAG + "arrows: " + t); }
    }

    synchronized void onPolicy(Object policy, Object[] args) {
        if (policies.contains(policy)) return;
        policies.add(policy);
        findRefreshers(policy.getClass());
        if (main == null) main = new Handler(Looper.getMainLooper());
        Context ctx = null;
        for (Object a : args) if (a instanceof Context) { ctx = (Context) a; break; }
        if (ctx != null && !receiverOn) {
            receiverOn = true;
            IntentFilter f = new IntentFilter();
            f.addAction("android.bluetooth.device.action.ACL_CONNECTED");
            f.addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED");
            f.addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED");
            f.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
            f.addAction("android.intent.action.USER_PRESENT");
            f.addAction("android.intent.action.BOOT_COMPLETED");
            try {
                ctx.getApplicationContext().registerReceiver(new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent i) {
                        later(2_500); later(8_000);
                    }
                }, f, null, main, 2 /* Context.RECEIVER_EXPORTED */);
            } catch (Throwable t) {
                XposedBridge.log(TAG + "receiver: " + t);
            }
        }
        for (long d : START_DELAYS) later(d);
    }

    /** the policy's own "update Bluetooth" methods: no arguments, named update...Bluetooth / Handsfree / Headset */
    void findRefreshers(Class<?> c) {
        if (!refreshers.isEmpty()) return;
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder all = new StringBuilder();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                String n = m.getName(), l = n.toLowerCase();
                boolean bt = l.contains("bluetooth") || l.contains("handsfree") || l.contains("headset");
                if (bt) all.append(' ').append(k.getSimpleName()).append('.').append(n).append('/').append(m.getParameterTypes().length);
                if (!bt || !l.startsWith("update") || m.getParameterTypes().length != 0
                        || Modifier.isStatic(m.getModifiers()) || !seen.add(n)) continue;
                m.setAccessible(true);
                refreshers.add(m);
            }
        }
        XposedBridge.log(TAG + "Bluetooth methods in " + c.getName() + ":" + all);
    }

    void later(long ms) {
        if (main == null) return;
        main.postDelayed(this::refresh, ms);
    }

    synchronized void refresh() {
        StringBuilder done = new StringBuilder();
        for (Object p : policies) {
            for (Method m : refreshers) {
                if (!m.getDeclaringClass().isInstance(p)) continue;
                try { m.invoke(p); done.append(' ').append(m.getName()); }
                catch (Throwable t) { XposedBridge.log(TAG + m.getName() + ": " + (t.getCause() != null ? t.getCause() : t)); }
            }
        }
        if (!logged) { logged = true; XposedBridge.log(TAG + "refreshed:" + (done.length() == 0 ? " nothing to call" : done)); }
    }
}
