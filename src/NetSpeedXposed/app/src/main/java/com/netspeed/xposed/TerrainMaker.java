package com.netspeed.xposed;

import android.content.Context;
import android.graphics.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live terrain wallpaper for anywhere in India (or the world): shaded relief and contours from AWS terrain tiles,
 * forest and water read from OpenStreetMap tiles, wetlands from OpenStreetMap (Overpass), faults from the app's own list.
 * Day and night looks. Needs the internet while it draws.
 */
final class TerrainMaker {
    private TerrainMaker() {}

    private static final String UA = "FieldAtlas/1.0 (github.com/dmk6091/NetSpeedXposed)";
    private static final float S = 2f;                    // grid pixel -> screen pixels
    static final int LAND = 0, FOREST = 1, WATER = 2, GRASS = 3, SCRUB = 4;

    static byte[] get(String url, int tries) {
        for (int t = 0; t < tries; t++) {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(10_000);
                c.setReadTimeout(25_000);
                c.setRequestProperty("User-Agent", UA);
                if (c.getResponseCode() != 200) { c.disconnect(); continue; }
                try (java.io.InputStream in = c.getInputStream()) {
                    java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                    byte[] b = new byte[16384];
                    int n;
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                    return o.toByteArray();
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static int[] grad(float[][] st, float v) {
        int i = 0;
        while (i < st.length - 2 && v > st[i + 1][0]) i++;
        float t = Math.max(0f, Math.min(1f, (v - st[i][0]) / (st[i + 1][0] - st[i][0])));
        return new int[]{(int) lerp(st[i][1], st[i + 1][1], t), (int) lerp(st[i][2], st[i + 1][2], t), (int) lerp(st[i][3], st[i + 1][3], t)};
    }

    private static final float[][] DAY_LAND = {{0, 196, 214, 170}, {60, 200, 214, 164}, {250, 206, 208, 156}, {550, 214, 196, 146},
            {800, 206, 178, 132}, {1100, 190, 160, 124}, {1500, 224, 208, 190}, {3000, 236, 236, 232}, {6000, 255, 255, 255}};
    private static final float[][] NIGHT_LAND = {{0, 14, 34, 34}, {60, 18, 44, 40}, {250, 28, 56, 44}, {550, 52, 66, 46},
            {800, 84, 78, 54}, {1100, 112, 96, 70}, {1500, 150, 134, 110}, {3000, 190, 190, 186}, {6000, 240, 240, 240}};

    static boolean isForest(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return g - r >= 28 && g - b >= 40 && r < 190 && g > 150;
    }

    /** OSM standard grass / meadow / park green (#cdebb0 and its neighbours). */
    static boolean isGrass(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return Math.abs(r - 205) <= 7 && Math.abs(g - 235) <= 7 && Math.abs(b - 176) <= 8;
    }

    /** OSM scrub (#c8d7ab). */
    static boolean isScrub(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return Math.abs(r - 200) <= 5 && Math.abs(g - 215) <= 5 && Math.abs(b - 171) <= 6;
    }

    static boolean isWater(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return Math.abs(r - 170) <= 7 && Math.abs(g - 211) <= 7 && Math.abs(b - 223) <= 7;
    }

    /** One 3x3 majority pass: a pixel stays set only if at least 5 of its 9 are set. */
    private static byte[] majority(byte[] m, int w, int h, int cls) {
        byte[] o = new byte[m.length];
        for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
            int n = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) if (m[(y + dy) * w + x + dx] == cls) n++;
            if (n >= 5) o[y * w + x] = (byte) cls;
        }
        return o;
    }

    /** Result of a draw, so the caller can show the numbers it found. */
    static final class Info {
        double forestKm = Double.NaN, wetKm = Double.NaN, reliefMin, reliefMax;
        int wetCount;
        String place = "";
    }

    static Bitmap draw(Context c, double lat, double lon, boolean day, int z, boolean card, Info info) throws Exception {
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        final int W = dm.widthPixels, H = dm.heightPixels;
        final int gw = (int) Math.ceil(W / S), gh = (int) Math.ceil(H / S);
        final double cx = WallpaperMaker.worldX(lon, z), cy = WallpaperMaker.worldY(lat, z);
        final float px = W / 2f, py = H * 0.50f;
        final double ox = cx - px / S, oy = cy - py / S;
        final int iox = (int) Math.floor(ox), ioy = (int) Math.floor(oy);
        final int tx0 = iox >> 8, tx1 = (iox + gw) >> 8, ty0 = ioy >> 8, ty1 = (ioy + gh) >> 8;
        final int n = 1 << z;
        final double mpp = 156543.03 * Math.cos(Math.toRadians(lat)) / n;      // metres per grid pixel

        final float[] el = new float[gw * gh];
        final byte[] cls = new byte[gw * gh];
        final boolean[] elOk = new boolean[1];
        final android.content.SharedPreferences wsp = WallpaperMaker.sp(c);
        final boolean showFaults = wsp.getInt("faults", 0) == 0, showCoast = wsp.getInt("coast", 0) == 0, showCities = wsp.getInt("cities", 0) == 0,
                showForest = wsp.getInt("forest", 0) == 0, showLegend = wsp.getInt("legend", 0) == 0,
                showRivers = wsp.getInt("rivers", 0) == 0, showDams = wsp.getInt("dams", 0) == 0, showParks = wsp.getInt("parks", 0) == 0, showPhys = wsp.getInt("phys", 0) == 0;
        { int dn0 = wsp.getInt("density", 2); IndiaMap.densF = dn0 == 0 ? 0.35f : dn0 == 1 ? 0.65f : 1f;
          IndiaMap.optTowns = wsp.getInt("towns", 0) == 0; IndiaMap.optGhats = wsp.getInt("ghats", 0) == 0;
          IndiaMap.optHills = wsp.getInt("hills", 0) == 0; IndiaMap.optList = wsp.getInt("nearlist", 0) == 0; }
        WpStatus.step(1);
        final List<int[]> need = new ArrayList<>();
        for (int ty = ty0; ty <= ty1; ty++) if (ty >= 0 && ty < n) for (int tx = tx0; tx <= tx1; tx++) need.add(new int[]{tx, ty});
        final AtomicInteger next = new AtomicInteger(), okEl = new AtomicInteger();
        final int fz = z;
        Thread[] th = new Thread[6];
        for (int t = 0; t < th.length; t++) {
            th[t] = new Thread(() -> {
                int i;
                int[] pix = new int[65536];
                while ((i = next.getAndIncrement()) < need.size()) {
                    int[] q = need.get(i);
                    int xx = ((q[0] % n) + n) % n;
                    byte[] a = get("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/" + fz + "/" + xx + "/" + q[1] + ".png", 3);
                    byte[] b = get("https://tile.openstreetmap.org/" + fz + "/" + xx + "/" + q[1] + ".png", 3);
                    Bitmap ba = a == null ? null : BitmapFactory.decodeByteArray(a, 0, a.length);
                    Bitmap bb = b == null ? null : BitmapFactory.decodeByteArray(b, 0, b.length);
                    if (ba != null) okEl.incrementAndGet();
                    for (int pass = 0; pass < 2; pass++) {
                        Bitmap bm = pass == 0 ? ba : bb;
                        if (bm == null || bm.getWidth() != 256 || bm.getHeight() != 256) continue;
                        bm.getPixels(pix, 0, 256, 0, 0, 256, 256);
                        for (int v = 0; v < 256; v++) {
                            int gy = q[1] * 256 + v - ioy;
                            if (gy < 0 || gy >= gh) continue;
                            for (int u = 0; u < 256; u++) {
                                int gx = q[0] * 256 + u - iox;
                                if (gx < 0 || gx >= gw) continue;
                                int p = pix[v * 256 + u];
                                if (pass == 0) el[gy * gw + gx] = (((p >> 16) & 255) * 256f + ((p >> 8) & 255) + (p & 255) / 256f) - 32768f;
                                else cls[gy * gw + gx] = isWater(p) ? (byte) WATER : isForest(p) ? (byte) FOREST : isGrass(p) ? (byte) GRASS : isScrub(p) ? (byte) SCRUB : (byte) LAND;
                            }
                        }
                    }
                    if (ba != null) ba.recycle();
                    if (bb != null) bb.recycle();
                }
            });
            th[t].start();
        }
        for (Thread t : th) t.join();
        if (okEl.get() == 0) throw new java.io.IOException("no terrain tiles could be fetched");

        // forest: clean the label / road specks; water: keep thin rivers
        byte[] fo = new byte[gw * gh];
        for (int i = 0; i < fo.length; i++) fo[i] = cls[i] == FOREST ? (byte) FOREST : 0;
        fo = majority(majority(fo, gw, gh, FOREST), gw, gh, FOREST);
        byte[] gr = new byte[gw * gh], sc = new byte[gw * gh];
        for (int i = 0; i < gr.length; i++) { gr[i] = cls[i] == GRASS ? (byte) GRASS : 0; sc[i] = cls[i] == SCRUB ? (byte) SCRUB : 0; }
        gr = majority(gr, gw, gh, GRASS);
        sc = majority(sc, gw, gh, SCRUB);
        if (!showForest) { java.util.Arrays.fill(fo, (byte) 0); java.util.Arrays.fill(gr, (byte) 0); java.util.Arrays.fill(sc, (byte) 0); }

        // smooth elevation (two box passes), then gradient
        float[] e2 = el.clone(), tmp = new float[el.length];
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < gh; y++) for (int x = 0; x < gw; x++) {
                float s = 0; int k = 0;
                for (int d = -1; d <= 1; d++) { int xx = x + d; if (xx >= 0 && xx < gw) { s += e2[y * gw + xx]; k++; } }
                tmp[y * gw + x] = s / k;
            }
            for (int y = 0; y < gh; y++) for (int x = 0; x < gw; x++) {
                float s = 0; int k = 0;
                for (int d = -1; d <= 1; d++) { int yy = y + d; if (yy >= 0 && yy < gh) { s += tmp[yy * gw + x]; k++; } }
                e2[y * gw + x] = s / k;
            }
        }
        float mn = 1e9f, mx = -1e9f;
        for (float v : e2) { if (v < mn) mn = v; if (v > mx) mx = v; }
        if (info != null) { info.reliefMin = mn; info.reliefMax = mx; }
        final float zex = (mx - mn) < 250 ? 6f : (mx - mn) < 900 ? 4f : 2.5f;
        final float step = (mx - mn) < 150 ? 20f : (mx - mn) < 500 ? 50f : 100f;

