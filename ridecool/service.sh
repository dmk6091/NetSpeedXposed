#!/system/bin/sh
# Ride Cool: stepwise CPU / GPU / charging limits by battery temperature, like stock Xiaomi throttling.
# Cheap by design: each round is one file read (a shell builtin) and a sleep; files are only
# written when the level changes, or when something else (mi_thermald) reset our cap.
MODDIR=${0%/*}
R=${RC_ROOT:-}          # test root (empty on the phone)
D=$R/data/adb/ridecool
CFG=$D/config.conf
LOG=$D/log.txt
mkdir -p $D
if [ ! -f $CFG ]; then
cat > $CFG <<EOC
# Ride Cool settings (battery temperature in degrees C). Edit, then reboot.
T1=40          # level 1: light CPU/GPU caps
T2=42          # level 2: more caps + slower charging
T3=45          # level 3: strongest caps, charging held back
HYST=2         # cool down this much below a level before stepping back
INTERVAL=15    # seconds between checks
# CPU caps per level, % of each cluster's top speed (big = policy2 + policy5, prime = policy7)
BIG1=85;  BIG2=70;  BIG3=55
PRIME1=75; PRIME2=60; PRIME3=45
GPU=1          # 1 = also cap the GPU
CHARGE=1       # 1 = also slow charging at level 2 / 3
NOTIFY=0       # 1 = notification when the level changes
EOC
fi
. $CFG
log() { echo "$(date '+%m-%d %H:%M:%S') $*" >> $LOG; }

if [ -z "$R" ]; then
  until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
  sleep 30
fi

BAT=$R/sys/class/power_supply/battery
TEMPF=$BAT/temp
[ -r $TEMPF ] || { log "no $TEMPF - stopping"; exit 0; }
CPUF=$R/sys/devices/system/cpu/cpufreq
GPUD=$R/sys/class/kgsl/kgsl-3d0

# clusters: the slowest policy (little cores) is left alone
LITTLE=""; lmax=999999999
for p in $CPUF/policy*; do read m < $p/cpuinfo_max_freq; [ "$m" -lt "$lmax" ] && { lmax=$m; LITTLE=$p; }; done
PRIMEP=""; pmax=0
for p in $CPUF/policy*; do read m < $p/cpuinfo_max_freq; [ "$m" -gt "$pmax" ] && { pmax=$m; PRIMEP=$p; }; done

GN=0; GORIG=0
[ "$GPU" = 1 ] && [ -w $GPUD/max_pwrlevel ] && { read GN < $GPUD/num_pwrlevels; read GORIG < $GPUD/max_pwrlevel; }
CMAX=0; CORIG=0
[ "$CHARGE" = 1 ] && [ -w $BAT/charge_control_limit ] && { read CMAX < $BAT/charge_control_limit_max; read CORIG < $BAT/charge_control_limit; }
log "---- started: little=$LITTLE prime=$PRIMEP gpu levels=$GN charge limit max=$CMAX"

want_cpu() { # policy level -> kHz (0 = no cap)
  local p=$1 lv=$2 m pc
  [ "$lv" = 0 ] && { echo 0; return; }
  [ "$p" = "$LITTLE" ] && { echo 0; return; }
  read m < $p/cpuinfo_max_freq
  if [ "$p" = "$PRIMEP" ]; then eval pc=\$PRIME$lv; else eval pc=\$BIG$lv; fi
  echo $(( m * pc / 100 ))
}

apply() { # level.  On a level change (FROM != lv) set exactly; otherwise only put back a cap
           # that something else loosened (never loosen a stricter limit from the thermal service)
  local lv=$1 p w cur m g c ch=0
  [ "$FROM" != "$lv" ] && ch=1
  for p in $CPUF/policy*; do
    [ "$p" = "$LITTLE" ] && continue
    w=$(want_cpu $p $lv); read m < $p/cpuinfo_max_freq; [ "$w" = 0 ] && w=$m
    read cur < $p/scaling_max_freq
    if [ $ch = 1 ] || [ "$cur" -gt $(( w + 1000 )) ]; then [ "$cur" != "$w" ] && echo $w > $p/scaling_max_freq 2>/dev/null; fi
  done
  if [ "$GN" -gt 0 ]; then
    case $lv in 0) g=$GORIG;; 1) g=2;; 2) g=$((GN / 2));; 3) g=$((GN - 2));; esac
    [ $g -gt $((GN - 1)) ] && g=$((GN - 1)); [ $g -lt 0 ] && g=0
    read cur < $GPUD/max_pwrlevel     # higher number = slower GPU
    if [ $ch = 1 ] || [ "$cur" -lt "$g" ]; then [ "$cur" != "$g" ] && echo $g > $GPUD/max_pwrlevel 2>/dev/null; fi
  fi
  if [ "$CMAX" -gt 0 ]; then
    case $lv in 0|1) c=$CORIG;; 2) c=$((CMAX / 2));; 3) c=$CMAX;; esac
    read cur < $BAT/charge_control_limit   # higher = slower charging
    if [ $ch = 1 ] || [ "$cur" -lt "$c" ]; then [ "$cur" != "$c" ] && echo $c > $BAT/charge_control_limit 2>/dev/null; fi
  fi
}

lv=0; n=0; FROM=0
while true; do
  read t < $TEMPF; t=$(( ${t:-0} / 10 ))
  tl=0; [ $t -ge $T1 ] && tl=1; [ $t -ge $T2 ] && tl=2; [ $t -ge $T3 ] && tl=3
  new=$lv
  if [ $tl -gt $lv ]; then new=$tl                       # hotter: go straight up
  elif [ $tl -lt $lv ]; then                             # cooler: one step down, once HYST below
    eval th=\$T$lv; [ $t -le $((th - HYST)) ] && new=$((lv - 1))
  fi
  if [ $new != $lv ]; then
    log "battery ${t}C: level $lv -> $new"
    [ "$NOTIFY" = 1 ] && cmd notification post -t "Ride Cool" ridecool "Battery ${t}°C - cooling level $new" >/dev/null 2>&1
    FROM=$lv; lv=$new; apply $lv; FROM=$lv; n=0
  elif [ $lv -gt 0 ]; then
    # every ~minute, put our caps back if the thermal service reset them
    n=$((n + 1)); [ $((n % 4)) = 0 ] && apply $lv
  fi
  [ -n "$RC_ONCE" ] && exit 0
  sleep $INTERVAL
done
