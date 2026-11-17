package com.netspeed.xposed;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Row 2's own icons, drawn here instead of the stock ones: notification count, Bluetooth devices,
 * ringer / DND, next alarm, Wi-Fi router, a small tray for the rest; plus the portrait battery.
 * All follow the bar's text colour (white / dark) and draw state colours that work on both.
 */
final class SysViews {
    private SysViews() {}

    // ------------------------------------------------------------ colours (dark bar / light bar)

    static boolean dark(int color) {
        return (0.299f * ((color >> 16) & 0xFF) + 0.587f * ((color >> 8) & 0xFF) + 0.114f * (color & 0xFF)) > 128;
    }

    static int green(boolean dk) { return dk ? 0xFF34C759 : 0xFF1E8E3E; }
    static int yellow(boolean dk) { return dk ? 0xFFFFD60A : 0xFFB38600; }
    static int amber(boolean dk) { return dk ? 0xFFFFB020 : 0xFFC47A00; }
    static int orange(boolean dk) { return dk ? 0xFFFF9F0A : 0xFFC46A00; }
    static int red(boolean dk) { return dk ? 0xFFFF453A : 0xFFC4281C; }
    static int blue(boolean dk) { return dk ? 0xFF64B5F6 : 0xFF1565C0; }
    static int purple(boolean dk) { return dk ? 0xFFBF5AF2 : 0xFF7B3FC4; }
    static int teal(boolean dk) { return dk ? 0xFF64D2FF : 0xFF0086A8; }

    static int dim(int color) {
        return (color & 0x00FFFFFF) | 0x99000000;
    }

    static int faint(int color) {
        return (color & 0x00FFFFFF) | 0x40000000;
    }

    /** Battery-style level colour: green > 50, amber 20..50, red below. */
    static int levelColor(int pct, boolean dk) {
        if (pct < 0) return 0;
        return pct > 50 ? green(dk) : pct >= 20 ? amber(dk) : red(dk);
    }

    /** Base for the small views: bar colour, font, the small (2-line) and big text sizes. */
    abstract static class Base extends View {
        int color = 0xFFFFFFFF;
        Typeface tf = Typeface.DEFAULT_BOLD;
        float small = 20, big = 28;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);

        Base(Context c) {
            super(c);
        }

        boolean set(int color, Typeface tf, float small, float big) {
            boolean changed = this.color != color || this.tf != tf || this.small != small || this.big != big;
            this.color = color;
            this.tf = tf;
            this.small = small;
            this.big = big;
            return changed;
        }

        float text(String s, float size) {
            t.setTypeface(tf);
            t.setTextSize(size);
            return t.measureText(s);
        }

        void label(Canvas c, String s, float x, float baseline, float size, int col) {
            t.setTypeface(tf);
            t.setTextSize(size);
            t.setColor(col);
            c.drawText(s, x, baseline, t);
        }

        /** Baselines for two lines centred in the view. */
        float line1() {
            return getHeight() / 2f - small * 0.12f;
        }

        float line2() {
            return getHeight() / 2f + small * 0.86f;
        }