        int[] out = new int[gw * gh];
        float[][] pal = day ? DAY_LAND : NIGHT_LAND;
        for (int y = 0; y < gh; y++) for (int x = 0; x < gw; x++) {
            int i = y * gw + x;
            int xm = Math.max(0, x - 1), xp = Math.min(gw - 1, x + 1), ym = Math.max(0, y - 1), yp = Math.min(gh - 1, y + 1);
            float dzx = (e2[y * gw + xp] - e2[y * gw + xm]) / (2f * (float) mpp);
            float dzy = (e2[yp * gw + x] - e2[ym * gw + x]) / (2f * (float) mpp);
            float shade = Math.max(0f, Math.min(1f, 0.5f + zex * (-dzx - dzy) * 0.7f));
            float k = day ? 0.62f + 0.62f * shade : 0.50f + 0.72f * shade;
            int[] cc = grad(pal, Math.max(0f, e2[i]));
            float r = cc[0] * k, g = cc[1] * k, b = cc[2] * k;
            if (cls[i] == WATER || e2[i] < -1f) {
                float wk = day ? 0.92f + 0.10f * shade : 0.9f + 0.2f * shade;
                if (day) { r = 70 * wk; g = 140 * wk; b = 210 * wk; } else { r = 18 * wk; g = 54 * wk; b = 104 * wk; }
            } else {
                if (fo[i] == FOREST) {
                    float fk = day ? 0.65f + 0.55f * shade : 0.6f + 0.8f * shade;
                    float fr = day ? 52 : 20, fg = day ? 128 : 74, fb = day ? 62 : 40;
                    r = lerp(r, fr * fk, 0.82f); g = lerp(g, fg * fk, 0.82f); b = lerp(b, fb * fk, 0.82f);
                }
                if (fo[i] != FOREST && gr[i] == GRASS) {      // grassland and parks: light yellow-green
                    float gk = day ? 0.75f + 0.45f * shade : 0.6f + 0.8f * shade;
                    float fr = day ? 176 : 70, fg = day ? 206 : 112, fb = day ? 92 : 44;
                    r = lerp(r, fr * gk, 0.6f); g = lerp(g, fg * gk, 0.6f); b = lerp(b, fb * gk, 0.6f);
                } else if (fo[i] != FOREST && sc[i] == SCRUB) {   // scrub: olive
                    float gk = day ? 0.7f + 0.45f * shade : 0.6f + 0.8f * shade;
                    float fr = day ? 150 : 76, fg = day ? 146 : 84, fb = day ? 74 : 40;
                    r = lerp(r, fr * gk, 0.55f); g = lerp(g, fg * gk, 0.55f); b = lerp(b, fb * gk, 0.55f);
                }
                // contours
                float a = e2[i], rr = e2[y * gw + xp], dd = e2[yp * gw + x];
                int la = (int) Math.floor(a / step), lr = (int) Math.floor(rr / step), ld = (int) Math.floor(dd / step);
                if ((la != lr || la != ld) && a > 2f) {
                    boolean bold = (Math.max(la, Math.max(lr, ld)) % 5) == 0;
                    float al = bold ? 0.34f : 0.16f;
                    float cr = day ? 110 : 205, cg = day ? 84 : 215, cb = day ? 52 : 190;
                    r = lerp(r, cr, al); g = lerp(g, cg, al); b = lerp(b, cb, al);
                }
            }
            out[i] = 0xFF000000 | (Math.min(255, (int) r) << 16) | (Math.min(255, (int) g) << 8) | Math.min(255, (int) b);
        }
        Bitmap small = Bitmap.createBitmap(out, gw, gh, Bitmap.Config.ARGB_8888);
        Bitmap bmp = Bitmap.createScaledBitmap(small, W, H, true);
        small.recycle();
        Canvas cv = new Canvas(bmp);

