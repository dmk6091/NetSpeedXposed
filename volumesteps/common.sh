#!/system/bin/sh
D=/data/adb/volumesteps
CFG=$D/config.conf
mkdir -p $D
if [ ! -f $CFG ]; then
cat > $CFG <<EOC
# Number of volume levels per sound type (stock Android: media 15, call 5, others 7). Reboot after changing.
MEDIA=30
CALL=15
RING=7
ALARM=7
NOTIF=7
SYSTEM=7
EOC
fi
. $CFG
