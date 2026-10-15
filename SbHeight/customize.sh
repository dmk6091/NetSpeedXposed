#!/system/bin/sh
SKIPUNZIP=0
ui_print "- Status Bar Height (dual row)"
ui_print "- Default height: 50dp"
ui_print "- Tap Action on the module to change it, then reboot"
[ -f "$MODPATH/height.conf" ] || echo 50 > "$MODPATH/height.conf"
set_perm_recursive "$MODPATH" 0 0 0755 0644
for f in service.sh action.sh uninstall.sh common.sh; do
    set_perm "$MODPATH/$f" 0 0 0755
done
