package com.netspeed.xposed;

import android.app.ActivityManager;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

/**
 * The "main colour" of what's on screen: the open app's icon colour (most common vivid hue),
 * or the wallpaper's colour on the home screen. Then adjusted so it stays readable on the
 * status bar (light on a dark bar, deep on a light one). 0 = no colour (keep the system's).
 */
final class AppColor {
    private static final String TAG = "NetSpeedXposed: [color] ";
    private static final Map<String, Integer> CACHE = new HashMap<>();
    private static String sHome;
    private static String sLastPkg = "";

    private AppColor() {}

    static String topPackage(Context ctx) {
        try {
            ActivityManager am = ctx.getSystemService(ActivityManager.class);
            @SuppressWarnings("deprecation")
            List<ActivityManager.RunningTaskInfo> t = am.getRunningTasks(1);
            if (t != null && !t.isEmpty() && t.get(0).topActivity != null) return t.get(0).topActivity.getPackageName();
        } catch (Throwable th) {
            Log1.once("tasks", TAG + "can't read the open app: " + th);
        }
        return null;
    }

    static String homePackage(Context ctx) {
        if (sHome != null) return sHome;
        try {
            ResolveInfo ri = ctx.getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
            if (ri != null && ri.activityInfo != null) sHome = ri.activityInfo.packageName;
        } catch (Throwable ignored) { }
        return sHome;
    }

    /** Raw accent for what's on screen now (0 = none). */
    static int current(Context ctx) {
        String pkg = topPackage(ctx);
        if (pkg == null) return 0;
        boolean home = pkg.equals(homePackage(ctx));
        String key = home ? "#wallpaper" : pkg;
        Integer c;
        synchronized (CACHE) {
            c = CACHE.get(key);
        }
        if (c == null) {
            c = home ? wallpaperColor(ctx) : iconColor(ctx, pkg);
            synchronized (CACHE) {
                CACHE.put(key, c);
            }
        }
        if (!pkg.equals(sLastPkg)) {
            sLastPkg = pkg;
            XposedBridge.log(TAG + "on screen: " + pkg + (home ? " (home, wallpaper)" : "")
                    + " -> " + (c == 0 ? "no colour" : String.format("#%06X", c & 0xFFFFFF)));
        }
        return c;
    }

    /** Wallpaper colour changes are rare; forget it so it's read again. */
    static void forgetWallpaper() {
        synchronized (CACHE) {
            CACHE.remove("#wallpaper");
        }
    }

    private static int wallpaperColor(Context ctx) {
        try {
            WallpaperColors wc = WallpaperManager.getInstance(ctx).getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            if (wc == null) return 0;
            int best = 0;
            float bestSat = 0.25f;
            for (android.graphics.Color col : new android.graphics.Color[]{wc.getPrimaryColor(), wc.getSecondaryColor(),
                    wc.getTertiaryColor()}) {
                if (col == null) continue;
                int argb = col.toArgb();
                float[] hsv = new float[3];
                Color.colorToHSV(argb, hsv);
                if (hsv[1] > bestSat && hsv[2] > 0.25f) {
                    bestSat = hsv[1];
                    best = argb;
                }
            }
            return best;
        } catch (Throwable t) {
            Log1.once("wpc", TAG + "wallpaper colours not readable: " + t);
            return 0;
        }
    }

    /** Most common vivid hue in the app's icon (white, black and greys are ignored). */
    private static int iconColor(Context ctx, String pkg) {
        try {
            Drawable d = ctx.getPackageManager().getApplicationIcon(pkg);
            int n = 48;
            Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, n, n);
            d.draw(cv);
            float[] weight = new float[24];
            float[][] sum = new float[24][3];
            float[] hsv = new float[3];
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) < 200) continue;
                    Color.colorToHSV(p, hsv);
                    if (hsv[1] < 0.30f || hsv[2] < 0.25f) continue;
                    int bucket = Math.min(23, (int) (hsv[0] / 15f));
                    float w = hsv[1] * hsv[2];
                    weight[bucket] += w;
                    sum[bucket][0] += Color.red(p) * w;
                    sum[bucket][1] += Color.green(p) * w;
                    sum[bucket][2] += Color.blue(p) * w;
                }
            }
            b.recycle();
            int best = -1;
            for (int i = 0; i < 24; i++) if (weight[i] > 0 && (best < 0 || weight[i] > weight[best])) best = i;
            if (best < 0 || weight[best] < n * n * 0.02f) return 0;   // monochrome icon
            float w = weight[best];
            return Color.rgb(Math.round(sum[best][0] / w), Math.round(sum[best][1] / w), Math.round(sum[best][2] / w));
        } catch (Throwable t) {
            return 0;
        }
    }

    static float luminance(int c) {
        return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** Readable on the bar: light enough on a dark bar, deep enough on a light one. */
    static int readable(int accent, boolean darkBar) {
        int c = accent | 0xFF000000;
        for (int i = 0; i < 12; i++) {
            float l = luminance(c);
            if (darkBar ? l >= 0.58f : l <= 0.36f) break;
            c = blend(c, darkBar ? Color.WHITE : Color.BLACK, 0.14f);
        }
        return c;
    }

    /** Tiny log-once helper. */
    static final class Log1 {
        private static final java.util.Set<String> S = new java.util.HashSet<>();

        static void once(String k, String msg) {
            synchronized (S) {
                if (!S.add(k)) return;
            }
            XposedBridge.log(msg);
        }
    }
}
