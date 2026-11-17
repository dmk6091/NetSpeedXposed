package com.netspeed.xposed;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Watch-only: runs inside the Play Store (com.android.vending), which hosts the Play Integrity API service
 * apps bind to. Every incoming call on an integrity service binder is reported to the NetSpeed app (caller
 * package, classic / standard API, transaction code), which shows a notification and keeps a history.
 * Nothing is blocked, delayed or changed: the hook only reads and returns.
 */
final class IntegrityWatch {
    private static final String TAG = "NetSpeedXposed: [integrity] ";
    static final String ACTION = "com.netspeed.xposed.INTEGRITY_CHECK";

    /** Binder class -> descriptor ("" = not an integrity service), so each class is looked up once. */
    private static final Map<Class<?>, String> DESC = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();

    private IntegrityWatch() {}

    static void install() {
        try {
            XposedHelpers.findAndHookMethod(Binder.class, "execTransact", int.class, long.class, long.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            try { seen((Binder) p.thisObject, (Integer) p.args[0]); } catch (Throwable ignored) { }
                        }
                    });
            XposedBridge.log(TAG + "watching Play Integrity calls in the Play Store");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook failed: " + t);
        }
    }

    private static void seen(Binder b, int code) {
        if (code <= 0 || code >= 0x00ffffff) return;            // interface / ping / dump transactions
        String d = DESC.get(b.getClass());
        if (d == null) {
            String x = null;
            try { x = b.getInterfaceDescriptor(); } catch (Throwable ignored) { }
            d = x != null && x.toLowerCase(java.util.Locale.US).contains("integrity")
                    && !x.toLowerCase(java.util.Locale.US).contains("callback") ? x : "";
            DESC.put(b.getClass(), d);
            if (!d.isEmpty()) XposedBridge.log(TAG + "integrity service found: " + d);
        }
        if (d.isEmpty()) return;
        int uid = Binder.getCallingUid();
        Context ctx = app();
        if (ctx == null) return;
        String pkg = null;
        try { pkg = ctx.getPackageManager().getNameForUid(uid); } catch (Throwable ignored) { }
        if (pkg == null) pkg = "uid " + uid;
        int colon = pkg.indexOf(':');                              // shared uid names look like "name:uid"
        if (colon > 0) pkg = pkg.substring(0, colon);
        String api = d.contains("Express") ? "standard" : "classic";
        long now = System.currentTimeMillis();
        String key = pkg + "|" + d + "|" + code;
        Long last = LAST.get(key);
        if (last != null && now - last < 3_000L) return;         // one report per burst
        LAST.put(key, now);
        try {
            ctx.sendBroadcast(new Intent(ACTION).setPackage("com.netspeed.xposed")
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra("pkg", pkg).putExtra("api", api).putExtra("code", code).putExtra("at", now)
                    .putExtra("desc", d));
        } catch (Throwable t) {
            XposedBridge.log(TAG + "report failed: " + t);
        }
    }

    private static Context app() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
