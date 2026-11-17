# NetSpeed Xposed

An LSPosed module for System UI that turns the status bar into two rows:

| | Left | Right |
|---|---|---|
| **Row 1** | clock, CPU / battery temperature, weather (from OmniJaws) | net speed, VoLTE / VoNR, battery |
| **Row 2** | notification icons (up to 12, full width) | Wi-Fi, signal, Bluetooth, location and other system icons |

- Net speed: two lines (↓ / ↑), always visible, fixed width.
- CPU temperature: hottest CPU core; battery temperature from the battery service.
- Weather: icon + temperature from the ROM's OmniJaws. Set location, units and
  update interval in OmniJaws itself (Settings → weather). Hidden if OmniJaws is off.
- Apps automatically start below both rows.

## Build

### Option A: Android Studio (your PC)
1. If your ROM has its own network speed indicator, turn it off in Settings → Status bar
   first, so you don't get two.
2. Unzip this folder and open it with **File → Open** in Android Studio.
3. Wait for Gradle sync to finish (first time downloads a few hundred MB).
4. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
5. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

### Option B: GitHub (builds online)
1. Create a new repository on GitHub and push this folder to it:
   ```
   git init
   git add .
   git commit -m "NetSpeed Xposed module"
   git branch -M main
   git remote add origin https://github.com/<your-username>/NetSpeedXposed.git
   git push -u origin main
   ```
2. Open the repo's **Actions** tab and wait for "Build APK" to turn green (~3 min).
3. Open that run, download **NetSpeedXposed-apk** under Artifacts, unzip it.

## Install on the phone
1. LSPosed must be installed (Zygisk/ReZygisk module + LSPosed module in your root manager).
2. Install the APK.
3. Open LSPosed → Modules → **NetSpeed Xposed** → enable it, and make sure
   **System UI** is ticked.
4. Restart System UI, or just reboot.

## If something goes wrong
- **Nothing shows:** it shows all the time (0B/s when idle). If it's missing, check
  LSPosed → Logs for lines starting with `NetSpeedXposed:`.
- **"no status bar container found" in the log:** your ROM names its views differently.
  Send the log and the container ids can be adjusted in `Hook.java`.
- **Still only 4 notification icons:** check LSPosed → Logs for lines starting with
  `NetSpeedXposed: [icons]` and send them; they show where your ROM keeps the limit.
- **System UI keeps crashing:** disable the module in LSPosed, or boot to safe mode /
  disable LSPosed from your root manager, then reboot.

## Scope
Tick **System Framework** and **System UI** in Vector/LSPosed. System Framework is
needed for the taller status bar (so apps start below row 2). Reboot after changing scope.

## Settings app
Open **NetSpeed settings** (app icon, or Settings on the module in Vector/LSPosed) to adjust:
row gap, row 2 height, extra bar height, text sizes, bold, which extras show, hiding the
speed when idle, and the layout mode. Most changes apply live; items marked ⟳ need
"Restart System UI" (root) or a reboot.

## Network band
Row 1 shows each SIM's current band just before the VoLTE / VoNR icons, one line per SIM
(e.g. n78 with a small 1 over B3 with a small 2). For 4G+ it is the main band. A SIM with
no known band is left out. Each line can also show the signal strength (RSRP, dBm), e.g.
n78 1 -92; it hides itself when there's no room after the camera. Turn either off or resize
in the settings app.

## Tweaking (code defaults)
In `DualRow.java`:
- `ENABLED` – `false` turns the two-row layout off (back to one row).
- `ROW2_DP` – height of row 2 (default 20).
- `ROW_GAP_DP` – gap between row 1's icons and row 2 (default 1).
- `EXTRA_DP` – how much taller than stock the bar gets (default 0; raise if row 2 is cut off).
- `ROW1_SLOT_KEYWORDS` – which status icons show on row 1 next to the battery (default VoLTE / VoNR / VoWiFi / IMS / mobile signal).
- `SHOW_CPU_TEMP`, `SHOW_BATTERY_TEMP`, `SHOW_WEATHER` – turn each extra on/off.
- `NETSPEED_AFTER_WEATHER` – `true` puts the net speed on row 1 after the weather, `false` keeps it on the right.

If something looks wrong, send the LSPosed log lines starting with
`NetSpeedXposed: [dual]` (they list the views, icon slots and data sources found).

In `NotifIconLimit.java`:
- `MAX_ICONS` – how many notification icons to show (default 8). Icons still
  can't go past the free space before the camera cutout.

In `Hook.java`:
- `INTERVAL_MS` – refresh rate (default 1000 ms)
- `HIDE_WHEN_IDLE` – `false` (default) keeps it always visible; `true` hides it after
  `IDLE_HIDE_MS` (10 s) below `IDLE_BPS` (1 KB/s)
- `tv.setTextSize(..., 8)` – text size in sp
