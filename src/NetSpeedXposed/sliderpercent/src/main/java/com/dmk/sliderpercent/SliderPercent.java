package com.dmk.sliderpercent;

import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.View;
import android.widget.ProgressBar;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * OxygenOS / ColorOS: shows the percentage on the brightness and volume sliders.
 *
 * Both the control-centre sliders (OplusQsVerticalSeekBar) and the volume-key panel
 * (OplusVolumeSeekBar) are COUIVerticalSeekBars, so one hook on its draw() covers all of
 * them. The number sits at the top of the track; the part of it inside the filled area is
 * drawn in a contrasting colour, so it stays readable at any level.
 *
 * Does nothing on non-Oplus ROMs (the classes aren't there).
 */
final class SliderPercent {
    private static final String TAG = "SliderPercent: ";
    private static final String BASE = "com.coui.appcompat.seekbar.COUIVerticalSeekBar";
    private static final String QS = "com.oplus.systemui.qs.base.seek.OplusQsVerticalSeekBar";
    private static final String VOL = "com.oplus.systemui.volume.OplusVolumeSeekBar";

    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static boolean loggedError;

    static {
        P.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        P.setTextAlign(Paint.Align.CENTER);
    }

    private SliderPercent() {}

    static void install(ClassLoader cl) {
        final Class<?> qs = XposedHelpers.findClassIfExists(QS, cl);
        final Class<?> vol = XposedHelpers.findClassIfExists(VOL, cl);
        if (qs == null && vol == null) return; // not OxygenOS / ColorOS
        try {
            XposedHelpers.findAndHookMethod(BASE, cl, "draw", Canvas.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    Object v = p.thisObject;
                    if (!((qs != null && qs.isInstance(v)) || (vol != null && vol.isInstance(v)))) return;
                    try {
                        draw((ProgressBar) v, (Canvas) p.args[0]);
                    } catch (Throwable t) {
                        if (!loggedError) {
                            loggedError = true;
                            XposedBridge.log(TAG + "draw failed: " + t);
                        }
                    }
                }
            });
            XposedBridge.log(TAG + "hooked (qs=" + (qs != null) + ", volume=" + (vol != null) + ")");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook failed: " + t);
        }
    }

    private static void draw(ProgressBar bar, Canvas c) {
        // The track (background) and the filled part, both worked out by draw() just now.
        Rect track = (Rect) XposedHelpers.getObjectField(bar, "mClipProgressRect");
        Rect fill = (Rect) XposedHelpers.getObjectField(bar, "mProgressRect");
        if (track == null || track.width() <= 0 || track.height() <= 0) return;

        float dp = bar.getResources().getDisplayMetrics().density;
        // Collapsed / thin bars (e.g. the volume panel while it animates in): no room.
        if (track.width() < 18 * dp || track.height() < 56 * dp) return;

        int pct = percent(bar);
        if (pct < 0) return;
        String s = pct + "%";

        float size = Math.min(13 * bar.getResources().getDisplayMetrics().scaledDensity, track.width() * 0.34f);
        P.setTextSize(size);
        float maxW = track.width() * 0.86f;
        float w = P.measureText(s);
        if (w > maxW) P.setTextSize(size * maxW / w);

        Paint.FontMetrics fm = P.getFontMetrics();
        float cx = track.exactCenterX();
        float top = track.top + Math.max(10 * dp, track.width() * 0.28f);
        float baseline = top - fm.ascent;

        // Where the fill starts (it grows from the bottom).
        int edge = (fill != null && fill.height() > 0) ? fill.top : track.bottom;

        int fillColor = 0xFFFFFFFF;
        try {
            fillColor = XposedHelpers.getIntField(bar, "mProgressColor");
        } catch (Throwable ignored) {
        }
        int onFill = luminance(fillColor) > 0.6f ? 0xE6000000 : 0xF2FFFFFF;
        boolean night = (bar.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int onTrack = night ? 0xE6FFFFFF : 0xCC000000;

        int alpha = Math.round(255 * ((View) bar).getAlpha());

        // Empty part of the track
        c.save();
        c.clipRect(track.left, track.top, track.right, Math.min(edge, track.bottom));
        P.setColor(onTrack);
        P.setAlpha(Math.round(((onTrack >>> 24) & 0xFF) * alpha / 255f));
        c.drawText(s, cx, baseline, P);
        c.restore();

        // Filled part
        if (edge < track.bottom) {
            c.save();
            c.clipRect(track.left, Math.max(edge, track.top), track.right, track.bottom);
            P.setColor(onFill);
            P.setAlpha(Math.round(((onFill >>> 24) & 0xFF) * alpha / 255f));
            c.drawText(s, cx, baseline, P);
            c.restore();
        }
    }

    /** 0..100 matching what the bar shows right now (also while dragging / animating). */
    private static int percent(ProgressBar bar) {
        try {
            float scale = ((Number) XposedHelpers.getObjectField(bar, "mScale")).floatValue();
            if (!Float.isNaN(scale)) return Math.round(Math.max(0f, Math.min(1f, scale)) * 100f);
        } catch (Throwable ignored) {
        }
        int min = bar.getMin(), max = bar.getMax();
        if (max <= min) return -1;
        float f = (bar.getProgress() - min) / (float) (max - min);
        return Math.round(Math.max(0f, Math.min(1f, f)) * 100f);
    }

    private static float luminance(int color) {
        return (0.299f * ((color >> 16) & 0xFF) + 0.587f * ((color >> 8) & 0xFF) + 0.114f * (color & 0xFF)) / 255f;
    }
}
