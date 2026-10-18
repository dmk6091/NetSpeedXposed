#!/system/bin/sh
MODDIR=${0%/*}
. $MODDIR/common.sh

while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 5; done
sleep 40
log "---- NetSwitch started (interval ${INTERVAL}s, slots $PRIMARY_SLOT / $BACKUP_SLOT)"

# SIM: 1 = data on the primary SIM, 2 = on the backup.  RAT: 5 = 5G allowed, 4 = locked to 4G.
SIM=1; RAT=5; POOR=0; ECO=0
[ -f $D/state ] && . $D/state
# trust the phone, not the saved state: which SIM really has mobile data right now?
ds=$(H getdata | tail -n1); ps=$(subid $PRIMARY_SLOT)
if [ -n "$ds" ] && [ -n "$ps" ]; then
  if [ "$ds" = "$ps" ]; then SIM=1; else SIM=2; fi
  log "data is on SIM $SIM (default data sub $ds, SIM 1 sub $ps)"
fi
pf=0
bad=0; lbad=0; rec=0; ngood=0; probing=0; probe_start=0
backoff=$RETRY_5G_AFTER; retry_at=0; last_switch=0; last_emit=0; last_key=""
NR1=0; LTE1=0; NR2=0; LTE2=0
light=0; kbps=0; loops=0
t_5g=0; t_eco=0; t_weak=0; last_stat=$(date +%s)
last_b=$(mobile_bytes); last_t=$(date +%s)
# daily 4G data counter (bytes used while on 4G)
BDAY=$(date +%Y%m%d); BUDGET_B=0
[ -f $D/budget ] && { read bd bb < $D/budget; [ "$bd" = "$BDAY" ] && BUDGET_B=${bb:-0}; }

save() { printf 'SIM=%s\nRAT=%s\nECO=%s\n' "$SIM" "$RAT" "$ECO" > $D/state; }
save_budget() { echo "$BDAY $BUDGET_B" > $D/budget; }
now_s() { date +%s; }

while true; do
  now=$(now_s)

  if [ "$PAUSE_ON_WIFI" = 1 ] && wifi_up; then
    POOR=0; bad=0; lbad=0; rec=0; ngood=0; light=0
    last_b=$(mobile_bytes); last_t=$now
    key="wifi"
    if [ "$key" != "$last_key" ] || [ $((now - last_emit)) -ge 60 ]; then emit ""; last_emit=$now; last_key=$key; fi
    sleep $INTERVAL; continue
  fi

  set -- $(read_sig)
  NR1=${1:-0}; LTE1=${2:-0}; NR2=${3:-0}; LTE2=${4:-0}
  [ "$PRIMARY_SLOT" = 1 ] && { t=$NR1; NR1=$NR2; NR2=$t; t=$LTE1; LTE1=$LTE2; LTE2=$t; }
  if net_ok; then ok=1; pf=0; else ok=0; pf=$((pf + 1)); fi
  # mobile traffic since the last check, and the daily 4G counter
  cur_b=$(mobile_bytes); dt=$((now - last_t)); [ $dt -lt 1 ] && dt=1
  db=$((cur_b - last_b)); [ $db -lt 0 ] && db=0
  kbps=$((db / 1024 / dt)); last_b=$cur_b; last_t=$now
  today=$(date +%Y%m%d)
  if [ "$today" != "$BDAY" ]; then BDAY=$today; BUDGET_B=0; save_budget; fi
  if [ "$SIM" = 1 ] && { [ $ECO = 1 ] || [ $RAT = 4 ] || [ "$NR1" -eq 0 ]; }; then BUDGET_B=$((BUDGET_B + db)); fi
  loops=$((loops + 1)); [ $((loops % 12)) -eq 0 ] && save_budget
  [ $((loops % 60)) -eq 1 ] && log "sig NR1=$NR1 LTE1=$LTE1 NR2=$NR2 LTE2=$LTE2 ping=$ok kbps=$kbps SIM=$SIM RAT=$RAT ECO=$ECO"
  if [ $((loops % 360)) -eq 1 ] && [ "$NR1" -eq 0 ] && [ "$LTE1" -eq 0 ]; then
    echo "raw signal lines:" >> $LOG; dumpsys telephony.registry 2>/dev/null | grep -m2 'mSignalStrength=' | cut -c1-900 >> $LOG
  fi
  if [ $ECO = 1 ]; then t_eco=$((t_eco + dt)); elif [ $RAT = 4 ]; then t_weak=$((t_weak + dt)); else t_5g=$((t_5g + dt)); fi
  if [ $((now - last_stat)) -ge 3600 ]; then
    log "stats (last hour): 5G $((t_5g / 60)) min, 4G eco $((t_eco / 60)) min, 4G weak-5G $((t_weak / 60)) min; 4G data today $((BUDGET_B / 1048576)) MB"
    t_5g=0; t_eco=0; t_weak=0; last_stat=$now
  fi
  dwell_ok=0; [ $((now - last_switch)) -ge $MIN_DWELL ] && dwell_ok=1
  msg=""

  # ---- how is the primary SIM doing?
  # 5G attached: weak or not carrying data = bad.  4G only: the same on the 4G signal.
  if [ "$NR1" -ne 0 ] && [ "$RAT" = 5 ]; then
    if [ "$NR1" -le "$NR_BAD" ] || [ $pf -ge $PING_FAILS ]; then bad=$((bad + 1)); else bad=0; fi
  else
    bad=0
  fi
  if [ "$NR1" -eq 0 ] || [ "$RAT" = 4 ]; then
    # an unknown reading (0) is not evidence of anything: only a real weak value or no internet counts
    if { [ "$LTE1" -ne 0 ] && [ "$LTE1" -le "$LTE_BAD" ]; } || { [ $pf -ge $PING_FAILS ] && [ "$SIM" = 1 ]; }; then lbad=$((lbad + 1)); else lbad=0; fi
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

    # 2b) eco: light use -> 4G (saves battery); heavy use or the daily 4G allowance used up -> 5G
    if [ "$ECO_ENABLE" = 1 ] && [ "$RAT" = 5 ] && [ $probing = 0 ]; then
      if [ $ECO = 1 ]; then
        reason=""
        [ $kbps -ge $ECO_HEAVY_KBPS ] && reason="heavy use - back on 5G"
        [ $((BUDGET_B / 1048576)) -ge $ECO_BUDGET_MB ] && reason="daily 4G limit reached - back on 5G"
        if [ -n "$reason" ] && set_nr $PRIMARY_SLOT on; then
          ECO=0; light=0; last_switch=$now; save_budget; save; msg="$reason"
        fi
      else
        if [ $kbps -le $ECO_LIGHT_KBPS ]; then light=$((light + 1)); else light=0; fi
        if [ $light -ge $((ECO_SCREENOFF_SECS / INTERVAL)) ] && [ "$NR1" -ne 0 ] && [ $ok = 1 ] && [ $dwell_ok = 1 ] \
           && [ $((BUDGET_B / 1048576)) -lt $ECO_BUDGET_MB ]; then
          go=0
          if [ $light -ge $((ECO_LIGHT_SECS / INTERVAL)) ]; then go=1; elif screen_off; then go=1; fi
          if [ $go = 1 ] && set_nr $PRIMARY_SLOT off; then
            ECO=1; light=0; last_switch=$now; save; msg="light use - saving battery on 4G"
          fi
        fi
      fi
    fi

    # 3) both weak (no 5G to use and 4G bad, or locked on 4G and bad) -> SIM 2
    if [ $lbad -ge $DOWN_CHECKS ] && [ $ECO = 1 ]; then
      # 4G is bad while eco has it locked: let 5G in again before giving up on SIM 1
      if set_nr $PRIMARY_SLOT on; then ECO=0; light=0; lbad=0; last_switch=$now; save; msg="4G is weak - back on 5G"; fi
    elif [ $lbad -ge $DOWN_CHECKS ] && [ $dwell_ok = 1 ] && [ $probing = 0 ]; then
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
    # SIM 1 known to be weak on every network it reports?
    s1weak=0
    if { [ "$NR1" -ne 0 ] || [ "$LTE1" -ne 0 ]; } \
       && { [ "$NR1" -eq 0 ] || [ "$NR1" -le "$NR_BAD" ]; } \
       && { [ "$LTE1" -eq 0 ] || [ "$LTE1" -le "$LTE_BAD" ]; }; then s1weak=1; fi
    if [ $rec -ge $UP_CHECKS ] && [ $dwell_ok = 1 ]; then
      if set_data $PRIMARY_SLOT; then
        SIM=1; last_switch=$now; rec=0; lbad=0; save
        msg="SIM 1 is back - mobile data on SIM 1"
      fi
    elif [ $s1weak = 0 ] && [ $((now - last_switch)) -ge $S2_RETRY_AFTER ]; then
      # no proof SIM 1 is still bad (maybe no reading at all): go and see
      if set_data $PRIMARY_SLOT; then
        SIM=1; last_switch=$now; rec=0; lbad=0; save
        msg="trying SIM 1 again - mobile data on SIM 1"
      fi
    fi
  fi

  # ---- symbol state
  if [ "$SIM" = 1 ]; then
    if [ $bad -ge 1 ] || [ $lbad -ge 1 ]; then POOR=1; else POOR=0; fi
  else
    if [ $ok = 0 ]; then POOR=1; else POOR=0; fi
  fi

  key="$SIM$RAT$ECO$POOR"
  if [ -n "$msg" ] || [ "$key" != "$last_key" ] || [ $((now - last_emit)) -ge 60 ]; then
    emit "$msg"; last_emit=$now; last_key=$key
  fi
  sleep $INTERVAL
done
