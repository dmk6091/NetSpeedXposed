#!/system/bin/sh
# Copies the numbers saved in NetSpeed settings (Volume steps) into the config; they apply at the next boot.
MODDIR=${0%/*}
. $MODDIR/common.sh
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 5; done
last=""
while true; do
  v=$(settings get global netspeed_volume_steps 2>/dev/null)
  if [ -n "$v" ] && [ "$v" != null ] && [ "$v" != "$last" ]; then
    last=$v
    OLDIFS=$IFS; IFS=';'
    for kv in $v; do
      k=${kv%%=*}; n=${kv#*=}
      case "$k" in m) K=MEDIA;; c) K=CALL;; r) K=RING;; a) K=ALARM;; n) K=NOTIF;; s) K=SYSTEM;; *) continue;; esac
      case "$n" in ''|*[!0-9]*) continue;; esac
      [ "$n" -ge 3 ] && [ "$n" -le 150 ] && sed -i "s/^$K=[^ #]*/$K=$n/" $CFG
    done
    IFS=$OLDIFS
    echo "$(date) settings page saved: $v" >> $D/log.txt
  fi
  sleep 10
done
