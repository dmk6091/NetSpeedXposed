#!/system/bin/sh
# EFS / modem settings backup (Qualcomm). Run as root:  su -c sh /sdcard/Download/efs_backup.sh
# Saves the partitions that hold IMEI, NV items, carrier/radio calibration. Read-only: nothing on the phone is changed.
[ "$(id -u)" = 0 ] || { echo "Run as root: su -c sh $0"; exit 1; }
OUT=/sdcard/EFS_backup_$(date +%Y%m%d_%H%M%S)
mkdir -p "$OUT" || exit 1
BN=/dev/block/by-name
[ -d $BN ] || BN=$(ls -d /dev/block/platform/*/by-name 2>/dev/null | head -1)
n=0
for p in modemst1 modemst2 fsg fsc persist; do
  if [ -e "$BN/$p" ]; then
    dd if="$BN/$p" of="$OUT/$p.img" bs=1M 2>/dev/null && { echo "saved $p ($(du -k "$OUT/$p.img" | cut -f1) KB)"; n=$((n+1)); }
  else
    echo "no $p partition (skipped)"
  fi
done
( cd "$OUT" && sha256sum *.img > SHA256SUMS 2>/dev/null )
cat > "$OUT/RESTORE.txt" <<'R'
Restore ONLY if the radio is broken (no IMEI / no signal) after modem tinkering, from fastboot:
  fastboot flash modemst1 modemst1.img
  fastboot flash modemst2 modemst2.img
  fastboot flash fsg fsg.img
  fastboot flash fsc fsc.img
  fastboot flash persist persist.img     (only if persist was damaged)
  fastboot reboot
Keep these files off the phone too (PC / cloud): they are unique to THIS phone. Never share them (they contain your IMEI).
R
echo "$n partitions saved to $OUT"
echo "Copy that folder to your PC / cloud now."
