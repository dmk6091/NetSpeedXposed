package com.netspeed.xposed;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings tile: touch report rate, 120 Hz or 240 Hz. Uses the touch240 module's helper with root;
 * a flag file tells the module not to put 240 back while you have chosen 120.
 */
public final class TouchRateTile extends TileService {
    private static final String FLAG = "/data/adb/touch240.off";
    private static final String X = "/data/adb/modules/touch240/bin/xt";

    @Override public void onStartListening() { refresh(); }

    @Override public void onClick() {
        final boolean off = isOff();
        new Thread(() -> {
            String cmd = off
                    ? "rm -f " + FLAG + "; " + X + " 0 1011 240; " + X + " 0 202 1"
                    : "touch " + FLAG + "; " + X + " 0 1011 120; " + X + " 0 202 0";
            try {
                new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start().waitFor();
            } catch (Throwable ignored) { }
            refresh();
        }).start();
        // show the new value at once
        Tile t = getQsTile();
        if (t != null) { apply(t, off); }
    }

    private static boolean isOff() {
        try {
            Process p = new ProcessBuilder("su", "-c", "[ -e " + FLAG + " ] && echo 1 || echo 0").start();
            byte[] b = new byte[4];
            int n = p.getInputStream().read(b);
            p.waitFor();
            return n > 0 && b[0] == '1';
        } catch (Throwable t) {
            return false;
        }
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t != null) apply(t, isOff());
    }

    private void apply(Tile t, boolean off) {
        t.setLabel("Touch rate");
        t.setSubtitle(off ? "120 Hz" : "240 Hz");
        t.setIcon(ValueIcon.icon(off ? "120" : "240"));
        t.setState(off ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        t.updateTile();
    }
}
