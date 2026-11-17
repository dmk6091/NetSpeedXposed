package com.netspeed.xposed;

import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppPredictionSessionId;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.AppTargetId;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.CancellationSignal;
import android.os.Process;
import android.service.appprediction.AppPredictionService;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * App suggestions for the launcher (Pixel-style "Suggestions"): the launcher opens sessions
 * ("hotseat" = empty dock slots, "home" = the row at the top of the app drawer) and we answer
 * with a ranked list of apps. Ranking uses this phone's own usage history only.
 *
 * Build A: simple ranking (time used in the last 7 days + a boost for recently used apps).
 */
public class PredictionService extends AppPredictionService {
    static final String TAG = "NetSpeedPredict";
    static final String STATUS_PREFS = "predict_status";

    private final Map<AppPredictionSessionId, AppPredictionContext> sessions = new ConcurrentHashMap<>();
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    /** Screen on? Kept by a receiver, so the ticker costs nothing while the phone sleeps. */
    private volatile boolean screenOn = true;
    private android.content.BroadcastReceiver screenRx;

    /** Backup trigger: every 5 s while the screen is on, if an app went to the background,
     *  refresh the suggestions. (It used to run every 2 s all day: ~200 mAh a day.) */
    private final java.util.concurrent.ScheduledExecutorService ticker =
            Executors.newSingleThreadScheduledExecutor();
    private long lastCheck = System.currentTimeMillis();
    private long lastBeat = 0;

    private long ticks = 0;

