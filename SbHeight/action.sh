#!/system/bin/sh
# Runs when you tap "Action" on the module in ReSukiSU / KernelSU.
MODDIR=${0%/*}
. "$MODDIR/common.sh"

cur=$(get_height)
next=""
found=0
for s in $STEPS; do
    if [ $found = 1 ]; then next=$s; break; fi
    [ "$s" = "$cur" ] && found=1
done
[ -z "$next" ] && next=${STEPS%% *}

echo "$next" > "$CONF"
apply_height "$next"

echo "Status bar height: $cur -> $next"
[ "$next" = "off" ] && echo "(off = stock height)" || echo "(${next}dp)"
echo ""
echo "Order: $STEPS"
echo "Reboot to see the change everywhere."
echo ""
status
