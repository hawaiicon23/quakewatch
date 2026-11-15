#!/system/bin/sh
printf 'MODEL: '; getprop ro.product.model
printf 'DEVICE: '; getprop ro.product.device
printf 'PLATFORM: '; getprop ro.board.platform
printf 'ANDROID: '; getprop ro.build.version.release
printf 'BASEBAND: '; getprop gsm.version.baseband
uname -a
id
printf '\nDIAGNOSTIC NODES (metadata only)\n'
ls -l /dev/diag /dev/qrtr /dev/smdcntl* /dev/smd* /dev/cdc-wdm* 2>/dev/null
printf '\nKERNEL INTERFACES\n'
ls /sys/class/remoteproc /sys/bus/msm_subsys/devices /proc/net/qrtr 2>/dev/null
printf '\nVENDOR MODEM/DIAGNOSTIC LIBRARY NAMES\n'
ls /vendor/lib /vendor/lib64 /vendor/bin 2>/dev/null | /system/bin/grep -Ei 'diag|qmi|modem|smd|qrtr|rmt_storage|libbt-vendor' | head -60
printf '\nBLUETOOTH CONFIGURATION FILE NAMES\n'
ls /system/etc/bluetooth /sys/module/bluetooth/parameters 2>/dev/null
