#!/system/bin/sh
# Runs once after boot: stop the NFC driver (HAL) service, then exit. Nothing stays running.
MODDIR=${0%/*}
LOG=/data/adb/nfcoff.log
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
sleep 20
n=0
for svc in $(getprop | sed -n 's/^\[init\.svc\.\([^]]*nfc[^]]*\)\]: \[running\]$/\1/p'); do
  case "$svc" in *hal*|*nxp*|*nfc_aidl*|*nfc-service*)
    stop "$svc" && n=$((n + 1)) && echo "$(date) stopped $svc" >> $LOG;;
  esac
done
[ $n = 0 ] && echo "$(date) no running NFC driver service found" >> $LOG
tail -n 50 $LOG > $LOG.t 2>/dev/null && mv $LOG.t $LOG
exit 0