    private void watchApps() {
        ticker.scheduleWithFixedDelay(() -> {
            try {
                if (!screenOn) {
                    lastCheck = System.currentTimeMillis();   // nothing to catch up on later
                    return;
                }
                ticks++;
                long n0 = System.currentTimeMillis();
                // Heartbeat first, so it shows even when something below fails.
                if (n0 - lastBeat > 60_000) {
                    lastBeat = n0;
                    status("alive", now() + " (pid " + Process.myPid() + ", " + ticks + " ticks, "
                            + sessions.size() + " sessions)");
                }
                if (sessions.isEmpty()) return;
                UsageStatsManager usm = getSystemService(UsageStatsManager.class);
                long now = System.currentTimeMillis();
                android.app.usage.UsageEvents ev = usm.queryEvents(lastCheck, now);
                lastCheck = now;
                android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
                boolean changed = false;
                while (ev.hasNextEvent()) {
                    ev.getNextEvent(e);
                    if (e.getEventType() == android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED) changed = true;
                }
                if (changed) exec.execute(this::pushAll);
            } catch (Throwable t) {
                status("tick_error", now() + ": " + t);
            }
        }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void onDestroy() {
        status("destroyed", now());
        ticker.shutdownNow();
        try { if (screenRx != null) unregisterReceiver(screenRx); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
            if (pm != null) screenOn = pm.isInteractive();
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            screenRx = new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, Intent i) {
                    screenOn = Intent.ACTION_SCREEN_ON.equals(i.getAction());
                }
            };
            registerReceiver(screenRx, f, android.content.Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            screenOn = true;
        }
        watchApps();
        status("service", "started " + now());
        Log.i(TAG, "prediction service started");
    }

    @Override
    public void onCreatePredictionSession(AppPredictionContext context, AppPredictionSessionId id) {
        sessions.put(id, context);
        String s = context.getUiSurface() + " (" + context.getPredictedTargetCount() + " apps) for "
                + context.getPackageName();
        Log.i(TAG, "session opened: " + s);
        status("session_" + context.getUiSurface(), s + " at " + now());
    }

    @Override
    public void onRequestPredictionUpdate(AppPredictionSessionId id) {
        status("request", now());
        // The launcher only asks again for the app drawer row when you come back home; the dock
        // only changes when we push. So any request refreshes every surface.
        exec.execute(this::pushAll);
    }

    private long lastPushAll = 0;

    private void pushAll() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastPushAll < 300) return;
        lastPushAll = now;
        for (AppPredictionSessionId s : sessions.keySet()) push(s);
    }

    @Override
    public void onStartPredictionUpdates() {
        exec.execute(() -> {
            for (AppPredictionSessionId id : sessions.keySet()) push(id);
        });
    }

    @Override
    public void onAppTargetEvent(AppPredictionSessionId id, AppTargetEvent event) {
        status("event", now());
        try {
            AppTarget t = event.getTarget();
            Log.i(TAG, "event " + event.getAction() + " " + (t != null ? t.getPackageName() : "?")
                    + " at " + event.getLaunchLocation());
        } catch (Throwable ignored) { }
        // Something was launched: refresh every surface so the next time home shows, it's current.
        exec.execute(() -> {
            for (AppPredictionSessionId s : sessions.keySet()) push(s);
        });
    }

    @Override
    public void onLaunchLocationShown(AppPredictionSessionId id, String launchLocation, List<AppTargetId> ids) {
    }

    @Override
    public void onSortAppTargets(AppPredictionSessionId id, List<AppTarget> targets,
            CancellationSignal cancel, Consumer<List<AppTarget>> callback) {
        exec.execute(() -> {
            Map<String, Double> score = scores();
            List<AppTarget> sorted = new ArrayList<>(targets);
            sorted.sort((a, b) -> Double.compare(
                    score.getOrDefault(b.getPackageName(), 0d), score.getOrDefault(a.getPackageName(), 0d)));
            callback.accept(sorted);
        });
    }

    @Override
    public void onDestroyPredictionSession(AppPredictionSessionId id) {
        sessions.remove(id);
    }

    // ------------------------------------------------------------ ranking

    private void push(AppPredictionSessionId id) {
        AppPredictionContext ctx = sessions.get(id);
        if (ctx == null) return;
        String surface = String.valueOf(ctx.getUiSurface());
        List<AppTarget> out = new ArrayList<>();
        try {
            // Only app surfaces; the launcher also asks for widget suggestions, which we don't do.
            if (surface.equals("hotseat") || surface.equals("home")) {
                int n = Math.max(1, ctx.getPredictedTargetCount());
                // A few extra, so apps already pinned in the dock can be skipped by the launcher.
                List<String[]> apps = ranked(n + 6);
                int rank = 0;
                StringBuilder names = new StringBuilder();
                for (String[] a : apps) {
                    out.add(new AppTarget.Builder(new AppTargetId("app:" + a[0] + "/" + a[1]), a[0],
                            Process.myUserHandle()).setClassName(a[1]).setRank(rank++).build());
                    if (names.length() > 0) names.append(", ");
                    names.append(a[0]);
                }
                status("last_" + surface, now() + ": " + names);
                Log.i(TAG, surface + " -> " + names);
            }
            updatePredictions(id, out);
        } catch (Throwable t) {
            Log.w(TAG, "prediction failed", t);
            status("error", now() + ": " + t);
        }
    }

    private long rootAt = 0;
    private Map<String, String> rootCache = new LinkedHashMap<>();

    /** package -> activity class, from "cmd package query-activities" run with root; cached 10 minutes. */
    private synchronized Map<String, String> rootLaunchable() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (rootAt != 0 && now - rootAt < 10 * 60_000L) return rootCache;
        Map<String, String> out = new LinkedHashMap<>();
        try {
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"});
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    int sl = line.indexOf('/');
                    if (sl <= 0 || line.indexOf(' ') >= 0 || line.contains("=")) continue;
                    String pkg = line.substring(0, sl), cls = line.substring(sl + 1);
                    if (cls.startsWith(".")) cls = pkg + cls;
                    out.putIfAbsent(pkg, cls);
                }
            }
            p.waitFor();
        } catch (Throwable t) {
            status("rootlist", "root list failed: " + t);
        }
        if (!out.isEmpty()) rootCache = out;
        rootAt = now;
        return rootCache;
    }

    /** Launchable apps, best first: {package, activity class}. */
    private List<String[]> ranked(int max) {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, String> launchable = new LinkedHashMap<>();
        // Same list the launcher's app drawer shows (LauncherApps), then whatever PackageManager adds.
        try {
            android.content.pm.LauncherApps la = getSystemService(android.content.pm.LauncherApps.class);
            for (android.content.pm.LauncherActivityInfo ai : la.getActivityList(null, Process.myUserHandle())) {
                String pkg = ai.getComponentName().getPackageName();
                if (pkg.equals(getPackageName())) continue;
                launchable.putIfAbsent(pkg, ai.getComponentName().getClassName());
            }
        } catch (Throwable t) {
            status("error", now() + ": launcher apps: " + t);
        }
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            launchable.putIfAbsent(pkg, ri.activityInfo.name);
        }
        // Some apps (sideloaded ones like Termux, MiXplorer here) are hidden from this process's package
        // list although the launcher shows them. Ask root once in a while for the full list.
        Map<String, String> rootList = rootLaunchable();
        int added = 0;
        for (Map.Entry<String, String> e : rootList.entrySet()) {
            if (e.getKey().equals(getPackageName())) continue;
            if (launchable.putIfAbsent(e.getKey(), e.getValue()) == null) added++;
        }
        status("rootlist", rootList.size() + " from root, " + added + " not visible to the app");
        Map<String, Double> score = scores();
        List<String> pkgs = new ArrayList<>(launchable.keySet());
        // Pickers and installers that other apps open on your behalf say nothing about what you want to open.
        pkgs.remove("com.android.documentsui");
        pkgs.remove("com.google.android.documentsui");
        pkgs.remove("com.android.packageinstaller");
        pkgs.remove("com.google.android.packageinstaller");
        if (score.isEmpty()) {
            // No usage access yet: alphabetical, so at least something shows.
            Collections.sort(pkgs);
        } else {
            pkgs.sort((a, b) -> Double.compare(score.getOrDefault(b, 0d), score.getOrDefault(a, 0d)));
        }
        try {
            StringBuilder d = new StringBuilder(launchable.size() + " launchable; top: ");
            for (int i = 0; i < Math.min(4, pkgs.size()); i++) {
                d.append(pkgs.get(i)).append('=').append(Math.round(score.getOrDefault(pkgs.get(i), 0d))).append(' ');
            }
            d.append("| recent:");
            for (Map.Entry<String, Double> e : score.entrySet()) {
                if (e.getValue() >= 10_000) {
                    d.append(' ').append(e.getKey()).append(launchable.containsKey(e.getKey()) ? "" : "(not launchable)");
                }
            }
            status("debug", d.toString());
        } catch (Throwable ignored) { }
        List<String[]> out = new ArrayList<>();
        for (String p : pkgs) {
            if (out.size() >= max) break;
            out.add(new String[]{p, launchable.get(p)});
        }
        return out;
    }

    /**
     * Usage score per package. Apps you closed in the last 30 minutes come first, most recent
     * first (so an app you just left shows up in the dock); after them, minutes used in 7 days.
     */
    private Map<String, Double> scores() {
        Map<String, Double> m = new HashMap<>();
        try {
            UsageStatsManager usm = getSystemService(UsageStatsManager.class);
            long now = System.currentTimeMillis();
            // Precise "last left" times from the event log of the last 30 minutes.
            Map<String, Long> left = new HashMap<>();
            try {
                android.app.usage.UsageEvents ev = usm.queryEvents(now - 30 * 60_000L, now);
                android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
                while (ev.hasNextEvent()) {
                    ev.getNextEvent(e);
                    int t = e.getEventType();
                    if (t == android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED
                            || t == android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED
                            || t == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                        left.merge(e.getPackageName(), e.getTimeStamp(), Math::max);
                    }
                }
            } catch (Throwable ignored) { }
            for (Map.Entry<String, Long> e : left.entrySet()) {
                double agoMin = (now - e.getValue()) / 60_000d;
                m.put(e.getKey(), 10_000 + (30 - Math.min(30, agoMin)) * 100);
            }
            Map<String, UsageStats> all = usm.queryAndAggregateUsageStats(now - 7L * 24 * 3600_000, now);
            for (Map.Entry<String, UsageStats> e : all.entrySet()) {
                UsageStats u = e.getValue();
                double s = u.getTotalTimeInForeground() / 60_000d;
                long ago = now - u.getLastTimeUsed();
                if (ago < 6 * 3600_000) s += 10;
                if (s > 0) m.merge(e.getKey(), s, Double::sum);
            }
            if (m.isEmpty()) status("usage", "no usage history readable (usage access not granted?)");
            else status("usage", m.size() + " apps with history");
        } catch (Throwable t) {
            status("usage", "can't read usage: " + t);
        }
        return m;
    }

    // ------------------------------------------------------------ status (shown in settings)

    private void status(String key, String value) {
        try {
            SharedPreferences sp = createDeviceProtectedStorageContext().getSharedPreferences(STATUS_PREFS, 0);
            sp.edit().putString(key, value).apply();
        } catch (Throwable ignored) { }
    }

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }
}
