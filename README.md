# NetSpeedXposed

An LSPosed module that turns the Android status bar into a two-row information strip.
Everything can be switched on/off and resized in the included settings app.

**Row 1**
- Clock, CPU and battery temperature (`C 41°` / `B 35°`)
- Network speed (`↓1.5M/s` / `↑79K/s`)
- Battery current and power (`-0.34A` / `-1.3W`, minus while on battery, plus while charging)
- Cells heard per SIM, with a dish symbol and a small SIM number
- Per SIM: band, signal strength and distance to the tower
  (`n78₁ -92 🗼1.2km` over `B3₂ -105 🗼~650m`), next to VoLTE / VoNR, network type and signal

**Row 2**
- Notification icons (up to 12) on the left
- System icons (Wi-Fi, Bluetooth, mute, DND …) and the weather on the right

Nothing is ever cut off: items that don't fit before the camera cutout hide themselves (or move
to the other side of the camera) and come back when there's room.

## Requirements
- Android 14–16 (developed and tested on Lunaris AOSP, Android 16, Poco F7)
- Root with an LSPosed-compatible framework (LSPosed, Vector, …)
- Weather needs [OmniJaws](https://github.com/omnirom/android_packages_apps_OmniJaws) (built into many custom ROMs)

Other ROMs: every part looks for the views it needs and switches itself off if something is
missing, so the worst case is a feature not appearing. Please open an issue with your LSPosed
log (lines starting with `NetSpeedXposed:`) if something doesn't show.

## Install
1. Install the APK from [Releases](../../releases) (or the LSPosed module repository).
2. Enable the module in LSPosed and tick **System UI** in its scope.
3. Open **NetSpeed settings** and tap **Restart System UI** (or reboot).

Optional: also tick **System Framework** if you raise "Extra status bar height" in settings.

## Settings
Open from the app icon or from the module's settings in LSPosed. Most changes apply live.

| Section | Options |
|---|---|
| Layout | Gap between rows, row 2 height, extra status bar height |
| Text | Sizes for temperatures, weather, net speed, amps/watts, band; bold small text |
| Show | CPU temp, battery temp, amps/watts, band, dBm, tower distance, cells heard, weather, hide net speed when idle |
| Towers (off by default) | Online tower lookup, OpenCelliD token, learning tower positions |
| Layout mode | Two-row on/off, net speed after the weather or on the right |
| Updates | Checks this repo's latest release and installs it with root in one tap |

## Tower distance
In order of preference:
1. **Timing advance** from the modem (4G, while the SIM is active): exact to about ±78 m.
2. **Tower databases** (opt-in): the tower's position from [BeaconDB](https://beacondb.net), then
   [OpenCelliD](https://opencellid.org) if you enter a free token. Shown with `~`.
3. **Learned positions** (opt-in): the phone records its position and the cell's signal while you
   move and estimates where the tower is. Shown with `~`.

`?` means none of these is available (common for 5G standalone and idle SIMs).

### Privacy
Both tower options are **off by default**.
- Tower lookups send only tower IDs (operator code, area code, cell ID) to BeaconDB/OpenCelliD.
  Your location is never sent; the distance is worked out on the phone. Results are cached.
- Learned positions use your location and stay on the phone (System UI's cache folder).
- The update check contacts GitHub only when you open the settings app.

## Building
GitHub Actions builds every push and publishes it as the `latest` release.
Release builds are signed with the key in the `SIGNING_KEYSTORE_B64` / `SIGNING_PASSWORD`
repository secrets (alias `netspeed`).

## Credits
Weather from OmniJaws. Tower positions from BeaconDB and OpenCelliD (CC BY-SA 4.0).
