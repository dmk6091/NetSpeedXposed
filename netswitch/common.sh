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
ECO_ENABLE=0
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
if ! grep -q ECO_HEAVY_APPS $CFG; then
sed -i 's/^ECO_HEAVY_KBPS=1000\([^0-9]\)/ECO_HEAVY_KBPS=600\1/' $CFG
cat >> $CFG <<EOC

# ---- eco: video apps keep 5G while they are on screen and moving data
ECO_HEAVY_APPS="com.google.android.youtube com.netflix.mediaclient in.startv.hotstar com.amazon.avod.thirdpartyclient com.jio.media.ondemand tv.twitch.android.app com.zhiliaoapp.musically com.instagram.android"
ECO_HEAVY_CHECKS=2        # heavy traffic this many checks in a row = back to 5G
EOC
fi
. $CFG
if ! grep -q ECO_HEAVY_OFF_KBPS $CFG; then
cat >> $CFG <<EOC

# ---- eco: heavy use is judged on a 30 s average (a streaming app's buffer bursts don't count);
# with the screen off (background music/video) only a much bigger load brings 5G back
ECO_HEAVY_OFF_KBPS=3000
EOC
fi
. $CFG

if ! grep -q ECO_FAST_KBPS $CFG; then
# smarter eco: values still at the old defaults are moved to the new ones, your own edits are kept
sed -i 's/^ECO_LIGHT_SECS=60\([^0-9]\)/ECO_LIGHT_SECS=180\1/; s/^ECO_HEAVY_KBPS=600\([^0-9]\)/ECO_HEAVY_KBPS=400\1/; s/^ECO_HEAVY_OFF_KBPS=3000\([^0-9]\)/ECO_HEAVY_OFF_KBPS=1500\1/' $CFG
cat >> $CFG <<EOC

# ---- eco v2: only when it is safe, and any heavy transfer brings 5G back (not just video apps)
ECO_FAST_KBPS=2500        # two checks in a row at or above this (KB/s) = a big download: 5G at once
ECO_HOLD_SECS=600         # after a heavy use, stay out of eco this long (no flapping between 4G and 5G)
ECO_NEED_LTE=1            # only drop to 4G when the 4G signal is good (never trade good 5G for poor 4G)
ECO_COMPARE=1             # after going back to 5G for a download, measure it: if 5G is clearly slower, return to 4G
ECO_COMPARE_SECS=30       # how long 5G gets to show it is not slower
EOC
fi
if ! grep -q ECO_OFF_V19 $CFG; then
sed -i 's/^ECO_ENABLE=.*/ECO_ENABLE=0   # eco (battery saver on 4G) is off; set 1 to try it again/' $CFG
echo "# ECO_OFF_V19 (eco switched off by default in v1.9)" >> $CFG
fi
if ! grep -q LOWHEAT_V21 $CFG; then
# v2.1 low heat: values still at the old defaults move to the new ones, your own edits are kept
sed -i 's/^INTERVAL=5\([^0-9]\)/INTERVAL=15\1/; s/^PING_FAILS=6\([^0-9]\)/PING_FAILS=3\1/' $CFG
cat >> $CFG <<EOC

# ---- low heat (v2.1)
SCREEN_OFF_INTERVAL=60    # seconds between checks while the screen is off
PING_EVERY=60             # ping at most this often, and only when no data is coming in
# LOWHEAT_V21
EOC
fi
if ! grep -q SYS_AUTO_SWITCH $CFG; then
cat >> $CFG <<EOC

# ---- HyperOS / Android 13+: use the phone's own hidden "switch mobile data automatically" (v2.4)
SYS_AUTO_SWITCH=1         # 1 = the system moves data to SIM 2 when SIM 1 is bad; NetSwitch only does 5G/4G
                          # 0 = NetSwitch moves data between the SIMs itself (old way)
EOC
fi
if ! grep -q SIM2_DATA $CFG; then
cat >> $CFG <<EOC

# ---- backup SIM without a data pack (v2.3)
SIM2_DATA=1               # 0 = SIM 2 has no data pack: never move mobile data to it
S2_BLOCK_MINS=240         # SIM 2 had no internet after a switch: leave it alone this long
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

# prints: nr1 lte1 nr2 lte2 ads (RSRP dBm; 0 = not reported) in SLOT order;
# ads = the subscription that really carries data now (same dumpsys, so no extra cost)
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
    /mActiveDataSubId=/ && ads == "" { v = $0; sub(/.*mActiveDataSubId=/, "", v); sub(/[^0-9-].*/, "", v); ads = v }
    END { split(out, f, " "); print f[1] + 0, f[2] + 0, f[3] + 0, f[4] + 0, (ads == "" ? "-" : ads) }'
}

