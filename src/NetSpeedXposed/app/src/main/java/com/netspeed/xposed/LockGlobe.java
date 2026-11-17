package com.netspeed.xposed;

import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * A live Earth on the lock screen: day/night terminator, your location as a red dot. Left alone
 * it turns slowly; drag (or flick) to turn it by hand, double-tap to bring your location back to
 * the middle (it rests there a while, then starts turning again).
 *
 * Frames are made on a worker thread. For a turn around the axis only the longitude changes, so
 * each pixel's latitude / relative longitude is cached and a frame is just texture lookups.
 */
final class LockGlobe extends View {
    private static final String TAG = "NetSpeedXposed: ";
    private static final int N_LO = 360, N_HI = 600;   // render resolution while moving / at rest
    private static final long SPIN_AFTER_MS = 2_500;    // after a drag: start turning again
    private static final long HOME_REST_MS = 15_000;    // after a double-tap: stay on your location
    // One turn in 90 s, the real way round: west to east, so land moves left to right
    // (the view's centre longitude goes down).
    private static final double SPIN_DEG_S = -4.0;
    private static final long FRAME_MS = 40;            // 25 fps while moving

    static void install(ClassLoader cl) {
        try {
            Class<?> k = XposedHelpers.findClass("com.android.systemui.shade.NotificationShadeWindowView", cl);
            try {
                XposedBridge.hookAllMethods(k, "dispatchTouchEvent", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (rawTouch((MotionEvent) p.args[0])) p.setResult(true);
                        } catch (Throwable ignored) { }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + "lock globe touch hook failed: " + t);
            }
            XposedBridge.hookAllConstructors(k, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    final ViewGroup v = (ViewGroup) p.thisObject;
                    final int[] tries = {0};
                    Runnable r = new Runnable() {
                        @Override public void run() {
                            try {
                                if (attach(v)) return;
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "lock globe attach failed: " + t);
                                return;
                            }
                            if (++tries[0] < 10) v.postDelayed(this, 1000);
                        }
                    };
                    v.postDelayed(r, 500);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "lock globe hook failed: " + t);
        }
    }

    private static boolean attach(ViewGroup root) {
        Context c = root.getContext();
        Cfg.init(c);
        if (root.findViewWithTag("netspeed_lockglobe") != null) return true;
        int id = root.getResources().getIdentifier("custom_overlay_container", "id", "com.android.systemui");
        ViewGroup host = id != 0 ? (ViewGroup) root.findViewById(id) : null;
        if (!(host instanceof FrameLayout)) {
            return false;
        }
        LockGlobe g = new LockGlobe(c);
        g.setTag("netspeed_lockglobe");
        int w = c.getResources().getDisplayMetrics().widthPixels;
        int h = c.getResources().getDisplayMetrics().heightPixels;
        int d = (int) (w * 0.90f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(d, d, android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
        lp.topMargin = (int) (h * 0.235f);
        host.addView(g, lp);
        sInstance = g;
        g.watchShade(root);
        try {
            Sky sky = new Sky(c);
            sky.setTag("netspeed_lockglobe_sky");
            root.addView(sky, 0, new FrameLayout.LayoutParams(-1, -1));
            sky.setVisibility(GONE);
            g.backdrop = sky;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "lock sky failed: " + t);
        }
        XposedBridge.log(TAG + "lock globe added " + d + "px");
        return true;
    }

    // ---- fade out while the quick settings panel is pulled down (the globe sits above the shade's tiles)
    private boolean qsWasOpen = false;

    private void watchShade(final ViewGroup root) {
        final View[] qs = {null};
        root.getViewTreeObserver().addOnPreDrawListener(() -> {
            try {
                if (qs[0] == null) {
                    for (String n : new String[]{"qs_frame", "quick_settings_panel", "qs_container"}) {
                        int id = root.getResources().getIdentifier(n, "id", "com.android.systemui");
                        View v = id != 0 ? root.findViewById(id) : null;
                        if (v != null) { qs[0] = v; break; }
                    }
                    if (qs[0] == null) return true;
                }
                View v = qs[0];
                boolean open = v.getVisibility() == View.VISIBLE && v.getHeight() > 0 && v.getAlpha() > 0.02f && v.isShown();
                float a = open ? Math.max(0f, 1f - v.getAlpha() * 1.5f) : 1f;
                if (getAlpha() != a) setAlpha(a);
                if (backdrop != null && backdrop.getAlpha() != a) backdrop.setAlpha(a);
                if (open != qsWasOpen) {
                    qsWasOpen = open;
                    XposedBridge.log(TAG + "[globe] quick settings " + (open ? "open: globe faded" : "closed: globe back"));
                }
            } catch (Throwable ignored) { }
            return true;
        });
    }

    // ---- state
    private static int[] sDay, sNight;
    private static int sW, sH;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final KeyguardManager km;
    private final PowerManager pm;
    private Bitmap bmp;
    private int[] px;
    private int curN = 0;
    private static LockGlobe sInstance;
    private long suppressUntil;

    /** An unlock has started: take the globe and starfield away at once so they don't pop in over the launcher. */
    static void hideForUnlock() {
        LockGlobe g = sInstance;
        if (g == null) return;
        g.suppressUntil = android.os.SystemClock.uptimeMillis() + 3000;
        g.setVisibility(GONE);
        if (g.backdrop != null) g.backdrop.setVisibility(GONE);
    }
    private View backdrop;
    private boolean grab;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final GestureDetector gd;
    private double homeLat = 18.65, homeLon = 73.74;
    private double lat0 = 18.65, lon0 = 73.74;
    private float lastX, lastY;
    private boolean dragging;
    private ValueAnimator back;
    private boolean sunDirty = true;
    private long lastRender;

    // motion
    private double vel;                 // deg/s around the axis (spin or coasting after a flick)
    private long holdUntil;             // no auto spin before this (uptime ms)
    private long lastFrameAt, lastMoveAt;
    private float flingV;               // px/s from the last drag moves
    private boolean frameQueued;

    // worker
    private static android.os.HandlerThread sWorkerThread;
    private Handler worker;
    private final Object lock = new Object();
    private boolean rendering, pending;
    private int pendingN;
    private double reqLat, reqLon;
    private Bitmap backBmp;
    private double shownLat = 18.65, shownLon = 73.74;

    // per-pixel cache for one latitude tilt
    private int cN = -1;
    private double cLat = 999;
    private float[] cSinP, cCosP, cRel, cShadeZ, cNz;
    private int[] cRow;
    private boolean[] cIn;
    private static final int COS_N = 4096;
    private static final float[] COS = new float[COS_N + 1];
    static {
        for (int i = 0; i <= COS_N; i++) COS[i] = (float) Math.cos(2 * Math.PI * i / COS_N);
    }

    private boolean spinOn() {
        return Cfg.b(Cfg.GLOBE_SPIN, Cfg.D_GLOBE_SPIN);
    }

    /** Moves the globe by its velocity / auto spin; runs while visible and something is moving. */
    private final Runnable frame = new Runnable() {
        @Override public void run() {
            frameQueued = false;
            if (getVisibility() != VISIBLE || !pm.isInteractive()) { lastFrameAt = 0; return; }
            long now = android.os.SystemClock.uptimeMillis();
            double dt = lastFrameAt == 0 ? 0 : Math.min(0.2, (now - lastFrameAt) / 1000.0);
            lastFrameAt = now;
            boolean moving = false;
            if (!dragging && (back == null || !back.isRunning())) {
                double target = spinOn() && now >= holdUntil ? SPIN_DEG_S : 0;
                // flick: coast and ease into the spin speed
                vel += (target - vel) * Math.min(1, dt * 1.6);
                if (Math.abs(vel) < 0.02 && target == 0) vel = 0;
                if (vel != 0) {
                    lon0 = wrap(lon0 + vel * dt);
                    moving = true;
                    requestRender(N_LO < N_HI && Math.abs(vel) > 30 ? N_LO : N_HI);
                }
                if (target != 0 || vel != 0) moving = true;
                if (target == 0 && vel == 0 && holdUntil > now) {
                    // resting on the location: wake up when the rest ends
                    h.postDelayed(this, holdUntil - now + 5);
                    frameQueued = true;
                    return;
                }
            } else {
                moving = true;
            }
            if (moving) {
                frameQueued = true;
                h.postDelayed(this, FRAME_MS);
            } else {
                lastFrameAt = 0;
            }
        }
    };

    private void startFrames() {
        if (frameQueued) return;
        frameQueued = true;
        lastFrameAt = 0;
        h.post(frame);
    }

    private static double wrap(double l) {
        while (l > 180) l -= 360;
        while (l < -180) l += 360;
        return l;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean on = keyguardShowing() && android.os.SystemClock.uptimeMillis() >= suppressUntil;
            setVisibility(on ? VISIBLE : GONE);
            if (backdrop != null) backdrop.setVisibility(on ? VISIBLE : GONE);
            if (on) {
                long now = System.currentTimeMillis();
                if (now - lastRender > 60_000 && !dragging) { sunDirty = true; requestRender(N_HI); }
                startFrames();
            }
            if (pm.isInteractive()) h.postDelayed(this, 2000);
        }
    };

    private final BroadcastReceiver rcv = new BroadcastReceiver() {
        @Override public void onReceive(Context cx, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                h.removeCallbacks(tick);
                setVisibility(GONE);
                if (backdrop != null) backdrop.setVisibility(GONE);
            } else {
                h.removeCallbacks(tick);
                refreshHome();
                h.post(tick);
            }
        }
    };

    private LockGlobe(Context c) {
        super(c);
        km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        setVisibility(GONE);
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) { goHome(); return true; }
            @Override public boolean onDown(MotionEvent e) { return true; }
        });
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        c.registerReceiver(rcv, f);
        synchronized (LockGlobe.class) {
            if (sWorkerThread == null) {
                sWorkerThread = new android.os.HandlerThread("lockglobe");
                sWorkerThread.start();
            }
        }
        worker = new Handler(sWorkerThread.getLooper());
        worker.post(() -> { loadTextures(c); h.post(() -> requestRender(N_HI)); });
        refreshHome();
        h.post(tick);
    }

    private boolean keyguardShowing() {
        try {
            return Cfg.b(Cfg.LOCK_GLOBE, Cfg.D_LOCK_GLOBE) && pm.isInteractive() && km.isKeyguardLocked();
        } catch (Throwable t) { return false; }
    }

    private void refreshHome() {
        try {
            android.location.Location l = TowerDb.myLocation(getContext());
            if (l != null) { homeLat = l.getLatitude(); homeLon = l.getLongitude(); }
        } catch (Throwable ignored) { }
        if (!dragging) { lat0 = homeLat; lon0 = homeLon; }
        sunDirty = true;
    }

    private static synchronized void loadTextures(Context c) {
        if (sDay != null) return;
        try {
            Context m = c.createPackageContext("com.netspeed.xposed", 0);
            Bitmap d = BitmapFactory.decodeStream(m.getAssets().open("earth_day.jpg"));
            Bitmap n = BitmapFactory.decodeStream(m.getAssets().open("earth_night.jpg"));
            sW = d.getWidth(); sH = d.getHeight();
            int[] a = new int[sW * sH];
            d.getPixels(a, 0, sW, 0, 0, sW, sH);
            int[] b = new int[sW * sH];
            Bitmap n2 = Bitmap.createScaledBitmap(n, sW, sH, true);
            n2.getPixels(b, 0, sW, 0, 0, sW, sH);
            sNight = b; sDay = a;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "lock globe textures failed: " + t);
        }
    }

    // ---- sun
    private static double[] sun(long ms) {
        double d = (ms - 946728000000L) / 86400000.0;
        double L = Math.toRadians(280.46 + 0.9856474 * d);
        double g = Math.toRadians(357.528 + 0.9856003 * d);
        double lam = L + Math.toRadians(1.915) * Math.sin(g) + Math.toRadians(0.02) * Math.sin(2 * g);
        double eps = Math.toRadians(23.439 - 0.0000004 * d);
        double dec = Math.asin(Math.sin(eps) * Math.sin(lam));
        double ra = Math.atan2(Math.cos(eps) * Math.sin(lam), Math.cos(lam));
        double gmst = Math.toRadians((280.46061837 + 360.98564736629 * d) % 360.0);
        double lon = ra - gmst;
        lon = Math.atan2(Math.sin(lon), Math.cos(lon));
        return new double[]{dec, lon};
    }

    // ---- render (worker thread)
    private void render() { requestRender(N_HI); }

    private void render(int N) { requestRender(N); }

    /** Asks the worker for a frame of the current view; extra requests while busy collapse into one. */
    private void requestRender(int N) {
        synchronized (lock) {
            reqLat = lat0;
            reqLon = lon0;
            pendingN = N;
            if (rendering) { pending = true; return; }
            rendering = true;
        }
        worker.post(this::renderLoop);
    }

    /** One frame on the worker; the swap on the main thread starts the next one if asked for. */
    private void renderLoop() {
        double la, lo;
        int N;
        synchronized (lock) {
            la = reqLat; lo = reqLon; N = pendingN;
            pending = false;
        }
        Bitmap out = null;
        try {
            out = renderFrame(N, la, lo);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "lock globe render: " + t);
        }
        final Bitmap b = out;
        final double fla = la, flo = lo;
        h.post(() -> {
            if (b != null) {
                backBmp = bmp;
                bmp = b;
                shownLat = fla;
                shownLon = flo;
                invalidate();
            }
            synchronized (lock) {
                if (pending) worker.post(this::renderLoop);
                else rendering = false;
            }
        });
    }

    /** Per-pixel latitude and longitude offset for a view centred on latitude lat0. */
    private void buildCache(int N, double lat) {
        int n = N * N;
        if (cSinP == null || cSinP.length != n) {
            cSinP = new float[n]; cCosP = new float[n]; cRel = new float[n];
            cShadeZ = new float[n]; cNz = new float[n]; cRow = new int[n]; cIn = new boolean[n];
        }
        double p0 = Math.toRadians(lat), sp0 = Math.sin(p0), cp0 = Math.cos(p0);
        double half = N / 2.0;
        for (int y = 0; y < N; y++) {
            double v = (half - (y + 0.5)) / half;
            for (int x = 0; x < N; x++) {
                int i = y * N + x;
                double u = ((x + 0.5) - half) / half;
                double r2 = u * u + v * v;
                if (r2 >= 1) { cIn[i] = false; continue; }
                cIn[i] = true;
                double z = Math.sqrt(1 - r2);
                double sinP = v * cp0 + z * sp0;
                if (sinP > 1) sinP = 1; else if (sinP < -1) sinP = -1;
                double phi = Math.asin(sinP);
                cSinP[i] = (float) sinP;
                cCosP[i] = (float) Math.cos(phi);
                cRel[i] = (float) Math.atan2(u, z * cp0 - v * sp0);
                double fy = (0.5 - phi / Math.PI) * sH;
                int iy = (int) fy; if (iy >= sH) iy = sH - 1; if (iy < 0) iy = 0;
                cRow[i] = iy * sW;
                cShadeZ[i] = (float) (0.55 + 0.45 * Math.pow(z, 0.6));
                cNz[i] = (float) (0.6 + 0.4 * z);
            }
        }
        cN = N;
        cLat = lat;
    }

    private Bitmap renderFrame(int N, double lat, double lon) {
        if (sDay == null) return null;
        lastRender = System.currentTimeMillis();
        if (cN != N || Math.abs(cLat - lat) > 1e-6) buildCache(N, lat);
        if (px == null || px.length != N * N) px = new int[N * N];
        double[] s = sun(lastRender);
        float sinD = (float) Math.sin(s[0]), cosD = (float) Math.cos(s[0]);
        double sl = s[1];
        double l0 = Math.toRadians(lon);
        final double TWO_PI = Math.PI * 2;
        final float kx = (float) (sW / TWO_PI), kc = (float) (COS_N / TWO_PI);
        for (int i = 0, n = N * N; i < n; i++) {
            if (!cIn[i]) { px[i] = 0; continue; }
            double lam = cRel[i] + l0;
            lam -= TWO_PI * Math.floor((lam + Math.PI) / TWO_PI);          // -pi .. pi
            int ix = (int) ((lam + Math.PI) * kx);
            if (ix >= sW) ix = sW - 1; else if (ix < 0) ix = 0;
            double dl = lam - sl;
            dl -= TWO_PI * Math.floor(dl / TWO_PI);                         // 0 .. 2pi
            int ci = (int) (dl * kc);
            if (ci > COS_N) ci = COS_N; else if (ci < 0) ci = 0;
            float dot = cSinP[i] * sinD + cCosP[i] * cosD * COS[ci];
            int di = sDay[cRow[i] + ix], ni = sNight[cRow[i] + ix];
            float w = (dot + 0.10f) / 0.28f;
            w = w < 0 ? 0 : w > 1 ? 1 : w;
            w = w * w * (3 - 2 * w);
            float shade = (0.72f + 0.28f * (dot < 0 ? 0 : dot > 1 ? 1 : dot)) * cShadeZ[i];
            float nk = (1 - w) * cNz[i];
            // night side: city lights plus a faint moonlit copy of the day map, so oceans and
            // continents stay visible instead of fading into the dark sky
            int dr = di >> 16 & 255, dg = di >> 8 & 255, db = di & 255;
            int r = (int) ((dr * shade) * w + ((ni >> 16 & 255) * 1.1f + dr * 0.20f + 10) * nk);
            int g = (int) ((dg * shade) * w + ((ni >> 8 & 255) * 1.1f + dg * 0.26f + 18) * nk);
            int b = (int) ((db * shade) * w + ((ni & 255) * 1.1f + db * 0.40f + 36) * nk);
            if (r > 255) r = 255; if (g > 255) g = 255; if (b > 255) b = 255;
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        Bitmap out = backBmp;
        if (out == null || out.getWidth() != N || out == bmp) out = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, N, 0, 0, N, N);
        return out;
    }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), hgt = getHeight();
        float cx = w / 2, cy = hgt / 2, R = Math.min(w, hgt) * 0.43f;
        // soft atmosphere
        p.setShader(new RadialGradient(cx, cy, R * 1.12f, new int[]{0x00000000, 0x00000000, 0x553A7BD8, 0x00000000},
                new float[]{0f, 0.88f, 0.94f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, R * 1.12f, p);
        p.setShader(null);
        if (bmp != null) cv.drawBitmap(bmp, null, new android.graphics.RectF(cx - R, cy - R, cx + R, cy + R), p);

        // location
        double p0 = Math.toRadians(shownLat), l0 = Math.toRadians(shownLon);
        double ph = Math.toRadians(homeLat), la = Math.toRadians(homeLon) - l0;
        double cosc = Math.sin(p0) * Math.sin(ph) + Math.cos(p0) * Math.cos(ph) * Math.cos(la);
        double ux = Math.cos(ph) * Math.sin(la);
        double uy = Math.cos(p0) * Math.sin(ph) - Math.sin(p0) * Math.cos(ph) * Math.cos(la);
        float dot = Math.max(3f, R * 0.045f);
        if (cosc >= 0) {
            float x = cx + (float) ux * R, y = cy - (float) uy * R;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x44FF2A2A);
            cv.drawCircle(x, y, dot * 2.6f, p);
            p.setColor(0xFFFFFFFF);
            cv.drawCircle(x, y, dot * 1.25f, p);
            p.setColor(0xFFFF2020);
            cv.drawCircle(x, y, dot, p);
        } else {
            double len = Math.hypot(ux, uy);
            if (len < 1e-6) { ux = 0; uy = -1; len = 1; }
            float x = cx + (float) (ux / len) * (R + dot * 1.2f), y = cy - (float) (uy / len) * (R + dot * 1.2f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xAAFF2020);
            Path t = new Path();
            float a = (float) Math.atan2(-(uy / len), ux / len);
            float s = dot * 1.5f;
            t.moveTo(x + (float) Math.cos(a) * s, y + (float) Math.sin(a) * s);
            t.lineTo(x + (float) Math.cos(a + 2.4f) * s, y + (float) Math.sin(a + 2.4f) * s);
            t.lineTo(x + (float) Math.cos(a - 2.4f) * s, y + (float) Math.sin(a - 2.4f) * s);
            t.close();
            cv.drawPath(t, p);
        }
    }

    // ---- touch: the shade window would otherwise swallow drags, so hand them over from its dispatch
    static boolean rawTouch(MotionEvent e) {
        LockGlobe g = sInstance;
        if (g == null) return false;
        return g.handleRaw(e);
    }

    private boolean handleRaw(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            grab = false;
            if (getVisibility() != VISIBLE || !isShown()) return false;
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            float cx = loc[0] + getWidth() / 2f, cy = loc[1] + getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) * 0.5f;
            if (Math.hypot(e.getRawX() - cx, e.getRawY() - cy) > r) return false;
            grab = true;
        }
        if (!grab) return false;
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        MotionEvent c = MotionEvent.obtain(e);
        c.setLocation(e.getRawX() - loc[0], e.getRawY() - loc[1]);
        onTouchEvent(c);
        c.recycle();
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) grab = false;
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        gd.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                if (back != null) back.cancel();
                dragging = true;
                vel = 0;
                flingV = 0;
                lastX = e.getX(); lastY = e.getY();
                lastMoveAt = android.os.SystemClock.uptimeMillis();
                startFrames();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - lastX, dy = e.getY() - lastY;
                lastX = e.getX(); lastY = e.getY();
                double k = 180.0 / Math.PI / (Math.min(getWidth(), getHeight()) * 0.43);
                lon0 -= dx * k / Math.max(0.35, Math.cos(Math.toRadians(lat0)));
                lat0 = Math.max(-85, Math.min(85, lat0 + dy * k));
                lon0 = wrap(lon0);
                long now = android.os.SystemClock.uptimeMillis();
                float mdt = Math.max(8, now - lastMoveAt) / 1000f;
                flingV = flingV * 0.4f + (dx / mdt) * 0.6f;
                lastMoveAt = now;
                requestRender(N_LO);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                long now = android.os.SystemClock.uptimeMillis();
                double k = 180.0 / Math.PI / (Math.min(getWidth(), getHeight()) * 0.43);
                // a flick keeps it turning (finger lifted soon after moving), then it slows into the spin
                vel = now - lastMoveAt < 80 ? -flingV * k / Math.max(0.35, Math.cos(Math.toRadians(lat0))) : 0;
                vel = Math.max(-360, Math.min(360, vel));
                holdUntil = now + (vel != 0 ? 0 : SPIN_AFTER_MS);
                requestRender(N_HI);
                startFrames();
                return true;
        }
        return true;
    }

    private void goHome() {
        if (back != null) back.cancel();
        vel = 0;
        holdUntil = android.os.SystemClock.uptimeMillis() + HOME_REST_MS;
        final double a0 = lat0;
        double dl = homeLon - lon0;
        while (dl > 180) dl -= 360;
        while (dl < -180) dl += 360;
        final double dlon = dl, startLon = lon0, dlat = homeLat - lat0;
        back = ValueAnimator.ofFloat(0f, 1f);
        back.setDuration(700);
        back.setInterpolator(new android.view.animation.DecelerateInterpolator());
        back.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            lat0 = a0 + dlat * f;
            lon0 = startLon + dlon * f;
            requestRender(f >= 1f ? N_HI : N_LO);
        });
        back.start();
        startFrames();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        h.removeCallbacksAndMessages(null);
    }

    /** Milky Way backdrop, painted once from noise (no image needed). */
    static final class Sky extends View {
        private static Bitmap sBmp;
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);

        Sky(Context c) {
            super(c);
            setBackgroundColor(0xFF03050E);
            if (sBmp == null) {
                final android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
                new Thread(() -> {
                    try {
                        Context m = c.createPackageContext("com.netspeed.xposed", 0);
                        Bitmap b = BitmapFactory.decodeStream(m.getAssets().open("milkyway.jpg"));
                        sBmp = b;
                        XposedBridge.log(TAG + "lock sky ready " + b.getWidth() + "x" + b.getHeight());
                        post(this::invalidate);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "lock sky failed: " + t);
                    }
                }, "lockglobe-sky").start();
            }
        }

        @Override protected void onDraw(Canvas cv) {
            if (sBmp != null) cv.drawBitmap(sBmp, null, new android.graphics.Rect(0, 0, getWidth(), getHeight()), p);
        }
    }
}
