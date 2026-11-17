package com.netspeed.xposed;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XposedBridge;

/**
 * Tower positions from BeaconDB (a public cell-tower database, Mozilla Location Service
 * compatible), used to estimate the distance to a SIM's tower when timing advance isn't
 * available (idle SIM, 5G). Only tower IDs are sent; the distance is worked out on the phone.
 * Results are cached on disk, so each tower is looked up once.
 */
final class TowerDb {
    private static final String TAG = "NetSpeedXposed: [towers] ";
    private static final String API = "https://api.beacondb.net/v1/geolocate";
    private static final long RETRY_NOT_FOUND_MS = 24L * 3600_000;
    private static final long MIN_GAP_MS = 1_500;
    /** Tower positions less accurate than this are ignored (an area, not a tower). */
    private static final double MAX_ACCURACY_M = 3_000;

    /** key -> {lat, lng, accuracy}; NaN lat = not found (with time in [2]). */
    private static final Map<String, double[]> CACHE = new HashMap<>();
    private static boolean sLoaded = false;
    private static long sLastRequest = 0;

    private TowerDb() {}

    /** "lte:405:864:tac:cid" — the radio type, operator, area and cell id of a tower. */
    static String key(String radio, String mcc, String mnc, int tac, long cid) {
        if (mcc == null || mnc == null || tac <= 0 || tac == Integer.MAX_VALUE || cid <= 0
                || cid == Long.MAX_VALUE || cid == Integer.MAX_VALUE) return null;
        return radio + ":" + Integer.parseInt(mcc) + ":" + Integer.parseInt(mnc) + ":" + tac + ":" + cid;
    }

    /**
     * Distance in metres from the phone to the tower, or -1 if the tower's position or the
     * phone's location isn't known (yet). May do one network lookup; call off the main thread.
     */
    /** Known tower position {lat, lon} from the cache only (no network), or null. */
    static double[] position(Context ctx, String key) {
        if (key == null) return null;
        load(ctx);
        double[] t;
        synchronized (CACHE) {
            t = CACHE.get(key);
        }
        if (t == null || Double.isNaN(t[0]) || t[2] > MAX_ACCURACY_M) return null;
        return new double[]{t[0], t[1]};
    }

    static int distance(Context ctx, String key) {
        if (key == null) return -1;
        load(ctx);
        double[] t;
        synchronized (CACHE) {
            t = CACHE.get(key);
        }
        boolean haveKey = !Cfg.s(Cfg.OCID_KEY).isEmpty();
        if (t == null || (Double.isNaN(t[0]) && (System.currentTimeMillis() - t[2] > RETRY_NOT_FOUND_MS
                || (haveKey && t[1] != 1)))) {
            t = lookup(ctx, key);
            if (t == null) return -1;
        }
        if (Double.isNaN(t[0]) || t[2] > MAX_ACCURACY_M) return -1;
        Location me = myLocation(ctx);
        if (me == null) return -1;
        float[] res = new float[1];
        Location.distanceBetween(me.getLatitude(), me.getLongitude(), t[0], t[1], res);
        return Math.round(res[0]);
    }

    /** BeaconDB first, then OpenCelliD (if a token is set). Caches the result. */
    private static double[] lookup(Context ctx, String key) {
        long now = SystemClock.elapsedRealtime();
        if (now - sLastRequest < MIN_GAP_MS) return null;
        sLastRequest = now;
        double[] r = beacondb(key);
        if (r == null) return null; // network error: try again later
        String token = Cfg.s(Cfg.OCID_KEY);
        if (Double.isNaN(r[0]) && !token.isEmpty()) {
            double[] o = opencellid(key, token);
            if (o == null) return null;
            r = o;
            if (Double.isNaN(r[0])) r[1] = 1; // remember: OpenCelliD was asked too
        } else if (!Double.isNaN(r[0]) && !token.isEmpty()) {
            // both databases know it: combine them (each weighted by 1 / accuracy²) when they roughly agree
            double[] o = opencellid(key, token);
            if (o != null && !Double.isNaN(o[0])) {
                float[] dd = new float[1];
                Location.distanceBetween(r[0], r[1], o[0], o[1], dd);
                double a1 = Math.max(50, r[2]), a2 = Math.max(50, o[2]);
                if (dd[0] <= 3_000) {
                    double w1 = 1 / (a1 * a1), w2 = 1 / (a2 * a2);
                    r = new double[]{(r[0] * w1 + o[0] * w2) / (w1 + w2), (r[1] * w1 + o[1] * w2) / (w1 + w2),
                            Math.min(a1, a2)};
                } else if (a2 < a1) {
                    r = o;   // they disagree: trust the one with the tighter accuracy
                }
            }
        }
        synchronized (CACHE) {
            CACHE.put(key, r);
        }
        save(ctx);
        return r;
    }

