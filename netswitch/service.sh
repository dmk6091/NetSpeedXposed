#!/system/bin/sh
MODDIR=${0%/*}
. $MODDIR/common.sh

while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 5; done
sleep 40
log "---- NetSwitch started (interval ${INTERVAL}s, slots $PRIMARY_SLOT / $BACKUP_SLOT)"

# SIM: 1 = data on the primary SIM, 2 = on the backup.  RAT: 5 = 5G allowed, 4 = locked to 4G.
SIM=1; RAT=5; POOR=0
[ -f $D/state ] && . $D/state
bad=0; lbad=0; rec=0; ngood=0; probing=0; probe_start=0
backoff=$RETRY_5G_AFTER; retry_at=0; last_switch=0; last_emit=0; last_key=""
NR1=0; LTE1=0; NR2=0; LTE2=0

save() { printf 'SIM=%s\nRAT=%s\n' "$SIM" "$RAT" > $D/state; }
now_s() { date +%s; }

while true; do
  now=$(now_s)

  if [ "$PAUSE_ON_WIFI" = 1 ] && wifi_up; then
    POOR=0; bad=0; lbad=0; rec=0; ngood=0
    key="wifi"
    if [ "$key" != "$last_key" ] || [ $((now - last_emit)) -ge 60 ]; then emit ""; last_emit=$now; last_key=$key; fi
    sleep $INTERVAL; continue
  fi

  set -- $(read_sig)
  NR1=${1:-0}; LTE1=${2:-0}; NR2=${3:-0}; LTE2=${4:-0}
  [ "$PRIMARY_SLOT" = 1 ] && { t=$NR1; NR1=$NR2; NR2=$t; t=$LTE1; LTE1=$LTE2; LTE2=$t; }
  if net_ok; then ok=1; else ok=0; fi
  dwell_ok=0; [ $((now - last_switch)) -ge $MIN_DWELL ] && dwell_ok=1
  msg=""

  # ---- how is the primary SIM doing?
  # 5G attached: weak or not carrying data = bad.  4G only: the same on the 4G signal.
  if [ "$NR1" -ne 0 ] && [ "$RAT" = 5 ]; then
    if [ "$NR1" -le "$NR_BAD" ] || [ $ok = 0 ]; then bad=$((bad + 1)); else bad=0; fi
  else
    bad=0
  fi
  if [ "$NR1" -eq 0 ] || [ "$RAT" = 4 ]; then
    if [ "$LTE1" -eq 0 ] || [ "$LTE1" -le "$LTE_BAD" ] || { [ $ok = 0 ] && [ "$SIM" = 1 ]; }; then lbad=$((lbad + 1)); else lbad=0; fi
  else
    lbad=0
  fi

  if [ "$SIM" = 1 ]; then
    # 1) 5G weak / unstable -> lock 4G
    if [ "$RAT" = 5 ] && [ $bad -ge $DOWN_CHECKS ] && [ $dwell_ok = 1 ]; then
      if set_nr $PRIMARY_SLOT off; then
        RAT=4; probing=0; backoff=$RETRY_5G_AFTER; retry_at=$((now + backoff)); last_switch=$now; bad=0; ngood=0; save
        msg="5G is weak - switched to 4G"
      fi
    # 2) on 4G: is 5G strong again?  (early if it shows up strong, otherwise a timed retry)
    elif [ "$RAT" = 4 ]; then
      if [ "$NR1" -ne 0 ] && [ "$NR1" -ge "$NR_GOOD" ] && [ $ok = 1 ]; then ngood=$((ngood + 1)); else ngood=0; fi
      if [ $probing = 1 ]; then
        if [ $ngood -ge 3 ]; then
          probing=0; RAT=5; backoff=$RETRY_5G_AFTER; last_switch=$now; save
          msg="5G is strong again - back on 5G"
        elif [ $((now - probe_start)) -ge $PROBE_SECS ]; then
          if set_nr $PRIMARY_SLOT off; then
            probing=0; backoff=$((backoff * 2)); [ $backoff -gt 600 ] && backoff=600
            retry_at=$((now + backoff)); log "5G retry failed, next in ${backoff}s"
          fi
        fi
      elif [ $ngood -ge $UP_CHECKS ] && [ $dwell_ok = 1 ]; then
        if set_nr $PRIMARY_SLOT on; then RAT=5; ngood=0; last_switch=$now; save; msg="5G is strong again - back on 5G"; fi
      elif [ $now -ge $retry_at ] && [ $dwell_ok = 1 ]; then
        if set_nr $PRIMARY_SLOT on; then probing=1; probe_start=$now; ngood=0; log "trying 5G again"; fi
      fi
    fi

    # 3) both weak (no 5G to use and 4G bad, or locked on 4G and bad) -> SIM 2
    if [ $lbad -ge $DOWN_CHECKS ] && [ $dwell_ok = 1 ] && [ $probing = 0 ]; then
      if { [ "$LTE2" -ne 0 ] && [ "$LTE2" -gt $((LTE_BAD + 5)) ]; } || [ "$NR2" -ne 0 ]; then
        if set_data $BACKUP_SLOT; then
          SIM=2; last_switch=$now; lbad=0; rec=0; save
          msg="SIM 1 is weak - mobile data moved to SIM 2"
        fi
      fi
    fi
  else
    # on SIM 2: has SIM 1 recovered?
    if { [ "$LTE1" -ne 0 ] && [ "$LTE1" -ge "$LTE_GOOD" ]; } || { [ "$NR1" -ne 0 ] && [ "$NR1" -ge "$NR_GOOD" ]; }; then rec=$((rec + 1)); else rec=0; fi
    if [ $rec -ge $UP_CHECKS ] && [ $dwell_ok = 1 ]; then
      if set_data $PRIMARY_SLOT; then
        SIM=1; last_switch=$now; rec=0; lbad=0; save
        msg="SIM 1 is back - mobile data on SIM 1"
      fi
    fi
  fi

  # ---- symbol state
  if [ "$SIM" = 1 ]; then
    if [ $bad -ge 1 ] || [ $lbad -ge 1 ]; then POOR=1; else POOR=0; fi
  else
    if [ $ok = 0 ]; then POOR=1; else POOR=0; fi
  fi

  key="$SIM$RAT$POOR"
  if [ -n "$msg" ] || [ "$key" != "$last_key" ] || [ $((now - last_emit)) -ge 60 ]; then
    emit "$msg"; last_emit=$now; last_key=$key
  fi
  sleep $INTERVAL
done
