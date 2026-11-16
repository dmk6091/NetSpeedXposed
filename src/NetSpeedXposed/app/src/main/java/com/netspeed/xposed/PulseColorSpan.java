package com.netspeed.xposed;

import android.os.SystemClock;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.UpdateAppearance;

/** Text colour that swings between two colours (needs the view redrawn to move). */
final class PulseColorSpan extends CharacterStyle implements UpdateAppearance {
    private final int a, b;
    private final long period;

    PulseColorSpan(int a, int b, long periodMs) {
        this.a = a;
        this.b = b;
        this.period = Math.max(200, periodMs);
    }

    @Override
    public void updateDrawState(TextPaint tp) {
        float f = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * (SystemClock.uptimeMillis() % period) / period);
        tp.setColor(mix(a, b, f));
        // a touch of glow at the peak
        tp.setShadowLayer(tp.getTextSize() * 0.25f * f, 0, 0, (b & 0x00FFFFFF) | (Math.round(150 * f) << 24));
    }

    static int mix(int x, int y, float f) {
        int ar = (x >> 16) & 0xFF, ag = (x >> 8) & 0xFF, ab = x & 0xFF;
        int br = (y >> 16) & 0xFF, bg = (y >> 8) & 0xFF, bb = y & 0xFF;
        return 0xFF000000 | (Math.round(ar + (br - ar) * f) << 16) | (Math.round(ag + (bg - ag) * f) << 8)
                | Math.round(ab + (bb - ab) * f);
    }
}
