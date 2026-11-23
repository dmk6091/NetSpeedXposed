package com.netspeed.xposed;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Takes a screenshot (root: screencap), keeps only the status bar strip, adds a small caption with the time, and saves it to Pictures/FieldAtlas. */
final class StatusShot {
    private StatusShot() {}

    static final class Result { Uri uri; String msg; }

    private static byte[] run(String cmd) throws Exception {
        Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(false).start();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        }
        p.waitFor();
        return bo.toByteArray();
    }

    /** Height of the status bar window in pixels, read from the window manager; 0 if not found. */
    private static int barHeight(int width) {
        try {
            String s = new String(run("dumpsys window windows"), "UTF-8");
            int at = 0;
            while ((at = s.indexOf("StatusBar", at)) >= 0) {
                String chunk = s.substring(at, Math.min(s.length(), at + 3000));
                Matcher m = Pattern.compile("\\[0,0\\]\\[(\\d+),(\\d+)\\]").matcher(chunk);
                while (m.find()) {
                    int w = Integer.parseInt(m.group(1)), h = Integer.parseInt(m.group(2));
                    if (w == width && h >= 50 && h <= 600) return h;
                }
                at += 9;
            }
        } catch (Throwable ignored) { }
        return 0;
    }

    static Result take(Context c) {
        Result r = new Result();
        try {
            byte[] png = run("screencap -p");
            Bitmap full = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (full == null) { r.msg = "No screenshot came back. Is root (su) allowed for Field Atlas?"; return r; }
            int w = full.getWidth();
            int h = barHeight(w);
            String how = "bar height from the window manager";
            if (h == 0) { h = Math.round(w * 0.16f); how = "bar height guessed"; }
            h = Math.min(h, full.getHeight());
            int cap = Math.round(w * 0.045f);
            Bitmap out = Bitmap.createBitmap(w, h + cap, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            cv.drawColor(0xFF000000);
            cv.drawBitmap(Bitmap.createBitmap(full, 0, 0, w, h), 0, 0, null);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFFB8C2D6);
            p.setTextSize(cap * 0.58f);
            String stamp = "Field Atlas  ·  " + new SimpleDateFormat("EEE d MMM yyyy  HH:mm:ss", Locale.getDefault()).format(new Date());
            cv.drawText(stamp, cap * 0.5f, h + cap * 0.72f, p);
            full.recycle();
            ContentValues cvv = new ContentValues();
            cvv.put(MediaStore.Images.Media.DISPLAY_NAME, "statusbar_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png");
            cvv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            cvv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FieldAtlas");
            Uri u = c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cvv);
            if (u == null) { r.msg = "Could not create the picture file."; return r; }
            try (OutputStream os = c.getContentResolver().openOutputStream(u)) { out.compress(Bitmap.CompressFormat.PNG, 100, os); }
            out.recycle();
            r.uri = u;
            r.msg = "Saved to Pictures/FieldAtlas (" + w + " x " + h + " px, " + how + ").";
        } catch (Throwable t) {
            r.msg = "Failed: " + t.getClass().getSimpleName() + (t.getMessage() != null ? " " + t.getMessage() : "");
        }
        return r;
    }
}
