package com.netspeed.xposed;

import android.content.res.Resources;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Raises how many notification icons the status bar shows before it
 * collapses the rest into the "•" overflow dot (AOSP default: 4).
 *
 * Android versions store this limit in different places, so several
 * approaches are tried. Whatever is found is written to the LSPosed log.
 */
final class NotifIconLimit {

    private static final String TAG = "NetSpeedXposed: [icons] ";
    private static final String SYSTEMUI = "com.android.systemui";

    /** How many notification icons to show. Change this number if you like. */
    static final int MAX_ICONS = 12;

    /** AOSP's default limit; only values equal to this get replaced. */
    private static final int AOSP_DEFAULT = 4;

    private static final String CONTAINER =
            "com.android.systemui.statusbar.phone.NotificationIconContainer";

    /** Resource id of max_notif_static_icons, resolved on first use. 0 = unknown, -1 = absent. */
    private static int sResId = 0;

    /** Log the first raise only, so the log isn't flooded on every layout. */
    private static boolean sLogged = false;
    private static boolean sOverflowLogged = false;
    private static final java.util.Set<String> sSeenIds = new java.util.HashSet<>();

    /** True while the status bar's icon container is being laid out. */
    private static final ThreadLocal<Boolean> IN_STATUS_BAR = new ThreadLocal<>();

    /**
     * Only the status bar row should change; the lock screen / AOD / shelf rows
     * use the same class and must keep their own limits.
     */
    private static boolean isStatusBar(Object o) {
        try {
            android.view.View v = (android.view.View) o;
            int id = v.getId();
            if (id == android.view.View.NO_ID) return false;
            String name = v.getResources().getResourceEntryName(id);
            if (sSeenIds.add(name)) XposedBridge.log(TAG + "container id: " + name);
            return "notificationIcons".equals(name);
        } catch (Throwable t) {
            return false;
        }
    }

    private NotifIconLimit() {}

    static void install(ClassLoader cl) {
        hookResource();
        hookContainer(cl);
    }

    /**
     * Android 14+: the limit is the SystemUI integer resource
     * "max_notif_static_icons". Return our number whenever it is read.
     */
    private static void hookResource() {
        try {
            XposedHelpers.findAndHookMethod(Resources.class, "getInteger", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (sResId == -1) return;
                            Resources res = (Resources) p.thisObject;
                            if (sResId == 0) {
                                int id = res.getIdentifier("max_notif_static_icons", "integer", SYSTEMUI);
                                sResId = id != 0 ? id : -1;
                                XposedBridge.log(TAG + "resource max_notif_static_icons "
                                        + (id != 0 ? "found" : "not found"));
                                if (id == 0) return;
                            }
                            if ((int) p.args[0] == sResId) {
                                p.setResult(MAX_ICONS);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "resource hook failed: " + t);
        }
    }

    /**
     * Older/other versions: the limit lives in a field or a setter on
     * NotificationIconContainer.
     */
    private static void hookContainer(ClassLoader cl) {
        final Class<?> c = XposedHelpers.findClassIfExists(CONTAINER, cl);
        if (c == null) {
            XposedBridge.log(TAG + "NotificationIconContainer not found");
            return;
        }

        // Log the int fields once, so the right one can be found from the LSPosed log.
        StringBuilder sb = new StringBuilder(TAG + "int fields: ");
        for (Field f : c.getDeclaredFields()) {
            if (f.getType() == int.class) sb.append(f.getName()).append(' ');
        }
        XposedBridge.log(sb.toString());

        // Log methods whose names mention the icon limit, to help if this still misses.
        StringBuilder ms = new StringBuilder(TAG + "methods: ");
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            String n = m.getName();
            if (n.contains("Max") || n.contains("Overflow") || n.contains("calculate")) {
                ms.append(n).append(' ');
            }
        }
        XposedBridge.log(ms.toString());

        // Main fix: the limit gets set to 4 after the container is created, so
        // re-apply ours right before every layout pass that places the icons.
        int hooked = 0;
        XC_MethodHook layout = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                boolean sb = isStatusBar(p.thisObject);
                IN_STATUS_BAR.set(sb);
                if (sb) raiseFields(p.thisObject);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                IN_STATUS_BAR.set(false);
            }
        };
        for (String n : new String[]{"calculateIconXTranslations", "onLayout", "resetViewStates"}) {
            try {
                hooked += XposedBridge.hookAllMethods(c, n, layout).size();
            } catch (Throwable ignored) {
                // Method doesn't exist in this version.
            }
        }

        // The real cap on this ROM: shouldForceOverflow(i, speedBump, appear, maxVisibleIcons)
        // returns true once i >= maxVisibleIcons (4). Raise that argument for the status bar.
        try {
            int n = XposedBridge.hookAllMethods(c, "shouldForceOverflow", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    boolean sb = p.thisObject != null ? isStatusBar(p.thisObject)
                            : Boolean.TRUE.equals(IN_STATUS_BAR.get());
                    if (!sb) return;
                    // maxVisibleIcons is the last int argument.
                    for (int i = p.args.length - 1; i >= 0; i--) {
                        if (p.args[i] instanceof Integer) {
                            int v = (Integer) p.args[i];
                            if (!sOverflowLogged) {
                                sOverflowLogged = true;
                                XposedBridge.log(TAG + "shouldForceOverflow limit arg = " + v
                                        + " (args: " + java.util.Arrays.toString(p.args) + ")");
                            }
                            if (v > 0 && v < MAX_ICONS) p.args[i] = MAX_ICONS;
                            break;
                        }
                    }
                }
            }).size();
            hooked += n;
            XposedBridge.log(TAG + "shouldForceOverflow hooks: " + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "shouldForceOverflow hook failed: " + t);
        }
        XposedBridge.log(TAG + "layout hooks installed: " + hooked);

        // After each container is created, raise any instance field holding the limit.
        XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (isStatusBar(p.thisObject)) raiseFields(p.thisObject);
            }
        });

        // Some versions set the limit from outside through a setter.
        for (String name : new String[]{"setMaxIconsAmount", "setMaxStaticIcons"}) {
            try {
                XposedHelpers.findAndHookMethod(c, name, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if ((int) p.args[0] == AOSP_DEFAULT) p.args[0] = MAX_ICONS;
                    }
                });
                XposedBridge.log(TAG + "hooked " + name);
            } catch (Throwable ignored) {
                // Method doesn't exist in this version.
            }
        }
    }

    private static void raiseFields(Object container) {
        for (String name : new String[]{"mMaxStaticIcons", "mMaxIcons", "mMaxIconsOnStatusBar"}) {
            try {
                Field f = XposedHelpers.findField(container.getClass(), name);
                if (Modifier.isStatic(f.getModifiers())) continue;
                int v = f.getInt(container);
                if (v > 0 && v < MAX_ICONS) {
                    f.setInt(container, MAX_ICONS);
                    if (!sLogged) {
                        sLogged = true;
                        XposedBridge.log(TAG + "raised " + name + " from " + v + " to " + MAX_ICONS);
                    }
                }
            } catch (Throwable ignored) {
                // Field doesn't exist in this version.
            }
        }
    }
}
