package com.netspeed.xposed;

import android.animation.ValueAnimator;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.WeakHashMap;

/**
 * Idle dimming: after a few quiet seconds every item in the status bar softens; an item whose
 * value changes lights up again for a few seconds. Uses the view's transition alpha, so it
 * stacks with the alpha the module already uses (lock screen hiding etc.).
 */
final class IdleDim {
    private IdleDim() {}

    private static final float DIM = 0.42f;
    private static final long LIT_MS = 4_000, TEST_LIT_MS = 1_200;

    private static final class St {
        int sig;
        long changedAt;
        float target = 1f;
        ValueAnimator anim;
    }

    private static final WeakHashMap<View, St> states = new WeakHashMap<>();
    private static boolean wasOn;

    /** Called once a second on the UI thread with the status bar root. */
    static void pass(ViewGroup root) {
        if (root == null) return;
        boolean on = Cfg.b(Cfg.ANIM_DIM, Cfg.D_ANIM_DIM);
        if (!on) {
            if (wasOn) {
                for (View v : states.keySet()) if (v != null) fade(v, states.get(v), 1f);
                wasOn = false;
            }
            return;
        }
        wasOn = true;
        long now = SystemClock.uptimeMillis();
        boolean test = SysState.testing();
        walk(root, now, test);
    }

    private static void walk(ViewGroup g, long now, boolean test) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            if (v instanceof ViewGroup && !(v instanceof ReadableBar)) {
                walk((ViewGroup) v, now, test);
                continue;
            }
            St s = states.get(v);
            int sig = sig(v);
            if (s == null) {
                s = new St();
                s.sig = sig;
                s.changedAt = now;
                states.put(v, s);
            } else if (sig != s.sig) {
                s.sig = sig;
                s.changedAt = now;
            }
            long lit = test ? TEST_LIT_MS : LIT_MS;
            // in the test, items light up one after another so the effect is easy to see
            if (test && (now / 700) % 6 == (Math.abs(System.identityHashCode(v)) % 6)) s.changedAt = now;
            fade(v, s, now - s.changedAt < lit ? 1f : DIM);
        }
    }

    private static void fade(final View v, St s, float to) {
        if (s == null || s.target == to) return;
        s.target = to;
        if (s.anim != null) s.anim.cancel();
        float from = v.getTransitionAlpha();
        ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(to > from ? 180 : 650);
        a.addUpdateListener(an -> v.setTransitionAlpha((Float) an.getAnimatedValue()));
        s.anim = a;
        a.start();
    }

    /** What the item currently shows; a change lights it up. */
    private static int sig(View v) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            return t == null ? 0 : t.toString().hashCode();
        }
        if (v instanceof ImageView) {
            Drawable d = ((ImageView) v).getDrawable();
            return d == null ? 0 : System.identityHashCode(d) * 31 + d.getLevel();
        }
        if (v instanceof SignalTowerView) return ((SignalTowerView) v).dimSig();
        if (v instanceof BatteryCellView) {
            BatteryCellView b = (BatteryCellView) v;
            return b.level * 2 + (b.charging ? 1 : 0);
        }
        if (v instanceof SysViews.Battery) {
            SysViews.Battery b = (SysViews.Battery) v;
            return b.level * 2 + (b.charging ? 1 : 0);
        }
        if (v instanceof SysViews.Notif) return ((SysViews.Notif) v).all;
        if (v instanceof SysViews.Wifi) {
            SysViews.Wifi w = (SysViews.Wifi) v;
            return (w.on ? 1 : 0) + w.level * 3 + w.freq / 1000 * 17;
        }
        if (v instanceof SysViews.Bt) {
            SysViews.Bt b = (SysViews.Bt) v;
            int h = b.on ? 1 : 0;
            for (SysState.Dev d : b.devs) h = h * 31 + d.name.hashCode() + d.battery / 5;
            return h;
        }
        return 0;
    }
}
