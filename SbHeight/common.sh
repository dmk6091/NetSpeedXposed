#!/system/bin/sh
# Shared helpers. Uses Android's built-in "fabricated overlay" feature
# (cmd overlay fabricate), so no overlay APK and no system file changes.

MODDIR=${MODDIR:-${0%/*}}
CONF="$MODDIR/height.conf"

# Heights the Action button cycles through (dp). "off" = stock height.
STEPS="44 48 50 52 56 off"
DEFAULT=50

RES_LIST="status_bar_height_default status_bar_height_portrait status_bar_height_landscape"

get_height() {
    h=$(cat "$CONF" 2>/dev/null | tr -d ' \r\n')
    [ -z "$h" ] && h=$DEFAULT
    echo "$h"
}

# Android dimension "complex" value: integer dp -> (dp << 8) | COMPLEX_UNIT_DIP(1)
dp_hex() {
    printf '0x%x' $(( ($1 << 8) | 1 ))
}

ov_name() {
    # status_bar_height_default -> SbHeight_default
    echo "SbHeight_${1##*_}"
}

apply_height() {
    h=$1
    LOG="$MODDIR/last_apply.log"
    echo "== $(date) height=$h sdk=$(getprop ro.build.version.sdk)" > "$LOG"
    for r in $RES_LIST; do
        n=$(ov_name "$r")
        if [ "$h" = "off" ]; then
            cmd overlay disable --user 0 "com.android.shell:$n" >>"$LOG" 2>&1
        else
            echo "-- $r = ${h}dp ($(dp_hex "$h"))" >>"$LOG"
            cmd overlay fabricate --target android --name "$n" \
                "android:dimen/$r" 0x05 "$(dp_hex "$h")" >>"$LOG" 2>&1
            echo "fabricate exit $?" >>"$LOG"
            cmd overlay enable --user 0 "com.android.shell:$n" >>"$LOG" 2>&1
            echo "enable exit $?" >>"$LOG"
        fi
    done
    echo "-- overlays on android:" >>"$LOG"
    cmd overlay list android >>"$LOG" 2>&1
    echo "-- current values:" >>"$LOG"
    for r in $RES_LIST; do
        cmd overlay lookup android "android:dimen/$r" >>"$LOG" 2>&1
    done
}

status() {
    cat "$MODDIR/last_apply.log" 2>/dev/null
}
