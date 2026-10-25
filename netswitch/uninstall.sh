#!/system/bin/sh
# give 5G back on the primary SIM
MODDIR=${0%/*}
. $MODDIR/common.sh
set_nr ${PRIMARY_SLOT:-0} on
restore_sys_auto   # the system's auto data switch back to how it was before NetSwitch
rm -rf /data/adb/netswitch
