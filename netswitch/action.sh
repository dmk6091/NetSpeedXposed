#!/system/bin/sh
# Magisk "Action" button: what NetSwitch sees (send me this if something doesn't switch)
MODDIR=${0%/*}
. $MODDIR/common.sh
OUT=$D/probe.txt
{
  echo "== NetSwitch probe $(date)"
  echo "== busybox: $BB"
  for s in 0 1; do
    sub=$(subid $s)
    echo "slot $s -> sub '$sub'; allowed types: $(H getnt "$sub" | tail -n1)"
  done
  echo "default data sub: $(H getdata | tail -n1)"
  echo "== signal (nr1 lte1 nr2 lte2): $(read_sig)"
  echo "== wifi connected: $(wifi_up && echo yes || echo no)   mobile ping: $(net_ok && echo ok || echo FAIL)"
  echo "== signal lines"
  dumpsys telephony.registry 2>/dev/null | grep -E '^ *mSignalStrength=' | cut -c1-900
  echo "== service running: $(pgrep -f netswitch/service.sh | head -n1)"
  echo "== config"; cat $CFG
  echo "== log (last 40)"; tail -n 40 $LOG 2>/dev/null
} > $OUT 2>&1
cat $OUT
echo
echo "Saved to $OUT"