        WpStatus.step(2);
        // nearest forest
        double bestF = Double.NaN;
        int fbx = -1, fby = -1;
        {
            double bd = 1e18;
            int gx0 = (int) (px / S), gy0 = (int) (py / S);
            for (int y = 0; y < gh; y++) for (int x = 0; x < gw; x++) if (fo[y * gw + x] == FOREST) {
                double d = (double) (x - gx0) * (x - gx0) + (double) (y - gy0) * (y - gy0);
                if (d < bd) { bd = d; fbx = x; fby = y; }
            }
            if (fbx >= 0) bestF = Math.sqrt(bd) * mpp / 1000.0;
        }

        WpStatus.step(3);
        // wetlands (OpenStreetMap, Overpass): best effort
        double halfLat = (H / S * mpp / 2000.0) / 111.2, halfLon = (W / S * mpp / 2000.0) / (111.3 * Math.cos(Math.toRadians(lat)));
        int wetN = 0, mangN = 0;
        double bestW = Double.NaN;
        float wbx = 0, wby = 0;
        final boolean bigMap = (H / S * mpp / 2000.0) > 60;
        if (bigMap) { halfLat = Math.min(halfLat, 0.45); halfLon = Math.min(halfLon, 0.47); WpStatus.note("wide map: wetlands and mangroves drawn only within about 50 km, no OpenStreetMap peak names (built-in list used)"); }
        try {
            String q = String.format(Locale.US, "[out:json][timeout:25][maxsize:60000000];(way[natural=wetland](%.4f,%.4f,%.4f,%.4f);relation[natural=wetland](%.4f,%.4f,%.4f,%.4f);way[wetland=mangrove][natural!=wetland](%.4f,%.4f,%.4f,%.4f););out geom qt;",
                    lat - halfLat, lon - halfLon, lat + halfLat, lon + halfLon, lat - halfLat, lon - halfLon, lat + halfLat, lon + halfLon, lat - halfLat, lon - halfLon, lat + halfLat, lon + halfLon);
            String js = overpass(q);
            if (js == null) WpStatus.note("wetlands skipped: OpenStreetMap servers busy");
            if (js != null) {
                Paint wp = new Paint(Paint.ANTI_ALIAS_FLAG);
                wp.setStyle(Paint.Style.FILL);
                final int wetCol = day ? Color.argb(185, 30, 170, 150) : Color.argb(150, 70, 225, 170);
                final int mangCol = day ? Color.argb(225, 120, 60, 150) : Color.argb(215, 200, 120, 235);   // mangrove: purple, so it is not mistaken for forest or marsh
                org.json.JSONArray els = new org.json.JSONObject(js).getJSONArray("elements");
                double bd = 1e18;
                for (int i = 0; i < els.length(); i++) {
                    org.json.JSONObject e = els.getJSONObject(i);
                    org.json.JSONObject tg = e.optJSONObject("tags");
                    boolean mang = tg != null && ("mangrove".equals(tg.optString("wetland")) || "mangrove".equals(tg.optString("leaf_type")));
                    wp.setColor(mang ? mangCol : wetCol);
                    if (mang) mangN++;
                    List<org.json.JSONArray> rings = new ArrayList<>();
                    if (e.has("geometry")) rings.add(e.getJSONArray("geometry"));
                    else if (e.has("members")) {
                        org.json.JSONArray ms = e.getJSONArray("members");
                        for (int m = 0; m < ms.length(); m++) {
                            org.json.JSONObject mo = ms.getJSONObject(m);
                            if ("inner".equals(mo.optString("role")) || !mo.has("geometry")) continue;
                            rings.add(mo.getJSONArray("geometry"));
                        }
                    }
                    for (org.json.JSONArray g : rings) {
                        int len = g.length();
                        if (len < 4) continue;
                        org.json.JSONObject a0 = g.getJSONObject(0), a1 = g.getJSONObject(len - 1);
                        if (a0.getDouble("lat") != a1.getDouble("lat") || a0.getDouble("lon") != a1.getDouble("lon")) continue;
                        Path p = new Path();
                        double sx = 0, sy = 0, area = 0, pxp = 0, pyp = 0;
                        for (int k = 0; k < len; k++) {
                            org.json.JSONObject pt = g.getJSONObject(k);
                            double x = (WallpaperMaker.worldX(pt.getDouble("lon"), z) - ox) * S, y = (WallpaperMaker.worldY(pt.getDouble("lat"), z) - oy) * S;
                            if (k == 0) p.moveTo((float) x, (float) y); else { p.lineTo((float) x, (float) y); area += pxp * y - x * pyp; }
                            pxp = x; pyp = y; sx += x; sy += y;
                        }
                        p.close();
                        if (Math.abs(area) / 2 < 20) continue;
                        cv.drawPath(p, wp);
                        wetN++;
                        sx /= len; sy /= len;
                        double d = (sx - px) * (sx - px) + (sy - py) * (sy - py);
                        if (d < bd) { bd = d; wbx = (float) sx; wby = (float) sy; }
                    }
                }
                if (bd < 1e17) bestW = Math.sqrt(bd) / S * mpp / 1000.0;
            }
        } catch (Throwable ignored) { }

