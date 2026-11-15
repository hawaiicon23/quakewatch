#!/usr/bin/env python3
"""Install official, checksum-verified Android/Gradle build tools for this project."""
import pathlib, urllib.request, hashlib, zipfile, xml.etree.ElementTree as ET, subprocess, os
root=pathlib.Path.home()/'.local/share/btprobe-build';root.mkdir(parents=True,exist_ok=True)
def fetch(url,path,algorithm,digest):
    if not path.exists() or hashlib.new(algorithm,path.read_bytes()).hexdigest()!=digest:
        with urllib.request.urlopen(url) as r: path.write_bytes(r.read())
    if hashlib.new(algorithm,path.read_bytes()).hexdigest()!=digest: raise RuntimeError('Checksum failed: '+url)
    print('Verified '+path.name,flush=True)
gradle='8.13';url=f'https://downloads.gradle.org/distributions/gradle-{gradle}-bin.zip'
digest=urllib.request.urlopen(url+'.sha256').read().decode().strip().split()[0]
gz=root/f'gradle-{gradle}-bin.zip';fetch(url,gz,'sha256',digest)
if not (root/f'gradle-{gradle}/bin/gradle').exists():
    with zipfile.ZipFile(gz) as z:z.extractall(root)
    (root/f'gradle-{gradle}/bin/gradle').chmod(0o755)
sdk=root/'android-sdk';sdk.mkdir(exist_ok=True)
if not (sdk/'cmdline-tools/latest/bin/sdkmanager').exists():
    xml=ET.fromstring(urllib.request.urlopen('https://dl.google.com/android/repository/repository2-1.xml').read())
    pkg=next(e for e in xml.iter() if e.tag.endswith('remotePackage') and e.get('path')=='cmdline-tools;latest')
    arch=next(e for e in pkg.iter('archive') if e.findtext('host-os')=='linux')
    complete=arch.find('complete');rel=complete.findtext('url');check=complete.find('checksum');algo=check.get('type','sha1')
    cz=root/'commandlinetools.zip';fetch('https://dl.google.com/android/repository/'+rel,cz,algo,check.text.strip())
    with zipfile.ZipFile(cz) as z:z.extractall(root/'sdk-extract')
    import shutil
    (sdk/'cmdline-tools').mkdir(exist_ok=True)
    shutil.move(str(root/'sdk-extract/cmdline-tools'),str(sdk/'cmdline-tools/latest'))
    for p in (sdk/'cmdline-tools/latest/bin').iterdir():p.chmod(0o755)
manager=str(sdk/'cmdline-tools/latest/bin/sdkmanager')
subprocess.run([manager,'--sdk_root='+str(sdk),'--licenses'],input='y\n'*200,text=True,check=True,stdout=subprocess.DEVNULL)
subprocess.run([manager,'--sdk_root='+str(sdk),'platforms;android-36','build-tools;35.0.0'],check=True)
print('ANDROID_HOME='+str(sdk));print('GRADLE='+str(root/f'gradle-{gradle}/bin/gradle'))
