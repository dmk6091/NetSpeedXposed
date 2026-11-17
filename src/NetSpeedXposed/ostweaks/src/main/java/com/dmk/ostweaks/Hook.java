package com.dmk.ostweaks;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * VoNR / VoLTE icons instead of "HD".
 *
 * OplusStatusBarSignalPolicy.updateVo5GIcon picks the icon set for HD voice: the VoNR/VoLTE set
 * (stat_signal_vonr..., e.g. "VoNR 1 / VoLTE 2") when CustomizeFeatureOption.sIsSupportShowVoNR is set
 * for either SIM, otherwise the HD set. That flag comes from the per-carrier app feature
 * "com.android.systemui.show_vonr", which is off for Jio / Vi. It is forced on for both SIM slots.
 */
public class Hook implements IXposedHookLoadPackage {
    static final String TAG = "OSTweaks: ";
    static final String CFO = "com.oplusos.systemui.common.feature.CustomizeFeatureOption";

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        try { DockSuggest.handle(lp); } catch (Throwable t) { XposedBridge.log(TAG + "suggest: " + t); }
        try { DockCount.handle(lp); } catch (Throwable t) { XposedBridge.log(TAG + "dock: " + t); }
        try { KeyboardGap.handle(lp); } catch (Throwable t) { XposedBridge.log(TAG + "kbgap: " + t); }
        try { ImmersiveNav.handleLoadPackage(lp); } catch (Throwable t) { XposedBridge.log(TAG + "immersive: " + t); }
        if ("com.android.phone".equals(lp.packageName) && "com.android.phone".equals(lp.processName)) {
            if (Cfg.on("ostweaks_phone")) vo5gSwitch(lp.classLoader);
            return;
        }
        if ("com.android.settings".equals(lp.packageName) && "com.android.settings".equals(lp.processName)) {
            if (Cfg.on("ostweaks_settings")) settings(lp.classLoader);
            return;
        }
        if ("com.oplus.eyeprotect".equals(lp.packageName)) {
            // Eye Care: visual comfort switch. (eyeprotect_ai_support is left off: on the OnePlus page
            // layout Eye Care then looks up the realme-only AI switch and crashes.)
            if (Cfg.on("ostweaks_eyecare"))
                featuresOn(lp.classLoader, "Eye Care", "oplus.software.display.visual_comfort_support");
            return;
        }
        if (!"com.android.systemui".equals(lp.packageName)) return;
        if (lp.processName != null && !"com.android.systemui".equals(lp.processName)) return;
        Class<?> cfo = XposedHelpers.findClassIfExists(CFO, lp.classLoader);
        if (cfo == null) { XposedBridge.log(TAG + "not an OxygenOS / ColorOS System UI"); return; }
        if (Cfg.on("ostweaks_vonr")) {
        int n = XposedBridge.hookAllMethods(cfo, "loadAppFeatureForSlotId", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { forceVoNr(cfo); }
        }).size();
        forceVoNr(cfo);
        XposedBridge.log(TAG + "VoNR/VoLTE icons on (" + n + " hook)");
        stockVolteIcons();
        voNrWhenOnNr(lp.classLoader);
        }
        if (Cfg.on("ostweaks_slider")) SliderPercent.install(lp.classLoader);
        operatorNameSystemUi(cfo);
        StatusBarPadding.install(lp.classLoader);
    }

    /**
     * Carrier name in the status bar. System UI only honours the Settings switch
     * (Secure carrier_info_settings_show: SIM slot, or -1 = off) when the carrier
     * feature CustomizeFeatureOption.sIsShowOperatorName is on.
     */
    static void opName(Class<?> cfo) {
        try { XposedHelpers.findField(cfo, "sIsShowOperatorName").setBoolean(null, true); }
        catch (Throwable t) { XposedBridge.log(TAG + "operator name flag: " + t); }
    }

    static void operatorNameSystemUi(final Class<?> cfo) {
        try {
            opName(cfo);
            int n = XposedBridge.hookAllMethods(cfo, "loadAppFeature", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    opName(cfo);
                }
            }).size();
            XposedBridge.log(TAG + "carrier name in status bar allowed (" + n + ")");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "operator name: " + t);
        }
    }

    /**
     * System UI shows the VoNR icon only if, besides IMS being registered over NR (registerTech 3),
     * its own "VoNR switch on" and "network type is NR" readings are true. On this port one of those
     * readings stays false even while Jio calls really run over NR. When IMS is registered over NR with
     * voice, both readings are passed as true: the icon then follows the real registration.
     * OplusMobileIconInteractorImpl.access$createImsModel(impl, phoneId, capability, isVoNrEnabled,
     * simState, isNrNetwork, z3, continuation)
     */
    static boolean loggedVoNr;

    static void voNrWhenOnNr(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists(
                "com.oplus.systemui.statusbar.pipeline.mobile.domain.interactor.OplusMobileIconInteractorImpl", cl);
        if (c == null) { XposedBridge.log(TAG + "no OplusMobileIconInteractorImpl"); return; }
        int n = XposedBridge.hookAllMethods(c, "access$createImsModel", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length < 7 || !(p.args[3] instanceof Boolean) || !(p.args[5] instanceof Boolean)) return;
                try {
                    Object cap = p.args[2];
                    int tech = (Integer) XposedHelpers.callMethod(cap, "getRegisterTech");
                    boolean reg = (Boolean) XposedHelpers.callMethod(cap, "getImsRegistered");
                    boolean voice = (Boolean) XposedHelpers.callMethod(cap, "getVoiceCapable");
                    if (tech != 3 || !reg || !voice) return;
                    if (!loggedVoNr) {
                        loggedVoNr = true;
                        XposedBridge.log(TAG + "slot " + p.args[1] + " IMS on NR: VoNR switch read " + p.args[3]
                                + ", NR network read " + p.args[5] + " -> both set true");
                    }
                    p.args[3] = true;
                    p.args[5] = true;
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "vonr: " + t);
                }
            }
        }).size();
        XposedBridge.log(TAG + "VoNR detection hook (" + n + ")");
    }

    /**
     * The ROM's locked overlay com.redmagic.sysui (RedMagicVoLTE_CN.apk) replaces System UI's
     * stat_signal_volte_exp* icons ("Vo / LTE" text) with Chinese-style "HD" ones. Those four icons are
     * served from this module instead: the stock Vo/LTE drawables copied from the ROM's own System UI.
     */
    static final String[][] ICONS = {
            {"stat_signal_volte_exp", "ost_volte_exp"},
            {"stat_signal_volte_exp_sim1", "ost_volte_exp_sim1"},
            {"stat_signal_volte_exp_sim2", "ost_volte_exp_sim2"},
            {"stat_signal_volte_exp_sim_both", "ost_volte_exp_sim_both"},
    };
    static final Map<Integer, String> wanted = new HashMap<>();
    static Resources moduleRes;
    static boolean idsDone, logged;

    static void stockVolteIcons() {
        try {
            XposedBridge.hookAllMethods(Resources.class, "getDrawableForDensity", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length != 3 || !(p.args[0] instanceof Integer)) return;
                    Resources r = (Resources) p.thisObject;
                    if (!ids(r)) return;
                    String mine = wanted.get((Integer) p.args[0]);
                    if (mine == null) return;
                    try {
                        Resources m = module();
                        if (m == null) return;
                        int id = m.getIdentifier(mine, "drawable", "com.dmk.ostweaks");
                        if (id == 0) return;
                        Drawable d = m.getDrawable(id, null);
                        p.setResult(d);
                        if (!logged) { logged = true; XposedBridge.log(TAG + "stock Vo/LTE icon used for " + mine); }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "icon: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "icon hook: " + t);
        }
    }

    static synchronized boolean ids(Resources r) {
        if (idsDone) return true;
        try {
            if (r.getIdentifier(ICONS[0][0], "drawable", "com.android.systemui") == 0) return false;
            for (String[] e : ICONS) {
                int id = r.getIdentifier(e[0], "drawable", "com.android.systemui");
                if (id != 0) wanted.put(id, e[1]);
            }
            idsDone = true;
        } catch (Throwable ignored) { }
        return idsDone;
    }

    static synchronized Resources module() {
        if (moduleRes != null) return moduleRes;
        try {
            Object at = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (!(at instanceof Context)) return null;
            moduleRes = ((Context) at).createPackageContext("com.dmk.ostweaks", Context.CONTEXT_IGNORE_SECURITY).getResources();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "module resources: " + t);
        }
        return moduleRes;
    }

    /**
     * Settings: SIM status extras that OxygenOS hides behind app features
     * (com.android.settings.show_ims_registration_status / show_imsi / show_carrier_config_version).
     */
    static void settings(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists("com.oplus.settings.utils.CustomizeFeatureUtils", cl);
        if (c == null) { XposedBridge.log(TAG + "no CustomizeFeatureUtils in Settings"); return; }
        XC_MethodHook yes = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { p.setResult(true); }
        };
        int n = 0;
        for (String m : new String[]{"isShowImsRegistrationStatus", "isSupportImsi", "isShowCarrierConfigVersion"}) {
            n += XposedBridge.hookAllMethods(c, m, yes).size();
        }
        XposedBridge.log(TAG + "Settings: IMS status / IMSI / carrier config version shown (" + n + ")");
        // Carrier name in status bar (Notifications & status bar), normally carrier-only
        int o = XposedBridge.hookAllMethods(c, "isOperatorSupport", yes).size();
        XposedBridge.log(TAG + "Settings: carrier name option shown (" + o + ")");
        voNrSwitch(cl);
        Class<?> sf = XposedHelpers.findClassIfExists("com.oplus.settings.utils.SysFeatureUtils", cl);
        if (sf != null) {
            // DC dimming switch (Display & brightness): writes Settings.Secure display_dc_settings_switch
            int d = XposedBridge.hookAllMethods(sf, "isSupportDCBacklight", yes).size();
            XposedBridge.log(TAG + "Settings: DC dimming switch shown (" + d + ")");
            // HDR video highlight mode (HDR brightness boost), Display & brightness
            int h = XposedBridge.hookAllMethods(sf, "isSupportHdrVideoHighLightMode", yes).size();
            XposedBridge.log(TAG + "Settings: HDR video highlight switch shown (" + h + ")");
        }
        // Keep Settings in step with Eye Care: visual comfort is switched off with auto-brightness.
        featuresOn(cl, "Settings", "oplus.software.display.visual_comfort_support");
    }

    /**
     * The VoNR switch on the SIM page (NrAdvancedCallingPreferenceController) is shown only when the
     * carrier profile has vonr_enabled_bool and vonr_setting_visibility_bool; Jio's profile hides it.
     * It is shown whenever the SIM can use 5G (the controller's own NR check still applies).
     */
    static void voNrSwitch(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists("com.android.settings.network.telephony.VoNrRepository", cl);
        if (c == null) { XposedBridge.log(TAG + "no VoNrRepository in Settings"); return; }
        int n = XposedBridge.hookAllMethods(c, "isVoNrAvailable", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length != 1) return;
                Object nrOk = "?";
                try {
                    Object nr = XposedHelpers.getObjectField(p.thisObject, "nrRepository");
                    nrOk = XposedHelpers.callMethod(nr, "isNrAvailable", p.args[0]);
                } catch (Throwable t) {
                    nrOk = t.toString();
                }
                XposedBridge.log(TAG + "VoNR switch check sub " + p.args[0] + ": carrier says " + p.getResult()
                        + ", 5G available " + nrOk + " -> shown");
                p.setResult(true);
            }
        }).size();
        XposedBridge.log(TAG + "Settings: VoNR switch (" + n + ")");
    }

    /**
     * Phone app, SIM info page (com.android.simsettings): the "Vo5G calls" switch is shown only when
     * com.android.simsettings.utils.h.c(subId, context) is true, which needs the carrier config
     * vonr_enabled_bool. Jio's config in this port has it off, so the switch is hidden although Jio
     * uses VoNR. The check is answered true; the switch itself reads and sets the real VoNR state.
     */
    static void vo5gSwitch(ClassLoader cl) {
        Class<?> h = XposedHelpers.findClassIfExists("com.android.simsettings.utils.h", cl);
        if (h == null) { XposedBridge.log(TAG + "Phone: SIM settings helper not found"); return; }
        int n = XposedBridge.hookAllMethods(h, "c", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                java.lang.reflect.Method m = (java.lang.reflect.Method) p.method;
                Class<?>[] t = m.getParameterTypes();
                if (m.getReturnType() == boolean.class && t.length == 2 && t[0] == int.class
                        && android.content.Context.class.isAssignableFrom(t[1])) p.setResult(true);
            }
        }).size();
        XposedBridge.log(TAG + "Phone: Vo5G calls switch shown (" + n + ")");
    }

    /**
     * Answers OplusFeatureConfigManager.hasFeature(name) with true for the given names, in this
     * process only (Eye Care / Settings), so the matching options are shown there.
     */
    static void featuresOn(ClassLoader cl, String where, String... names) {
        final java.util.Set<String> set = new java.util.HashSet<>(java.util.Arrays.asList(names));
        Class<?> m = XposedHelpers.findClassIfExists("com.oplus.content.OplusFeatureConfigManager", cl);
        if (m == null) { XposedBridge.log(TAG + where + ": no OplusFeatureConfigManager"); return; }
        int n = XposedBridge.hookAllMethods(m, "hasFeature", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length > 0 && p.args[0] instanceof String && set.contains(p.args[0])) p.setResult(true);
            }
        }).size();
        XposedBridge.log(TAG + where + ": features on " + set + " (" + n + ")");
    }

    static void forceVoNr(Class<?> cfo) {
        try {
            boolean[] a = (boolean[]) XposedHelpers.findField(cfo, "sIsSupportShowVoNR").get(null);
            for (int i = 0; i < a.length; i++) a[i] = true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "show_vonr: " + t);
        }
    }
}
