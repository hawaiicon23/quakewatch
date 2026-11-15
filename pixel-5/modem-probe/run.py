#!/usr/bin/env python3
"""Record a Pixel 5's exposed modem services without changing radio settings."""
import argparse
import datetime
import glob
import json
from pathlib import Path
import shlex
import subprocess
from zoneinfo import ZoneInfo

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',help='ADB serial; otherwise use the single USB-connected device')
args=parser.parse_args()
adb=['adb','-s',args.serial] if args.serial else ['adb','-d']
root=Path(__file__).resolve().parent

def execute(arguments):
    p=subprocess.run(arguments,capture_output=True,text=True,timeout=30)
    return {'returncode':p.returncode,'stdout':p.stdout.strip(),'stderr':p.stderr.strip()}

def shell(command,root=False):
    return execute(adb+['shell', 'su -c '+shlex.quote(command) if root else command])

device=shell('getprop ro.product.device')
if device['stdout']!='redfin':
    raise SystemExit('Expected a connected, authorized Pixel 5 (redfin): '+json.dumps(device))
report={'tested_at':datetime.datetime.now(ZoneInfo('America/Los_Angeles')).isoformat(),'device':{},'checks':{}}
for key in ('ro.product.device','ro.build.version.release','gsm.version.baseband','ro.board.platform'):
    report['device'][key]=shell('getprop '+key)['stdout']
report['checks']['root']=shell('id',root=True)
if 'uid=0(root)' not in report['checks']['root']['stdout']:
    raise SystemExit('Root is not available: '+json.dumps(report['checks']['root']))
report['checks']['diag_node']=shell('ls -l /dev/diag',root=True)
report['checks']['network_interfaces']=shell('ls /sys/class/net',root=True)
report['checks']['discovery_library']=shell('ls -l /vendor/lib64/libril-qc-ltedirectdisc.so',root=True)
report['checks']['discovery_library_capability_strings']=shell(
    "strings /vendor/lib64/libril-qc-ltedirectdisc.so | grep -E 'LteDirectDiscovery_(DeviceCapability|ServiceStatus)_fields'",root=True)
for name in ('qrtr-probe','qrtr-probe-lte'):
    binary=root/'build'/name
    if not binary.is_file():
        raise SystemExit('Build the native probes on Arch first: '+str(binary))
    remote='/data/local/tmp/blx-'+name
    pushed=execute(adb+['push',str(binary),remote])
    if pushed['returncode']:
        raise SystemExit('ADB push failed: '+json.dumps(pushed))
    try:
        result=shell('chmod 700 '+remote+' && '+remote,root=True)
        rows=[]
        for line in result['stdout'].splitlines():
            try: rows.append(json.loads(line))
            except json.JSONDecodeError: pass
        report['checks'][name]={'returncode':result['returncode'],'rows':rows,'stderr':result['stderr']}
        if not rows:
            report['checks'][name]['unparsed_output']=result['stdout']
    finally:
        shell('rm -f '+remote,root=True)
report['laptop']={'wwan_sysfs':glob.glob('/sys/class/wwan/*'),'modem_nodes':glob.glob('/dev/cdc-wdm*')+glob.glob('/dev/wwan*')}
report['limitations']=[
    'Service enumeration is a local modem-interface test, not a device-to-device RF transfer test.',
    'A library on disk does not establish usable firmware or hardware sidelink support.',
    'No exposed LTE service endpoint at this moment does not prove permanent hardware inability.',
    'No radio settings, NV items, modem firmware, SIM settings or carrier provisioning were changed.'
]
reports=root.parent/'reports'
reports.mkdir(exist_ok=True)
output=reports/('modem-probe-'+datetime.datetime.now(ZoneInfo('America/Los_Angeles')).date().isoformat()+'.json')
output.write_text(json.dumps(report,indent=2)+'\n')
print('Report:',output)
for name in ('qrtr-probe','qrtr-probe-lte'):
    print(name+':',json.dumps(report['checks'][name]['rows'][-1] if report['checks'][name]['rows'] else report['checks'][name]))
