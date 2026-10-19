#!/system/bin/sh
# Runs once after boot (after the other hiding modules have set their props), then exits.
LOG=/data/adb/cleanboot.log
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
sleep 60
{
  echo "---- $(date)"
  # 1) a stock Xiaomi has no sys.oem_unlock_allowed (it doesn't use Google's OEM-unlock switch)
  if [ "$(getprop ro.oem_unlock_supported)" != 1 ] && [ -n "$(getprop sys.oem_unlock_allowed)" ]; then
    resetprop -d sys.oem_unlock_allowed && echo "removed sys.oem_unlock_allowed"
  fi
  # 2) vendor SELinux files that mention LineageOS (only init reads them, at boot: safe to hide)
  S=$(command -v ksu_susfs || echo /data/adb/ksu/bin/ksu_susfs)
  if [ -x "$S" ]; then
    for f in /vendor/etc/selinux/vendor_sepolicy.cil /vendor/etc/selinux/vendor_file_contexts; do
      [ -e "$f" ] && "$S" add_sus_path_loop "$f" >/dev/null 2>&1 && echo "hid $f"
    done
  else
    echo "ksu_susfs not found - SELinux files not hidden"
  fi
} >> $LOG 2>&1
tail -n 40 $LOG > $LOG.t 2>/dev/null && mv $LOG.t $LOG
exit 0
