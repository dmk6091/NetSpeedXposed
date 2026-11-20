package com.netspeed.xposed;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Once a day: finds the nearest CWC dam to where the phone last was, reads how full it is, keeps one reading a day
 * (for the trend) and posts a notification when the state changes (danger low / low / normal / high / danger high)
 * or the level has gone steady. "Danger" = % full only, not a flood warning.
 */
public class DamJob extends JobService {
    private static final int JOB_ID = 4712;
    private static final String CH = "dam_watch", CH_DANGER = "dam_danger";
    static final String PREFS = "dam";

    static void schedule(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null) return;
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, DamJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(8 * 3600 * 1000L)      // wakes 3 times a day, but only does the check once a day
                    .setPersisted(true)
                    .build());
        } catch (Throwable t) { android.util.Log.w("FieldAtlasDam", "schedule failed: " + t); }
    }

    static boolean scheduled(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js != null) for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    @Override
    public boolean onStartJob(JobParameters p) {
        final Context c = getApplicationContext();
        new Thread(() -> {
            try {
                String today = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
                SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                if (!today.equals(sp.getString("okDay", ""))) check(c, false);
            } catch (Throwable ignored) {
            } finally {
                jobFinished(p, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) { return true; }

    /** Where the phone is: a fresh fix, else the last one the app saw, else Ravet. {lat, lon, ageMin, sourceText}. */
    static Object[] where(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            android.location.Location l = TowerDb.myLocation(c);
            // the first call only asks for a fix; give it a few seconds to arrive
            for (int i = 0; i < 8 && l == null; i++) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                l = TowerDb.myLocation(c);
            }
            if (l == null) {   // any old fix is fine for "nearest dam"
                android.location.LocationManager lm = c.getSystemService(android.location.LocationManager.class);
                for (String p : new String[]{"fused", android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.PASSIVE_PROVIDER}) {
                    try {
                        android.location.Location x = lm.getLastKnownLocation(p);
                        if (x != null && (l == null || x.getTime() > l.getTime())) l = x;
                    } catch (Throwable ignored) { }
                }
            }
            if (l != null) {
                sp.edit().putFloat("lat", (float) l.getLatitude()).putFloat("lon", (float) l.getLongitude())
                        .putLong("fixAt", System.currentTimeMillis()).apply();
                return new Object[]{l.getLatitude(), l.getLongitude(), 0L, "GPS fix now"};
            }
        } catch (Throwable ignored) { }
        if (sp.contains("lat")) {
            long age = (System.currentTimeMillis() - sp.getLong("fixAt", 0)) / 60000L;
            return new Object[]{(double) sp.getFloat("lat", 0), (double) sp.getFloat("lon", 0), age, "last known fix"};
        }
        return new Object[]{18.62, 73.80, -1L, "no fix yet (using Ravet)"};
    }

    /**
     * Does the daily check. force = the "Check now" button (also fires notifications). Returns a one-line result.
     */
    static String check(Context c, boolean force) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit().putLong("tryAt", System.currentTimeMillis()).putString("err", "running…").commit();
        try {
            return check0(c, force);
        } catch (Throwable t) {
            String m = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
            sp.edit().putString("err", m).commit();
            return "Failed: " + m;
        }
    }

    private static String check0(Context c, boolean force) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        Object[] w = where(c);
        double lat = (Double) w[0], lon = (Double) w[1];
        sp.edit().putLong("tryAt", now).putString("where", (String) w[3]).putLong("whereAge", (Long) w[2]).apply();
        boolean ok = Dam.refresh(c.getCacheDir(), lat, lon, now);
        if (!ok) {
            sp.edit().putString("err", Dam.lastError.isEmpty() ? "no data" : Dam.lastError).putString("http", Dam.lastHttp).apply();
            return "Failed: " + sp.getString("err", "");
        }
        Dam.Res r = Dam.cur;
        Dam.Reading g = Dam.curRead;
        String prevUid = sp.getString("uid", "");
        int prevState = sp.getInt("state", -1);
        boolean prevSteady = sp.getBoolean("steady", false);
        boolean sameDam = r.uid.equals(prevUid);

        // one reading per reading-date, last 14 kept, for this dam
        org.json.JSONObject hist;
        try { hist = sameDam ? new org.json.JSONObject(sp.getString("hist", "{}")) : new org.json.JSONObject(); }
        catch (Throwable t) { hist = new org.json.JSONObject(); }
        String day = g.date.isEmpty() ? new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date(now)) : g.date;
        try {
            hist.put(day, Math.round(g.pct * 10) / 10.0);
            java.util.ArrayList<String> keys = new java.util.ArrayList<>();
            for (java.util.Iterator<String> it = hist.keys(); it.hasNext(); ) keys.add(it.next());
            java.util.Collections.sort(keys);
            while (keys.size() > 14) hist.remove(keys.remove(0));
        } catch (Throwable ignored) { }

        double perDay = trend(hist);                         // pct points per day over about 3 days, NaN if too little history
        boolean steady = !Double.isNaN(perDay) && Math.abs(perDay) <= 0.5;
        int st = Dam.state(g.pct);

        sp.edit().putString("uid", r.uid).putString("name", r.name).putString("river", r.river).putFloat("pct", (float) g.pct)
                .putFloat("storage", (float) g.storage).putFloat("liveCap", (float) g.liveCap)
                .putFloat("frl", (float) r.frl).putFloat("mw", (float) r.mw).putFloat("km", (float) Dam.curKm)
                .putString("date", g.date).putFloat("ly", (float) g.ly).putBoolean("approx", r.approx).putString("rtime", g.time).putString("district", r.district)
                .putString("hist", hist.toString()).putInt("state", st).putBoolean("steady", steady)
                .putLong("okAt", now).putString("okDay", new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date(now)))
                .putString("err", "").putString("http", Dam.lastHttp).apply();

        boolean notify = SettingsProvider.prefs(c).getBoolean(Cfg.DAM_NOTIF, Cfg.D_DAM_NOTIF);
        String result = r.name + " " + Math.round(g.pct) + "% (" + Dam.STATE_NAMES[st] + ")";
        if (notify) {
            if (!sameDam ? st != 2 : st != prevState) post(c, r, g, st, Dam.STATE_NAMES[st] + ": " + r.name + " " + Math.round(g.pct) + "% full", 4712);
            else if (force) post(c, r, g, st, r.name + " " + Math.round(g.pct) + "% full (" + Dam.STATE_NAMES[st].toLowerCase(Locale.ROOT) + ")", 4712);
            if (sameDam && steady && !prevSteady)
                post(c, r, g, 2, "Steady: " + r.name + " stays near " + Math.round(g.pct) + "% (" + String.format(Locale.US, "%+.1f", perDay) + " pts/day)", 4713);
        }
        return result;
    }

    /** Change in % points per day between the latest reading and the one about 3 days earlier; NaN if there isn't one. */
    static double trend(org.json.JSONObject hist) {
        try {
            java.util.ArrayList<String> keys = new java.util.ArrayList<>();
            for (java.util.Iterator<String> it = hist.keys(); it.hasNext(); ) keys.add(it.next());
            java.util.Collections.sort(keys);
            if (keys.size() < 2) return Double.NaN;
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
            String last = keys.get(keys.size() - 1);
            long lt = f.parse(last).getTime();
            for (int i = keys.size() - 2; i >= 0; i--) {
                long days = Math.round((lt - f.parse(keys.get(i)).getTime()) / 86400_000.0);
                if (days >= 3) return (hist.getDouble(last) - hist.getDouble(keys.get(i))) / days;
            }
        } catch (Throwable ignored) { }
        return Double.NaN;
    }

    private static void post(Context c, Dam.Res r, Dam.Reading g, int st, String title, int id) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            boolean danger = st == 0 || st == 4;
            if (nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(new NotificationChannel(CH, "Dam level", NotificationManager.IMPORTANCE_DEFAULT));
            if (nm.getNotificationChannel(CH_DANGER) == null)
                nm.createNotificationChannel(new NotificationChannel(CH_DANGER, "Dam level (danger low / danger high)", NotificationManager.IMPORTANCE_HIGH));
            Intent open = new Intent(c, SettingsActivity.class).putExtra("show_dam", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, 12, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String body = String.format(Locale.US, "%.3f of %.3f BCM live storage, %s away, reading %s. \"Danger\" means how full the "
                            + "reservoir is, not a flood warning.", g.storage, g.liveCap, Dam.kmText(Dam.curKm), g.date);
            Notification n = new Notification.Builder(c, danger ? CH_DANGER : CH)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setColor(Dam.STATE_COLORS[st])
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(id, n);
        } catch (Throwable t) { android.util.Log.w("FieldAtlasDam", "notify failed: " + t); }
    }

    /** Puts the job back after a reboot or an app update. */
    public static class Boot extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) { schedule(c); LocationWatch.schedule(c); }
    }
}
