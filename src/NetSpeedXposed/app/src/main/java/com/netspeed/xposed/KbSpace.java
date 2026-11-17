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

    private static void fix(InputMethodService ims, View decor) {
        int id = ims.getResources().getIdentifier("keyboard_holder", "id", ims.getPackageName());
        View holder = id != 0 ? decor.findViewById(id) : null;
        if (!(holder instanceof ViewGroup) || holder.getHeight() <= 0) return;
        float dp = ims.getResources().getDisplayMetrics().density;
        int[] hl = new int[2];
        holder.getLocationInWindow(hl);
        View s = spacer((ViewGroup) holder, hl[1] + holder.getHeight(), holder.getWidth(), dp, 0);
        if (s == null) return;
        if (!said) { said = true; log(TAG + "spacer under the keys " + s.getWidth() + "x" + s.getHeight() + " px -> gone"); }
        s.setVisibility(View.GONE);
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
