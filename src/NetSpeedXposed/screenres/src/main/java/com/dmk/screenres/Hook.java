package com.dmk.screenres;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * HyperOS shows Settings > Display > Screen resolution only when the device feature file lists the
 * supported screen widths (screen_resolution_supported). The Poco F7's file has no such list, so the
 * option is hidden. Inside Settings, this module answers that list with FHD+ (1080) and the
 * panel's own width (1280, 1.5K). Settings then shows the option and switches the resolution the same
 * way it does on phones that have it officially.
 *
 * It also shows DC dimming (Settings > Display; the panel supports it, the feature file just hides the
 * switch) and Touch sensitivity / glove mode (only works if the touch driver supports it).
 */
public class Hook implements IXposedHookLoadPackage {
    static final String TAG = "ScreenRes: ";
    static final String KEY = "screen_resolution_supported";
    /** screen widths offered, smallest first (FHD+, then the native 1.5K width) */
    static final int[] WIDTHS = {1080, 1280};

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        if (!"com.android.settings".equals(lp.packageName)) return;
        Class<?> fp = XposedHelpers.findClassIfExists("miui.util.FeatureParser", lp.classLoader);
        if (fp == null) { XposedBridge.log(TAG + "no FeatureParser on this ROM"); return; }
        XposedBridge.hookAllMethods(fp, "getIntArray", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length < 1 || !KEY.equals(p.args[0])) return;
                Object r = p.getResult();
                if (r instanceof int[] && ((int[]) r).length > 0) return;   // the ROM has its own list: keep it
                p.setResult(WIDTHS.clone());
            }
        });
        // DC dimming and glove mode (touch sensitivity): the feature file hides both on the Poco F7
        XposedBridge.hookAllMethods(fp, "getBoolean", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length < 1 || !(p.args[0] instanceof String)) return;
                switch ((String) p.args[0]) {
                    case "hide_flicker_backlight": p.setResult(false); break;   // shows DC dimming
                    case "support_touch_sensitive": p.setResult(true); break;  // shows glove mode
                }
            }
        });
        XposedBridge.log(TAG + "loaded in Settings");
    }
}
