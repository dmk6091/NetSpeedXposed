package com.dmk.ostweaks;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Suggestion row above the dock (OxygenOS launcher). Shows the apps most recently opened from the
 * launcher, newest first (the app you just left is first), skipping apps already in the dock.
 * The row is a plain view added to the launcher's root layout: the dock and the launcher's layout
 * data are not touched.
 */
final class DockSuggest {
    private DockSuggest() {}
    private static final String PREF = "ostweaks_dock";
    private static final String KEY = "recent";
    private static WeakReference<LinearLayout> rowRef;
    private static final java.util.Map<String, WeakReference<View>> rowIcons = new java.util.HashMap<>();
    private static boolean done;

    static void handle(LoadPackageParam lp) {
        if (!"com.android.launcher".equals(lp.packageName) || !lp.packageName.equals(lp.processName)) return;
        synchronized (DockSuggest.class) { if (done) return; done = true; }
        final ClassLoader cl = lp.classLoader;
        Cfg.whenReady(new Runnable() { public void run() { install(cl); } });
    }

    private static void install(ClassLoader cl) {
        if (!Cfg.get("ostweaks_dock_sug", false)) { XposedBridge.log(Hook.TAG + "dock suggestions: off"); return; }
        try {
            // 1) remember apps opened from the launcher
            int n = XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivity", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    for (Object a : p.args) {
                        if (a instanceof Intent) { record((Intent) a); break; }
                    }
                }
            }).size();
            // 2) show the row when the launcher resumes
            final Class<?> launcher = XposedHelpers.findClass("com.android.launcher3.Launcher", cl);
            n += XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!launcher.isInstance(p.thisObject)) return;
                    final Activity act = (Activity) p.thisObject;
                    act.getWindow().getDecorView().post(new Runnable() {
                        public void run() {
                            try { show(act); } catch (Throwable t) { XposedBridge.log(Hook.TAG + "dock row: " + t); }
                            if (predicted.isEmpty()) loadPredicted(act, act.getClassLoader());
                        }
                    });
                }
            }).size();
            if (Cfg.get("ostweaks_dock_anim", false)) {
                try {
                    Class<?> ol = XposedHelpers.findClass("com.android.launcher.Launcher", cl);
                    n += XposedBridge.hookAllMethods(ol, "getFirstMatchForAppClose", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                if (p.getResult() != null || p.args.length < 2 || !(p.args[1] instanceof String)) return;
                                WeakReference<View> w = rowIcons.get((String) p.args[1]);
                                View v = w == null ? null : w.get();
                                if (v != null && v.isShown()) {
                                    p.setResult(v);
                                    
                                }
                            } catch (Throwable t) { XposedBridge.log(Hook.TAG + "dock anim: " + t); }
                        }
                    }).size();
                    try {   // diagnostics: what happens to the view afterwards
                        Class<?> fiv = XposedHelpers.findClass("com.android.launcher3.views.FloatingIconView", cl);
                        XposedBridge.hookAllMethods(fiv, "getFloatingIconView", new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                try {
                                    Object r = p.getResult();
                                    if (r == null) return;
                                    for (Object a : p.args) if (a instanceof View && isRowIcon((View) a)) { hiddenFloat.add(r); break; }
                                } catch (Throwable ignored) {}
                            }
                        });
                        XC_MethodHook skip = new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                if (hiddenFloat.contains(p.thisObject)) p.setResult(null);
                            }
                        };
                        XposedBridge.hookAllMethods(fiv, "draw", skip);
                        XposedBridge.hookAllMethods(fiv, "dispatchDraw", skip);
                        XposedBridge.hookAllMethods(fiv, "onDraw", skip);
                        XposedBridge.hookAllMethods(fiv, "getLocationBoundsForView", new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                try {
                                    if (p.args.length < 5 || !(p.args[1] instanceof View) || !isRowIcon((View) p.args[1])) return;
                                    View v = (View) p.args[1];
                                    // the row is rebuilt with the exited app first, so aim at slot 0
                                    if (v.getParent() instanceof android.view.ViewGroup && ((android.view.ViewGroup) v.getParent()).getChildCount() > 0)
                                        v = ((android.view.ViewGroup) v.getParent()).getChildAt(0);
                                    int[] xy = new int[2];
                                    v.getLocationOnScreen(xy);
                                    try {   // where the row will finally sit (hotseat translation / row offsets not applied yet)
                                        View hs = hotseatRef == null ? null : hotseatRef.get();
                                        android.view.ViewGroup rowV = (android.view.ViewGroup) v.getParent();
                                        if (hs != null && rowV.getParent() instanceof android.view.ViewGroup) {
                                            android.view.ViewGroup par = (android.view.ViewGroup) rowV.getParent();
                                            int[] pxy = new int[2];
                                            par.getLocationOnScreen(pxy);
                                            int shift = lastMargin - origMargin;
                                            float top = hs.getTop() + shift + (hs.getHeight() - rowV.getHeight()) / 2f + v.getTop();
                                            float left = (par.getWidth() - rowV.getWidth()) / 2f + v.getLeft();
                                            xy[0] = Math.round(pxy[0] + left);
                                            xy[1] = Math.round(pxy[1] + top);
                                        }
                                    } catch (Throwable ignored) {}
                                    final int fx = xy[0], fy = xy[1];
                                    final View slot0 = v;
                                    xy[0] += calDx; xy[1] += calDy;
                                    XposedBridge.log(Hook.TAG + "dock anim: formula " + fx + "," + fy + " using " + xy[0] + "," + xy[1]);
                                    slot0.postDelayed(new Runnable() { public void run() {   // self-calibrate from where the slot really ended up
                                        try {
                                            int[] a = new int[2]; slot0.getLocationOnScreen(a);
                                            if (slot0.getAlpha() == 1f && slot0.getVisibility() == View.VISIBLE && Math.abs(a[1] - fy) < 400) {
                                                calDx = a[0] - fx; calDy = a[1] - fy;
                                                XposedBridge.log(Hook.TAG + "dock anim: calibrated " + calDx + "," + calDy);
                                            }
                                        } catch (Throwable ignored) {}
                                    } }, 1800);
                                    
                                    android.graphics.RectF rf = (android.graphics.RectF) p.args[3];
                                    
                                    rf.set(xy[0], xy[1], xy[0] + v.getWidth(), xy[1] + v.getHeight());
                                    try {   // aim at the icon drawn inside the view, not at the view's top edge
                                        android.graphics.Rect ib = new android.graphics.Rect();
                                        v.getClass().getMethod("getIconBounds", android.graphics.Rect.class).invoke(v, ib);
                                        if (ib.width() > 0 && ib.height() > 0) {
                                            XposedBridge.log(Hook.TAG + "dock anim: iconBounds " + ib + " view " + v.getWidth() + "x" + v.getHeight() + " pad " + v.getPaddingTop());
                                            rf.set(xy[0] + ib.left, xy[1] + ib.top, xy[0] + ib.right, xy[1] + ib.bottom);
                                        }
                                    } catch (Throwable ig) {
                                        XposedBridge.log(Hook.TAG + "dock anim: no iconBounds " + ig);
                                    }
                                    if (p.args[4] instanceof android.graphics.Rect) ((android.graphics.Rect) p.args[4]).set(0, 0, v.getWidth(), v.getHeight());
                                } catch (Throwable t) { XposedBridge.log(Hook.TAG + "dock anim bounds: " + t); }
                            }
                        });
                    } catch (Throwable t) { XposedBridge.log(Hook.TAG + "dock anim diag: " + t); }
                } catch (Throwable t) { XposedBridge.log(Hook.TAG + "dock anim hook: " + t); }
            }
            XposedBridge.log(Hook.TAG + "dock suggestions on (" + n + " hooks)");
        } catch (Throwable t) {
            XposedBridge.log(Hook.TAG + "dock suggestions: " + t);
        }
    }

    // ---- recent apps ----
    private static void record(Intent it) {
        try {
            ComponentName cn = it.getComponent();
            if (cn == null) return;
            String pkg = cn.getPackageName();
            if (pkg.equals("com.android.launcher") || pkg.equals("com.android.systemui")) return;
            Context c = Cfg.ctx();
            if (c == null) return;
            if (c.getPackageManager().getLaunchIntentForPackage(pkg) == null) return;
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            List<String> l = load(sp);
            l.remove(pkg);
            l.add(0, pkg);
            while (l.size() > 12) l.remove(l.size() - 1);
            sp.edit().putString(KEY, join(l)).apply();
        } catch (Throwable ignored) {}
    }

    private static List<String> load(SharedPreferences sp) {
        List<String> l = new ArrayList<>();
        String s = sp.getString(KEY, "");
        if (s != null) for (String x : s.split(",")) if (!x.isEmpty()) l.add(x);
        return l;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) { if (sb.length() > 0) sb.append(','); sb.append(s); }
        return sb.toString();
    }

    // ---- row ----
    private static int id(Activity a, String name) {
        return a.getResources().getIdentifier(name, "id", a.getPackageName());
    }

    private static volatile List<String> predicted = new ArrayList<>();
    private static boolean predLoading;

    /** Seeds the row from the launcher's own prediction engine (PredictedAppManager), on a worker thread. */
    private static void loadPredicted(final Activity act, final ClassLoader cl) {
        synchronized (DockSuggest.class) { if (predLoading) return; predLoading = true; }
        new Thread(new Runnable() {
            public void run() {
                List<String> out = new ArrayList<>();
                try {
                    Class<?> pm = XposedHelpers.findClass("com.android.launcher.predictedapps.PredictedAppManager", cl);
                    Object inst = pm.getMethod("getInstance").invoke(null);
                    Object res = XposedHelpers.callMethod(inst, "getPredictedApps", 12);
                    if (res instanceof List) {
                        for (Object o : (List<?>) res) {
                            String pkg = pkgOf(o);
                            if (pkg != null && !out.contains(pkg)) out.add(pkg);
                        }
                    }
                    XposedBridge.log(Hook.TAG + "dock suggestions: launcher predicted " + out);
                } catch (Throwable t) {
                    XposedBridge.log(Hook.TAG + "dock suggestions: predicted: " + t);
                }
                predicted = out;
                synchronized (DockSuggest.class) { predLoading = false; }
                act.runOnUiThread(new Runnable() {
                    public void run() { try { show(act); } catch (Throwable ignored) {} }
                });
            }
        }).start();
    }

    private static String pkgOf(Object o) {
        try {
            if (o == null) return null;
            if (o instanceof ComponentName) return ((ComponentName) o).getPackageName();
            String s = null;
            if (o instanceof String) s = (String) o;
            else {
                try { Object c = XposedHelpers.getObjectField(o, "mComponent"); if (c != null) s = c.toString(); }
                catch (Throwable ignored) {}
                if (s == null) s = o.toString();
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/").matcher(s);
            if (m.find()) return m.group(1);
            return s.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+") ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int origMargin = Integer.MIN_VALUE, lastMargin = Integer.MIN_VALUE;

    /** Moves the real dock up above the page dots by raising its bottom margin; returns the shift in px. */
    private static int ensureShift(View hotseat, View indicator, float d) {
        try {
            if (!(hotseat.getLayoutParams() instanceof FrameLayout.LayoutParams)) return 0;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) hotseat.getLayoutParams();
            if (lp.bottomMargin != lastMargin) origMargin = lp.bottomMargin;   // the launcher set it: new baseline
            int applied = lp.bottomMargin - origMargin;
            float hot0 = hotseat.getTop() + applied;                          // dock top with no shift
            float dots = indicator != null && indicator.getTop() > 0 ? indicator.getTop() : hot0 - 30 * d;
            int shift = Math.round(hot0 - (dots - hotseat.getHeight() - 6 * d));
            if (shift < 0) shift = 0;
            int want = origMargin + shift;
            if (lp.bottomMargin != want) {
                lp.bottomMargin = want;
                hotseat.setLayoutParams(lp);
            }
            lastMargin = want;
            return shift;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void show(final Activity act) {
        final View hotseat = act.findViewById(id(act, "hotseat"));
        if (hotseat == null || !(hotseat.getParent() instanceof FrameLayout)) {
            XposedBridge.log(Hook.TAG + "dock suggestions: hotseat parent "
                    + (hotseat == null ? "missing" : hotseat.getParent().getClass().getName()));
            return;
        }
        final FrameLayout parent = (FrameLayout) hotseat.getParent();
        final View indicator = act.findViewById(id(act, "page_indicator"));
        final float d = act.getResources().getDisplayMetrics().density;
        LinearLayout row = rowRef == null ? null : rowRef.get();
        if (row == null || row.getParent() != parent) {
            row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            parent.addView(row, lp);
            rowRef = new WeakReference<>(row);
            final LinearLayout r = row;
            hotseat.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                public void onLayoutChange(View v, int l, int t, int rr, int b, int ol, int ot, int or, int ob) {
                    ensureShift(hotseat, indicator, d);
                }
            });
            hotseat.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    int shift = ensureShift(hotseat, indicator, d);
                    float origTop = hotseat.getTop() + hotseat.getTranslationY() + shift;   // where the dock used to sit
                    r.setTranslationY(origTop - r.getTop() + (hotseat.getHeight() - r.getHeight()) / 2f);
                    r.setTranslationX((parent.getWidth() - r.getWidth()) / 2f);
                    r.setAlpha(hotseat.getAlpha());
                    r.setVisibility(hotseat.getVisibility() == View.VISIBLE && r.getChildCount() > 0 ? View.VISIBLE : View.INVISIBLE);
                    if (r.getChildCount() > 0 && hotseat.getAlpha() == 1f && r.getAlpha() == 1f && r.getVisibility() == View.VISIBLE) {
                        int[] sx = new int[2];   // remember where slot 0 really sits when everything is at rest
                        r.getChildAt(0).getLocationOnScreen(sx);
                        settledXY = sx;
                    }
                    return true;
                }
            });
        }
        hotseatRef = new WeakReference<View>(hotseat);
        ensureShift(hotseat, indicator, d);
        fill(act, row, hotseat, d);
    }

    private static void fill(final Activity act, LinearLayout row, View hotseat, float d) {
        int max = Settings.System.getInt(act.getContentResolver(), "ostweaks_dock_sug_n", 5);
        if (max < 1) max = 1;
        if (max > 5) max = 5;
        int icon = Math.round(50 * d);
        try {   // same icon size as the home screen
            Object dp = XposedHelpers.callMethod(act, "getDeviceProfile");
            int px = XposedHelpers.getIntField(dp, "iconSizePx");
            if (px > 0) icon = px;
        } catch (Throwable ignored) {}
        int cell = Math.round(69 * d);
        try {
            if (hotseat instanceof ViewGroup && ((ViewGroup) hotseat).getChildCount() > 0) {
                int w = ((ViewGroup) hotseat).getChildAt(0).getWidth();
                if (w > 0) cell = w / 5;
            }
        } catch (Throwable ignored) {}
        final PackageManager pm = act.getPackageManager();
        SharedPreferences sp = act.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        List<String> inDock = new ArrayList<>();
        dockPackages(hotseat, inDock);
        int shown = 0;
        List<String> order = new ArrayList<>();
        try {   // system-wide recents written by the watcher script (newest first)
            String sys = Settings.System.getString(act.getContentResolver(), "ostweaks_recent");
            if (sys != null) for (String x : sys.split(",")) if (!x.isEmpty() && !order.contains(x)) order.add(x);
        } catch (Throwable ignored) {}
        for (String p : load(sp)) if (!order.contains(p)) order.add(p);
        for (String p : predicted) if (!order.contains(p)) order.add(p);
        StringBuilder sg = new StringBuilder().append(max).append('|').append(icon).append('|').append(cell);
        for (String p : order) if (!inDock.contains(p) && !p.equals("com.android.launcher")) sg.append(',').append(p);
        if (row.getChildCount() > 0 && sg.toString().equals(lastSig)) return;   // nothing changed: don't churn during animations
        lastSig = sg.toString();
        row.removeAllViews();
        rowIcons.clear();
        for (String pkg : order) {
            if (shown >= max) break;
            if (inDock.contains(pkg) || pkg.equals("com.android.launcher")) continue;
            final Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) continue;
            ImageView iv = new ImageView(act);
            try {
                iv.setImageDrawable(pm.getApplicationIcon(pkg));
            } catch (Throwable t) { continue; }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(icon, icon);
            int m = Math.max(0, (cell - icon) / 2);
            lp.setMargins(m, 0, m, 0);
            iv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    act.startActivity(launch);
                }
            });
            View target = iv;
            try {
                Class<?> wc = Class.forName("com.android.launcher3.model.data.WorkspaceItemInfo", true, act.getClassLoader());
                Object wi = wc.getConstructor().newInstance();
                try { wc.getField("intent").set(wi, launch); } catch (Throwable ig) {}
                try { wc.getField("user").set(wi, android.os.Process.myUserHandle()); } catch (Throwable ig) {}
                try { wc.getField("title").set(wi, pkg); } catch (Throwable ig) {}
                try {
                    android.graphics.drawable.Drawable dd = iv.getDrawable();
                    int sz = Math.max(1, icon);
                    android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(sz, sz, android.graphics.Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
                    dd.setBounds(0, 0, sz, sz); dd.draw(cv);
                    Class<?> bc = Class.forName("com.android.launcher3.icons.BitmapInfo", true, act.getClassLoader());
                    Object bi = null;
                    try { bi = bc.getMethod("of", android.graphics.Bitmap.class, int.class).invoke(null, bmp, 0); }
                    catch (Throwable e1) { bi = bc.getConstructor(android.graphics.Bitmap.class, int.class).newInstance(bmp, 0); }
                    Class<?> k = wc;
                    java.lang.reflect.Field bf = null;
                    while (k != null && bf == null) { try { bf = k.getDeclaredField("bitmap"); } catch (Throwable e2) { k = k.getSuperclass(); } }
                    if (bf != null) { bf.setAccessible(true); bf.set(wi, bi); }
                    else XposedBridge.log(Hook.TAG + "dock anim: no bitmap field");
                } catch (Throwable t) { XposedBridge.log(Hook.TAG + "dock anim bitmap: " + t); }
                iv.setTag(wi);
                try {   // a real launcher icon view, so the launcher animates into it like a home-screen icon
                    Class<?> btc = Class.forName("com.android.launcher3.BubbleTextView", true, act.getClassLoader());
                    View b = (View) btc.getConstructor(Context.class).newInstance(act);
                    btc.getMethod("applyFromWorkspaceItem", wc).invoke(b, wi);
                    try { btc.getMethod("setTextVisibility", boolean.class).invoke(b, false); } catch (Throwable ig) {}
                    b.setTag(wi);
                    final Intent l2 = launch;
                    b.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) { l2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); act.startActivity(l2); }
                    });
                    target = b;
                } catch (Throwable t) { XposedBridge.log(Hook.TAG + "dock anim bubble: " + t); }
            } catch (Throwable t) { XposedBridge.log(Hook.TAG + "dock anim tag: " + t); }
            row.addView(target, lp);
            rowIcons.put(pkg, new WeakReference<View>(target));
            shown++;
        }
        row.setVisibility(shown > 0 ? View.VISIBLE : View.INVISIBLE);
    }

    private static volatile int[] settledXY;
    private static volatile int calDx = -51, calDy = -112;
    private static WeakReference<View> hotseatRef;
    private static String lastSig = "";
    private static final java.util.Map<String, android.graphics.drawable.Drawable> iconCache = new java.util.HashMap<>();
    private static final java.util.Set<Object> hiddenFloat = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Object, Boolean>());

    private static boolean isRowIcon(View v) {
        for (WeakReference<View> w : rowIcons.values()) if (w.get() == v) return true;
        return false;
    }

    private static void dockPackages(View v, List<String> out) {
        try {
            Object tag = v.getTag();
            if (tag != null) {
                Object cn = XposedHelpers.callMethod(tag, "getTargetComponent");
                if (cn instanceof ComponentName) out.add(((ComponentName) cn).getPackageName());
            }
        } catch (Throwable ignored) {}
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dockPackages(g.getChildAt(i), out);
        }
    }
}
