package com.dmk.autodataswitch;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Shows the SIM options HyperOS hides on Settings > SIM cards & mobile networks and on each SIM's own page:
 * Smart dual SIM, Intelligent dual SIM (dual data), Smart switch dual SIM, data on the other SIM during
 * VoLTE calls, the Dual 4G switch, the 5G SA switch (also SIM 2's), 5G hosted mode, the 5G network
 * option, "Always receive MMS" and SIM Toolkit.
 *
 * The checks that hide them are only overridden while those pages' own code runs, so the rest of the
 * Phone app (calls, data, the modem) sees the real values.
 * Left out on purpose: eSIM (no eSIM hardware), China Mobile / Korea / Mi Mobile / virtual SIM items.
 */
final class HiddenSimOptions {
    static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    static boolean onPage() { return DEPTH.get()[0] > 0; }

    static final String SIM_PAGE = "com.android.phone.settings.MultiSimInfoEditorActivity$MultiSimInfoEditorFragment";

    static void install(Class<?> frag, ClassLoader cl) {
        mark(frag);
        keepViceSlotData(frag);
        Class<?> simPage = XposedHelpers.findClassIfExists(SIM_PAGE, cl);
        if (simPage != null) mark(simPage); else XposedBridge.log(Hook.TAG + "no per-SIM page");
        forceAll(cl);
    }

    /**
     * HyperOS shows either "Smart dual SIM" or "Use mobile data during VoLTE calls on the secondary SIM",
     * never both. With Smart dual SIM unhidden, the VoLTE-calls one disappeared, so it is put back.
     */
    static void keepViceSlotData(Class<?> frag) {
        XposedBridge.hookAllMethods(frag, "initIntelligentDualSimView", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Object pref = XposedHelpers.getObjectField(p.thisObject, "mButtonViceSlotVolteData");
                    Object group = XposedHelpers.getObjectField(p.thisObject, "mPrefGeneral");
                    if (pref == null || group == null) return;
                    Object key = XposedHelpers.callMethod(pref, "getKey");
                    if (XposedHelpers.callMethod(group, "findPreference", key) != null) return;
                    XposedHelpers.callMethod(group, "addPreference", pref);
                    try { XposedHelpers.callMethod(p.thisObject, "updateViceSlotDataButton"); } catch (Throwable ignored) { }
                } catch (Throwable t) {
                    XposedBridge.log(Hook.TAG + "VoLTE-calls data option: " + t);
                }
            }
        });
    }

    static void mark(Class<?> frag) {
        // 1) mark when the page's code is running
        XC_MethodHook mark = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { DEPTH.get()[0]++; }
            @Override protected void afterHookedMethod(MethodHookParam p) { DEPTH.get()[0]--; }
        };
        Set<String> done = new HashSet<>();
        for (Method m : frag.getDeclaredMethods()) {
            if (m.isSynthetic() || !done.add(m.getName())) continue;
            XposedBridge.hookAllMethods(frag, m.getName(), mark);
        }
    }

    static void forceAll(ClassLoader cl) {
        // 2) while it is, the "hide this" checks say show
        force(cl, "com.android.phone.MiuiPhoneUtils", false,
                "shouldHideSmartDualSimButton", "shouldHideIntelligentDualSimButton",
                "shouldHideViceSlotVolteDataButton", "isRemoveDual4GSwitch", "isRemoveStkSettings");
        force(cl, "com.android.phone.MiuiPhoneUtils", true,
                "isSmartSwitchDualSimSupported", "isFiveGHostedModeSupported");
        force(cl, "com.android.phone.utils.FiveGUtils", true, "shouldShow5GSaSwitch", "shouldShowViceSaSwitch", "isDualSaSupported");
        force(cl, "com.android.phone.utils.FiveGUtils", false, "isFiveGNetWorkInVisible");
        force(cl, "com.android.internal.telephony.InternalTelephonyUtils", false, "isHideAlwaysReceiveMms", "isHideStkIcon");
    }

    static void force(ClassLoader cl, String cls, boolean value, String... names) {
        Class<?> c = XposedHelpers.findClassIfExists(cls, cl);
        if (c == null) { XposedBridge.log(Hook.TAG + "no " + cls); return; }
        for (String n : names) {
            XC_MethodHook h = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (onPage()) p.setResult(value);
                }
            };
            if (XposedBridge.hookAllMethods(c, n, h).isEmpty()) XposedBridge.log(Hook.TAG + "no " + cls + "." + n);
        }
    }
}
