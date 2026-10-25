#!/system/bin/sh
MODDIR=${0%/*}
. $MODDIR/common.sh

while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 5; done
[ "$(cut -d. -f1 /proc/uptime)" -lt 240 ] && sleep 40   # a restart from the web page skips the wait
log "---- NetSwitch started (interval ${INTERVAL}s, slots $PRIMARY_SLOT / $BACKUP_SLOT)"

# SIM: 1 = data on the primary SIM, 2 = on the backup.  RAT: 5 = 5G allowed, 4 = locked to 4G.
SIM=1; RAT=5; POOR=0; ECO=0
[ -f $D/state ] && . $D/state
# trust the phone, not the saved state: which SIM really has mobile data right now?
ds=$(H getdata | tail -n1); ps=$(subid $PRIMARY_SLOT)
case "$ds" in ''|*[!0-9]*) DSUB="";; *) DSUB=$ds;; esac
if [ -n "$ds" ] && [ -n "$ps" ]; then
  if [ "$ds" = "$ps" ]; then SIM=1; else SIM=2; fi
  log "data is on SIM $SIM (default data sub $ds, SIM 1 sub $ps)"
fi
pf=0
bad=0; lbad=0; rec=0; ngood=0; probing=0; probe_start=0
backoff=$RETRY_5G_AFTER; retry_at=0; last_switch=0; last_emit=0; last_key=""
NR1=0; LTE1=0; NR2=0; LTE2=0
light=0; kbps=0; loops=0; hv=0; h1=0; h2=0; h3=0; h4=0; h5=0; eavg=0
eco_hold_until=0; cmp_at=0; eco_ref=0; h6=0
t_5g=0; t_eco=0; t_weak=0; last_stat=$(date +%s)
set -- $(mobile_bytes); last_b=${1:-0}; last_rx=${2:-0}; last_t=$(date +%s)
ok=1; last_ping=0
# daily 4G data counter (bytes used while on 4G)
BDAY=$(date +%Y%m%d); BUDGET_B=0
[ -f $D/budget ] && { read bd bb < $D/budget; [ "$bd" = "$BDAY" ] && BUDGET_B=${bb:-0}; }

save() { printf 'SIM=%s\nRAT=%s\nECO=%s\n' "$SIM" "$RAT" "$ECO" > $D/state; }
save_budget() { echo "$BDAY $BUDGET_B" > $D/budget; }
now_s() { date +%s; }

# v2.4: hand the SIM 2 part to the system's own auto data switch (needs the default data SIM = SIM 1)
PSUB=$ps; VSIM=$SIM; last_vsim=$SIM
sys_auto_check() {
  enable_sys_auto
  if sys_auto_on && [ "$SIM" = 2 ]; then
    set_data $PRIMARY_SLOT && { SIM=1; VSIM=1; last_vsim=1; save; log "default data back on SIM 1 - the system's auto switch handles SIM 2 now"; }
  fi
}
if [ "${SYS_AUTO_SWITCH:-0}" = 1 ]; then
  sys_auto_check
else
  restore_sys_auto   # switched off in the settings: give the system toggle back its old state
fi

mode_done=""
while true; do
  now=$(now_s)
  screen_check

  # manual override from the web page: auto | 5g | 4g
  MODE=$(cat $D/mode 2>/dev/null); [ -z "$MODE" ] && MODE=auto
  if [ "$MODE" != auto ]; then
    if [ "$MODE" != "$mode_done" ]; then
      if [ "$MODE" = 5g ]; then
        set_nr $PRIMARY_SLOT on && { RAT=5; ECO=0; POOR=0; mode_done=$MODE; }
      else
        set_nr $PRIMARY_SLOT off && { RAT=4; ECO=0; mode_done=$MODE; }
      fi
      if [ "$mode_done" = "$MODE" ]; then save; emit "manual: forced ${MODE}"; fi
    fi
    printf 'manual=1\nsim=%s\nrat=%s\neco=0\nts=%s\n' "$SIM" "$RAT" "$now" > $D/live
    sleep 30; continue
  fi
  if [ -n "$mode_done" ]; then
    mode_done=""; bad=0; lbad=0; rec=0; ngood=0; probing=0; light=0; last_switch=$now
    emit "back to automatic"
  fi

  # mobile data off (or airplane mode): nothing to manage, don't touch the SIMs
  if data_off; then
    if [ "$paused_off" != 1 ]; then paused_off=1; log "mobile data is off - paused"; emit ""; fi
    printf 'dataoff=1\nsim=%s\nrat=%s\neco=%s\nts=%s\n' "$SIM" "$RAT" "${ECO:-0}" "$now" > $D/live
    sleep 30; continue
  fi
  if [ "$paused_off" = 1 ]; then
    paused_off=0; bad=0; lbad=0; rec=0; ngood=0; light=0; pf=0; ok=1; last_switch=$now
    set -- $(mobile_bytes); last_b=${1:-0}; last_rx=${2:-0}; last_t=$now
    log "mobile data is on again - resumed"
  fi

  if [ "$PAUSE_ON_WIFI" = 1 ] && wifi_up; then
    POOR=0; bad=0; lbad=0; rec=0; ngood=0; light=0
    set -- $(mobile_bytes); last_b=${1:-0}; last_rx=${2:-0}; last_t=$now
    printf 'wifi=1\nsim=%s\nrat=%s\neco=%s\nts=%s\n' "$SIM" "$RAT" "${ECO:-0}" "$now" > $D/live
    key="wifi"
    if [ "$key" != "$last_key" ] || { [ $((now - last_emit)) -ge 60 ] && ! screen_off; }; then emit ""; last_emit=$now; last_key=$key; fi
    sleep 30; continue
  fi

  set -- $(read_sig)
  NR1=${1:-0}; LTE1=${2:-0}; NR2=${3:-0}; LTE2=${4:-0}; ADS=${5:--}
  [ "$PRIMARY_SLOT" = 1 ] && { t=$NR1; NR1=$NR2; NR2=$t; t=$LTE1; LTE1=$LTE2; LTE2=$t; }
  # which SIM really carries data (the system's auto switch moves it without changing the default)
  VSIM=$SIM; sysmsg=""
  if sys_auto_on && [ "$SIM" = 1 ] && [ -n "$PSUB" ]; then
    case "$ADS" in ''|-|*[!0-9]*) ;; *) [ "$ADS" != "$PSUB" ] && VSIM=2;; esac
  fi
  if [ "$VSIM" != "$last_vsim" ]; then
    if [ "$VSIM" = 2 ]; then sysmsg="SIM 1 is weak - the phone moved data to SIM 2"; else sysmsg="SIM 1 is back - the phone moved data to SIM 1"; fi
    last_vsim=$VSIM
  fi
  [ $((loops % 360)) -eq 359 ] && [ "${SYS_AUTO_SWITCH:-0}" = 1 ] && sys_auto_check
  # mobile traffic since the last check, and the daily 4G counter
  set -- $(mobile_bytes); cur_b=${1:-0}; cur_rx=${2:-0}
  dt=$((now - last_t)); [ $dt -lt 1 ] && dt=1
  db=$((cur_b - last_b)); [ $db -lt 0 ] && db=0
  drx=$((cur_rx - last_rx)); [ $drx -lt 0 ] && drx=0
  kbps=$((db / 1024 / dt)); last_b=$cur_b; last_rx=$cur_rx; last_t=$now
  # internet check: data coming in proves it works. Only ping when nothing came in, and not too often
  # (a ping every few seconds keeps the modem awake, which heats the phone)
  if [ $drx -ge 4096 ]; then ok=1; pf=0
  elif [ $((db - drx)) -ge 2048 ] || [ $((now - last_ping)) -ge ${PING_EVERY:-60} ]; then
    # (sending but nothing coming back = maybe no internet: check now, don't wait for PING_EVERY)
    last_ping=$now
    if net_ok; then ok=1; pf=0; else ok=0; pf=$((pf + 1)); fi
  fi
  eavg=$(( (kbps + h1 + h2 + h3 + h4 + h5) / 6 )); avg3=$(( (kbps + h1 + h2) / 3 )); fast2=0; [ $kbps -ge ${ECO_FAST_KBPS:-2500} ] && [ $h1 -ge ${ECO_FAST_KBPS:-2500} ] && fast2=1; h5=$h4; h4=$h3; h3=$h2; h2=$h1; h1=$kbps
  today=$(date +%Y%m%d)
  if [ "$today" != "$BDAY" ]; then BDAY=$today; BUDGET_B=0; save_budget; fi
  if [ "$VSIM" = 1 ] && { [ $ECO = 1 ] || [ $RAT = 4 ] || [ "$NR1" -eq 0 ]; }; then BUDGET_B=$((BUDGET_B + db)); fi
  loops=$((loops + 1)); [ $((loops % 12)) -eq 0 ] && save_budget
  printf 'wifi=0\nnr=%s\nlte=%s\nnr2=%s\nlte2=%s\nkbps=%s\nping=%s\nsim=%s\nrat=%s\neco=%s\npoor=%s\nbudget_mb=%s\nts=%s\n' \
    "$NR1" "$LTE1" "$NR2" "$LTE2" "$kbps" "$ok" "$VSIM" "$RAT" "${ECO:-0}" "${POOR:-0}" "$((BUDGET_B / 1048576))" "$now" > $D/live
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

    # 2a) after a heavy transfer pulled us back to 5G: is 5G really faster than the 4G it left?
    if [ $cmp_at -gt 0 ] && [ "$RAT" = 5 ] && [ $ECO = 0 ] && [ $now -ge $cmp_at ]; then
      cmp_at=0
      if [ "$NR1" -ne 0 ] && [ $kbps -ge $((ECO_HEAVY_KBPS / 2)) ] && [ $((avg3 * 10)) -lt $((eco_ref * 6)) ] \
         && [ "$NR1" -le $((NR_BAD + 8)) ] && [ $dwell_ok = 1 ]; then
        if set_nr $PRIMARY_SLOT off; then
          RAT=4; probing=0; backoff=$RETRY_5G_AFTER; retry_at=$((now + backoff)); last_switch=$now; ngood=0; save
          msg="5G is slower than 4G here (${avg3} vs ${eco_ref} KB/s) - back on 4G"
        fi
      else
        log "5G checked after heavy use: ${avg3} KB/s (4G was ${eco_ref}) - staying on 5G"
      fi
    fi

    # 2b) eco: light use -> 4G (saves battery); heavy use or the daily 4G allowance used up -> 5G
    if [ "$ECO_ENABLE" != 1 ] && [ $ECO = 1 ]; then
      if set_nr $PRIMARY_SLOT on; then ECO=0; light=0; last_switch=$now; save; msg="eco off - back on 5G"; fi
    fi
    if [ "$ECO_ENABLE" = 1 ] && [ "$RAT" = 5 ] && [ $probing = 0 ]; then
      if [ $ECO = 1 ]; then
        reason=""
        # screen on: a 15 s average over the limit, or two very fast checks (a big download);
        # screen off: only a much bigger 30 s load counts (background music / sync)
        heavy=0
        if screen_off; then
          [ $eavg -ge ${ECO_HEAVY_OFF_KBPS:-1500} ] && heavy=1
        else
          { [ $avg3 -ge $ECO_HEAVY_KBPS ] || [ $fast2 = 1 ]; } && heavy=1
        fi
        if [ $heavy = 1 ]; then hv=$((hv + 1)); else hv=0; fi
        [ $hv -ge $ECO_HEAVY_CHECKS ] && { reason="heavy transfer (${avg3} KB/s) - back on 5G"; log "eco exit: 15s avg ${avg3} KB/s, 30s avg ${eavg}, now ${kbps}, 4G signal ${LTE1}"; }
        if [ -z "$reason" ] && [ $kbps -ge 100 ] && [ -n "$ECO_HEAVY_APPS" ] && ! screen_off; then
          fgp=$(fg_pkg)
          case " $ECO_HEAVY_APPS " in *" $fgp "*)
            case "$fgp" in
              com.google.android.youtube) nm=YouTube;; com.netflix.mediaclient) nm=Netflix;; in.startv.hotstar) nm=Hotstar;;
              com.amazon.avod.thirdpartyclient) nm="Prime Video";; com.jio.media.ondemand) nm=JioCinema;; tv.twitch.android.app) nm=Twitch;;
              com.zhiliaoapp.musically) nm=TikTok;; com.instagram.android) nm=Instagram;; *) nm="Video";;
            esac
            reason="$nm is streaming - back on 5G";; esac
        fi
        [ $((loops % 6)) -eq 0 ] && log "eco: ${kbps} KB/s, heavy x$hv, 4G today $((BUDGET_B / 1048576)) MB"
        [ $((BUDGET_B / 1048576)) -ge $ECO_BUDGET_MB ] && reason="daily 4G limit reached - back on 5G"
        if [ -n "$reason" ] && set_nr $PRIMARY_SLOT on; then
          ECO=0; light=0; last_switch=$now; save_budget; save; msg="$reason"
          eco_hold_until=$((now + ${ECO_HOLD_SECS:-600}))
          case "$reason" in heavy*) if [ "${ECO_COMPARE:-1}" = 1 ] && [ $hv -ge 1 ]; then
            eco_ref=$avg3; [ $kbps -gt $eco_ref ] && eco_ref=$kbps
            cmp_at=$((now + ${ECO_COMPARE_SECS:-30})); fi;; esac
          hv=0
        fi
      else
        if [ $kbps -le $ECO_LIGHT_KBPS ]; then light=$((light + 1)); else light=0; fi
        lte_ok=1
        if [ "${ECO_NEED_LTE:-1}" = 1 ]; then { [ "$LTE1" -ne 0 ] && [ "$LTE1" -ge "$LTE_GOOD" ]; } || lte_ok=0; fi
        if [ $light -ge $((ECO_SCREENOFF_SECS / INTERVAL)) ] && [ "$NR1" -ne 0 ] && [ $ok = 1 ] && [ $dwell_ok = 1 ] \
           && [ $now -ge $eco_hold_until ] && [ $lte_ok = 1 ] \
           && [ $((BUDGET_B / 1048576)) -lt $ECO_BUDGET_MB ]; then
          go=0
          if [ $light -ge $((ECO_LIGHT_SECS / INTERVAL)) ]; then go=1; elif screen_off; then go=1; fi
          # a video app on screen: it just paused between bursts, stay on 5G
          if [ $go = 1 ] && [ -n "$ECO_HEAVY_APPS" ] && ! screen_off; then
            fgp=$(fg_pkg)
            case " $ECO_HEAVY_APPS " in *" $fgp "*) go=0; light=0;; esac
          fi
          if [ $go = 1 ] && set_nr $PRIMARY_SLOT off; then
            ECO=1; light=0; last_switch=$now; save; msg="light use - saving battery on 4G"; log "eco enter: ${kbps} KB/s, 5G ${NR1} dBm, 4G ${LTE1} dBm"
          fi
        fi
      fi
    fi

    # 3) both weak (no 5G to use and 4G bad, or locked on 4G and bad) -> SIM 2
    if [ $lbad -ge $DOWN_CHECKS ] && [ $ECO = 1 ]; then
      # 4G is bad while eco has it locked: let 5G in again before giving up on SIM 1
      if set_nr $PRIMARY_SLOT on; then ECO=0; light=0; lbad=0; last_switch=$now; save; msg="4G is weak - back on 5G"; fi
    elif [ $lbad -ge $DOWN_CHECKS ] && [ $dwell_ok = 1 ] && [ $probing = 0 ] && ! sys_auto_on; then
      if [ "${SIM2_DATA:-1}" != 1 ]; then
        [ $((loops % 60)) -eq 1 ] && log "SIM 1 weak, but SIM 2 is set as having no data - staying on SIM 1"
      elif [ $now -lt ${s2_block_until:-0} ]; then
        [ $((loops % 60)) -eq 1 ] && log "SIM 1 weak, SIM 2 had no internet earlier - staying on SIM 1"
      elif { [ "$LTE2" -ne 0 ] && [ "$LTE2" -gt $((LTE_BAD + 5)) ]; } || [ "$NR2" -ne 0 ]; then
        if set_data $BACKUP_SLOT; then
          SIM=2; last_switch=$now; lbad=0; rec=0; s2_verify=1; s2_fail=0; save
          msg="SIM 1 is weak - mobile data moved to SIM 2"
        fi
      fi
    fi
  else
    # just moved to SIM 2: does it actually have internet (a data pack)?
    s2_nodata=0
    if [ "${s2_verify:-0}" = 1 ]; then
      if [ $((now - last_switch)) -ge 10 ]; then
        if [ $drx -ge 4096 ] || net_ok; then s2_verify=0; log "SIM 2 has internet"
        else s2_fail=$((s2_fail + 1)); [ $s2_fail -ge 3 ] && s2_nodata=1; fi
      fi
    elif [ $pf -ge $PING_FAILS ]; then
      s2_nodata=1      # it worked before, but no internet now (pack ran out?)
    fi
    if [ $s2_nodata = 1 ]; then
      if set_data $PRIMARY_SLOT; then
        SIM=1; last_switch=$now; rec=0; lbad=0; s2_verify=0; pf=0; save
        s2_block_until=$((now + ${S2_BLOCK_MINS:-240} * 60))
        msg="SIM 2 has no internet (no data pack?) - back on SIM 1"
        log "SIM 2 blocked for ${S2_BLOCK_MINS:-240} min"
      fi
    fi
    if [ "$SIM" = 2 ]; then   # (unless we just went back above)
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
  fi

  [ -z "$msg" ] && [ -n "$sysmsg" ] && msg=$sysmsg

  # ---- symbol state
  if [ "$SIM" = 1 ]; then
    if [ $bad -ge 1 ] || [ $lbad -ge 1 ]; then POOR=1; else POOR=0; fi
  else
    if [ $ok = 0 ]; then POOR=1; else POOR=0; fi
  fi

  key="$VSIM$RAT$ECO$POOR"
  if [ -n "$msg" ] || [ "$key" != "$last_key" ] || { [ $((now - last_emit)) -ge 60 ] && ! screen_off; }; then
    emit "$msg"; last_emit=$now; last_key=$key
  fi
  # screen off: check much less often (nobody is looking, and the CPU and modem can sleep)
  if screen_off && [ $probing = 0 ] && [ $cmp_at = 0 ]; then sleep ${SCREEN_OFF_INTERVAL:-60}; else sleep $INTERVAL; fi
done
