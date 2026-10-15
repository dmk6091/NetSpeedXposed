#!/system/bin/sh
# Shared helpers. Heights are applied with small overlay APKs (one per
# height) that the module mounts into /product/overlay.

MODDIR=${MODDIR:-${0%/*}}
CONF="$MODDIR/height.conf"

# Heights the Action button cycles through (dp). "off" = stock height.
# Each height is a small overlay APK in system/product/overlay.
STEPS="44 48 50 52 56 off"
DEFAULT=50
HEIGHTS="44 48 50 52 56"
PKG_PREFIX="com.dmk.sbheight.h"

get_height() {
    h=$(cat "$CONF" 2>/dev/null | tr -d ' \r\n')
    [ -z "$h" ] && h=$DEFAULT
    echo "$h"
}

# Run a cmd with stdin from /dev/null and output through a pipe, so
# system_server never has to touch files in the module folder (SELinux
# blocks that and the call fails with "Failed transaction").
run() {
    out=$("$@" </dev/null 2>&1)
    rc=$?
    # Keep only the first line of an error (no Java stack traces).
    [ -n "$out" ] && echo "$out" | grep -v '^[[:space:]]*at ' | grep -v '^[[:space:]]*$' | head -3 >>"$LOG"
    return $rc
}

apply_height() {
    h=$1
    LOG="$MODDIR/last_apply.log"
    echo "== $(date) height=$h sdk=$(getprop ro.build.version.sdk)" > "$LOG"
    diag >>"$LOG"
    for x in $HEIGHTS; do
        [ "$x" = "$h" ] && continue
        run cmd overlay disable --user 0 "$PKG_PREFIX$x"
    done
    if [ "$h" != "off" ]; then
        run cmd overlay enable --user 0 "$PKG_PREFIX$h"
        echo "enable exit $?" >>"$LOG"
        run cmd overlay set-priority "$PKG_PREFIX$h" highest
        echo "set-priority exit $?" >>"$LOG"
    fi
    echo "-- SbHeight overlays:" >>"$LOG"
    out=$(cmd overlay list android </dev/null 2>&1)
    echo "$out" | grep -i sbheight >>"$LOG" || echo "(none found - overlays not mounted?)" >>"$LOG"
    echo "-- current values:" >>"$LOG"
    for r in status_bar_height_default status_bar_height_portrait; do
        run cmd overlay lookup android "android:dimen/$r"
    done
}

# Is the overlay APK visible where Android looks for it?
diag() {
    echo "-- mount check:"
    for d in /product/overlay /system/product/overlay; do
        if [ -f "$d/SbHeight50/SbHeight50.apk" ]; then echo "$d: SbHeight APKs present"
        else echo "$d: SbHeight APKs MISSING"; fi
    done
    [ -f "$MODDIR/system/product/overlay/SbHeight50/SbHeight50.apk" ] \
        && echo "module folder: APKs included" || echo "module folder: APKs NOT included"
    meta=""
    [ -e /data/adb/metamodule ] && meta="$(readlink /data/adb/metamodule 2>/dev/null || echo yes)"
    for m in /data/adb/modules/*/module.prop; do
        grep -qiE '^metamodule=(1|true)' "$m" 2>/dev/null && meta="$meta $(grep '^name=' "$m" | cut -d= -f2)"
    done
    [ -n "$meta" ] && echo "metamodule:$meta" || echo "metamodule: none found"
    echo "-- overlay registered:"
    cmd overlay list android </dev/null 2>/dev/null | grep -i sbheight || echo "(none)"
    echo "--"
}

status() {
    cat "$MODDIR/last_apply.log" 2>/dev/null
}
