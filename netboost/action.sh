#!/system/bin/sh
# Action button (KernelSU / Magisk 27+): what is applied right now
P=/proc/sys/net
D=/data/adb/netboost
echo "Net Boost v1.1 - live values"
for f in ipv4/tcp_congestion_control core/default_qdisc ipv4/tcp_fastopen ipv4/tcp_ecn ipv4/tcp_slow_start_after_idle ipv4/tcp_mtu_probing core/rmem_max core/wmem_max ipv4/tcp_rmem ipv4/tcp_wmem; do
  echo "  $f = $(cat $P/$f 2>/dev/null || echo n/a)"
done
echo "queues:"
for i in $(ls /sys/class/net); do case $i in rmnet*|wlan*|ccmni*) echo "  $i: $(tc qdisc show dev $i 2>/dev/null | head -1)";; esac; done
echo "log tail:"
tail -n 8 $D/log.txt 2>/dev/null
echo
echo "Turn off: touch $D/disable and reboot"
