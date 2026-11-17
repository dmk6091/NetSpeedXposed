package com.dmk.autodataswitch;

import android.content.Context;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Adds Android's own "Switch mobile data automatically" (auto data switch, Android 13+) to the HyperOS
 * page Settings > SIM cards & mobile networks, which lives in the Phone app
 * (com.android.phone/.settings.MobileNetworkSettings). The switch sits right below "Mobile data".
 *
 * On: when the data SIM has poor service, Android uses the other SIM's data until it recovers.
 * The setting is turned on/off for every active SIM, the same way NetSwitch does it.
 */
public class Hook implements IXposedHookLoadPackage {
    static final String TAG = "AutoDataSwitch: ";
    static final String FRAG = "com.android.phone.settings.MobileNetworkSettings$MobileNetworkSettingsFragment";
    static final String KEY = "dmk_auto_data_switch";
    /** our switch, per SIM settings page (weak: pages that are closed are let go) */
    static final Map<Object, Object> PREFS = new WeakHashMap<>();
    /** TelephonyManager.MOBILE_DATA_POLICY_AUTO_DATA_SWITCH */
    static final int AUTO_DATA_SWITCH = 3;

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        if (!"com.android.phone".equals(lp.packageName)) return;
        Class<?> frag = XposedHelpers.findClassIfExists(FRAG, lp.classLoader);
        if (frag == null) { XposedBridge.log(TAG + "SIM settings page not found on this ROM"); return; }
        XC_MethodHook add = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try { add(p.thisObject, lp.classLoader); } catch (Throwable t) { XposedBridge.log(TAG + "add: " + t); }
            }
        };
        XC_MethodHook refresh = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try { refresh(p.thisObject); } catch (Throwable t) { XposedBridge.log(TAG + "refresh: " + t); }
            }
        };
        XposedBridge.hookAllMethods(frag, "onCreatePreferences", add);
        XposedBridge.hookAllMethods(frag, "onResume", refresh);
        XposedBridge.hookAllMethods(frag, "onSubscriptionsChanged", refresh);
        XposedBridge.hookAllMethods(frag, "updatePreferenceUI", refresh);
        try { HiddenSimOptions.install(frag, lp.classLoader); } catch (Throwable t) { XposedBridge.log(TAG + "hidden options: " + t); }
        XposedBridge.log(TAG + "loaded in the Phone app");
    }

    static void add(Object frag, ClassLoader cl) throws Throwable {
        if (PREFS.get(frag) != null) return;
        Context ctx = (Context) XposedHelpers.callMethod(frag, "getContext");
        if (ctx == null) return;

        // put it next to "Mobile data", in the same group and with the same look
        Object anchor = XposedHelpers.getObjectField(frag, "mButtonDataEnabled");
        Object group = anchor != null ? XposedHelpers.callMethod(anchor, "getParent") : null;
        if (group == null) group = XposedHelpers.getObjectField(frag, "mPrefGeneral");
        if (group == null) group = XposedHelpers.callMethod(frag, "getPreferenceScreen");
        if (group == null) { XposedBridge.log(TAG + "no preference group to add to"); return; }
        if (XposedHelpers.callMethod(group, "findPreference", KEY) != null) return;

        Class<?> cls = anchor != null ? anchor.getClass() : XposedHelpers.findClass("androidx.preference.CheckBoxPreference", cl);
        Object pref = newPref(cls, ctx);
        if (pref == null) pref = newPref(XposedHelpers.findClass("androidx.preference.CheckBoxPreference", cl), ctx);
        XposedHelpers.callMethod(pref, "setKey", KEY);
        XposedHelpers.callMethod(pref, "setTitle", (CharSequence) "Switch mobile data automatically");
        XposedHelpers.callMethod(pref, "setSummary", (CharSequence) "Use the other SIM's data when the data SIM's network is poor");
        XposedHelpers.callMethod(pref, "setPersistent", false);

        if (anchor != null) {
            // make room right below "Mobile data"
            int at = (Integer) XposedHelpers.callMethod(anchor, "getOrder");
            int n = (Integer) XposedHelpers.callMethod(group, "getPreferenceCount");
            for (int i = 0; i < n; i++) {
                Object q = XposedHelpers.callMethod(group, "getPreference", i);
                int o = (Integer) XposedHelpers.callMethod(q, "getOrder");
                if (o > at) XposedHelpers.callMethod(q, "setOrder", o + 1);
            }
            XposedHelpers.callMethod(pref, "setOrder", at + 1);
        }

        Class<?> lis = XposedHelpers.findClass("androidx.preference.Preference$OnPreferenceChangeListener", cl);
        Object listener = Proxy.newProxyInstance(cl, new Class<?>[]{lis}, (proxy, m, args) -> {
            switch (m.getName()) {
                case "onPreferenceChange":
                    boolean on = Boolean.TRUE.equals(args[1]);
                    boolean ok = set(ctx, on);
                    XposedBridge.log(TAG + "turned " + (on ? "on" : "off") + (ok ? "" : " - failed"));
                    return ok;           // false = the switch stays as it was
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                case "toString": return "AutoDataSwitchListener";
            }
            return null;
        });
        XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", listener);
        XposedHelpers.callMethod(group, "addPreference", pref);
        PREFS.put(frag, pref);
        refresh(frag);
    }

    static Object newPref(Class<?> cls, Context ctx) {
        try {
            Constructor<?> c = cls.getConstructor(Context.class);
            return c.newInstance(ctx);
        } catch (Throwable ignored) { }
        try {
            Constructor<?> c = cls.getConstructor(Context.class, android.util.AttributeSet.class);
            return c.newInstance(ctx, null);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "can't create " + cls.getName() + ": " + t);
            return null;
        }
    }

    static void refresh(Object frag) {
        Object pref = PREFS.get(frag);
        if (pref == null) return;
        Context ctx = (Context) XposedHelpers.callMethod(frag, "getContext");
        if (ctx == null) return;
        int[] subs = activeSubs(ctx);
        boolean two = subs.length >= 2;
        XposedHelpers.callMethod(pref, "setVisible", two);   // needs two SIMs, like on stock Android
        if (!two) return;
        XposedHelpers.callMethod(pref, "setChecked", isOn(ctx, subs));
    }

    static int[] activeSubs(Context ctx) {
        try {
            SubscriptionManager sm = ctx.getSystemService(SubscriptionManager.class);
            List<SubscriptionInfo> l = sm.getActiveSubscriptionInfoList();
            if (l == null) return new int[0];
            int[] r = new int[l.size()];
            for (int i = 0; i < r.length; i++) r[i] = l.get(i).getSubscriptionId();
            return r;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "subs: " + t);
            return new int[0];
        }
    }

    /** On when it is on for the SIM that doesn't carry data (that is the SIM Android switches to). */
    static boolean isOn(Context ctx, int[] subs) {
        int dds = SubscriptionManager.getDefaultDataSubscriptionId();
        boolean any = false;
        for (int s : subs) {
            boolean on = policy(ctx, s);
            if (s != dds) return on;
            any |= on;
        }
        return any;
    }

    static boolean policy(Context ctx, int sub) {
        try {
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class).createForSubscriptionId(sub);
            return (Boolean) XposedHelpers.callMethod(tm, "isMobileDataPolicyEnabled", AUTO_DATA_SWITCH);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "read sub " + sub + ": " + t);
            return false;
        }
    }

    static boolean set(Context ctx, boolean on) {
        boolean ok = true;
        for (int s : activeSubs(ctx)) {
            try {
                TelephonyManager tm = ctx.getSystemService(TelephonyManager.class).createForSubscriptionId(s);
                XposedHelpers.callMethod(tm, "setMobileDataPolicyEnabled", AUTO_DATA_SWITCH, on);
            } catch (Throwable t) {
                ok = false;
                XposedBridge.log(TAG + "set sub " + s + ": " + t);
            }
        }
        return ok;
    }
}
