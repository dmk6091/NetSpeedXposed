#!/system/bin/sh
# Turns the overlay on (it ships switched off). Safe to run on every boot.
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
sleep 5
cmd overlay enable --user 0 com.netspeed.immersivenav.overlay
