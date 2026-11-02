#!/system/bin/sh
# Net Boost: kernel TCP settings for mobile data. Applied once after boot, logged.
D=/data/adb/netboost
LOG=$D/log.txt
mkdir -p $D
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
sleep 10
P=/proc/sys/net
set_() { [ -w "$1" ] && echo "$2" > "$1" 2>/dev/null && echo "  $1 = $(cat $1)" >> $LOG || echo "  $1: not available" >> $LOG; }
echo "---- $(date '+%m-%d %H:%M:%S') kernel $(uname -r)" >> $LOG

AV=$(cat $P/ipv4/tcp_available_congestion_control 2>/dev/null)
echo "  available congestion control: $AV" >> $LOG
CC=""
for c in bbr bbr2 westwood cubic; do case " $AV " in *" $c "*) CC=$c; break;; esac; done
[ -z "$CC" ] && modprobe tcp_bbr 2>/dev/null && CC=bbr
[ -n "$CC" ] && set_ $P/ipv4/tcp_congestion_control $CC
set_ $P/core/default_qdisc fq
# re-queue existing interfaces with fq where tc is present
if command -v tc >/dev/null 2>&1; then
  for i in $(ls /sys/class/net); do case $i in lo|dummy*|ifb*) ;; *) tc qdisc replace dev $i root fq 2>/dev/null;; esac; done
fi

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
# keep the log short
tail -n 200 $LOG > $LOG.tmp && mv $LOG.tmp $LOG
