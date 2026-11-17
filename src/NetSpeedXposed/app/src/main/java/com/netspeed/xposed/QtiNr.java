package com.netspeed.xposed;

import android.content.Context;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Experimental: asks Qualcomm's own telephony helper (com.qti.phone, the "ExtTelephony" service that
 * stock ROMs use for their 5G+ icon) for each SIM's NR icon type. Everything is found by reflection
 * from the client library on the phone, and what was found is written to the diagnostics dialog.
 * Icon type: 0 none, 1 5G basic, 2 5G UWB (shown as 5G+). -1 = unknown.
 */
final class QtiNr {
    private static final String TAG = "NetSpeedXposed: [qti] ";
    static volatile String report = "";
    static final int[] icon = {-1, -1, -1};
    static final long[] iconAt = new long[3];

    private static boolean started;
    private static Object mgr, client;
    private static Method query;
    private static volatile boolean connected;
    private static long lastQuery;
    private static String step = "begin";
    private static final StringBuilder log = new StringBuilder();

    private QtiNr() {}

    static final String ACTION = "com.netspeed.xposed.QTI_NR";
    /** true inside com.qti.phone, where the reader runs (System UI isn't allowed to bind Qualcomm's service). */
    private static boolean remote;
    private static Context appCtx;
    private static boolean rxReady;

    private static String status = "starting";

    private static void note(String s) {
        synchronized (log) {
            log.append("  ").append(s).append('\n');
            if (log.length() > 3000) log.delete(0, log.length() - 3000);
            if (s.startsWith("query ready")) status = "connected to Qualcomm's helper";
            else if (s.contains("failed") || s.startsWith("no ") || s.startsWith("reader")) status = s;
            report = summary();
        }
        XposedBridge.log(TAG + s);
        send(-1, -1);
    }

    /** From com.qti.phone to System UI: the log, and an icon type for a slot (slot -1 = log only). */
    private static void send(int slot, int type) {
        if (!remote || appCtx == null) return;
        try {
            appCtx.sendBroadcast(new android.content.Intent(ACTION).setPackage("com.android.systemui")
                    .putExtra("report", report).putExtra("slot", slot).putExtra("type", type));
        } catch (Throwable t) {
            XposedBridge.log(TAG + "send failed: " + t);
        }
    }

