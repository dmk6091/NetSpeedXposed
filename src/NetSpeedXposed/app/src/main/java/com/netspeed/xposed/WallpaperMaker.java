package com.netspeed.xposed;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;

import java.util.List;
import java.util.Locale;

/** Draws a map of where the phone is (street or relief tiles, fault lines, a position dot, an info card) and sets it as wallpaper. */
final class WallpaperMaker {
    private WallpaperMaker() {}

    static final String PREFS = "wp";
    static final int[] ZOOMS = {15, 14, 12};
    static final String[] ZOOM_NAMES = {"Street (about 2 km across)", "Area (about 5 km across)", "Wide (about 20 km across)"};
    static final int[] KMS = {1, 2, 5, 10};
    static final String[] STYLES = {"Street map", "Relief map (contours, hills shaded)"};
    static final String[] TARGETS = {"Home and lock screen", "Home screen only", "Lock screen only"};

    static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private static byte[] getBytes(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", "FieldAtlas/1.0 (github.com/dmk6091/NetSpeedXposed)");
        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
        try (java.io.InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        }
    }

    static String tileUrl(int style, int z, int x, int y) {
        return style == 1 ? "https://tile.opentopomap.org/" + z + "/" + x + "/" + y + ".png"
                : "https://tile.openstreetmap.org/" + z + "/" + x + "/" + y + ".png";
    }

    static double worldX(double lon, int z) { return (lon + 180.0) / 360.0 * (1 << z) * 256.0; }
    static double worldY(double lat, int z) {
        double s = Math.sin(Math.toRadians(lat));
        return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * (1 << z) * 256.0;
    }

    /** Elevation in metres, or NaN. */
    static double elevation(double lat, double lon) {
        try {
            String j = Geo.get(String.format(Locale.US, "https://api.open-meteo.com/v1/elevation?latitude=%.4f&longitude=%.4f", lat, lon), 15_000);
            return new org.json.JSONObject(j).getJSONArray("elevation").getDouble(0);
        } catch (Throwable t) { return Double.NaN; }
    }

