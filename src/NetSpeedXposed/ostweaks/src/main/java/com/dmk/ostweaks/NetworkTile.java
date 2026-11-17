package com.dmk.ostweaks;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.telephony.SubscriptionManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quick settings tile: switch the data SIM between 5G allowed and 4G only.
 * Uses root: "cmd phone get/set-allowed-network-types-for-users" (user reason), toggling only the NR bit.
 */
public class NetworkTile extends TileService {
    private static final String TAG = "OSTweaksNetTile";
    private static final long NR = 1L << 19; // TelephonyManager.NETWORK_TYPE_BITMASK_NR
    private volatile Boolean nrOn;
    private static final java.util.Map<String, Integer> TYPES = new java.util.HashMap<>();
    static {
        String[] n = {"GPRS","EDGE","UMTS","CDMA","EVDO_0","EVDO_A","1xRTT","HSDPA","HSUPA","HSPA","IDEN","EVDO_B","LTE","EHRPD","HSPAP","GSM","TD_SCDMA","IWLAN","LTE_CA","NR"};
        for (int i = 0; i < n.length; i++) TYPES.put(n[i], i + 1);
    }

    @Override
    public void onStartListening() {
        draw();
        new Thread(() -> { nrOn = readNr(slot()); draw(); }).start();
    }

    @Override
    public void onClick() {
        new Thread(() -> {
            int slot = slot();
            Long cur = readMask(slot);
            if (cur == null) { Log.w(TAG, "cannot read network types (root granted?)"); nrOn = null; draw(); return; }
            long next = (cur & NR) != 0 ? (cur & ~NR) : (cur | NR);
            String out = su("cmd phone set-allowed-network-types-for-users -s " + slot + " " + Long.toBinaryString(next));
            Log.i(TAG, "slot " + slot + ": " + Long.toBinaryString(cur) + " -> " + Long.toBinaryString(next) + " : " + out);
            nrOn = readNr(slot);
            draw();
        }).start();
    }

    private int slot() {
        try {
            int sub = SubscriptionManager.getDefaultDataSubscriptionId();
            int s = SubscriptionManager.getSlotIndex(sub);
            return s >= 0 ? s : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private Boolean readNr(int slot) {
        Long m = readMask(slot);
        return m == null ? null : (m & NR) != 0;
    }

    private Long readMask(int slot) {
        String out = su("cmd phone get-allowed-network-types-for-users -s " + slot);
        if (out == null) return null;
        // Newer builds print names, e.g. "LTE|LTE_CA|NR"
        Matcher names = Pattern.compile("[A-Z][A-Z0-9_]*(\\|[A-Z][A-Z0-9_]*)*").matcher(out);
        String lastNames = null;
        while (names.find()) if (names.group().contains("|") || TYPES.containsKey(names.group())) lastNames = names.group();
        if (lastNames != null) {
            long m = 0;
            for (String n : lastNames.split("\\|")) {
                Integer type = TYPES.get(n.trim());
                if (type != null) m |= 1L << (type - 1);
            }
            if (m != 0) return m;
        }
        Matcher bin = Pattern.compile("\\b[01]{10,}\\b").matcher(out);
        String last = null;
        while (bin.find()) last = bin.group();
        try {
            if (last != null) return Long.parseLong(last, 2);
            Matcher dec = Pattern.compile("(\\d{4,})").matcher(out);
            String d = null;
            while (dec.find()) d = dec.group(1);
            if (d != null) return Long.parseLong(d);
        } catch (Throwable ignored) {}
        Log.w(TAG, "unparsed: " + out);
        return null;
    }

    private static String su(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (Throwable t) {
            Log.w(TAG, "su failed: " + t);
            return null;
        }
    }

    private void draw() {
        Tile t = getQsTile();
        if (t == null) return;
        Boolean on = nrOn;
        t.setLabel("Network mode");
        if (on == null) {
            t.setSubtitle("Needs root");
            t.setState(Tile.STATE_INACTIVE);
            t.setIcon(textIcon("5G?"));
        } else {
            t.setSubtitle(on ? "5G" : "4G only");
            t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            t.setIcon(textIcon(on ? "5G" : "4G"));
        }
        t.updateTile();
    }

    private Icon textIcon(String text) {
        int size = Math.round(48 * getResources().getDisplayMetrics().density);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        float ts = size * 0.62f;
        p.setTextSize(ts);
        float w = p.measureText(text);
        if (w > size * 0.96f) p.setTextSize(ts * size * 0.96f / w);
        Rect r = new Rect();
        p.getTextBounds(text, 0, text.length(), r);
        c.drawText(text, size / 2f, size / 2f - r.exactCenterY(), p);
        return Icon.createWithBitmap(b);
    }
}
