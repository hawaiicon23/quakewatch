#!/usr/bin/env python3
"""Inspect exposed Pixel 8 Pro modem interfaces and query CPIF status/version."""
import argparse
import datetime
import json
from pathlib import Path
import shlex
import subprocess
from zoneinfo import ZoneInfo

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',help='ADB serial; otherwise select the single USB device')
args=parser.parse_args()
adb=['adb','-s',args.serial] if args.serial else ['adb','-d']
here=Path(__file__).resolve().parent

def execute(argv):
    p=subprocess.run(argv,capture_output=True,text=True,timeout=30)
    return {'returncode':p.returncode,'stdout':p.stdout.strip(),'stderr':p.stderr.strip()}

def shell(command,root=False):
    return execute(adb+['shell','su -c '+shlex.quote(command) if root else command])

device=shell('getprop ro.product.device')
if device['stdout']!='husky':
    raise SystemExit('Expected Pixel 8 Pro (husky): '+json.dumps(device))
report={'tested_at':datetime.datetime.now(ZoneInfo('America/Los_Angeles')).isoformat(),'device':{},'checks':{}}
for key in ('ro.product.device','ro.build.version.release','gsm.version.baseband','ro.board.platform'):
    report['device'][key]=shell('getprop '+key)['stdout']
report['device']['kernel']=shell('uname -r')['stdout']
report['checks']['root']=shell('id',root=True)
if 'uid=0(root)' not in report['checks']['root']['stdout']:
    raise SystemExit('Root unavailable: '+json.dumps(report['checks']['root']))
commands={
    'modem_nodes':'ls -l /dev/umts* 2>/dev/null',
    'network_interfaces':'ls /sys/class/net',
    'radio_services':"service list | grep -Ei 'android.hardware.radio|radioext|modem|oemrilhook'; lshal -i 2>/dev/null | grep -Ei 'radioext|samsung.*radio|samsung.*oem'",
    'direct_feature_files':"find /vendor/bin /vendor/lib64 /vendor/etc/init /vendor/etc/vintf -maxdepth 2 -type f 2>/dev/null | grep -Ei '(sidelink|prose|pc5|cv2x|v2x|directdisc|ltedirect)'",
    'direct_feature_strings':"for f in /vendor/lib64/libsitril.so /vendor/lib64/libsit_oem.so /vendor/lib64/liboemservice.so /vendor/lib64/vendor.radio.protocol.sit.json.so /vendor/lib64/vendor.radio.protocol.sit.base.so; do if [ -f \"$f\" ]; then echo \"$f\"; strings \"$f\" | grep -Ei '(sidelink|prose|pc5|cv2x|v2x|direct.?discovery|lte.?direct)' | head -n 40; fi; done"
}
for name,cmd in commands.items(): report['checks'][name]=shell(cmd,root=True)
binary=here/'build/cpif-probe'
if not binary.is_file(): raise SystemExit('Build cpif-probe on Arch first.')
remote='/data/local/tmp/blx-cpif-probe'
pushed=execute(adb+['push',str(binary),remote])
if pushed['returncode']: raise SystemExit('Push failed: '+json.dumps(pushed))
try:
    result=shell('chmod 700 '+remote+' && timeout 5 '+remote,root=True)
    report['checks']['native_cpif_query']=result
    try: result['data']=json.loads(result['stdout'])
    except json.JSONDecodeError: pass
finally:
    shell('rm -f '+remote,root=True)
report['limitations']=[
    'CPIF status/version queries demonstrate local driver access, not sidelink support.',
    'No over-the-air direct cellular transfer was attempted or verified.',
    'A string/file search cannot prove that modem hardware lacks a feature.',
    'No radio configuration, NV items, modem firmware, SIM or carrier provisioning was changed.'
]
reports=here.parent/'reports'; reports.mkdir(exist_ok=True)
output=reports/('modem-probe-'+datetime.datetime.now(ZoneInfo('America/Los_Angeles')).date().isoformat()+'.json')
output.write_text(json.dumps(report,indent=2)+'\n')
print('Report:',output)
print('CPIF query:',json.dumps(report['checks']['native_cpif_query']))
