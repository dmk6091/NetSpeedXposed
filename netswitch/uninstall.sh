#!/system/bin/sh
# give 5G back on the primary SIM
MODDIR=${0%/*}
. $MODDIR/common.sh
set_nr ${PRIMARY_SLOT:-0} on
rm -rf /data/adb/netswitch
