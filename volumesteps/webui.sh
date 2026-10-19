#!/system/bin/sh
MODDIR=${0%/*}
. $MODDIR/common.sh
KEYS=" MEDIA CALL RING ALARM NOTIF SYSTEM "
case "$1" in
  get) grep -E '^[A-Z]+=' $CFG | sed 's/[[:space:]]*#.*//' ;;
  live)
    echo "media=$(getprop ro.config.media_vol_steps)"
    echo "call=$(getprop ro.config.vc_call_vol_steps)"
    echo "ring=$(getprop ro.config.ring_vol_steps)"
    echo "alarm=$(getprop ro.config.alarm_vol_steps)"
    echo "notif=$(getprop ro.config.notification_vol_steps)"
    echo "system=$(getprop ro.config.system_vol_steps)"
    echo "mediavol=$(settings get system volume_music_speaker 2>/dev/null)"
    ;;
  set)
    case "$KEYS" in *" $2 "*) ;; *) echo "bad key"; exit 1;; esac
    case "$3" in ''|*[!0-9]*) echo "bad value"; exit 1;; esac
    [ "$3" -lt 3 ] && exit 1; [ "$3" -gt 150 ] && exit 1
    sed -i "s/^$2=[^ #]*/$2=$3/" $CFG; echo "ok" ;;
  reboot) sleep 1; svc power reboot ;;
  log) tail -n 20 $D/log.txt 2>/dev/null ;;
esac
