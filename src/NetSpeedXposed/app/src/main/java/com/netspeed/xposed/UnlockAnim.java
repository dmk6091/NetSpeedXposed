package com.netspeed.xposed;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Pixel-style lock screen exit. The moment an unlock starts (swipe, fingerprint or face) a picture
 * of the lock screen (clock, notifications, globe; the wallpaper is separate) is put in its own
 * overlay window on top of the launcher. It drifts up a little and fades out (like the Pixel lock
 * screen) while the launcher comes in behind it, then the overlay goes away. A separate window is used because System UI hides or
 * restyles its own lock screen window at that moment, so animating that one shows nothing.
 *
 * Switch: Cfg.UNLOCK_ANIM (off by default). Log tag "[unlock]".
 */
final class UnlockAnim {
    private static final String TAG = "NetSpeedXposed: [unlock] ";
    private static final float RISE = 0.035f;      // lock screen drifts up by 3.5 % of the screen height
    private static final long DURATION_MS = 420;
    private static final float ZOOM_FROM = 1f;      // wallpaper depth start (1 = zoomed out end)

    private static WeakReference<View> sRoot = new WeakReference<>(null);
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static long sLast;
    private static View sOverlay;

    private UnlockAnim() {}

    static void install(ClassLoader cl) {
        try {
            Class<?> shade = XposedHelpers.findClass("com.android.systemui.shade.NotificationShadeWindowView", cl);
            XposedBridge.hookAllConstructors(shade, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    sRoot = new WeakReference<>((View) p.thisObject);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "shade window hook failed: " + t);
        }
        int n = 0;
        try {
            Class<?> c = XposedHelpers.findClass("com.android.systemui.keyguard.KeyguardUnlockAnimationController", cl);
            for (final String name : new String[]{"playCannedUnlockAnimation", "notifyStartSurfaceBehindRemoteAnimation",
                    "setSurfaceBehindAppearAmount"}) {
                int k = XposedBridge.hookAllMethods(c, name, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (name.equals("setSurfaceBehindAppearAmount")) {
                                if (p.args.length < 1 || !(p.args[0] instanceof Float) || (Float) p.args[0] <= 0.001f) return;
                            }
                            start(name);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + name + " error " + t);
                        }
                    }
                }).size();
                n += k;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "install failed: " + t);
        }
        XposedBridge.log(TAG + "installed (" + n + " hooks, overlay version)");
    }

    /** Called at the very start of an unlock; the first call of a burst wins. */
    private static void start(String from) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sLast < 1500) return;
        View v = sRoot.get();
        if (v == null) return;
        Cfg.init(v.getContext());
        if (!Cfg.b(Cfg.UNLOCK_ANIM, Cfg.D_UNLOCK_ANIM)) return;
        sLast = now;
        int w = v.getWidth(), h = v.getHeight();
        XposedBridge.log(TAG + "start from " + from + ", lock screen " + w + "x" + h + " shown=" + v.isShown());
        if (w <= 0 || h <= 0) return;
        LockGlobe.hideForUnlock();
        Bitmap b = snapshot(v, w, h);
        if (b == null) return;
        show(v.getContext(), b);
        zoomWallpaper(v);
    }

    /**
     * The lock screen uses RuntimeShader (software canvases refuse it), so it is drawn on the GPU:
     * record the view into a RenderNode, render that once into an ImageReader surface, copy it out.
     */
    private static Bitmap snapshot(View v, int w, int h) {
        android.graphics.RenderNode node = null;
        android.graphics.HardwareRenderer r = null;
        android.media.ImageReader reader = null;
        try {
            node = new android.graphics.RenderNode("netspeed_unlock");
            node.setPosition(0, 0, w, h);
            Canvas rc = node.beginRecording(w, h);
            v.draw(rc);
            node.endRecording();
            reader = android.media.ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1,
                    android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                            | android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
            r = new android.graphics.HardwareRenderer();
            r.setContentRoot(node);
            r.setSurface(reader.getSurface());
            r.createRenderRequest().setWaitForPresent(true).syncAndDraw();
            android.media.Image img = reader.acquireNextImage();
            if (img == null) {
                XposedBridge.log(TAG + "snapshot: no frame");
                return null;
            }
            try {
                Bitmap hw = Bitmap.wrapHardwareBuffer(img.getHardwareBuffer(),
                        android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB));
                Bitmap soft = hw == null ? null : hw.copy(Bitmap.Config.ARGB_8888, false);
                XposedBridge.log(TAG + "snapshot " + (soft == null ? "failed" : soft.getWidth() + "x" + soft.getHeight()));
                return soft;
            } finally {
                img.close();
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "snapshot error " + t);
            return null;
        } finally {
            try { if (r != null) r.destroy(); } catch (Throwable ignored) { }
            try { if (reader != null) reader.close(); } catch (Throwable ignored) { }
        }
    }

    private static void show(final Context c, Bitmap b) {
        removeOverlay();
        final WindowManager wm = c.getSystemService(WindowManager.class);
        final ImageView iv = new ImageView(c);
        iv.setImageBitmap(b);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        boolean added = false;
        for (int type : new int[]{2024 /* navigation bar panel */, 2006 /* system overlay */}) {
            try {
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1, type, flags, PixelFormat.TRANSLUCENT);
                lp.setTitle("NetSpeedUnlockAnim");
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                wm.addView(iv, lp);
                added = true;
                XposedBridge.log(TAG + "overlay added (window type " + type + ")");
                break;
            } catch (Throwable t) {
                XposedBridge.log(TAG + "overlay type " + type + " failed: " + t);
            }
        }
        if (!added) return;
        sOverlay = iv;
        iv.setPivotX(b.getWidth() / 2f);
        iv.setPivotY(b.getHeight() * 0.5f);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(DURATION_MS);
        a.setInterpolator(new DecelerateInterpolator(1.3f));
        a.addUpdateListener(an -> {
            float p = (Float) an.getAnimatedValue();
            iv.setTranslationY(-RISE * iv.getHeight() * p);
            iv.setAlpha(Math.max(0f, 1f - p * 1.5f));
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) {
                removeOverlay();
                XposedBridge.log(TAG + "overlay removed");
            }
        });
        a.start();
        // safety net: never leave a full-screen overlay behind
        H.postDelayed(UnlockAnim::removeOverlay, 2000);
    }

    /**
     * Wallpaper depth effect: the same call System UI uses when the shade is pulled. Starts from the
     * zoomed-out end (1) and settles to normal (0) while the lock screen fades. Whether the wallpaper
     * engine reacts depends on the wallpaper / ROM, so every step is logged.
     */
    private static void zoomWallpaper(View root) {
        try {
            final android.os.IBinder token = root.getWindowToken();
            final Object wm = root.getContext().getSystemService(android.app.WallpaperManager.class);
            if (token == null || wm == null) {
                XposedBridge.log(TAG + "wallpaper zoom: no window token / manager");
                return;
            }
            final boolean[] logged = {false};
            ValueAnimator z = ValueAnimator.ofFloat(ZOOM_FROM, 0f);
            z.setDuration(DURATION_MS + 120);
            z.setInterpolator(new DecelerateInterpolator(1.3f));
            z.addUpdateListener(an -> {
                try {
                    XposedHelpers.callMethod(wm, "setWallpaperZoomOut", token, (Float) an.getAnimatedValue());
                    if (!logged[0]) { logged[0] = true; XposedBridge.log(TAG + "wallpaper zoom running"); }
                } catch (Throwable t) {
                    if (!logged[0]) { logged[0] = true; XposedBridge.log(TAG + "wallpaper zoom failed: " + t); }
                    an.cancel();
                }
            });
            z.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator an) {
                    try { XposedHelpers.callMethod(wm, "setWallpaperZoomOut", token, 0f); } catch (Throwable ignored) { }
                }
            });
            z.start();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "wallpaper zoom error " + t);
        }
    }

    private static void removeOverlay() {
        View o = sOverlay;
        sOverlay = null;
        if (o == null) return;
        try {
            o.getContext().getSystemService(WindowManager.class).removeViewImmediate(o);
        } catch (Throwable ignored) { }
    }
}
