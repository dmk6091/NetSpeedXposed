#!/system/bin/sh
# Back end of the web page (KernelSU / KSU WebUI): sh webui.sh live | get | set KEY VAL | mode auto|5g|4g | restart | log [N]
MODDIR=${0%/*}
. $MODDIR/common.sh

KEYS=" PRIMARY_SLOT BACKUP_SLOT INTERVAL NR_BAD NR_GOOD LTE_BAD LTE_GOOD DOWN_CHECKS UP_CHECKS MIN_DWELL RETRY_5G_AFTER PROBE_SECS PAUSE_ON_WIFI NOTIFY PING_FAILS S2_RETRY_AFTER ECO_ENABLE ECO_LIGHT_KBPS ECO_LIGHT_SECS ECO_SCREENOFF_SECS ECO_HEAVY_KBPS ECO_HEAVY_OFF_KBPS ECO_BUDGET_MB ECO_HEAVY_CHECKS ECO_FAST_KBPS ECO_HOLD_SECS ECO_NEED_LTE ECO_COMPARE ECO_COMPARE_SECS SCREEN_OFF_INTERVAL PING_EVERY SIM2_DATA S2_BLOCK_MINS "

case "$1" in
  live)
    cat $D/live 2>/dev/null
    echo "mode=$(cat $D/mode 2>/dev/null || echo auto)"
    echo "running=$(pgrep -f netswitch/service.sh | head -n1)"
    echo "now=$(date +%s)"
    ;;
  get)
    grep -E '^[A-Z_0-9]+=' $CFG | sed 's/[[:space:]]*#.*//; s/"//g'
    ;;
  set)
    case "$KEYS" in *" $2 "*) ;; *) echo "bad key"; exit 1;; esac
    case "$3" in ''|*[!0-9-]*) echo "bad value"; exit 1;; esac
    if grep -q "^$2=" $CFG; then
      sed -i "s/^$2=[^ #]*/$2=$3/" $CFG
    else
      echo "$2=$3" >> $CFG
    fi
    echo "ok $2=$3"
    ;;
  mode)
    case "$2" in auto|5g|4g) echo "$2" > $D/mode; echo "ok";; *) echo "bad mode"; exit 1;; esac
    ;;
  restart)
    pkill -f netswitch/service.sh
    sleep 1
    (setsid sh $MOD/service.sh </dev/null >/dev/null 2>&1 &)
    echo "restarted"
    ;;
  log)
    tail -n "${2:-60}" $LOG 2>/dev/null
    ;;
  *)
    echo "usage: live | get | set KEY VAL | mode auto|5g|4g | restart | log [N]"
    ;;
esac