        void stroke(float w, int col) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(w);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(col);
        }

        void fill(int col) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(col);
        }

        void relayout(int w) {
            if (getMeasuredWidth() != w) requestLayout();
            invalidate();
        }
    }

    // ------------------------------------------------------------ notifications: bell, count, (silent)

    static final class Notif extends Base {
        int all = -1, silent;
        int prevAll = -1;
        long animAt = 0;

        /** A new notification arrived: shake the bell once and roll the number up. */
        void bump(int from) {
            prevAll = from;
            animAt = android.os.SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }

        Notif(Context c) {
            super(c);
        }

        int want() {
            float w = big * 0.8f + big * 0.18f + text(all < 0 ? "?" : String.valueOf(all), big);
            if (silent > 0) w += big * 0.12f + text("(" + silent + ")", big * 0.62f);
            return (int) Math.ceil(w) + 2;
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(want(), MeasureSpec.getSize(h));
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = big * 0.8f, cx = h / 2f, cy = getHeight() / 2f;
            int col = all == 0 ? dim(color) : color;
            long el = android.os.SystemClock.uptimeMillis() - animAt;
            boolean anim = animAt > 0 && el < 1000;
            c.save();
            if (anim) {
                float k = 1f - el / 1000f;
                c.rotate(26f * k * (float) Math.sin(el / 55.0), cx, cy - h * 0.45f);
            }
            bell(c, cx, cy, h, col);
            c.restore();
            float x = h + big * 0.18f, base = cy + big * 0.36f;
            String n = all < 0 ? "?" : String.valueOf(all);
            if (anim && el < 380 && prevAll >= 0) {
                float f = el / 380f, d = big * 0.9f;
                c.save();
                c.clipRect(x - 1, cy - big * 0.6f, x + text(n, big) + big * 0.3f, cy + big * 0.6f);
                label(c, String.valueOf(prevAll), x, base - d * f, big, (col & 0x00FFFFFF) | (Math.round(255 * (1 - f)) << 24));
                label(c, n, x, base + d * (1 - f), big, (col & 0x00FFFFFF) | (Math.round(255 * f) << 24));
                c.restore();
            } else {
                label(c, n, x, base, big, col);
            }
            if (anim) postInvalidateOnAnimation();
            if (silent > 0) {
                x += text(n, big) + big * 0.12f;
                label(c, "(" + silent + ")", x, base, big * 0.62f, dim(color));
            }
        }

        void bell(Canvas c, float cx, float cy, float h, int col) {
            fill(col);
            float w = h * 0.78f;
            RectF dome = new RectF(cx - w / 2, cy - h * 0.5f, cx + w / 2, cy + h * 0.3f);
            c.drawArc(dome, 180, 180, true, p);
            c.drawRect(cx - w / 2, cy - h * 0.1f, cx + w / 2, cy + h * 0.28f, p);
            Path base = new Path();
            base.moveTo(cx - w * 0.66f, cy + h * 0.34f);
            base.lineTo(cx + w * 0.66f, cy + h * 0.34f);
            base.lineTo(cx + w * 0.5f, cy + h * 0.2f);
            base.lineTo(cx - w * 0.5f, cy + h * 0.2f);
            base.close();
            c.drawPath(base, p);
            c.drawCircle(cx, cy + h * 0.44f, h * 0.1f, p);
        }
    }

    // ------------------------------------------------------------ Bluetooth: rune + up to 2 devices

    static final class Bt extends Base {
        boolean on;
        List<SysState.Dev> devs = new ArrayList<>();

        Bt(Context c) {
            super(c);
        }

        float runeW() {
            return small * 0.75f;
        }

        int want() {
            if (!on) return 0;
            float w = runeW() + small * 0.3f, col = 0;
            for (int i = 0; i < Math.min(2, devs.size()); i++) col = Math.max(col, lineW(devs.get(i)));
            if (devs.size() > 2) col = Math.max(col, 0) + small * 0.2f + text("+" + (devs.size() - 2), small * 0.8f);
            return (int) Math.ceil(w + col) + 2;
        }

        float lineW(SysState.Dev d) {
            return text(String.valueOf(d.kind), small) + small * 0.2f
                    + text(d.battery >= 0 ? d.battery + "%" : "--", small) + small * 0.25f + text(d.codec, small * 0.72f);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(want(), MeasureSpec.getSize(h));
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!on) return;
            boolean dk = dark(color);
            int rc = devs.isEmpty() ? dim(color) : blue(dk);
            float h = small * 1.5f, cy = getHeight() / 2f, x0 = small * 0.1f, x1 = x0 + runeW() * 0.85f;
            stroke(Math.max(2f, small * 0.12f), rc);
            Path r = new Path();
            float mx = (x0 + x1) / 2f;
            r.moveTo(x0, cy - h * 0.22f);
            r.lineTo(x1, cy + h * 0.2f);
            r.lineTo(mx, cy + h * 0.45f);
            r.lineTo(mx, cy - h * 0.45f);
            r.lineTo(x1, cy - h * 0.2f);
            r.lineTo(x0, cy + h * 0.22f);
            c.drawPath(r, p);
            float x = runeW() + small * 0.3f;
            for (int i = 0; i < Math.min(2, devs.size()); i++) {
                SysState.Dev d = devs.get(i);
                float base = devs.size() == 1 ? cy + small * 0.36f : (i == 0 ? line1() : line2());
                float xx = x;
                int kc = d.kind == 'W' ? blue(dk) : d.kind == 'H' ? purple(dk) : d.kind == 'P' ? green(dk) : color;
                String k = String.valueOf(d.kind);
                label(c, k, xx, base, small, kc);
                xx += text(k, small) + small * 0.2f;
                String pct = d.battery >= 0 ? d.battery + "%" : "--";
                label(c, pct, xx, base, small, d.battery >= 0 ? levelColor(d.battery, dk) : dim(color));
                xx += text(pct, small) + small * 0.25f;
                label(c, d.codec, xx, base, small * 0.72f, dim(color));
            }
            if (devs.size() > 2) {
                float col = 0;
                for (int i = 0; i < 2; i++) col = Math.max(col, lineW(devs.get(i)));
                label(c, "+" + (devs.size() - 2), x + col + small * 0.2f, cy + small * 0.3f, small * 0.8f, dim(color));
            }
        }
    }

    // ------------------------------------------------------------ ringer: vibrate / silent / DND

    static final class Ringer extends Base {
        int mode;   // 0 normal (hidden), 1 vibrate, 2 silent, 3 DND

        Ringer(Context c) {
            super(c);
        }

        int want() {
            return mode == 0 ? 0 : (int) Math.ceil(big * 1.05f);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(want(), MeasureSpec.getSize(h));
        }

        @Override
        protected void onDraw(Canvas c) {
            if (mode == 0) return;
            boolean dk = dark(color);
            float cx = getWidth() / 2f, cy = getHeight() / 2f, h = big * 0.82f;
            if (mode == 1) {
                int col = orange(dk);
                stroke(Math.max(2f, big * 0.09f), col);
                c.drawRoundRect(new RectF(cx - h * 0.2f, cy - h * 0.48f, cx + h * 0.2f, cy + h * 0.48f), h * 0.08f, h * 0.08f, p);
                for (int s = -1; s <= 1; s += 2) {
                    c.drawLine(cx + s * h * 0.34f, cy - h * 0.22f, cx + s * h * 0.34f, cy + h * 0.22f, p);
                    c.drawLine(cx + s * h * 0.48f, cy - h * 0.12f, cx + s * h * 0.48f, cy + h * 0.12f, p);
                }
            } else if (mode == 2) {
                int col = red(dk);
                fill(col);
                float w = h * 0.7f;
                c.drawArc(new RectF(cx - w / 2, cy - h * 0.5f, cx + w / 2, cy + h * 0.3f), 180, 180, true, p);
                c.drawRect(cx - w / 2, cy - h * 0.1f, cx + w / 2, cy + h * 0.28f, p);
                c.drawRect(cx - w * 0.62f, cy + h * 0.22f, cx + w * 0.62f, cy + h * 0.34f, p);
                c.drawCircle(cx, cy + h * 0.44f, h * 0.09f, p);
                // slash, with a gap in the bar colour's opposite so it reads
                stroke(Math.max(2f, big * 0.1f), col);
                c.drawLine(cx - h * 0.5f, cy - h * 0.5f, cx + h * 0.5f, cy + h * 0.5f, p);
            } else {
                int col = purple(dk);
                fill(col);
                c.drawCircle(cx, cy, h * 0.46f, p);
                fill(dk ? 0xFF1C1C1E : 0xFFFFFFFF);
                c.drawRoundRect(new RectF(cx - h * 0.26f, cy - h * 0.07f, cx + h * 0.26f, cy + h * 0.07f), h * 0.05f, h * 0.05f, p);
            }
        }
    }

    // ------------------------------------------------------------ NetSwitch: poor-signal bars, SIM card, 4G

    static final class NetSw extends Base {
        int sim = 1, rat = 5;
        boolean poor, eco;
        boolean on;     // the module has reported recently

        NetSw(Context c) {
            super(c);
        }

        boolean show() {
            return on && (poor || sim == 2);
        }

        int want() {
            if (!show()) return 0;
            float h = big * 0.8f, w = 0;
            if (poor) w += h * 0.9f;
            if (sim == 2) w += h * 0.75f;
            return (int) Math.ceil(w) + 2;
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(want(), MeasureSpec.getSize(h));
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!show()) return;
            boolean dk = dark(color);
            float h = big * 0.8f, cy = getHeight() / 2f, x = 1;
            if (poor) {
                // three signal bars, the first one alone lit, and a "!" - poor network
                int col = red(dk);
                float bw = h * 0.2f, gap = h * 0.08f;
                for (int i = 0; i < 3; i++) {
                    float bh = h * (0.35f + 0.3f * i);
                    fill(i == 0 ? col : faint(color));
                    c.drawRoundRect(new RectF(x + i * (bw + gap), cy + h * 0.45f - bh, x + i * (bw + gap) + bw, cy + h * 0.45f),
                            bw * 0.3f, bw * 0.3f, p);
                }
                label(c, "!", x + 3 * (bw + gap) - gap * 0.2f, cy + h * 0.22f, h * 0.75f, col);
                x += h * 0.9f;
            }
            if (sim == 2) {
                // SIM card with a cut corner and a 2
                int col = purple(dk);
                float w = h * 0.55f, top = cy - h * 0.5f, bot = cy + h * 0.5f;
                Path path = new Path();
                path.moveTo(x, top + h * 0.22f);
                path.lineTo(x + h * 0.22f, top);
                path.lineTo(x + w, top);
                path.lineTo(x + w, bot);
                path.lineTo(x, bot);
                path.close();
                fill(col);
                c.drawPath(path, p);
                float tw = text("2", h * 0.62f);
                label(c, "2", x + (w - tw) / 2f, cy + h * 0.36f, h * 0.62f, dk ? 0xFF1C1C1E : 0xFFFFFFFF);
                x += h * 0.75f;
            }
        }
    }

    // ------------------------------------------------------------ alarm: clock + time

    static final class Alarm extends Base {
        long at = -1;

        Alarm(Context c) {
            super(c);
        }

        String label() {
            if (at <= 0) return "";
            long now = System.currentTimeMillis();
            String f = at - now < 20 * 3600_000L ? "HH:mm" : "EEE HH:mm";
            return new SimpleDateFormat(f, Locale.US).format(new Date(at));
        }

        int want() {
            if (at <= 0) return 0;
            return (int) Math.ceil(big * 0.9f + big * 0.15f + text(label(), big * 0.85f)) + 2;
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(want(), MeasureSpec.getSize(h));
        }

        @Override
        protected void onDraw(Canvas c) {
            if (at <= 0) return;
            float h = big * 0.8f, cx = h / 2f + 1, cy = getHeight() / 2f;
            float sw = Math.max(2f, big * 0.085f);
            stroke(sw, color);
            c.drawCircle(cx, cy + h * 0.05f, h * 0.38f, p);
            c.drawLine(cx, cy + h * 0.05f, cx, cy - h * 0.18f, p);
            c.drawLine(cx, cy + h * 0.05f, cx + h * 0.16f, cy + h * 0.14f, p);
            c.drawLine(cx - h * 0.44f, cy - h * 0.3f, cx - h * 0.26f, cy - h * 0.46f, p);
            c.drawLine(cx + h * 0.44f, cy - h * 0.3f, cx + h * 0.26f, cy - h * 0.46f, p);
            label(c, label(), h + big * 0.15f, cy + big * 0.3f, big * 0.85f, color);
        }
    }

    // ------------------------------------------------------------ Wi-Fi: band | router with waves | dBm

    static final class Wifi extends Base {
        boolean on, down, up;
        int rssi, level, freq, std, mbps;

        Wifi(Context c) {
            super(c);
        }

        String left1() { return SysState.band(freq); }

        String left2() {
            int ch = SysState.channel(freq);
            return ch > 0 ? "ch " + ch : "";
        }

        String right2() { return mbps > 0 ? mbps + " Mbps" : ""; }

        float leftW() {
            return Math.max(text(left1(), small), text(left2(), small * 0.8f));
        }

        float routerW() {
            return small * 3.6f;
        }

        float rightW() {
            return Math.max(text(String.valueOf(rssi), small * 1.05f) + text("dBm", small * 0.65f) + 2,
                    text(right2(), small * 0.8f));
        }

        int want() {
            if (!on) return 0;
            return (int) Math.ceil(leftW() + small * 0.25f + routerW() + small * 0.25f + rightW()) + 2;
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(want(), MeasureSpec.getSize(h));
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!on) return;
            boolean dk = dark(color);
            // left: band (teal) over channel
            label(c, left1(), 0, line1(), small, teal(dk));
            label(c, left2(), 0, line2(), small * 0.8f, dim(color));
            // router
            float rx = leftW() + small * 0.25f, rw = routerW(), cx = rx + rw / 2f, H = getHeight();
            float bodyH = small * 0.95f, bodyW = small * 2.6f, bodyTop = H / 2f + small * 0.05f;
            float ant = small * 0.95f;
            // waves: count lit = level; colour = activity (left = down / green, right = up / blue)
            float ox = small * 0.55f, oy = bodyTop - ant * 0.55f;
            for (int i = 0; i < 4; i++) {
                float r = small * (0.45f + i * 0.33f);
                boolean lit = i < Math.max(1, level);
                int lc = lit ? (down ? green(dk) : color) : faint(color);
                int rc = lit ? (up ? blue(dk) : color) : faint(color);
                stroke(Math.max(1.5f, small * 0.1f), lc);
                c.drawArc(new RectF(cx - ox - r, oy - r, cx - ox + r, oy + r), 150, 60, false, p);
                stroke(Math.max(1.5f, small * 0.1f), rc);
                c.drawArc(new RectF(cx + ox - r, oy - r, cx + ox + r, oy + r), -30, 60, false, p);
            }
            // antennas
            stroke(Math.max(1.5f, small * 0.1f), color);
            c.drawLine(cx - bodyW * 0.25f, bodyTop, cx - ox, bodyTop - ant, p);
            c.drawLine(cx + bodyW * 0.25f, bodyTop, cx + ox, bodyTop - ant, p);
            fill(down ? green(dk) : color);
            c.drawCircle(cx - ox, bodyTop - ant, small * 0.13f, p);
            fill(up ? blue(dk) : color);
            c.drawCircle(cx + ox, bodyTop - ant, small * 0.13f, p);
            // body with the generation
            fill(color);
            c.drawRoundRect(new RectF(cx - bodyW / 2, bodyTop, cx + bodyW / 2, bodyTop + bodyH), small * 0.22f, small * 0.22f, p);
            String gen = SysState.wifiGen(std, freq);
            float gs = small * 0.66f;
            float gw = text(gen, gs);
            if (gw > bodyW * 0.92f) gs *= bodyW * 0.92f / gw;
            label(c, gen, cx - text(gen, gs) / 2f, bodyTop + bodyH / 2f + gs * 0.36f, gs, dk ? 0xFF1C1C1E : 0xFFFFF0B0);
            // right: dBm coloured by strength over link speed
            float x = rx + rw + small * 0.25f;
            int sc = rssi >= -60 ? green(dk) : rssi >= -70 ? yellow(dk) : rssi >= -80 ? orange(dk) : red(dk);
            String rs = String.valueOf(rssi);
            label(c, rs, x, line1(), small * 1.05f, sc);
            label(c, "dBm", x + text(rs, small * 1.05f) + 2, line1(), small * 0.65f, dim(color));
            label(c, right2(), x, line2(), small * 0.8f, dim(color));
        }
    }

    // ------------------------------------------------------------ tray: the other system icons, small

    static final class Tray extends Base {
        final List<Bitmap> icons = new ArrayList<>();
        final List<String> extras = new ArrayList<>();
        int maxW = Integer.MAX_VALUE;
        int shown;
        final Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        Tray(Context c) {
            super(c);
        }

        float iconH() {
            return small * 1.25f;
        }

        float itemW(int i) {
            Bitmap b = icons.get(i);
            float w = b.getHeight() == 0 ? 0 : iconH() * b.getWidth() / b.getHeight();
            String e = extras.get(i);
            if (e != null && !e.isEmpty()) w += small * 0.1f + text(e, small * 0.8f);
            return w + small * 0.35f;
        }

        int want() {
            float w = 0;
            int n = 0;
            float plus = text("+9", small * 0.85f) + small * 0.2f;
            for (int i = 0; i < icons.size(); i++) {
                float iw = itemW(i);
                boolean last = i == icons.size() - 1;
                if (w + iw + (last ? 0 : plus) > maxW) break;
                w += iw;
                n++;
            }
            shown = n;
            if (n < icons.size()) w += plus;
            return (int) Math.ceil(w);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(icons.isEmpty() ? 0 : want(), MeasureSpec.getSize(h));
        }

        @Override
        protected void onDraw(Canvas c) {
            ip.setColorFilter(new PorterDuffColorFilter(dim(color), PorterDuff.Mode.SRC_IN));
            float x = 0, ih = iconH(), cy = getHeight() / 2f;
            for (int i = 0; i < shown && i < icons.size(); i++) {
                Bitmap b = icons.get(i);
                float w = ih * b.getWidth() / Math.max(1, b.getHeight());
                c.drawBitmap(b, null, new RectF(x, cy - ih / 2, x + w, cy + ih / 2), ip);
                x += w;
                String e = extras.get(i);
                if (e != null && !e.isEmpty()) {
                    x += small * 0.1f;
                    label(c, e, x, cy + ih * 0.45f, small * 0.8f, dim(color));
                    x += text(e, small * 0.8f);
                }
                x += small * 0.35f;
            }
            if (shown < icons.size()) label(c, "+" + (icons.size() - shown), x, cy + small * 0.3f, small * 0.85f, dim(color));
        }
    }

    // ------------------------------------------------------------ portrait battery with % inside

    static final class Battery extends Base {
        int level = -1;
        boolean charging;

        Battery(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension((int) Math.ceil(small * 1.3f) + 2, MeasureSpec.getSize(h));
        }

        @Override
        protected void onDraw(Canvas c) {
            boolean dk = dark(color);
            float bw = small * 1.2f, bh = small * 2.05f, cap = small * 0.18f;
            float left = (getWidth() - bw) / 2f, top = (getHeight() - bh - cap) / 2f + cap;
            float sw = Math.max(1.5f, small * 0.1f);
            // cap
            fill(color);
            c.drawRoundRect(new RectF(left + bw * 0.3f, top - cap, left + bw * 0.7f, top + sw), cap * 0.4f, cap * 0.4f, p);
            // body
            stroke(sw, color);
            RectF body = new RectF(left + sw / 2, top + sw / 2, left + bw - sw / 2, top + bh - sw / 2);
            c.drawRoundRect(body, small * 0.28f, small * 0.28f, p);
            if (level >= 0) {
                float in = sw * 1.5f;
                float x0 = left + in, x1 = left + bw - in, y0 = top + in, y1 = top + bh - in;
                int lc = charging ? green(dk) : levelColor(level, dk);
                float frac = Math.min(100, level) / 100f;
                if (charging) {
                    // slow rising sweep above the level while charging
                    long ph = android.os.SystemClock.uptimeMillis() % 2400;
                    float extra = (1 - frac) * (ph / 2400f);
                    fill((lc & 0x00FFFFFF) | 0x55000000);
                    c.drawRoundRect(new RectF(x0, y1 - (y1 - y0) * (frac + extra), x1, y1), small * 0.15f, small * 0.15f, p);
                }
                fill(lc);
                c.drawRoundRect(new RectF(x0, y1 - (y1 - y0) * frac, x1, y1), small * 0.15f, small * 0.15f, p);
                if ((charging || SysState.testing()) && Cfg.b(Cfg.ANIM_CHARGE, Cfg.D_ANIM_CHARGE)) {
                    if (isShown()) postInvalidateDelayed(60);
                    // a light running up through the fill
                    float fh = (y1 - y0) * frac, band = Math.max(small * 0.5f, fh * 0.35f);
                    float ty = y1 - fh + fh * (1f - (android.os.SystemClock.uptimeMillis() % 1400) / 1400f) - band / 2f;
                    c.save();
                    c.clipRect(x0, y1 - fh, x1, y1);
                    fill(0x66FFFFFF);
                    c.drawRect(x0, ty, x1, ty + band, p);
                    c.restore();
                }
                // % inside: white with a dark edge so it reads over the fill and the empty part
                String s = String.valueOf(level);
                float ts = small * (level >= 100 ? 0.62f : 0.82f);
                t.setTypeface(tf);
                t.setTextSize(ts);
                float tw = t.measureText(s);
                float tx = left + (bw - tw) / 2f, ty = top + bh * 0.62f;
                t.setStyle(Paint.Style.STROKE);
                t.setStrokeWidth(Math.max(2f, ts * 0.16f));
                t.setColor(0xCC000000);
                c.drawText(s, tx, ty, t);
                t.setStyle(Paint.Style.FILL);
                t.setColor(0xFFFFFFFF);
                c.drawText(s, tx, ty, t);
                if (charging) {
                    // small bolt in the top part
                    fill(dk ? 0xFFFFFFFF : 0xFF1C1C1E);
                    float bx = left + bw / 2f, by = top + bh * 0.2f, s2 = small * 0.22f;
                    Path bolt = new Path();
                    bolt.moveTo(bx + s2 * 0.2f, by - s2);
                    bolt.lineTo(bx - s2 * 0.6f, by + s2 * 0.15f);
                    bolt.lineTo(bx - s2 * 0.05f, by + s2 * 0.15f);
                    bolt.lineTo(bx - s2 * 0.25f, by + s2 * 1.1f);
                    bolt.lineTo(bx + s2 * 0.6f, by - s2 * 0.1f);
                    bolt.lineTo(bx + s2 * 0.05f, by - s2 * 0.1f);
                    bolt.close();
                    c.drawPath(bolt, p);
                    if (isShown() && Cfg.b(Cfg.ANIM_CHARGE, Cfg.D_ANIM_CHARGE)) postInvalidateDelayed(120);
                }
            }
        }
    }

    // ------------------------------------------------------------ hidden host for the stock icon areas

    /**
     * Holds the stock notification icons and system icons: they keep being laid out (System UI
     * and the row-1 copies need them) but take no width and draw nothing.
     */
    static final class HiddenHost extends FrameLayout {
        HiddenHost(Context c) {
            super(c);
            setClipChildren(false);
        }

        @Override
        protected void onMeasure(int w, int h) {
            int hh = MeasureSpec.getSize(h);
            int cw = MeasureSpec.makeMeasureSpec(getResources().getDisplayMetrics().widthPixels, MeasureSpec.AT_MOST);
            int ch = MeasureSpec.makeMeasureSpec(hh, MeasureSpec.EXACTLY);
            for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(cw, ch);
            setMeasuredDimension(0, hh);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
            }
        }

        @Override
        protected void dispatchDraw(Canvas c) {
            // nothing: row 2 shows our own icons
        }
    }

    /**
     * A horizontal row that never grows wider than maxW (so the weather on the right stays put).
     * Any item that would sit under the camera cutout is moved to just right of it (the items
     * after it move along), so nothing is drawn under the camera.
     */
    static final class CapRow extends LinearLayout {
        int maxW = Integer.MAX_VALUE;
        /** Total extra width added to skip the cutout (the tray's room is reduced by this). */
        int cutShift = 0;
        private int[] shifts = new int[0];

        CapRow(Context c) {
            super(c);
        }

        /** The cutout's [left, right] in this row's own x, or null when it doesn't reach this row. */
        private int[] cutoutSpan() {
            try {
                android.view.WindowInsets wi = getRootWindowInsets();
                android.view.DisplayCutout dc = wi == null ? null : wi.getDisplayCutout();
                if (dc == null) return null;
                int[] me = new int[2];
                getLocationOnScreen(me);
                float d = getResources().getDisplayMetrics().density;
                int tol = Math.round(6 * d);
                for (android.graphics.Rect r : dc.getBoundingRects()) {
                    if (r.width() <= 0 || r.left <= 0) continue;          // corner / full-width cutouts
                    if (r.bottom + tol <= me[1]) continue;                // entirely above this row
                    if (r.top >= me[1] + getHeight()) continue;           // entirely below
                    int pad = Math.round(3 * d);
                    return new int[]{r.left - me[0] - pad, r.right - me[0] + pad};
                }
            } catch (Throwable ignored) { }
            return null;
        }

        @Override
        protected void onMeasure(int w, int h) {
            int mode = MeasureSpec.getMode(w), size = MeasureSpec.getSize(w);
            int cap = maxW;
            if (mode != MeasureSpec.UNSPECIFIED) cap = Math.min(cap, size);
            super.onMeasure(MeasureSpec.makeMeasureSpec(Math.max(0, cap), MeasureSpec.AT_MOST), h);
            int n = getChildCount();
            if (shifts.length != n) shifts = new int[n];
            java.util.Arrays.fill(shifts, 0);
            int total = 0;
            int[] cut = isLaidOut() ? cutoutSpan() : null;
            if (cut != null) {
                int x = getPaddingLeft();
                for (int i = 0; i < n; i++) {
                    View c = getChildAt(i);
                    if (c.getVisibility() == GONE) continue;
                    LayoutParams lp = (LayoutParams) c.getLayoutParams();
                    x += lp.leftMargin;
                    int cw = c.getMeasuredWidth();
                    if (cw > 0 && x < cut[1] && x + cw > cut[0]) {
                        shifts[i] = cut[1] - x;
                        x += shifts[i];
                        total += shifts[i];
                    }
                    x += cw + lp.rightMargin;
                }
            }
            if (total != cutShift) {
                cutShift = total;
                post(this::requestLayout);   // positions depend on where we end up on screen
            }
            if (total > 0) {
                setMeasuredDimension(Math.min(getMeasuredWidth() + total, Math.max(0, cap)), getMeasuredHeight());
            }
        }

        /** Width child may use before reaching the camera cutout (MAX_VALUE when there is none). */
        int roomBefore(View child) {
            int[] cut = isLaidOut() ? cutoutSpan() : null;
            if (cut == null) return Integer.MAX_VALUE;
            int x = getPaddingLeft();
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                LayoutParams lp = (LayoutParams) c.getLayoutParams();
                x += lp.leftMargin;
                if (c == child) return Math.max(0, cut[0] - x);
                x += c.getMeasuredWidth() + lp.rightMargin;
                if (x > cut[0]) return 0;
            }
            return Integer.MAX_VALUE;
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            int acc = 0;
            for (int i = 0; i < getChildCount() && i < shifts.length; i++) {
                acc += shifts[i];
                if (acc != 0) getChildAt(i).offsetLeftAndRight(acc);
            }
        }
    }

    static LinearLayout.LayoutParams wrap(int marginStartPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMarginStart(marginStartPx);
        return lp;
    }
}