        WpStatus.step(4);
        // faults
        double halfKm = Math.max(W, H) / S * mpp / 2000.0;
        Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
        fp.setStyle(Paint.Style.STROKE);
        fp.setStrokeWidth(5f);
        fp.setColor(day ? Color.argb(255, 170, 25, 25) : Color.argb(250, 255, 96, 72));
        fp.setPathEffect(new DashPathEffect(new float[]{26f, 16f}, 0f));
        String faultTxt = "";
        float nfx = 0, nfy = 0; double nfd = 1e18;
        if (showFaults) try {
            // every fault is thin and faint; only the one nearest to you keeps the bold line, so the lines stop covering the icons and text
            List<Path> fps = new ArrayList<>();
            int nearIdx = -1;
            for (float[][] l : Faults.linesIn(lat, lon, halfKm)) {
                Path p = new Path();
                for (int i = 0; i < l[0].length; i++) {
                    float x = (float) ((WallpaperMaker.worldX(l[0][i], z) - ox) * S), y = (float) ((WallpaperMaker.worldY(l[1][i], z) - oy) * S);
                    if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                    double dd2 = (double) (x - px) * (x - px) + (double) (y - py) * (y - py);
                    if (dd2 < nfd) { nfd = dd2; nfx = x; nfy = y; nearIdx = fps.size(); }
                }
                fps.add(p);
            }
            Paint faint = new Paint(fp);
            faint.setStrokeWidth(2.2f);
            faint.setAlpha(day ? 95 : 85);
            faint.setPathEffect(new DashPathEffect(new float[]{18f, 20f}, 0f));
            for (int i = 0; i < fps.size(); i++) if (i != nearIdx) cv.drawPath(fps.get(i), faint);
            if (nearIdx >= 0) cv.drawPath(fps.get(nearIdx), fp);
            List<Faults.Hit> hs = Faults.nearest(lat, lon, 1);
            if (!hs.isEmpty()) faultTxt = hs.get(0).label() + " · " + Math.round(hs.get(0).km) + " km " + hs.get(0).dir;
        } catch (Throwable ignored) { }

