#!/system/bin/sh
MODDIR=${0%/*}
. $MODDIR/common.sh
RP=
for r in resetprop /data/adb/magisk/resetprop /data/adb/ksu/bin/resetprop /data/adb/ap/bin/resetprop; do
  command -v $r >/dev/null 2>&1 && RP=$r && break
  [ -x "$r" ] && RP=$r && break
done
[ -z "$RP" ] && { echo "$(date) no resetprop found" >> $D/log.txt; exit 0; }
setp() { case "$2" in ''|*[!0-9]*) return;; esac; [ "$2" -ge 3 ] && [ "$2" -le 150 ] && $RP -n "$1" "$2"; }
setp ro.config.media_vol_steps "$MEDIA"
setp ro.config.vc_call_vol_steps "$CALL"
setp ro.config.ring_vol_steps "$RING"
setp ro.config.alarm_vol_steps "$ALARM"
setp ro.config.notification_vol_steps "$NOTIF"
setp ro.config.system_vol_steps "$SYSTEM"
echo "$(date) applied media=$MEDIA call=$CALL ring=$RING alarm=$ALARM notif=$NOTIF system=$SYSTEM via $RP" >> $D/log.txt
