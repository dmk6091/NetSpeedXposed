#!/system/bin/sh
# Shared by service.sh and action.sh
MOD=${MODDIR:-${0%/*}}
D=/data/adb/netswitch
CFG=$D/config.conf
LOG=$D/log.txt
mkdir -p $D
NRBIT=524288   # TelephonyManager.NETWORK_TYPE_BITMASK_NR (1 << 19)

BB=
for b in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /system/bin/busybox; do
  [ -x "$b" ] && BB=$b && break
done
awk() { if [ -n "$BB" ]; then $BB awk "$@"; else toybox awk "$@"; fi; }

if [ ! -f $CFG ]; then
cat > $CFG <<EOC
# NetSwitch settings (edit, then reboot or: pkill -f netswitch/service.sh; sh /data/adb/modules/netswitch/service.sh &)
PRIMARY_SLOT=0        # SIM 1 (unlimited 5G) = slot 0
BACKUP_SLOT=1         # SIM 2
INTERVAL=5            # seconds between checks
NR_BAD=-110           # 5G RSRP (dBm) at or below this = weak
NR_GOOD=-100          # 5G RSRP at or above this = strong again
LTE_BAD=-112          # 4G RSRP at or below this = weak
LTE_GOOD=-105         # 4G RSRP at or above this = good again
DOWN_CHECKS=3         # weak this many checks in a row before switching down
UP_CHECKS=6           # good this many checks in a row before switching back up
MIN_DWELL=45          # seconds to stay after any switch
RETRY_5G_AFTER=120    # seconds on 4G before trying 5G again (doubles up to 600 when 5G keeps failing)
PROBE_SECS=30         # how long a 5G retry is given to prove itself
PING_HOST=1.1.1.1
PAUSE_ON_WIFI=1       # do nothing while Wi-Fi is connected
NOTIFY=0              # 1 = also post a notification for every switch
EOC
fi
. $CFG
if ! grep -q ECO_ENABLE $CFG; then
cat >> $CFG <<EOC

# ---- eco mode: light use -> 4G (saves battery), heavy use -> 5G
ECO_ENABLE=1
ECO_LIGHT_KBPS=150        # mobile traffic at or below this (KB/s) counts as light
ECO_LIGHT_SECS=60         # light this long before going to 4G
ECO_SCREENOFF_SECS=20     # the same, but with the screen off
ECO_HEAVY_KBPS=1000       # at or above this (KB/s) = heavy: back to 5G at once
ECO_BUDGET_MB=1500        # daily 4G data allowance; eco stops (stays on 5G) once this is used
EOC
fi
. $CFG
if ! grep -q PING_FAILS $CFG; then
cat >> $CFG <<EOC

# ---- safety
PING_FAILS=6              # no internet for this many checks in a row (x INTERVAL s) counts as a bad connection
S2_RETRY_AFTER=600        # on SIM 2: seconds before trying SIM 1 again if it isn't known to be weak
EOC
fi
. $CFG

log() {
  echo "$(date '+%m-%d %H:%M:%S') $*" >> $LOG
  [ "$(stat -c %s $LOG 2>/dev/null || echo 0)" -gt 200000 ] && { tail -n 300 $LOG > $LOG.t; mv $LOG.t $LOG; }
}

H() { app_process -Djava.class.path=$MOD/helper.jar /system/bin NetHelper "$@" 2>>$LOG; }

subid() { # slot -> subscription id
  local v
  v=$(H subid "$1" | tail -n1)
  case "$v" in ''|*[!0-9-]*) echo ""; return 1;; esac
  echo "$v"
}

# prints: nr1 lte1 nr2 lte2 (RSRP dBm; 0 = not reported) in SLOT order
read_sig() {
  dumpsys telephony.registry 2>/dev/null | awk '
    /^ *mSignalStrength=/ && cnt < 4 {
      n = split($0, seg, "SignalStrength:")
      for (i = 2; i <= n && cnt < 4; i++) {
        s = seg[i]; lte = 0; nr = 0
        k = index(s, "mNr=")
        l = (k ? substr(s, 1, k - 1) : s)
        r = (k ? substr(s, k) : "")
        # LTE is written "rsrp=-78", NR is written "ssRsrp = -110" (spaces around the =)
        if (match(l, /rsrp *= *-?[0-9]+/)) { t = substr(l, RSTART, RLENGTH); gsub(/[^-0-9]/, "", t); lte = t + 0 }
        if (match(r, /ssRsrp *= *-?[0-9]+/)) { t = substr(r, RSTART, RLENGTH); gsub(/[^-0-9]/, "", t); nr = t + 0 }
        else if (match(r, /csiRsrp *= *-?[0-9]+/)) { t = substr(r, RSTART, RLENGTH); gsub(/[^-0-9]/, "", t); nr = t + 0 }
        if (lte > 0 || lte < -200) lte = 0
        if (nr > 0 || nr < -200) nr = 0
        out = out nr " " lte " "
        cnt++
      }
    }
    END { print out }'
}

# bytes moved through the mobile interfaces (rx + tx)
mobile_bytes() {
  sed 's/:/ /' /proc/net/dev 2>/dev/null | awk '$1 ~ /^rmnet/ { s += $2 + $10 } END { printf "%d", s }'
}

screen_off() { dumpsys power 2>/dev/null | grep -m1 -q 'mWakefulness=Asleep\|mWakefulness=Dozing'; }

wifi_up() { cmd wifi status 2>/dev/null | grep -q 'connected to'; }

net_ok() { # a ping through any mobile interface
  local i
  for i in $(ip -4 -o addr show 2>/dev/null | awk '$2 ~ /^rmnet/ {print $2}' | sort -u); do
    ping -c 1 -W 2 -I "$i" $PING_HOST >/dev/null 2>&1 && return 0
    ping -c 1 -W 2 -I "$i" 8.8.8.8 >/dev/null 2>&1 && return 0
  done
  return 1
}

# 5G on / off for a slot (keeps every other allowed network type as it was)
set_nr() { # slot on|off
  local sub cur orig new
  [ "$(date +%s)" -lt "${nr_fail_until:-0}" ] && return 1     # cooling down after a failed change
  sub=$(subid "$1") || { log "no subscription id for slot $1"; return 1; }
  cur=$(H getnt "$sub" | tail -n1)
  case "$cur" in ''|*[!0-9]*) log "can't read network types of slot $1 (sub $sub): '$cur'"; return 1;; esac
  if [ "$2" = off ]; then
    [ -f $D/orig_$1 ] || echo "$cur" > $D/orig_$1
    new=$(( cur & ~NRBIT ))
  else
    orig=$(cat $D/orig_$1 2>/dev/null)
    if [ -n "$orig" ] && [ $(( orig & NRBIT )) -ne 0 ]; then new=$orig; else new=$(( cur | NRBIT )); fi
  fi
  [ "$new" = "$cur" ] && return 0
  H setnt "$sub" "$new" >/dev/null || { nr_fail_until=$(( $(date +%s) + 300 )); log "network type change failed - pausing 5G switching for 5 min"; return 1; }
  log "slot $1 network types $cur -> $new (5G $2)"
  return 0
}

set_data() { # slot
  local sub now
  sub=$(subid "$1") || { log "no subscription id for slot $1"; return 1; }
  H setdata "$sub" >/dev/null
  sleep 2
  now=$(H getdata | tail -n1)
  if [ "$now" = "$sub" ]; then log "mobile data now on slot $1 (sub $sub)"; return 0; fi
  log "data switch to slot $1 (sub $sub) did not stick (default data sub = '$now')"
  return 1
}

# message "" = just the state
emit() { # msg
  local r=$RAT
  [ "$ECO" = 1 ] && r=4
  am broadcast --user 0 -a com.dmk.netswitch.STATE -p com.android.systemui \
    --es msg "$1" --ei sim "$SIM" --ei rat "$r" --ei eco "${ECO:-0}" --ei poor "$POOR" --ei nr "${NR1:-0}" --ei lte "${LTE1:-0}" >/dev/null 2>&1
  if [ -n "$1" ]; then
    log "EVENT $1"
    [ "$NOTIFY" = 1 ] && cmd notification post -t NetSwitch netswitch "$1" >/dev/null 2>&1
  fi
}