        // calm band under the clock
        Paint g = new Paint();
        g.setShader(new LinearGradient(0, 0, 0, H * 0.20f, Color.argb(day ? 120 : 190, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, W, H * 0.20f, g);

        RegionNames.noteRects.clear();
        // leader lines to the nearest forest / wetland
        float u = W / 1080f;
        Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        lp.setStyle(Paint.Style.STROKE);
        lp.setStrokeWidth(3.5f * u);
        lp.setPathEffect(new DashPathEffect(new float[]{14f * u, 10f * u}, 0f));
        if (fbx >= 0) {
            lp.setColor(day ? Color.rgb(20, 90, 30) : Color.rgb(120, 230, 140));
            cv.drawLine(px, py, fbx * S, fby * S, lp);
            cv.drawCircle(fbx * S, fby * S, 9 * u, lp);
        }
        if (wbx != 0 || wby != 0) {
            lp.setColor(day ? Color.rgb(0, 110, 90) : Color.rgb(110, 240, 200));
            cv.drawLine(px, py, wbx, wby, lp);
            cv.drawCircle(wbx, wby, 9 * u, lp);
        }
        // nearest-place labels (and a dashed line to the nearest fault)
        if (showCoast) try {
            if (nfd < 1e17 && showFaults) {
                lp.setColor(day ? Color.rgb(170, 25, 25) : Color.rgb(255, 130, 110));
                cv.drawLine(px, py, nfx, nfy, lp); cv.drawCircle(nfx, nfy, 9 * u, lp);
                RegionNames.tag(cv, "nearest fault " + Math.round(Math.sqrt(nfd) / S * mpp / 1000.0) + " km", nfx, nfy, W, H, u, day ? Color.rgb(170, 25, 25) : Color.rgb(255, 150, 130), day);
            }
            if (fbx >= 0 && showForest) RegionNames.tag(cv, "nearest forest " + Math.round(bestF) + " km", fbx * S, fby * S, W, H, u, day ? Color.rgb(20, 90, 30) : Color.rgb(140, 235, 150), day);
            if ((wbx != 0 || wby != 0) && !Double.isNaN(bestW)) RegionNames.tag(cv, "nearest wetland " + Math.round(bestW) + " km", wbx, wby, W, H, u, day ? Color.rgb(0, 110, 90) : Color.rgb(120, 240, 205), day);
        } catch (Throwable ignored) { }
        // coast: the nearest sea (or tidal creek) on the picture, with its distance and direction
        if (showCoast) try {
            int gx0 = (int) (px / S), gy0 = (int) (py / S), sbx = -1, sby = -1;
            double bd = 1e18;
            for (int y = 0; y < gh; y++) for (int x = 0; x < gw; x++) if (cls[y * gw + x] == WATER && e2[y * gw + x] < 1f) {
                double d = (double) (x - gx0) * (x - gx0) + (double) (y - gy0) * (y - gy0);
                if (d < bd) { bd = d; sbx = x; sby = y; }
            }
            if (sbx >= 0) {
                double km = Math.sqrt(bd) * mpp / 1000.0;
                lp.setColor(day ? Color.rgb(20, 70, 160) : Color.rgb(140, 200, 255));
                cv.drawLine(px, py, sbx * S, sby * S, lp);
                cv.drawCircle(sbx * S, sby * S, 9 * u, lp);
                String dir = RegionNames.dir8((gy0 - sby) * mpp / 1000.0, (sbx - gx0) * mpp / 1000.0);
                Paint ct = new Paint(Paint.ANTI_ALIAS_FLAG);
                ct.setTextSize(22 * u); ct.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
                Paint ch = new Paint(ct); ch.setStyle(Paint.Style.STROKE); ch.setStrokeWidth(3.2f * u); ch.setColor(day ? 0xD2FFFFFF : 0xDC000000);
                ct.setColor(day ? Color.rgb(20, 70, 160) : Color.rgb(170, 215, 255));
                String ctx = "coast " + Math.round(km) + " km " + dir;
                float tx = Math.max(20 * u, Math.min(W - 20 * u - ct.measureText(ctx), sbx * S + 14 * u)), ty = Math.max(H * 0.24f, Math.min(H * 0.93f, sby * S - 14 * u));
                cv.drawText(ctx, tx, ty, ch); cv.drawText(ctx, tx, ty, ct);
            }
        } catch (Throwable ignored) { }
        // you
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(Color.argb(70, 47, 127, 209));
        cv.drawCircle(px, py, 70 * u, dot);
        dot.setColor(Color.WHITE);
        cv.drawCircle(px, py, 24 * u, dot);
        dot.setColor(Color.rgb(47, 127, 209));
        cv.drawCircle(px, py, 17 * u, dot);
        if (bigMap) {
            String me = info != null && info.place != null && !info.place.isEmpty() ? info.place.split(",")[0].trim() : "";
            RegionNames.tag(cv, (me.isEmpty() ? "you" : me + " \u00b7 you"), px, py + 34 * u, W, H, u, day ? Color.rgb(20, 40, 90) : Color.WHITE, day);
        }

        if (info != null) { info.forestKm = bestF; info.wetKm = bestW; info.wetCount = wetN; }
        if (card) drawCard(c, cv, W, H, lat, lon, day, faultTxt, bestF, bestW, info, mx - mn);
        MapNames.demPeaks = null;
        try { MapNames.findPeaks(el, e2, gw, gh, S, (iox - ox) * S, (ioy - oy) * S, mpp); } catch (Throwable ignored) { MapNames.demPeaks = null; }
        IndiaMap.dedupe = showDams;
        IndiaMap.legendRows = 4;
        IndiaMap.panelOn = halfKm <= 150 && (showRivers || showDams || showParks) && IndiaMap.optList;
        if (showLegend) drawLegend(cv, W, H, day, showDams, showParks);
        WpStatus.step(5);
        MapNames.sharedUsed = null;
        try { if (WallpaperMaker.sp(c).getBoolean("names", true)) MapNames.draw(c, cv, W, H, lat, lon, z, ox, oy, S, day, halfKm); } catch (Throwable ignored) { }
        RegionNames.lastUsed = null;
        if (showCities && halfKm > 40) try { RegionNames.draw(cv, W, H, lat, lon, z, ox, oy, S, day); } catch (Throwable ignored) { }
        IndiaMap.traced = null; IndiaMap.tracedName = null;
        if (showRivers && halfKm <= 150) try { IndiaMap.trace(e2, gw, gh, S, mpp, (iox - ox) * S, (ioy - oy) * S, z, ox, oy, halfKm, W, H); } catch (Throwable ignored) { IndiaMap.traced = null; }
        if (showRivers || showDams || showParks || showPhys) try { IndiaMap.draw(cv, W, H, lat, lon, z, ox, oy, S, day, halfKm, showRivers, showDams, showParks, showPhys, RegionNames.lastUsed); } catch (Throwable ignored) { }
        Paint at = new Paint(Paint.ANTI_ALIAS_FLAG);
        at.setColor(day ? Color.argb(210, 40, 40, 30) : Color.argb(190, 255, 255, 255));
        at.setTextSize(W * 0.019f);
        cv.drawText("Elevation: Mapzen / AWS terrain tiles · Land cover: © OpenStreetMap · Plants: iNaturalist · Faults: GSI", W * 0.06f, H * 0.972f, at);
        return bmp;
    }

    static String overpass(String q) {
        String[] hosts = {"https://overpass-api.de/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter", "https://overpass.kumi.systems/api/interpreter"};
        for (String h : hosts) {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(h).openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(10_000);
                c.setReadTimeout(40_000);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                c.getOutputStream().write(("data=" + java.net.URLEncoder.encode(q, "UTF-8")).getBytes("UTF-8"));
                if (c.getResponseCode() != 200) { c.disconnect(); continue; }
                try (java.io.InputStream in = c.getInputStream()) {
                    java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                    byte[] b = new byte[32768];
                    int n;
                    while ((n = in.read(b)) > 0) { o.write(b, 0, n); if (o.size() > 40_000_000) break; }
                    String s = o.toString("UTF-8");
                    if (s.startsWith("{")) return s;
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static void drawLegend(Canvas cv, int W, int H, boolean day, boolean dams, boolean parks) {
        float u = W / 1080f;
        String[] names = {"Forest", "Grassland", "Scrub", "Marsh / swamp", "Mangrove", "Water", "Fault line"};
        int[] cols = day ? new int[]{Color.rgb(52, 128, 62), Color.rgb(176, 206, 92), Color.rgb(150, 146, 74), Color.rgb(30, 170, 150), Color.rgb(120, 60, 150), Color.rgb(70, 140, 210), Color.rgb(210, 40, 30)}
                : new int[]{Color.rgb(20, 74, 40), Color.rgb(70, 112, 44), Color.rgb(76, 84, 40), Color.rgb(70, 225, 170), Color.rgb(200, 120, 235), Color.rgb(18, 54, 104), Color.rgb(255, 96, 72)};
        float lx = 60 * u, ly = H * 0.185f, rh = 40 * u, colW = 330 * u;
        java.util.List<String> nl = new java.util.ArrayList<>(java.util.Arrays.asList(names));
        java.util.List<Integer> kinds = new java.util.ArrayList<>();   // 0 = colour swatch, 1..4 = map symbols
        for (int i = 0; i < names.length; i++) kinds.add(0);
        if (dams) { nl.add("Dam"); kinds.add(1); }
        if (parks) { nl.add("National park"); kinds.add(2); nl.add("Wildlife sanctuary"); kinds.add(3); nl.add("Biosphere reserve"); kinds.add(4); }
        int rows = Math.max(4, (nl.size() + 1) / 2);
        IndiaMap.legendRows = rows;
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTextSize(24 * u);
        t.setColor(day ? Color.rgb(30, 30, 25) : Color.rgb(224, 230, 236));
        Paint sw = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < nl.size(); i++) {
            float x0 = lx + (i / rows) * colW;
            float y = ly + (i % rows) * rh + 14 * u;
            int kd = kinds.get(i);
            if (kd > 0) IndiaMap.symbol(cv, kd, x0 + 17 * u, y, 9 * u, day);
            else {
                sw.setColor(cols[i]);
                if (i == names.length - 1) { sw.setStyle(Paint.Style.STROKE); sw.setStrokeWidth(5 * u); cv.drawLine(x0, y, x0 + 34 * u, y, sw); }
                else {
                    sw.setStyle(Paint.Style.FILL); cv.drawRect(x0, y - 10 * u, x0 + 34 * u, y + 10 * u, sw);
                    Paint ob = new Paint(Paint.ANTI_ALIAS_FLAG); ob.setStyle(Paint.Style.STROKE); ob.setStrokeWidth(2 * u); ob.setColor(day ? 0xCC000000 : 0xCCFFFFFF);
                    cv.drawRect(x0, y - 10 * u, x0 + 34 * u, y + 10 * u, ob);
                }
            }
            IndiaMap.dt(cv, nl.get(i), x0 + 52 * u, y + 8 * u, t, day, u);
        }
    }

    private static void drawCard(Context ctx, Canvas cv, int W, int H, double lat, double lon, boolean day, String fault, double forestKm, double wetKm, Info info, double relief) {
        String rock = Geo.rockText(lat, lon), soil = Geo.soilText(lat, lon);
        double el = WallpaperMaker.elevation(lat, lon);
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
        if (info != null) info.place = place;
        String habEco = "no data", habPlants = "no records nearby";
        try {
            Habitat.Data hd = Habitat.load(ctx, lat, lon, false);
            if (hd != null) {
                if (!hd.region().isEmpty()) habEco = hd.region();
                if (!hd.names.isEmpty()) habPlants = android.text.TextUtils.join(" \u00b7 ", hd.top(4));
            }
        } catch (Throwable ignored) { }
        String[][] rows = {{"Rock", rock.isEmpty() ? "no data" : rock}, {"Soil", soil.isEmpty() ? "no data" : soil},
                {"Fault", fault.isEmpty() ? "none in the list" : fault},
                {"Forest", Double.isNaN(forestKm) ? "none in view" : String.format(Locale.US, "%.0f km away", forestKm)},
                {"Wetland", Double.isNaN(wetKm) ? "none mapped in view" : String.format(Locale.US, "%.0f km away", wetKm)},
                {"Relief", Double.isNaN(el) ? "no data" : Math.round(el) + " m above sea level"},
                {"Region", habEco}, {"Plants", habPlants}};
        float u = W / 1080f;
        float left = 150 * u, right = W - 150 * u, top = H * 0.545f, rowH = 54 * u, head = 66 * u;
        float bottom = top + head + rows.length * rowH * 1.2f + 20 * u;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.argb(208, 10, 14, 18));
        cv.drawRoundRect(new RectF(left, top, right, bottom), 28 * u, 28 * u, bg);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(Color.WHITE);
        t.setTextSize(38 * u);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        String head1 = place.isEmpty() ? String.format(Locale.US, "%.3f, %.3f", lat, lon) : place;
        float hw = right - left - 56 * u;
        while (head1.length() > 6 && t.measureText(head1) > hw) head1 = head1.substring(0, head1.length() - 2).trim();
        if (!head1.equals(place) && !place.isEmpty()) head1 += "…";
        cv.drawText(head1, left + 28 * u, top + 52 * u, t);
        t.setTypeface(Typeface.DEFAULT);
        t.setTextSize(27 * u);
        float y = top + head + 34 * u;
        int maxW = (int) (right - left - 28 * u * 2 - 112 * u);
        for (String[] r : rows) {
            t.setColor(Color.argb(255, 150, 165, 180));
            cv.drawText(r[0], left + 28 * u, y, t);
            t.setColor(r[0].equals("Fault") ? Color.rgb(255, 140, 120) : r[0].equals("Forest") ? Color.rgb(140, 225, 150)
                    : r[0].equals("Wetland") ? Color.rgb(120, 235, 205) : r[0].equals("Plants") || r[0].equals("Region") ? Color.rgb(140, 225, 150) : Color.rgb(224, 230, 236));
            String v = r[1];
            while (v.length() > 4 && t.measureText(v) > maxW) v = v.substring(0, v.length() - 2).trim();
            if (!v.equals(r[1])) v += "…";
            cv.drawText(v, left + 28 * u + 112 * u, y, t);
            y += rowH * 1.2f;
        }
    }
}
