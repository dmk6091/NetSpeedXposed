package com.netspeed.xposed;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-tap updates from the repo's "latest" GitHub release: check, download, install with
 * root and restart System UI. Runs in the settings app only (never in System UI).
 */
final class AppUpdater {
    static final String API = "https://api.github.com/repos/dmk6091/NetSpeedXposed/releases/tags/latest";
    static final String APK = "https://github.com/dmk6091/NetSpeedXposed/releases/download/latest/FieldAtlas.apk";

    /** What the release says: build number and the change description. */
    static final class Info {
        final long build;
        final String notes;

        Info(long build, String notes) {
            this.build = build;
            this.notes = notes;
        }
    }

    private AppUpdater() {}

    /** Reads the latest release. Throws with a readable message on failure. */
    static Info check() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setConnectTimeout(6_000);
        c.setReadTimeout(8_000);
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        int code = c.getResponseCode();
        if (code == 404) throw new Exception("No build published yet");
        if (code != 200) throw new Exception("GitHub answered " + code);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        String json = sb.toString();
        // The release text is "build=N" on the first line, then the change description.
        Matcher m = Pattern.compile("\"body\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        if (!m.find()) throw new Exception("Release has no description");
        String body = m.group(1).replace("\\r", "").replace("\\n", "\n").replace("\\\"", "\"")
                .replace("\\\\", "\\");
        Matcher b = Pattern.compile("build=(\\d+)").matcher(body);
        if (!b.find()) throw new Exception("Release has no build number");
        long build = Long.parseLong(b.group(1));
        StringBuilder nb = new StringBuilder();
        for (String ln : body.substring(b.end()).split("\n")) {
            String t = ln.trim();
            // commit trailers are not part of the change list
            if (t.startsWith("Co-Authored-By:") || t.startsWith("Claude-Session:") || t.startsWith("🤖")) continue;
            nb.append(ln).append('\n');
        }
        String notes = nb.toString().trim();
        return new Info(build, notes);
    }

    /** Downloads the APK into dir and returns the file. */
    static File download(File dir) throws Exception {
        URL url = new URL(APK);
        HttpURLConnection c = null;
        // Follow GitHub's redirect to its download host ourselves (keeps it simple and explicit).
        for (int i = 0; i < 5; i++) {
            c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                url = new URL(url, c.getHeaderField("Location"));
                c.disconnect();
                continue;
            }
            if (code != 200) throw new Exception("Download failed (" + code + ")");
            break;
        }
        File out = new File(dir, "update.apk");
        try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
        }
        if (out.length() < 10_000) throw new Exception("Downloaded file is too small");
        return out;
    }

    /**
     * Installs the APK with root, then restarts System UI so the module reloads. Runs detached,
     * because installing this app also closes it. Returns false if root wasn't granted.
     */
    static boolean installAndRestart(File apk) throws Exception {
        String tmp = "/data/local/tmp/netspeed_update.apk";
        String cmd = "cp '" + apk.getAbsolutePath() + "' " + tmp + " && chmod 644 " + tmp
                + " && (setsid sh -c 'pm install -r " + tmp + " > /data/local/tmp/netspeed_update.log 2>&1"
                + " && killall com.android.systemui; rm -f " + tmp + "' </dev/null >/dev/null 2>&1 &)";
        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
        return p.waitFor() == 0;
    }
}
