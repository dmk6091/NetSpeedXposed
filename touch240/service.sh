#!/system/bin/sh
MODDIR=${0%/*}
X=$MODDIR/bin/xt; chmod 755 $X
apply(){ [ -e /data/adb/touch240.off ] && return; $X 0 1011 240 >/dev/null 2>&1; $X 0 202 1 >/dev/null 2>&1; }
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 3; done
sleep 10
apply
# re-apply the moment the touch service resets the rate
dmesg -w 2>/dev/null | while read -r l; do
  case "$l" in
    *"nvt_set_cur_value"*"mode:1011, value:120"*|*"nvt_set_cur_value"*"mode:202, value:0"*) sleep 1; apply;;
  esac
done &
# safety net
while true; do sleep 30; apply; done
