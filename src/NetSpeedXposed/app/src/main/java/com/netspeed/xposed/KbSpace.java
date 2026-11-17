package com.netspeed.xposed;

import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Keyboard without the blank strip under its keys. With gesture navigation Gboard puts an empty spacer view
 * (about 48 dp) below the key rows, inside its own window; no system inset or dimension controls it
 * (zeroing those in Gboard's process changed nothing), so the spacer itself is collapsed. Only inside the
 * keyboard's process: the pill, its window and the swipe area are untouched. Follows immersive navigation
 * (same property).
 */
final class KbSpace {
    private static final String TAG = "NetSpeedXposed: [kb] ";

    private KbSpace() {}

    private static void log(String m) {
        XposedBridge.log(m);
        try { android.util.Log.e("KbSpace", m); } catch (Throwable ignored) { }
    }

    private static boolean said;

    static void install(final String pkg) {
        if (!NavImmersive.enabled()) {
            log(TAG + pkg + ": immersive navigation is off, keyboard left alone");
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(InputMethodService.class, "onWindowShown", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        final InputMethodService ims = (InputMethodService) p.thisObject;
                        final View decor = ims.getWindow().getWindow().getDecorView();
                        fix(ims, decor);
                        hookInsets(ims.getClass());
                        if (decor.getTag(0x7f0b7e57) == null) {
                            decor.setTag(0x7f0b7e57, Boolean.TRUE);
                            decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                                @Override
                                public void onGlobalLayout() {
                                    try { fix(ims, decor); } catch (Throwable ignored) { }
                                }
                            });
                        }
                    } catch (Throwable t) {
                        log(TAG + "fix failed: " + t);
                    }
                }
            });
            log(TAG + pkg + ": keyboard space hook ready");
        } catch (Throwable t) {
            log(TAG + "hook failed: " + t);
        }
    }

    private static boolean insetsHooked;

    /** The keyboard now starts lower: tell the system, so apps use the freed space and touches above the keys go to the app. */
    private static void hookInsets(Class<?> c) {
        if (insetsHooked) return;
        insetsHooked = true;
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    int sh = shift;
                    View m = moved;
                    if (sh <= 0 || m == null || !m.isShown() || m.getTranslationY() != sh) return;
                    InputMethodService.Insets in = (InputMethodService.Insets) p.args[0];
                    int[] l = new int[2];
                    m.getLocationInWindow(l);
                    int top = l[1];
                    if (in.contentTopInsets >= top) return;       // already counted
                    int old = Math.min(in.contentTopInsets, in.visibleTopInsets);
                    in.contentTopInsets = Math.max(in.contentTopInsets, top);
                    in.visibleTopInsets = Math.max(in.visibleTopInsets, top);
                    if (in.touchableInsets == InputMethodService.Insets.TOUCHABLE_INSETS_REGION && in.touchableRegion != null)
                        in.touchableRegion.op(0, old, m.getRootView().getWidth(), top, android.graphics.Region.Op.DIFFERENCE);
                    if (!saidI) { saidI = true; log(TAG + "keyboard top reported " + old + " -> " + top); }
                } catch (Throwable ignored) { }
            }
        };
        int n = 0;
        // Gboard overrides onComputeInsets (possibly without calling super): hook every override up the chain
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                k.getDeclaredMethod("onComputeInsets", InputMethodService.Insets.class);
                XposedHelpers.findAndHookMethod(k, "onComputeInsets", InputMethodService.Insets.class, h);
                n++;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) { log(TAG + "insets hook failed: " + t); }
        }
        log(TAG + "insets hooks " + n);
    }

    private static boolean saidI;

    /** Height of the spacer and the view that was pushed down by it (the keyboard), for onComputeInsets. */
    private static volatile int shift;
    private static volatile View moved;

    private static void fix(InputMethodService ims, View decor) {
        int id = ims.getResources().getIdentifier("keyboard_holder", "id", ims.getPackageName());
        View holder = id != 0 ? decor.findViewById(id) : null;
        if (!(holder instanceof ViewGroup) || holder.getHeight() <= 0) return;
        float dp = ims.getResources().getDisplayMetrics().density;
        int[] hl = new int[2];
        holder.getLocationInWindow(hl);
        View s = spacer((ViewGroup) holder, hl[1] + holder.getHeight(), holder.getWidth(), dp, 0);
        int h = s == null ? 0 : s.getHeight();
        if (h > 0 && !said) { said = true; log(TAG + "spacer under the keys " + s.getWidth() + "x" + h + " px -> keyboard moved down by it"); }
        // Gboard keeps the keyboard's height fixed, so hiding the spacer leaves a hole: slide the whole keyboard down instead.
        // Its background (and blur) are siblings of the same size, so every same-sized layer moves together.
        View top = holder;
        while (top.getParent() instanceof View && ((View) top.getParent()).getHeight() == holder.getHeight()) top = (View) top.getParent();
        if (top.getParent() instanceof ViewGroup) {
            ViewGroup pg = (ViewGroup) top.getParent();
            for (int i = 0; i < pg.getChildCount(); i++) {
                View c = pg.getChildAt(i);
                if (c == top || (c.getHeight() == top.getHeight() && c.getTop() == top.getTop())) {
                    if (c.getTranslationY() != h) c.setTranslationY(h);
                }
            }
        } else if (top.getTranslationY() != h) top.setTranslationY(h);
        shift = h;
        moved = top;
    }

    /** The empty full-width view, 20-90 dp tall, that is the last child of its parent and ends at the keyboard's bottom edge. */
    private static View spacer(ViewGroup g, int bottom, int width, float dp, int depth) {
        if (depth > 6) return null;
        int n = g.getChildCount();
        if (n >= 2) {
            View last = g.getChildAt(n - 1);
            if (last.getVisibility() == View.VISIBLE && last.getWidth() == width
                    && last.getHeight() >= 20 * dp && last.getHeight() <= 90 * dp
                    && (!(last instanceof ViewGroup) || ((ViewGroup) last).getChildCount() == 0)) {
                int[] l = new int[2];
                last.getLocationInWindow(l);
                if (Math.abs(l[1] + last.getHeight() - bottom) <= 2) return last;
            }
        }
        for (int i = n - 1; i >= 0; i--) {
            View c = g.getChildAt(i);
            if (c instanceof ViewGroup && c.getVisibility() == View.VISIBLE) {
                View r = spacer((ViewGroup) c, bottom, width, dp, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }
}
