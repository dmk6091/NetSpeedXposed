# My phone modules (dmk)

Everything is built automatically and published on one page, the **latest release**:
**https://github.com/dmk6091/NetSpeedXposed/releases/tag/latest**
Download links below are direct: `https://github.com/dmk6091/NetSpeedXposed/releases/download/latest/<file>`

Device: Poco F7 (onyx), root with KernelSU, Zygisk + LSPosed. Magisk/KernelSU zips are installed from the root manager
(Modules > Install, then reboot). `.apk` files are LSPosed modules (install, enable in LSPosed, tick the scope, restart System UI).

## For AOSP-based ROMs (Lunaris, crDroid, etc.)

| File | What it does | Kind |
|---|---|---|
| `FieldAtlas.apk` | Status bar with live data, Info page, QS tiles (touch rate, screen timeout, 5G/4G/3G). Scope: System UI, System Framework, launcher | LSPosed |
| `NetBoost.zip` | BBR + fq, TCP Fast Open, bigger buffers, ECN, MTU probing; restores your values on uninstall | zip |
| `VolumeSteps.zip` | Finer or coarser volume steps for every stream (set in Field Atlas or the module page, then reboot) | zip |
| `NfcOff.zip` | Stops the NFC driver once after boot (it can loop and heat the CPU on some ROMs even with NFC off) | zip |
| `Touch240.zip` | 240 Hz touch reporting in all apps, re-applied when the system resets it. **Poco F7 only** | zip |
| `WiFiActivity.apk` | Wi-Fi in/out arrows inside the Wi-Fi icon | LSPosed |
| `RideCool.zip` | Stepwise CPU/GPU/charging limits by battery temperature (needs Xiaomi thermal files) | zip |
| `CleanBoot.zip` | Hides traces a custom ROM leaves on a Xiaomi phone (OEM-unlock prop, LineageOS SELinux files) | zip |

## For other ROMs only

| File | ROM | What it does |
|---|---|---|
| `ImmersiveNav.zip`, `ImmersiveNavX.apk` | any, only if Field Atlas is not used | Gesture navigation: apps draw to the bottom edge, pill floats over them. **Not needed with Field Atlas**, which already does this on its own |
| `NetSwitch.zip` | HyperOS | 5G weak to 4G, SIM 1 weak to SIM 2 through the hidden auto data switch |
| `AutoDataSwitch.apk` | HyperOS | Brings back "Switch mobile data automatically" and other hidden SIM options |
| `ScreenRes.apk` | HyperOS | Unhides screen resolution, DC dimming and glove mode |
| `SliderPercent.apk` | OxygenOS / ColorOS | Percent on the brightness and volume sliders |
| `OxygenOSTweaks.apk` | OxygenOS / ColorOS | VoNR / VoLTE icons, slider percent, Eye Care, IMS status |
| `BtBatteryFix.apk` | for StatusbarTemp | Bluetooth headset battery after reboot, keeps stock data arrows |
| `CarrierNames.apk`, `NetSpeedXposed.apk` | any | Old names kept so older installs can still update (`NetSpeedXposed.apk` is the same file as `FieldAtlas.apk`) |

## After switching ROM
1. Root with KernelSU; install Zygisk Next and LSPosed; reboot.
2. Install the zips you want from the first table (NetBoost, VolumeSteps, Touch240; NfcOff only if NFC loops); reboot.
3. Install `FieldAtlas.apk`, enable it in LSPosed with scope **System UI** (+ System Framework and the launcher), then `killall com.android.systemui`.
4. Open Field Atlas, restore your settings, allow "Modify system settings" and location.
5. Edit the QS panel and add **Touch rate** and **Screen timeout**; **Preferred network** shows 5G/4G/3G on ROMs that have that tile.

## Source
`netboost/ nfcoff/ volumesteps/ touch240/ immersivenav/ ridecool/ cleanboot/ netswitch/` are the module sources;
`src/` and `NetSpeedXposed.zip` are the LSPosed modules (CI builds from the zip).
