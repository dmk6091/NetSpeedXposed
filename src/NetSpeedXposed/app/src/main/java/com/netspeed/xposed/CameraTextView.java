package com.netspeed.xposed;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Text curved around the front-camera hole: one line arcing over the top of the camera, one
 * under it (readable, left to right). Drawn on top of the status bar, never takes touches.
 */
final class CameraTextView extends View {
    float cx = -1, cy, r;              // camera circle in this view's coordinates
    String top = "", bottom = "";
    int topColor = 0xFFFFFFFF, bottomColor = 0xFFFF9F0A;
    boolean showBottom = true;
    float textPx = 18f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();

    CameraTextView(Context c) {
        super(c);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        p.setTypeface(Typeface.DEFAULT_BOLD);
    }

    void setFont(Typeface tf) {
        if (tf != null) p.setTypeface(Typeface.create(tf, Typeface.BOLD));
    }

    /** Shrink the text (down to 65%) so it fits the half circle; returns its width. */
    private float fit(String s, float len) {
        p.setTextSize(textPx);
        float w = p.measureText(s);
        if (w > len * 0.96f) p.setTextSize(Math.max(textPx * 0.65f, textPx * len * 0.96f / w));
        return p.measureText(s);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (cx < 0 || r <= 0) return;
        p.setTextSize(textPx);
        Paint.FontMetrics fm = p.getFontMetrics();
        float gap = textPx * 0.25f;
        // top: baseline on a circle just outside the camera, letters pointing outward
        if (!top.isEmpty()) {
            float R = r + gap - fm.descent;
            oval.set(cx - R, cy - R, cx + R, cy + R);
            path.reset();
            path.addArc(oval, 180, 180);                     // left -> over the top -> right
            float len = (float) (Math.PI * R);
            float tw = fit(top, len);
            p.setColor(topColor);
            c.drawTextOnPath(top, path, Math.max(0, (len - tw) / 2f), 0, p);
        }
        // bottom: left -> under the camera -> right, so it reads normally
        if (showBottom && !bottom.isEmpty()) {
            float R = r + gap - fm.ascent;                   // letters reach in towards the camera
            oval.set(cx - R, cy - R, cx + R, cy + R);
            path.reset();
            path.addArc(oval, 180, -180);
            float len = (float) (Math.PI * R);
            float tw = fit(bottom, len);
            p.setColor(bottomColor);
            c.drawTextOnPath(bottom, path, Math.max(0, (len - tw) / 2f), 0, p);
        }
    }
}