    /** Hook entry for com.qti.phone (its own process, or sharing the phone process). */
    static void installQti(boolean first, String proc) {
        XposedBridge.log(TAG + "loaded in " + proc + (first ? "" : " (shared process)"));
        if (first) {
            try {
                de.robv.android.xposed.XposedHelpers.findAndHookMethod(android.app.Instrumentation.class, "callApplicationOnCreate",
                        android.app.Application.class, new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                try { begin((Context) p.args[0]); } catch (Throwable t) { XposedBridge.log(TAG + "install failed: " + t); }
                            }
                        });
            } catch (Throwable t) {
                XposedBridge.log(TAG + "hook failed: " + t);
            }
        } else {
            // the process already has its Application: start from it shortly
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    Context c = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
                    if (c != null) begin(c); else XposedBridge.log(TAG + "no application yet");
                } catch (Throwable t) { XposedBridge.log(TAG + "install failed: " + t); }
            });
        }
    }

    private static synchronized void begin(Context c) {
        if (started) return;
        started = true;
        remote = true;
        appCtx = c;
        Cfg.init(c);
        note("running in " + c.getPackageName());
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(() -> {
            // settings load in the background; only an explicit "off" stops it here
            // (being ticked in LSPosed is already the opt-in)
            if (!Cfg.b(Cfg.QTI_NR, true)) { note("reader switched off"); return; }
            try { start(c); } catch (Throwable t) { note("start failed at " + step + ": " + cause(t)); }
        }, 15_000L);
        h.postDelayed(new Runnable() {
            @Override public void run() {
                try { queryNow(); } catch (Throwable ignored) { }
                send(-1, -1);       // resend the log: System UI may have started after we did
                h.postDelayed(this, 30_000L);
            }
        }, 30_000L);
    }

    private static void queryNow() {
        if (!connected || query == null || client == null) return;
        for (int s = 0; s < 2; s++) {
            try { query.invoke(mgr, s, client); }
            catch (Throwable t) { note("query slot " + s + " failed: " + cause(t)); }
            if (query2 != null) {
                try { query2.invoke(mgr, s, client); }
                catch (Throwable t) { if (SEEN.add("q2fail")) note("queryNrIcon failed: " + cause(t)); }
            }
        }
    }

    private static String summary() {
        return "QUALCOMM 5G ICON\n  " + status + "\n" + iconLine();
    }

    private static String iconLine() {
        StringBuilder b = new StringBuilder("  rating:");
        for (int i = 0; i < 2; i++) b.append(" SIM").append(i + 1).append('=').append(name(icon[i]));
        return b.append('\n').toString();
    }

    static String name(int t) {
        switch (t) {
            case 0: return "none";
            case 1: return "5G basic";
            case 2: return "5G+ (UWB)";
            case -1: return "?";
            default: return "type " + t;
        }
    }

    /** Icon type for a SIM slot (0-based), -1 when unknown or older than 2 minutes. */
    static int iconFor(int slot0) {
        if (slot0 < 0 || slot0 > 1) return -1;
        return System.currentTimeMillis() - iconAt[slot0] < 120_000L ? icon[slot0] : -1;
    }

    /** Worker thread, every few seconds. */
    static void tick(Context ctx) {
        if (!Cfg.b(Cfg.QTI_NR, Cfg.D_QTI_NR)) return;
        if (rxReady) return;
        rxReady = true;
        report = "QUALCOMM 5G ICON\n  waiting for Qualcomm's phone helper: tick com.qti.phone in LSPosed, then reboot\n";
        try {
            ctx.registerReceiver(new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context c, android.content.Intent in) {
                    String r = in.getStringExtra("report");
                    if (r != null) report = r;
                    int slot = in.getIntExtra("slot", -1), type = in.getIntExtra("type", -1);
                    if (slot >= 0 && slot < 2) { icon[slot] = type; iconAt[slot] = System.currentTimeMillis(); }
                }
            }, new android.content.IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            report = "QUALCOMM 5G ICON\n  receiver failed: " + t + "\n";
        }
    }

    private static String cause(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static String sig(Method m) {
        StringBuilder b = new StringBuilder(m.getName()).append('(');
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) b.append(i > 0 ? "," : "").append(p[i].getSimpleName());
        return b.append(')').toString();
    }

    private static void start(Context ctx) throws Throwable {
        // 1. the client library on the phone
        List<File> jars = new ArrayList<>();
        for (String d : new String[]{"/system_ext/framework", "/system/framework", "/product/framework", "/system_ext/priv-app",
                "/vendor/framework"}) {
            File[] fs = new File(d).listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                String n = f.getName().toLowerCase(Locale.US);
                if (n.contains("extphone") || n.contains("qti-telephony") || n.contains("qtitelephony")) {
                    if (f.isDirectory()) {
                        File[] in = f.listFiles();
                        if (in != null) for (File g : in) if (g.getName().endsWith(".apk") || g.getName().endsWith(".jar")) jars.add(g);
                    } else if (n.endsWith(".jar") || n.endsWith(".apk")) jars.add(f);
                }
            }
        }
        StringBuilder jl = new StringBuilder();
        for (File f : jars) jl.append(jl.length() > 0 ? ", " : "").append(f.getPath());
        note("libraries: " + (jl.length() == 0 ? "none found" : jl));
        ClassLoader cl = null;
        Class<?> etm = null;
        try {
            etm = Class.forName("com.qti.extphone.ExtTelephonyManager", false, ctx.getClassLoader());
            cl = ctx.getClassLoader();
            note("ExtTelephonyManager already in System UI");
        } catch (Throwable ignored) { }
        for (int i = 0; etm == null && i < jars.size(); i++) {
            try {
                ClassLoader c = new dalvik.system.PathClassLoader(jars.get(i).getPath(), ctx.getClassLoader());
                etm = Class.forName("com.qti.extphone.ExtTelephonyManager", true, c);
                cl = c;
                note("ExtTelephonyManager from " + jars.get(i).getName());
            } catch (Throwable ignored) { }
        }
        if (etm == null) { note("ExtTelephonyManager not found"); return; }

        // 2. what it offers
        StringBuilder ms = new StringBuilder();
        for (Method m : etm.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            String n = m.getName();
            if (n.matches("(?i).*(nr|icon|connect|register|callback|instance|carrier|agg|band|endc|capab).*"))
                ms.append(ms.length() > 0 ? ", " : "").append(sig(m));
        }
        note("methods:");
        for (String one : ms.toString().split(", ")) if (one.matches("(?i).*(nricon|connect|register).*")) note("  " + one);

        Method gi = null;
        for (Method m : etm.getMethods()) if (m.getName().equals("getInstance") && m.getParameterTypes().length == 1) gi = m;
        step = "getInstance";
        mgr = gi != null ? gi.invoke(null, ctx) : etm.getConstructor(Context.class).newInstance(ctx);

        step = "listener";
        // 3. callback listener: a concrete class whose onNrIconType we hook
        Object listener = null;
        Class<?> lc = null;
        for (String n : new String[]{"com.qti.extphone.ExtPhoneCallbackListener", "com.qti.extphone.ExtPhoneCallbackBase"}) {
            try {
                lc = Class.forName(n, true, cl);
                List<Class<?>> cs = new ArrayList<>();
                cs.add(lc);
                for (Class<?> in : lc.getDeclaredClasses()) cs.add(in);
                int hooked = 0;
                for (Class<?> k : cs) for (Method m : k.getDeclaredMethods()) {
                    final String mn = m.getName();
                    if (!mn.startsWith("on") || mn.equals("onTransact") || Modifier.isAbstract(m.getModifiers())) continue;
                    final String where = k.getSimpleName();
                    XC_MethodHook h = new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                if (SEEN.add(where + "." + mn)) note("callback " + where + "." + mn + " " + args(p.args));
                                if (mn.equals("onNrIconType")) onIcon(p.args);
                                else if (mn.equals("onNrIcon")) onNrIcon(p.args);
                            } catch (Throwable t) { note("callback parse failed: " + t); }
                        }
                    };
                    XposedBridge.class.getMethod("hookMethod", java.lang.reflect.Member.class, XC_MethodHook.class)
                            .invoke(null, m, h);
                    hooked++;
                }
                note("hooked " + hooked + " callbacks");
                listener = newInstance(lc, ctx);
                if (listener != null) { note("listener " + lc.getSimpleName()); break; }
            } catch (Throwable t) {
                note("listener " + n + ": " + cause(t));
            }
        }
        if (listener == null) { note("no listener class usable"); return; }
        final Object lis = listener;

        // 4. connect, then register and find the query
        Method conn = null;
        for (Method m : etm.getMethods())
            if ((m.getName().equals("connectExtTelephonyService") || m.getName().equals("connectService"))
                    && m.getParameterTypes().length == 1) conn = m;
        if (conn == null) { note("no connect method"); return; }
        Class<?> cbType = conn.getParameterTypes()[0];
        Object cb = Proxy.newProxyInstance(cbType.getClassLoader(), new Class<?>[]{cbType}, (proxy, m, a) -> {
            if (m.getDeclaringClass() == Object.class) {
                if (m.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (m.getName().equals("equals")) return proxy == a[0];
                return "QtiNrCallback";
            }
            note("service: " + m.getName() + (a != null && a.length > 0 ? " " + a[0] : ""));
            if (m.getName().equals("onConnected") && (a == null || a.length == 0 || Boolean.TRUE.equals(a[0]))) {
                try { afterConnect(ctx, lis); } catch (Throwable t) { note("register failed: " + cause(t)); }
            }
            return null;
        });
        step = "connect";
        Object r = conn.invoke(mgr, cb);
        note("connect asked: " + r);
    }

    private static Object newInstance(Class<?> c, Context ctx) throws Throwable {
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            Class<?>[] p = k.getParameterTypes();
            k.setAccessible(true);
            if (p.length == 0) return k.newInstance();
            if (p.length == 1 && p[0] == android.os.Looper.class) return k.newInstance(android.os.Looper.getMainLooper());
            if (p.length == 1 && p[0] == Context.class) return k.newInstance(ctx);
        }
        return null;
    }

    private static void afterConnect(Context ctx, Object lis) throws Throwable {
        Method reg = null;
        // newer API first: registerCallbackWithEvents(String, listener, int[] events)
        for (Method m : mgr.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().startsWith("registerCallback") && p.length == 3 && p[0] == String.class
                    && p[1].isInstance(lis) && p[2] == int[].class) reg = m;
        }
        if (reg != null) {
            List<Integer> ev = new ArrayList<>(), all = new ArrayList<>();
            StringBuilder en = new StringBuilder();
            for (Class<?> k = lis.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    if (f.getType() != int.class || !Modifier.isStatic(f.getModifiers()) || !f.getName().startsWith("EVENT_")) continue;
                    f.setAccessible(true);
                    int v = f.getInt(null);
                    all.add(v);
                    if (f.getName().contains("NR_ICON")) { ev.add(v); en.append(f.getName()).append(' '); }
                }
            }
            if (ev.isEmpty()) ev = all;
            int[] arr = new int[ev.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = ev.get(i);
            note("events: " + (en.length() > 0 ? en.toString().trim() : "all " + arr.length));
            client = reg.invoke(mgr, ctx.getPackageName(), lis, arr);
        } else {
            Object arg = null;
            for (Method m : mgr.getClass().getMethods()) {
                if (!m.getName().equals("registerCallback")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 2 || p[0] != String.class) continue;
                if (p[1].isInstance(lis)) { reg = m; arg = lis; break; }
                for (java.lang.reflect.Field f : lis.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    Object v = f.get(lis);
                    if (v != null && p[1].isInstance(v)) { reg = m; arg = v; break; }
                }
                if (reg != null) break;
            }
            if (reg == null) { note("no matching registerCallback"); return; }
            client = reg.invoke(mgr, ctx.getPackageName(), arg);
        }
        note("registered via " + reg.getName() + ": " + (client == null ? "null" : client.getClass().getSimpleName()));
        for (Method m : mgr.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 2 || p[0] != int.class) continue;
            if (m.getName().equals("queryNrIconType")) query = m;
            if (m.getName().equals("queryNrIcon")) query2 = m;
        }
        note(query == null ? "no queryNrIconType" : "query ready" + (query2 != null ? " (+ queryNrIcon)" : ""));
        connected = client != null && query != null;
        lastQuery = 0;
        queryNow();
    }

    private static final java.util.Set<String> SEEN = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static Method query2;

    private static String args(Object[] a) {
        StringBuilder b = new StringBuilder();
        if (a != null) for (Object o : a) b.append(b.length() > 0 ? ", " : "").append(String.valueOf(o));
        return b.length() > 160 ? b.substring(0, 160) + "…" : b.toString();
    }

    /** onNrIcon(slotId, token, status, NrIcon): log what it holds the first few times. */
    private static int nrIconLogs;
    private static void onNrIcon(Object[] a) {
        if (nrIconLogs++ < 4) note("NrIcon " + args(a));
    }

    /** onNrIconType(slotId, token, status, NrIconType) */
    private static void onIcon(Object[] a) throws Throwable {
        int slot = -1, type = -1;
        for (Object o : a) {
            if (o instanceof Integer && slot < 0) slot = (Integer) o;
            else if (o != null && o.getClass().getSimpleName().equals("NrIconType")) {
                for (String g : new String[]{"get", "getType", "getValue"}) {
                    try { type = (Integer) o.getClass().getMethod(g).invoke(o); break; } catch (Throwable ignored) { }
                }
            }
        }
        if (slot < 0 || slot > 1) return;
        boolean changed = icon[slot] != type;
        icon[slot] = type;
        iconAt[slot] = System.currentTimeMillis();
        if (changed) note("SIM" + (slot + 1) + " icon type " + type + " = " + name(type));
        else synchronized (log) { report = summary(); }
        send(slot, type);
    }
}
