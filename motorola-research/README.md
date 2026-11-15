# Moto G6 Play modem investigation — 2026-11-14

No verified, reproducible modem transmit-power-limit bypass for this Moto G6 Play was found in the sources searched. No functioning bypass was developed. Root and a diagnostic device node alone do not demonstrate control over RF output.

The connected Motorola is the previously identified XT1922-9 / jeter, now reporting model `phh` under its Android 11 GSI. Live inventory confirmed `msm8937`, baseband `M8920_34.26.11.75R`, kernel `3.18.120-perf-gc55108dd2649`, and root access. `/dev/diag`, SMD devices, Qualcomm diagnostic/QMI libraries, and vendor diagnostic utilities exist. The visible Bluetooth module parameters were `disable_ertm` and `disable_esco`; these are not transmit-power controls.

`moto-modem-inventory.sh` is the developed read-only capability probe. It was run on the Motorola as root. It enumerates properties, node metadata, interface names and library filenames. It does not send diagnostic commands, write NV/calibration, change USB modes, or flash modem firmware. The presence of these interfaces is a starting point for research, not evidence of an unlock.

Related work: [P1sec QCSuper](https://github.com/P1sec/QCSuper) uses Qualcomm Diag to capture radio frames on compatible devices. Its documentation does not establish a Moto G6 Play power-limit bypass or arbitrary carrier-free cellular transmission. QCSuper was not installed or exercised on this phone.

Primary device documentation: [Motorola/Sporton SAR report for XT1922-6/-7/-9, FCC ID IHDT56XB1](https://files.bbystatic.com/XzXjbng1%2Fv2KeUhLoYQDgg==/f946bc48-2a7a-4cc6-8234-7ee64507de98.pdf). Regulatory power-management descriptions do not supply an unlock procedure.

Earlier BT Probe BLE tests requested +20 and rooted +21 dBm, but the controller reported 0 dBm. Those results do not establish an actual increase, and BLE advertising settings do not control Classic Bluetooth voice or cellular modem power.

A credible bypass would still require identifying the exact firmware control and RF path, understanding calibration constraints, and measuring actual output with appropriate RF equipment. No undocumented firmware or calibration patch was applied. Software changes here do not turn either phone into a VHF/UHF/FRS transmitter.
