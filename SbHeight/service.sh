#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"

# Wait for boot to finish so the overlay service is ready.
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
sleep 5

apply_height "$(get_height)"
status > "$MODDIR/last_apply.log" 2>&1
