echo 'Kernel Bluetooth support:'
if [ -r /proc/config.gz ]; then gzip -dc /proc/config.gz | grep -E '^CONFIG_BT[= _]|^# CONFIG_BT |^CONFIG_BT_HCI|^CONFIG_BT_.*UART'; fi
echo 'HCI device class:'
ls -ld /sys/class/bluetooth /sys/class/bluetooth/* 2>/dev/null
echo 'Bluetooth related device nodes:'
ls -l /dev | grep -Ei 'bluetooth|btpower|bt_|hci|ttyHS|ttySAC|ttyMSM'
echo 'Bluetooth HAL processes:'
ps -A | grep -Ei 'bluetooth|vendor.*bt'
echo 'Vendor Bluetooth tools:'
ls /vendor/bin /vendor/bin/hw /system/bin | grep -Ei 'bluetooth|btmon|hcitool|hciconfig|hciattach|bt_diag|btconfig'
echo 'Diagnostic settings:'
getprop persist.bluetooth.btsnooplogmode
getprop ro.build.type
