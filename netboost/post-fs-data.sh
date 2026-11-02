#!/system/bin/sh
# fq must be the default queue before the mobile / Wi-Fi interfaces come up
echo fq > /proc/sys/net/core/default_qdisc 2>/dev/null