# bytes through the mobile interfaces: "rx+tx rx"
mobile_bytes() {
  sed 's/:/ /' /proc/net/dev 2>/dev/null | awk '$1 ~ /^rmnet/ { s += $2 + $10; r += $2 } END { printf "%d %d", s, r }'
}

# package of the app on screen
fg_pkg() {
  dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity=|mResumedActivity:' \
    | sed -n 's/.*ActivityRecord{[^ ]* [^ ]* \([^/ ]*\)\/.*/\1/p'
}

# screen state, read once per check (screen_check) from the backlight file: no dumpsys every time
BL=""
for f in /sys/class/backlight/*/brightness; do [ -r "$f" ] && { BL=$f; break; }; done
screen_check() {
  local v=""
  if [ -n "$BL" ] && read v < "$BL" 2>/dev/null && [ -n "$v" ]; then
    if [ "$v" = 0 ]; then SCR=off; else SCR=on; fi
  elif dumpsys power 2>/dev/null | grep -m1 -q 'mWakefulness=Asleep\|mWakefulness=Dozing'; then SCR=off
  else SCR=on; fi
}
screen_off() { [ -n "$SCR" ] || screen_check; [ "$SCR" = off ]; }

# mobile data switched off by the user (or airplane mode on)?  'settings' is a thin wrapper
# around 'cmd settings' (no new Java process), so this is cheap.  DSUB = sub that carries data.
data_off() {
  local v
  [ "$(settings get global airplane_mode_on 2>/dev/null)" = 1 ] && return 0
  if [ -n "$DSUB" ]; then
    v=$(settings get global mobile_data$DSUB 2>/dev/null)
    case "$v" in 0) return 0;; 1) return 1;; esac
  fi
  [ "$(settings get global mobile_data 2>/dev/null)" = 0 ]
}

# Wi-Fi: a cheap file read first; only ask the Wi-Fi service when the interface is up
wifi_up() {
  local s=""
  if [ -r /sys/class/net/wlan0/operstate ]; then
    read s < /sys/class/net/wlan0/operstate 2>/dev/null
    [ "$s" = up ] || return 1
  fi
  cmd wifi status 2>/dev/null | grep -q 'connected to'
}

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
  if [ "$now" = "$sub" ]; then DSUB=$sub; log "mobile data now on slot $1 (sub $sub)"; return 0; fi
  log "data switch to slot $1 (sub $sub) did not stick (default data sub = '$now')"
  return 1
}

# message "" = just the state
emit() { # msg
  local r=$RAT
  [ "$ECO" = 1 ] && r=4
  am broadcast --user 0 -a com.dmk.netswitch.STATE -p com.android.systemui \
    --es msg "$1" --ei sim "${VSIM:-$SIM}" --ei rat "$r" --ei eco "${ECO:-0}" --ei poor "$POOR" --ei nr "${NR1:-0}" --ei lte "${LTE1:-0}" >/dev/null 2>&1
  if [ -n "$1" ]; then
    log "EVENT $1"
    [ "$NOTIFY" = 1 ] && cmd notification post -t NetSwitch netswitch "$1" >/dev/null 2>&1
  fi
}

# ---- the system's own auto data switch (Settings > SIM > "Switch mobile data automatically",
# hidden on HyperOS).  Turned on for both SIMs; what it was before is kept so uninstall can put it back.
SYSAUTO_OK=0
sys_auto_on() { [ "${SYS_AUTO_SWITCH:-0}" = 1 ] && [ "$SYSAUTO_OK" = 1 ]; }
enable_sys_auto() {
  local s sub cur good=0
  for s in $PRIMARY_SLOT $BACKUP_SLOT; do
    sub=$(subid $s) || continue
    cur=$(H getautosw "$sub" | tail -n1)
    case "$cur" in true|false) ;; *) log "auto data switch not available here (slot $s: '$cur')"; continue;; esac
    [ -f $D/autosw_orig_$s ] || echo "$cur" > $D/autosw_orig_$s
    if [ "$cur" != true ]; then
      H setautosw "$sub" 1 >/dev/null
      cur=$(H getautosw "$sub" | tail -n1)
      log "auto data switch on slot $s: $cur"
    fi
    [ "$cur" = true ] && good=1
  done
  SYSAUTO_OK=$good
  [ $good = 1 ] || log "system auto data switch could not be turned on - NetSwitch moves data to SIM 2 itself"
}
restore_sys_auto() {
  local s sub o
  for s in 0 1; do
    o=$(cat $D/autosw_orig_$s 2>/dev/null) || continue
    sub=$(subid $s) || continue
    [ "$o" = false ] && H setautosw "$sub" 0 >/dev/null
  done
}
