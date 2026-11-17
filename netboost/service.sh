#!/system/bin/sh
# Net Boost v1.1: kernel TCP settings for mobile data. Applied after boot and again when network interfaces
# appear (rmnet / wlan are created after boot or when data is toggled). Original values are saved for uninstall.
MODDIR=${0%/*}
D=/data/adb/netboost
LOG=$D/log.txt
ORIG=$D/orig.txt
mkdir -p $D
[ -f $D/disable ] && { echo "---- $(date '+%m-%d %H:%M:%S') disabled (remove $D/disable to enable)" >> $LOG; exit 0; }
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
sleep 10
P=/proc/sys/net
: > $D/ok.cnt; : > $D/fail.cnt

# set_ file value: remember the original once, write, log the value the kernel actually kept
set_() {
  if [ ! -w "$1" ]; then echo "  $1: not available" >> $LOG; echo x >> $D/fail.cnt; return; fi
  grep -q "^$1 " $ORIG 2>/dev/null || echo "$1 $(cat $1 2>/dev/null)" >> $ORIG
  echo "$2" > "$1" 2>/dev/null
  got=$(cat "$1" 2>/dev/null)
  if [ "$got" = "$2" ] || [ "$(echo $got)" = "$(echo $2)" ]; then echo "  $1 = $got" >> $LOG; echo x >> $D/ok.cnt
  else echo "  $1: asked $2, kernel kept $got" >> $LOG; echo x >> $D/fail.cnt; fi
}

fq_all() {
  command -v tc >/dev/null 2>&1 || return
  for i in $(ls /sys/class/net); do
    case $i in lo|dummy*|ifb*|bond*|sit*|ip6*|tun*) ;; *) tc qdisc replace dev $i root fq 2>/dev/null;; esac
  done
}

apply() {
  echo "---- $(date '+%m-%d %H:%M:%S') kernel $(uname -r)" >> $LOG
  AV=$(cat $P/ipv4/tcp_available_congestion_control 2>/dev/null)
  echo "  available congestion control: $AV" >> $LOG
  CC=""
  for c in bbr bbr2 westwood cubic; do case " $AV " in *" $c "*) CC=$c; break;; esac; done
  [ -z "$CC" ] && modprobe tcp_bbr 2>/dev/null && CC=bbr
  [ -n "$CC" ] && set_ $P/ipv4/tcp_congestion_control $CC
  set_ $P/core/default_qdisc fq
  fq_all
  set_ $P/ipv4/tcp_fastopen 3
  set_ $P/ipv4/tcp_ecn 1
  set_ $P/ipv4/tcp_ecn_fallback 1
  set_ $P/ipv4/tcp_slow_start_after_idle 0
  set_ $P/ipv4/tcp_mtu_probing 1
  set_ $P/ipv4/tcp_window_scaling 1
  set_ $P/ipv4/tcp_sack 1
  set_ $P/ipv4/tcp_timestamps 1
  set_ $P/core/rmem_max 16777216
  set_ $P/core/wmem_max 16777216
  set_ $P/ipv4/tcp_rmem "4096 131072 16777216"
  set_ $P/ipv4/tcp_wmem "4096 65536 16777216"
  set_ $P/ipv4/tcp_notsent_lowat 131072
  echo "  summary: $(wc -l < $D/ok.cnt) applied, $(wc -l < $D/fail.cnt) not applied" >> $LOG
  tail -n 200 $LOG > $LOG.tmp && mv $LOG.tmp $LOG
}

apply
# watch for new interfaces (data toggled, Wi-Fi reconnect): re-queue them with fq, nothing else is re-written
last=$(ls /sys/class/net | tr '\n' ' ')
while true; do
  sleep 30
  now=$(ls /sys/class/net | tr '\n' ' ')
  if [ "$now" != "$last" ]; then
    last=$now
    fq_all
    echo "$(date '+%m-%d %H:%M:%S') interfaces changed -> fq re-applied ($now)" >> $LOG
  fi
done
