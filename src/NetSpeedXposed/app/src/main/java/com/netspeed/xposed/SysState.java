package com.netspeed.xposed;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioManager;
import android.net.TrafficStats;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * What row 2 shows instead of the stock icons: notification counts, Bluetooth devices (type,
 * battery, codec), ringer / Do Not Disturb, next alarm and the Wi-Fi connection. Read on the
 * worker thread (refresh) except the cheap ones, which are read on the UI thread.
 */
final class SysState {
    private static final String TAG = "NetSpeedXposed: [sys] ";

    // ---- captured from System UI (hooks installed once)
    static volatile Object sNotifListener;     // System UI's NotificationListener (a NotificationListenerService)
    static volatile Object sHotspot;           // HotspotControllerImpl

    static void install(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.android.systemui.statusbar.NotificationListener", cl);
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    sNotifListener = p.thisObject;
                }
            });
            XposedBridge.log(TAG + "notification listener hook ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "notification listener hook failed: " + t);
        }
        try {
            Class<?> c = XposedHelpers.findClass("com.android.systemui.statusbar.policy.HotspotControllerImpl", cl);
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    sHotspot = p.thisObject;
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hotspot hook failed: " + t);
        }
    }

    // ---- notifications
    volatile int notifAll = -1, notifSilent = 0;

    // ---- Bluetooth
    static final class Dev {
        char kind;          // W watch, H headphones / speaker, P phone, C computer, K keyboard / mouse, ? other
        int battery = -1;   // %, -1 = not reported
        String codec = "";  // LDAC / AAC / SBC / aptX / LC3 / HFP / LE / BT
        String name = "";
        boolean audio;      // playing / carrying audio (shown first)
    }

    volatile boolean btOn;
    volatile List<Dev> btDevs = new ArrayList<>();

    // ---- ringer, alarm
    volatile int ringer = AudioManager.RINGER_MODE_NORMAL;
    volatile boolean dnd;
    volatile long nextAlarm = -1;

    // ---- Wi-Fi
    volatile boolean wifi;
    volatile int wifiRssi, wifiLevel, wifiFreq, wifiStd, wifiMbps;
    volatile boolean wifiDown, wifiUp;
    private long lastRx = -1, lastTx = -1, lastAt;

    // ---- hotspot
    volatile int hotspotClients = -1;

    private BluetoothProfile a2dp, headset, leAudio;
    private boolean proxiesAsked;
    private final Context ctx;

    // ---- NetSwitch (Magisk module) state, broadcast to System UI
    static volatile int nsSim = 1, nsRat = 5, nsPoor = 0, nsEco = 0;
    static volatile long nsAt = 0;
    /** Ping dot: last round trip in ms (-1 = failed) and when it was measured (0 = never). */
    static volatile int pingMs = -1;
    static volatile long pingAt = 0;
    private long pingTried = 0;
    /** "Test animations" button: when it was pressed (all animations play for ~8 s). */
    static volatile long animTestAt = 0;
    static boolean testing() { return animTestAt > 0 && System.currentTimeMillis() - animTestAt < 8_000; }
    private static boolean sNsRegistered;

    /** Debug: "am broadcast -a com.netspeed.xposed.DUMP_VIEWS -p com.android.systemui" writes every System UI window's view tree. */
    private static void dumpViews(Context c) {
        StringBuilder out = new StringBuilder();
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object g = wmg.getMethod("getInstance").invoke(null);
            Object views = XposedHelpers.getObjectField(g, "mViews");
            for (Object o : (java.util.List<?>) views) {
                if (!(o instanceof android.view.View)) continue;
                android.view.View root = (android.view.View) o;
                out.append("==== window root ").append(root.getClass().getName());
                try {
                    out.append("  title=").append(((android.view.WindowManager.LayoutParams) root.getLayoutParams()).getTitle());
                } catch (Throwable ignored) { }
                out.append('\n');
                dumpTree(root, 0, out, new int[]{0});
            }
        } catch (Throwable t) {
            out.append("dump failed: ").append(t).append('\n');
        }
        try {
            java.io.File f = new java.io.File(c.getCacheDir(), "views.txt");
            try (java.io.FileWriter w = new java.io.FileWriter(f)) {
                w.write(out.toString());
            }
            f.setReadable(true, false);
            XposedBridge.log(TAG + "view tree written to " + f + " (" + out.length() + " chars)");
            // also stream to the log, which root can read (the cache dir is not visible from the shell)
            XposedBridge.log("VDUMP-BEGIN");
            for (String line : out.toString().split("\n")) XposedBridge.log("VD|" + line);
            XposedBridge.log("VDUMP-END");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "view dump write failed: " + t);
        }
    }

    private static void dumpTree(android.view.View v, int depth, StringBuilder out, int[] count) {
        if (count[0]++ > 4000 || depth > 24) return;
        if (v.getVisibility() == android.view.View.GONE) return;
        String id = "";
        try {
            if (v.getId() != android.view.View.NO_ID) id = " id=" + v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable ignored) {
            id = " id=0x" + Integer.toHexString(v.getId());
        }
        int[] xy = new int[2];
        try { v.getLocationOnScreen(xy); } catch (Throwable ignored) { }
        for (int i = 0; i < depth; i++) out.append("  ");
        out.append(v.getClass().getName()).append(id)
                .append(" [").append(xy[0]).append(',').append(xy[1]).append(' ').append(v.getWidth()).append('x').append(v.getHeight())
                .append("] vis=").append(v.getVisibility() == 0 ? "V" : v.getVisibility() == 4 ? "I" : "G")
                .append(" a=").append(v.getAlpha()).append('\n');
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dumpTree(g.getChildAt(i), depth + 1, out, count);
        }
    }

    SysState(Context ctx) {
        this.ctx = ctx;
        registerNetSwitch(ctx);
    }

    private static synchronized void registerNetSwitch(Context c) {
        if (sNsRegistered) return;
        try {
            c.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context cx, android.content.Intent i) {
                    nsSim = i.getIntExtra("sim", 1);
                    nsRat = i.getIntExtra("rat", 5);
                    nsPoor = i.getIntExtra("poor", 0);
                    nsEco = i.getIntExtra("eco", 0);
                    nsAt = System.currentTimeMillis();
                    final String m = i.getStringExtra("msg");
                    if (m != null && !m.isEmpty()) {
                        XposedBridge.log(TAG + "NetSwitch event: " + m);
                        final Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                            try {
                                android.widget.Toast.makeText(app, m, android.widget.Toast.LENGTH_LONG).show();
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "toast failed: " + t);
                            }
                        });
                    }
                }
            }, new android.content.IntentFilter("com.dmk.netswitch.STATE"), Context.RECEIVER_EXPORTED);
            c.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context cx, android.content.Intent i) {
                    dumpViews(cx);
                }
            }, new android.content.IntentFilter("com.netspeed.xposed.DUMP_VIEWS"), Context.RECEIVER_EXPORTED);
            c.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context cx, android.content.Intent i) {
                    animTestAt = System.currentTimeMillis();
                }
            }, new android.content.IntentFilter("com.netspeed.xposed.ANIM_TEST"), Context.RECEIVER_EXPORTED);
            sNsRegistered = true;
            XposedBridge.log(TAG + "NetSwitch receiver registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "NetSwitch receiver failed: " + t);
        }
    }

    /** TCP connect time to 1.1.1.1:443, every ~6 s, only while the ping dot is switched on. */
    private void readPing() {
        boolean col = Cfg.b(Cfg.STATS_COL, Cfg.D_STATS_COL) || Cfg.b(Cfg.PRO, Cfg.D_PRO);
        if (!col && !Cfg.b(Cfg.ANIM_PING, Cfg.D_ANIM_PING)) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (!ScreenState.on()) return;                 // no pinging while the screen is off
        if (now - pingTried < 30_000) return;
        pingTried = now;
        long t0 = System.nanoTime();
        int ms = -1;
        java.net.Socket s = new java.net.Socket();
        try {
            s.connect(new java.net.InetSocketAddress("1.1.1.1", 443), 2000);
            ms = (int) ((System.nanoTime() - t0) / 1_000_000L);
        } catch (Throwable ignored) {
        } finally {
            try { s.close(); } catch (Throwable ignored) { }
        }
        pingMs = ms;
        pingAt = System.currentTimeMillis();
    }

    /** Worker thread, every ~2 s. */
    void refresh() {
        try {
            readPing();
        } catch (Throwable t) {
            log("ping", "ping: " + t);
        }
        try {
            readNotifications();
        } catch (Throwable t) {
            log("notif", "notifications: " + t);
        }
        try {
            readBluetooth();
        } catch (Throwable t) {
            log("bt", "bluetooth: " + t);
        }
        try {
            readWifi();
        } catch (Throwable t) {
            log("wifi", "wifi: " + t);
        }
        try {
            Object h = sHotspot;
            hotspotClients = h == null ? -1 : (Integer) XposedHelpers.callMethod(h, "getNumConnectedDevices");
        } catch (Throwable t) {
            hotspotClients = -1;
        }
    }

    /** UI thread, every second (cheap calls). */
    void refreshQuick() {
        try {
            AudioManager am = ctx.getSystemService(AudioManager.class);
            ringer = am.getRingerMode();
        } catch (Throwable ignored) {
        }
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            int f = nm.getCurrentInterruptionFilter();
            dnd = f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        } catch (Throwable ignored) {
        }
        try {
            AlarmManager al = ctx.getSystemService(AlarmManager.class);
            AlarmManager.AlarmClockInfo i = al.getNextAlarmClock();
            nextAlarm = i == null ? -1 : i.getTriggerTime();
            if (i != null && i.getShowIntent() != null) {
                String pkg = i.getShowIntent().getCreatorPackage();   // Tasker schedules its profiles as alarm clocks; not a real alarm
                if (pkg != null && pkg.startsWith("net.dinglisch.android.taskerm")) nextAlarm = -1;
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ notifications

    private void readNotifications() {
        Object l = sNotifListener;
        if (!(l instanceof NotificationListenerService)) {
            notifAll = -1;
            return;
        }
        NotificationListenerService nls = (NotificationListenerService) l;
        StatusBarNotification[] a;
        try {
            a = (StatusBarNotification[]) XposedHelpers.callMethod(nls, "getActiveNotifications", 1); // light copies
        } catch (Throwable t) {
            a = nls.getActiveNotifications();
        }
        if (a == null) {
            notifAll = -1;
            return;
        }
        NotificationListenerService.RankingMap rm = nls.getCurrentRanking();
        NotificationListenerService.Ranking r = new NotificationListenerService.Ranking();
        int all = 0, silent = 0;
        for (StatusBarNotification s : a) {
            Notification n = s.getNotification();
            if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
            all++;
            boolean quiet = false;
            if (rm != null && rm.getRanking(s.getKey(), r)) {
                quiet = r.isAmbient() || r.getImportance() <= NotificationManager.IMPORTANCE_LOW;
            }
            if (quiet) silent++;
        }
        notifAll = all;
        notifSilent = silent;
    }

    // ------------------------------------------------------------ Bluetooth

    private void askProxies(BluetoothAdapter ad) {
        if (proxiesAsked) return;
        proxiesAsked = true;
        BluetoothProfile.ServiceListener sl = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.A2DP) a2dp = proxy;
                else if (profile == BluetoothProfile.HEADSET) headset = proxy;
                else leAudio = proxy;
            }

            @Override
            public void onServiceDisconnected(int profile) {
                if (profile == BluetoothProfile.A2DP) a2dp = null;
                else if (profile == BluetoothProfile.HEADSET) headset = null;
                else leAudio = null;
            }
        };
        try {
            ad.getProfileProxy(ctx, sl, BluetoothProfile.A2DP);
            ad.getProfileProxy(ctx, sl, BluetoothProfile.HEADSET);
            ad.getProfileProxy(ctx, sl, 22 /* LE_AUDIO */);
        } catch (Throwable t) {
            log("proxy", "bluetooth profiles: " + t);
        }
    }

    private static boolean connected(BluetoothProfile p, BluetoothDevice d) {
        try {
            return p != null && p.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void readBluetooth() {
        BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) {
            btOn = false;
            btDevs = new ArrayList<>();
            return;
        }
        btOn = true;
        askProxies(ad);
        Set<BluetoothDevice> bonded = ad.getBondedDevices();
        List<Dev> out = new ArrayList<>();
        BluetoothDevice active = null;
        try {
            if (a2dp != null) active = (BluetoothDevice) XposedHelpers.callMethod(a2dp, "getActiveDevice");
        } catch (Throwable ignored) {
        }
        if (bonded != null) {
            for (BluetoothDevice d : bonded) {
                boolean conn;
                try {
                    conn = (Boolean) XposedHelpers.callMethod(d, "isConnected");
                } catch (Throwable t) {
                    conn = false;
                }
                if (!conn) continue;
                Dev v = new Dev();
                try {
                    String n = d.getAlias();
                    v.name = n != null ? n : d.getName();
                } catch (Throwable t) {
                    v.name = "";
                }
                v.kind = kind(d, v.name);
                try {
                    v.battery = (Integer) XposedHelpers.callMethod(d, "getBatteryLevel");
                    if (v.battery > 100) v.battery = -1;
                } catch (Throwable t) {
                    v.battery = -1;
                }
                v.codec = codec(d);
                v.audio = d.equals(active) || connected(a2dp, d) || connected(leAudio, d);
                out.add(v);
            }
        }
        // Watches do not report their battery over Bluetooth: the Astro phone app gets it from the watch
        for (Dev v : out) {
            if (v.kind == 'W' && v.battery < 0) v.battery = watchBattery();
        }
        // audio first, then the rest
        out.sort((x, y) -> Boolean.compare(y.audio, x.audio));
        btDevs = out;
    }

    private long wbRead;
    private int wbPct = -1;

    /** The watch's battery % as last sent by the Astro phone app (content provider), -1 if unknown or older than 6 h. */
    private int watchBattery() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - wbRead < 30_000) return wbPct;
        wbRead = now;
        wbPct = -1;
        android.database.Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    android.net.Uri.parse("content://com.dmk.astrocompanion.watchbattery"), null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int pct = c.getInt(c.getColumnIndex("pct"));
                long t = c.getLong(c.getColumnIndex("time"));
                if (pct >= 0 && pct <= 100 && System.currentTimeMillis() - t < 6 * 3600_000L) wbPct = pct;
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return wbPct;
    }

    private static char kind(BluetoothDevice d, String name) {
        int major = -1;
        try {
            BluetoothClass c = d.getBluetoothClass();
            if (c != null) major = c.getMajorDeviceClass();
        } catch (Throwable ignored) {
        }
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        if (major == BluetoothClass.Device.Major.WEARABLE || n.contains("watch")) return 'W';
        if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) return 'H';
        if (major == BluetoothClass.Device.Major.PHONE) return 'P';
        if (major == BluetoothClass.Device.Major.COMPUTER) return 'C';
        if (major == BluetoothClass.Device.Major.PERIPHERAL) return 'K';
        if (n.contains("bud") || n.contains("pods") || n.contains("ear") || n.contains("head") || n.contains("airdopes")
                || n.contains("jbl") || n.contains("boat") || n.contains("sound") || n.contains("speaker")) return 'H';
        if (n.contains("phone") || n.contains("galaxy s") || n.contains("pixel") || n.contains("redmi")) return 'P';
        return '?';
    }

    private String codec(BluetoothDevice d) {
        if (connected(a2dp, d)) {
            try {
                Object st = XposedHelpers.callMethod(a2dp, "getCodecStatus", d);
                Object cfg = st == null ? null : XposedHelpers.callMethod(st, "getCodecConfig");
                if (cfg != null) {
                    int t = (Integer) XposedHelpers.callMethod(cfg, "getCodecType");
                    switch (t) {
                        case 0: return "SBC";
                        case 1: return "AAC";
                        case 2: return "aptX";
                        case 3: return "aptX HD";
                        case 4: return "LDAC";
                        case 5: return "LC3";
                        case 6: return "Opus";
                        default: return "A2DP";
                    }
                }
            } catch (Throwable ignored) {
            }
            return "A2DP";
        }
        if (connected(leAudio, d)) return "LC3";
        if (connected(headset, d)) return "HFP";
        try {
            int ty = d.getType();
            if (ty == BluetoothDevice.DEVICE_TYPE_LE || ty == BluetoothDevice.DEVICE_TYPE_DUAL) return "LE";
        } catch (Throwable ignored) {
        }
        return "BT";
    }

    // ------------------------------------------------------------ Wi-Fi

    @SuppressWarnings("deprecation")
    private void readWifi() {
        WifiManager wm = ctx.getSystemService(WifiManager.class);
        WifiInfo wi = wm == null || !wm.isWifiEnabled() ? null : wm.getConnectionInfo();
        boolean on = wi != null && wi.getSupplicantState() == SupplicantState.COMPLETED && wi.getFrequency() > 0
                && wi.getRssi() > -127;
        wifi = on;
        long rx = TrafficStats.getTotalRxBytes() - Math.max(0, TrafficStats.getMobileRxBytes());
        long tx = TrafficStats.getTotalTxBytes() - Math.max(0, TrafficStats.getMobileTxBytes());
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastRx >= 0 && now > lastAt) {
            double s = (now - lastAt) / 1000.0;
            wifiDown = on && (rx - lastRx) / s > 2048;
            wifiUp = on && (tx - lastTx) / s > 2048;
        }
        lastRx = rx;
        lastTx = tx;
        lastAt = now;
        if (!on) return;
        wifiRssi = wi.getRssi();
        wifiFreq = wi.getFrequency();
        try {
            int max = wm.getMaxSignalLevel();
            int lv = wm.calculateSignalLevel(wifiRssi);
            wifiLevel = max <= 0 ? 0 : Math.round(lv * 4f / max);
        } catch (Throwable t) {
            wifiLevel = WifiManager.calculateSignalLevel(wifiRssi, 5);
        }
        try {
            wifiStd = wi.getWifiStandard();
        } catch (Throwable t) {
            wifiStd = 0;
        }
        try {
            wifiMbps = Math.max(wi.getRxLinkSpeedMbps(), wi.getTxLinkSpeedMbps());
        } catch (Throwable t) {
            wifiMbps = wi.getLinkSpeed();
        }
    }

    static String wifiGen(int std, int freq) {
        switch (std) {
            case 4: return "Wi-Fi 4";
            case 5: return "Wi-Fi 5";
            case 6: return freq >= 5925 ? "Wi-Fi 6E" : "Wi-Fi 6";
            case 7: return "WiGig";
            case 8: return "Wi-Fi 7";
            default: return "Wi-Fi";
        }
    }

    static String band(int freq) {
        if (freq >= 5925) return "6 GHz";
        if (freq >= 4900) return "5 GHz";
        if (freq >= 2400 && freq < 2500) return "2.4 GHz";
        return freq > 0 ? (freq / 1000f) + " GHz" : "?";
    }

    static int channel(int freq) {
        if (freq == 2484) return 14;
        if (freq >= 2412 && freq <= 2472) return (freq - 2407) / 5;
        if (freq >= 5925) return (freq - 5950) / 5;
        if (freq >= 4900) return (freq - 5000) / 5;
        return -1;
    }

    private static final java.util.Set<String> LOGGED = new java.util.HashSet<>();

    private static void log(String key, String msg) {
        synchronized (LOGGED) {
            if (!LOGGED.add(key)) return;
        }
        XposedBridge.log(TAG + msg);
    }
}
