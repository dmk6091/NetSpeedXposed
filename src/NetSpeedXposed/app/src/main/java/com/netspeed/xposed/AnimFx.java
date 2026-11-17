package com.netspeed.xposed;

import android.view.View;
import android.view.animation.OvershootInterpolator;

/** Small one-shot animations for the status bar views. */
final class AnimFx {
    private AnimFx() {}

    /** Grow and settle back with a little overshoot. */
    static void pop(final View v, float scale) {
        v.animate().cancel();
        v.setPivotX(v.getWidth() / 2f);
        v.setPivotY(v.getHeight() / 2f);
        v.animate().scaleX(scale).scaleY(scale).setDuration(150).withEndAction(new Runnable() {
            @Override public void run() {
                v.animate().scaleX(1f).scaleY(1f).setDuration(320)
                        .setInterpolator(new OvershootInterpolator(2f)).start();
            }
        }).start();
    }
}
