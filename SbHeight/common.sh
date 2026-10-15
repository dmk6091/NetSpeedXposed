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

# Run a cmd with stdin from /dev/null and output through a pipe, so
# system_server never has to touch files in the module folder (SELinux
# blocks that and the call fails with "Failed transaction").
run() {
    out=$("$@" </dev/null 2>&1)
    rc=$?
    [ -n "$out" ] && echo "$out" >>"$LOG"
    return $rc
}

apply_height() {
    h=$1
    LOG="$MODDIR/last_apply.log"
    echo "== $(date) height=$h sdk=$(getprop ro.build.version.sdk)" > "$LOG"
    for r in $RES_LIST; do
        n=$(ov_name "$r")
        if [ "$h" = "off" ]; then
            run cmd overlay disable --user 0 "com.android.shell:$n"
        else
            echo "-- $r = ${h}dp ($(dp_hex "$h"))" >>"$LOG"
            run cmd overlay fabricate --target android --name "$n" \
                "android:dimen/$r" 0x05 "$(dp_hex "$h")"
            echo "fabricate exit $?" >>"$LOG"
            run cmd overlay enable --user 0 "com.android.shell:$n"
            echo "enable exit $?" >>"$LOG"
        fi
    done
    echo "-- overlays on android:" >>"$LOG"
    run cmd overlay list android
    echo "-- current values:" >>"$LOG"
    for r in $RES_LIST; do
        run cmd overlay lookup android "android:dimen/$r"
    done
}

status() {
    cat "$MODDIR/last_apply.log" 2>/dev/null
}
