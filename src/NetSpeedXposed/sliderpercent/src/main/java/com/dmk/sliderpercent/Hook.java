package com.dmk.sliderpercent;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/** Entry point: only System UI is hooked. */
public class Hook implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        if (!"com.android.systemui".equals(lp.packageName)) return;
        SliderPercent.install(lp.classLoader);
    }
}
