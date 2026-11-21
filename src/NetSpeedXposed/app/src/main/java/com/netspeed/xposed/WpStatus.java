package com.netspeed.xposed;

import java.util.ArrayList;
import java.util.List;

/** What the wallpaper maker is doing right now and what it had to skip, so the app can show a countdown and the reasons. */
final class WpStatus {
    private WpStatus() { }

    static final String[] STEPS = {"Finding your place", "Elevation and land tiles", "Forest and water", "Wetlands (OpenStreetMap)", "Faults", "Names on the map", "Putting it on the screen"};

    static volatile boolean active;
    static volatile long startAt;
    static volatile int step;
    static volatile long expectMs = 45_000;
    private static final List<String> notes = new ArrayList<>();

    static synchronized void begin(long lastMs) {
        active = true; startAt = System.currentTimeMillis(); step = 0;
        expectMs = lastMs > 5_000 ? lastMs : 45_000;
        notes.clear();
    }

    static void step(int i) { if (active) step = i; }

    static synchronized void note(String s) { if (active && !notes.contains(s)) notes.add(s); }

    static synchronized String notesText() {
        StringBuilder b = new StringBuilder();
        for (String n : notes) { if (b.length() > 0) b.append("; "); b.append(n); }
        return b.toString();
    }

    /** Ends the run and returns how long it took in ms. */
    static long end() { long ms = System.currentTimeMillis() - startAt; active = false; return ms; }

    /** "Step 3 of 7: Forest and water · 24 s so far, about 20 s left" */
    static String progress() {
        long el = System.currentTimeMillis() - startAt;
        long left = expectMs - el;
        String eta = left > 2_000 ? "about " + (left + 999) / 1000 + " s left" : "taking longer than usual (busy servers)";
        return "Step " + (step + 1) + " of " + STEPS.length + ": " + STEPS[Math.min(step, STEPS.length - 1)] + " · " + el / 1000 + " s so far, " + eta;
    }
}
