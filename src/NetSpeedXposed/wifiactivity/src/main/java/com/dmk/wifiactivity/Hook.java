package com.dmk.wifiactivity;

import android.content.res.Resources;
import android.view.View;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Android 16 still has the Wi-Fi activity arrows (in / out, inside the Wi-Fi icon), switched off
 * by System UI's config_showActivity. We switch it on. The same switch would also add the stock
 * arrows to the mobile signal icon, so those are kept hidden (only Wi-Fi gets them).
 */
public class Hook implements IXposedHookLoadPackage {
    private static final String TAG = "WiFiActivity: ";
    private static final String SYSUI = "com.android.systemui";

    private static int sShowActivityId = 0;      // 0 = not looked up yet, -1 = not found
    private static int[] sMobileIds = null;

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        if (!SYSUI.equals(lp.packageName)) return;

        // 1) config_showActivity -> true
        try {
            XposedHelpers.findAndHookMethod(Resources.class, "getBoolean", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    int id = showActivityId((Resources) p.thisObject);
                    if (id > 0 && (Integer) p.args[0] == id) p.setResult(true);
                }
            });
            XposedBridge.log(TAG + "activity arrows switched on");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "config hook failed: " + t);
        }

        // 2) keep the stock arrows off the mobile signal icon
        try {
            XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if ((Integer) p.args[0] != View.VISIBLE) return;
                    int id = ((View) p.thisObject).getId();
                    if (id == View.NO_ID) return;
                    int[] ids = mobileIds(((View) p.thisObject).getResources());
                    for (int m : ids) {
                        if (m != 0 && m == id) {
                            p.args[0] = View.GONE;
                            return;
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "mobile arrows hook failed: " + t);
        }
    }

    private static int showActivityId(Resources r) {
        if (sShowActivityId != 0) return sShowActivityId;
        int id = 0;
        try {
            id = r.getIdentifier("config_showActivity", "bool", SYSUI);
        } catch (Throwable ignored) { }
        if (id == 0) return 0; // maybe a framework Resources object; try again with the next call
        sShowActivityId = id;
        XposedBridge.log(TAG + "config_showActivity found (" + Integer.toHexString(id) + ")");
        return id;
    }

    private static int[] mobileIds(Resources r) {
        if (sMobileIds != null) return sMobileIds;
        // (not "inout_container": the Wi-Fi icon uses a container with that name too)
        int[] ids = new int[2];
        String[] names = {"mobile_in", "mobile_out"};
        boolean any = false;
        for (int i = 0; i < names.length; i++) {
            try {
                ids[i] = r.getIdentifier(names[i], "id", SYSUI);
                any |= ids[i] != 0;
            } catch (Throwable ignored) { }
        }
        if (any) sMobileIds = ids;
        return ids;
    }
}