    /** Bitmap of the map around (lat, lon). card = write the rock/soil/fault/relief lines. */
    static Bitmap draw(Context c, double lat, double lon, int z, int style, boolean card, String place) throws Exception {
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        final int W = dm.widthPixels, H = dm.heightPixels;
        final float S = 2f;                       // a 256 px tile is drawn 512 px wide
        final double cx = worldX(lon, z), cy = worldY(lat, z);
        final float px = W / 2f, py = H * 0.50f;  // where the position dot sits
        int x0 = (int) Math.floor((cx - px / S) / 256), x1 = (int) Math.floor((cx + (W - px) / S) / 256);
        int y0 = (int) Math.floor((cy - py / S) / 256), y1 = (int) Math.floor((cy + (H - py) / S) / 256);
        int n = 1 << z;
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.drawColor(Color.rgb(30, 34, 38));
        // tiles, 4 at a time
        final java.util.List<int[]> need = new java.util.ArrayList<>();
        for (int ty = y0; ty <= y1; ty++) for (int tx = x0; tx <= x1; tx++) if (ty >= 0 && ty < n) need.add(new int[]{tx, ty});
        final Bitmap[] got = new Bitmap[need.size()];
        final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        final int fz = z, fs = style, fn = n;
        Thread[] th = new Thread[4];
        for (int t = 0; t < th.length; t++) {
            th[t] = new Thread(() -> {
                int i;
                while ((i = next.getAndIncrement()) < need.size()) {
                    int[] q = need.get(i);
                    int xx = ((q[0] % fn) + fn) % fn;
                    for (int tries = 0; tries < 2 && got[i] == null; tries++) {
                        try {
                            byte[] b = getBytes(tileUrl(fs, fz, xx, q[1]));
                            got[i] = BitmapFactory.decodeByteArray(b, 0, b.length);
                        } catch (Throwable ignored) { }
                    }
                }
            });
            th[t].start();
        }
        for (Thread t : th) t.join();
        int ok = 0;
        Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
        for (int i = 0; i < need.size(); i++) {
            if (got[i] == null) continue;
            ok++;
            float dx = (float) ((need.get(i)[0] * 256 - cx) * S + px), dy = (float) ((need.get(i)[1] * 256 - cy) * S + py);
            cv.drawBitmap(got[i], null, new RectF(dx, dy, dx + 256 * S, dy + 256 * S), bp);
        }
        if (ok == 0) throw new java.io.IOException("no map tiles could be fetched");
        // dim, so icons and the clock stay readable
        cv.drawColor(Color.argb(style == 1 ? 70 : 95, 0, 0, 0));
        // faults
        double halfKm = Math.max(W, H) / S * 156543.03 * Math.cos(Math.toRadians(lat)) / (1 << z) / 1000.0;
        Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
        fp.setStyle(Paint.Style.STROKE);
        fp.setStrokeWidth(5f);
        fp.setColor(Color.argb(235, 255, 80, 70));
        fp.setStrokeCap(Paint.Cap.ROUND);
        fp.setStrokeJoin(Paint.Join.ROUND);
        try {
            for (float[][] l : Faults.linesIn(lat, lon, halfKm)) {
                Path p = new Path();
                for (int i = 0; i < l[0].length; i++) {
                    float x = (float) ((worldX(l[0][i], z) - cx) * S + px), y = (float) ((worldY(l[1][i], z) - cy) * S + py);
                    if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                }
                cv.drawPath(p, fp);
            }
        } catch (Throwable ignored) { }
        // position
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(Color.argb(70, 47, 127, 209));
        cv.drawCircle(px, py, 70, dot);
        dot.setColor(Color.WHITE);
        cv.drawCircle(px, py, 24, dot);
        dot.setColor(Color.rgb(47, 127, 209));
        cv.drawCircle(px, py, 17, dot);
        // calm band under the clock
        Paint g = new Paint();
        g.setShader(new LinearGradient(0, 0, 0, H * 0.22f, Color.argb(190, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, W, H * 0.22f, g);
        if (card) drawCard(cv, W, H, lat, lon, place);
        Paint at = new Paint(Paint.ANTI_ALIAS_FLAG);
        at.setColor(Color.argb(190, 255, 255, 255));
        at.setTextSize(W * 0.024f);
        cv.drawText(style == 1 ? "© OpenStreetMap contributors, OpenTopoMap (CC-BY-SA)" : "© OpenStreetMap contributors", W * 0.04f, H - H * 0.012f, at);
        return out;
    }

    private static void drawCard(Canvas cv, int W, int H, double lat, double lon, String place) {
        String rock = Geo.rockText(lat, lon), soil = Geo.soilText(lat, lon);
        double el = elevation(lat, lon);
        String fault = "";
        try {
            List<Faults.Hit> hs = Faults.nearest(lat, lon, 1);
            if (!hs.isEmpty()) fault = hs.get(0).label() + " · " + Math.round(hs.get(0).km) + " km " + hs.get(0).dir;
        } catch (Throwable ignored) { }
        String[][] rows = {{"Rock", rock.isEmpty() ? "no data" : rock}, {"Soil", soil.isEmpty() ? "no data" : soil},
                {"Fault", fault.isEmpty() ? "none in the list" : fault}, {"Relief", Double.isNaN(el) ? "no data" : Math.round(el) + " m above sea level"}};
        float u = W / 1080f;
        float left = 50 * u, right = W - 50 * u, top = H * 0.745f, rowH = 62 * u, head = 78 * u;
        float bottom = top + head + rows.length * rowH * 1.25f + 24 * u;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.argb(208, 10, 14, 18));
        cv.drawRoundRect(new RectF(left, top, right, bottom), 34 * u, 34 * u, bg);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(Color.WHITE);
        t.setTextSize(44 * u);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cv.drawText(place == null || place.isEmpty() ? String.format(Locale.US, "%.3f, %.3f", lat, lon) : place, left + 36 * u, top + 62 * u, t);
        t.setTypeface(Typeface.DEFAULT);
        t.setTextSize(31 * u);
        float y = top + head + 40 * u;
        int maxW = (int) (right - left - 36 * u * 2 - 150 * u);
        for (String[] r : rows) {
            t.setColor(Color.argb(255, 150, 165, 180));
            cv.drawText(r[0], left + 36 * u, y, t);
            t.setColor(r[0].equals("Fault") ? Color.rgb(255, 140, 120) : Color.rgb(224, 230, 236));
            String v = r[1];
            while (v.length() > 4 && t.measureText(v) > maxW) v = v.substring(0, v.length() - 2).trim();
            if (!v.equals(r[1])) v += "…";
            cv.drawText(v, left + 36 * u + 150 * u, y, t);
            y += rowH * 1.25f;
        }
    }

    /** Where we are now -> bitmap -> wallpaper. Returns a one-line result. */
    static String run(Context c, boolean force) {
        SharedPreferences sp = sp(c);
        try {
            Object[] w = DamJob.where(c);
            double lat = (Double) w[0], lon = (Double) w[1];
            if ((Long) w[2] < 0) return "no location fix yet";
            if (!force && sp.contains("lastLat")) {
                float[] d = new float[1];
                android.location.Location.distanceBetween(sp.getFloat("lastLat", 0), sp.getFloat("lastLon", 0), lat, lon, d);
                long age = System.currentTimeMillis() - sp.getLong("lastAt", 0);
                if (d[0] < sp.getInt("km", 2) * 1000f && age < 24 * 3600_000L) return "still near the last picture";
            }
            String place = "";
            try {
                org.json.JSONObject a = new org.json.JSONObject(Geo.get(String.format(Locale.US,
                        "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=14&lat=%.5f&lon=%.5f", lat, lon), 12_000)).optJSONObject("address");
                if (a != null) {
                    String[] ks = {"suburb", "neighbourhood", "village", "town", "city_district", "city", "county"};
                    String p1 = "", p2 = "";
                    for (String k : ks) { String v = a.optString(k, ""); if (!v.isEmpty()) { if (p1.isEmpty()) p1 = v; else if (p2.isEmpty() && !v.equals(p1)) p2 = v; } }
                    place = p2.isEmpty() ? p1 : p1 + ", " + p2;
                }
            } catch (Throwable ignored) { }
            Bitmap b = draw(c, lat, lon, ZOOMS[Math.max(0, Math.min(2, sp.getInt("zoom", 1)))], sp.getInt("style", 0), sp.getBoolean("card", true), place);
            WallpaperManager wm = WallpaperManager.getInstance(c);
            int tg = sp.getInt("target", 0);
            int flags = tg == 1 ? WallpaperManager.FLAG_SYSTEM : tg == 2 ? WallpaperManager.FLAG_LOCK : (WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
            wm.setBitmap(b, null, true, flags);
            b.recycle();
            sp.edit().putFloat("lastLat", (float) lat).putFloat("lastLon", (float) lon).putLong("lastAt", System.currentTimeMillis())
                    .putString("status", "ok").apply();
            return "Wallpaper set for " + (place.isEmpty() ? String.format(Locale.US, "%.3f, %.3f", lat, lon) : place);
        } catch (Throwable t) {
            String m = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
            sp.edit().putString("status", m).apply();
            return "Failed: " + m;
        }
    }
}
