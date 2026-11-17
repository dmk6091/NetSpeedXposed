package com.netspeed.xposed;

import java.util.Locale;

/** Last success / failure of each web feed (by host), shown on the Info page's diagnostics. */
final class Diag {
    private Diag() {}

    private static final java.util.Map<String, long[]> T = new java.util.concurrent.ConcurrentHashMap<>();       // host -> {last ok, last fail}
    private static final java.util.Map<String, String> E = new java.util.concurrent.ConcurrentHashMap<>();       // host -> last error text

    static String host(String url) {
        try { return new java.net.URL(url).getHost(); } catch (Throwable t) { return url; }
    }

    private static long[] slot(String h) {
        long[] a = T.get(h);
        if (a == null) { a = new long[2]; T.put(h, a); }
        return a;
    }

    static void ok(String url) { slot(host(url))[0] = System.currentTimeMillis(); }

    static void fail(String url, Throwable t) {
        String h = host(url);
        slot(h)[1] = System.currentTimeMillis();
        E.put(h, String.valueOf(t).replace("java.io.IOException: ", "").replace("java.net.", ""));
    }

    private static String ago(long t) {
        long m = Math.max(0, (System.currentTimeMillis() - t) / 60000L);
        return m < 1 ? "just now" : m < 90 ? m + " min ago" : (m / 60) + " h ago";
    }

    /** "ok 3 min ago" / "failed 2 min ago: HTTP 429 (last ok 40 min ago)" / "not tried yet". */
    static String line(String host) {
        long[] a = T.get(host);
        if (a == null || (a[0] == 0 && a[1] == 0)) return "not tried yet";
        if (a[1] > a[0]) return "failed " + ago(a[1]) + ": " + E.get(host) + (a[0] > 0 ? " (last ok " + ago(a[0]) + ")" : " (never ok since the bar started)");
        return "ok " + ago(a[0]);
    }

    static String fmt(String f, Object... a) { return String.format(Locale.US, f, a); }
}