    /** OpenCelliD cell/get. Returns {lat, lng, range}, {NaN, 0, time} if unknown, null on error. */
    private static double[] opencellid(String key, String token) {
        String[] k = key.split(":");
        try {
            String url = "https://opencellid.org/cell/get?key=" + java.net.URLEncoder.encode(token, "UTF-8")
                    + "&mcc=" + k[1] + "&mnc=" + k[2] + "&lac=" + k[3] + "&cellid=" + k[4]
                    + "&radio=" + (k[0].equals("nr") ? "NR" : "LTE") + "&format=json";
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8_000);
            c.setReadTimeout(8_000);
            c.setRequestProperty("User-Agent", "NetSpeedXposed/1.0 (github.com/dmk6091/NetSpeedXposed)");
            int code = c.getResponseCode();
            java.io.InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                try (BufferedReader r = new BufferedReader(new java.io.InputStreamReader(in, "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
            }
            Matcher lat = Pattern.compile("\"lat\"\\s*:\\s*(-?[0-9.]+)").matcher(sb);
            Matcher lon = Pattern.compile("\"lon\"\\s*:\\s*(-?[0-9.]+)").matcher(sb);
            Matcher rng = Pattern.compile("\"range\"\\s*:\\s*([0-9.]+)").matcher(sb);
            if (code == 200 && lat.find() && lon.find()) {
                double acc = rng.find() ? Double.parseDouble(rng.group(1)) : 0;
                XposedBridge.log(TAG + key + " found in OpenCelliD, range " + Math.round(acc) + " m");
                return new double[]{Double.parseDouble(lat.group(1)), Double.parseDouble(lon.group(1)), acc};
            }
            String text = sb.toString().toLowerCase(java.util.Locale.US);
            if (text.contains("not found") || code == 404) {
                XposedBridge.log(TAG + key + " not in OpenCelliD either");
                return new double[]{Double.NaN, 0, System.currentTimeMillis()};
            }
            String ans = (sb.length() > 120 ? sb.substring(0, 120) : sb.toString()).replace(token, "<token>");
            XposedBridge.log(TAG + "OpenCelliD answered " + code + ": " + ans);
            return null;
        } catch (Throwable t) {
            // never write the token to the log, even if an error message contains the request address
            String msg = String.valueOf(t);
            if (token != null && !token.isEmpty()) msg = msg.replace(token, "<token>");
            msg = msg.replaceAll("key=[^&\\s]+", "key=<token>");
            XposedBridge.log(TAG + "OpenCelliD lookup failed: " + msg);
            return null;
        }
    }

    /** BeaconDB lookup. Returns {lat, lng, accuracy}, {NaN, 0, time} if unknown, null on error. */
    private static double[] beacondb(String key) {
        String[] k = key.split(":");
        String body = "{\"considerIp\":false,\"fallbacks\":{\"lacf\":false,\"ipf\":false},"
                + "\"cellTowers\":[{\"radioType\":\"" + k[0] + "\",\"mobileCountryCode\":" + k[1]
                + ",\"mobileNetworkCode\":" + k[2] + ",\"locationAreaCode\":" + k[3]
                + ",\"cellId\":" + k[4] + "}]}";
        double[] result;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
            c.setConnectTimeout(8_000);
            c.setReadTimeout(8_000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "NetSpeedXposed/1.0 (github.com/dmk6091/NetSpeedXposed)");
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes("UTF-8"));
            }
            int code = c.getResponseCode();
            if (code == 200) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                Matcher lat = Pattern.compile("\"lat\"\\s*:\\s*(-?[0-9.]+)").matcher(sb);
                Matcher lng = Pattern.compile("\"lng\"\\s*:\\s*(-?[0-9.]+)").matcher(sb);
                Matcher acc = Pattern.compile("\"accuracy\"\\s*:\\s*([0-9.]+)").matcher(sb);
                if (lat.find() && lng.find()) {
                    result = new double[]{Double.parseDouble(lat.group(1)), Double.parseDouble(lng.group(1)),
                            acc.find() ? Double.parseDouble(acc.group(1)) : 0};
                    XposedBridge.log(TAG + key + " found, accuracy " + Math.round(result[2]) + " m");
                } else {
                    result = new double[]{Double.NaN, 0, System.currentTimeMillis()};
                }
            } else if (code == 404 || code == 400) {
                // Not in the database (or this radio type isn't supported): try again tomorrow.
                result = new double[]{Double.NaN, 0, System.currentTimeMillis()};
                XposedBridge.log(TAG + key + " not in BeaconDB (" + code + ")");
            } else {
                XposedBridge.log(TAG + key + " lookup failed: HTTP " + code);
                return null;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + key + " lookup failed: " + t);
            return null;
        }
        return result;
    }

    // ------------------------------------------------------------ phone location

    private static Location sLoc;
    private static long sLocRequested = 0;
    private static boolean sPassive = false;

    /**
     * Listens to location fixes other apps already ask for (Maps, delivery apps' navigation):
     * free, accurate GPS positions without turning GPS on ourselves.
     */
    private static void listenPassive(Context ctx, LocationManager lm) {
        if (sPassive) return;
        sPassive = true;
        try {
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 5_000, 10f,
                    ctx.getMainExecutor(), l -> {
                        if (l != null && (sLoc == null
                                || l.getElapsedRealtimeNanos() > sLoc.getElapsedRealtimeNanos())) sLoc = l;
                    });
            XposedBridge.log(TAG + "listening to passive location");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "passive location not available: " + t);
        }
    }

    /** A recent, accurate fix (for learning tower positions), or null. */
    static Location freshLocation(Context ctx, long maxAgeMs, float maxAccuracyM) {
        Location l = myLocation(ctx);
        if (l == null) return null;
        long age = (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000;
        if (age > maxAgeMs || !l.hasAccuracy() || l.getAccuracy() > maxAccuracyM) return null;
        return l;
    }

    /** Newest known location of the phone (at most 10 minutes old), or null. */
    static Location myLocation(Context ctx) {
        try {
            LocationManager lm = ctx.getSystemService(LocationManager.class);
            if (lm == null) return null;
            listenPassive(ctx, lm);
            Location best = sLoc;
            for (String p : new String[]{"fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                try {
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getElapsedRealtimeNanos() > best.getElapsedRealtimeNanos())) best = l;
                } catch (Throwable ignored) { }
            }
            long ageMs = best == null ? Long.MAX_VALUE
                    : (SystemClock.elapsedRealtimeNanos() - best.getElapsedRealtimeNanos()) / 1_000_000;
            // Stale: ask once a minute for a fresh, low-power (network) fix.
            if (ageMs > 60_000 && SystemClock.elapsedRealtime() - sLocRequested > 60_000) {
                sLocRequested = SystemClock.elapsedRealtime();
                String provider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                        ? LocationManager.NETWORK_PROVIDER : LocationManager.GPS_PROVIDER;
                lm.getCurrentLocation(provider, null, ctx.getMainExecutor(), l -> {
                    if (l != null) sLoc = l;
                });
            }
            return ageMs <= 10 * 60_000 ? best : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ disk cache

    private static File file(Context ctx) {
        return new File(ctx.getCacheDir(), "netspeed_towers.txt");
    }

    private static void load(Context ctx) {
        synchronized (CACHE) {
            if (sLoaded) return;
            sLoaded = true;
            try (BufferedReader r = new BufferedReader(new FileReader(file(ctx)))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split(" ");
                    if (p.length == 4) {
                        CACHE.put(p[0], new double[]{Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                                Double.parseDouble(p[3])});
                    }
                }
            } catch (Throwable ignored) { }
        }
    }

    private static void save(Context ctx) {
        synchronized (CACHE) {
            try (FileWriter w = new FileWriter(file(ctx))) {
                for (Map.Entry<String, double[]> e : CACHE.entrySet()) {
                    double[] v = e.getValue();
                    w.write(e.getKey() + " " + v[0] + " " + v[1] + " " + v[2] + "\n");
                }
            } catch (Throwable ignored) { }
        }
    }
}
