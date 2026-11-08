"""Bounded Windows-only OpenAL path/encoding matrix. No audio samples or settings.

Requires an existing owned RUN.json under APP_ROOT/artifacts/agent-runs.
Only two pinned native DLLs are copied to two small test directories. They and
the compiled diagnostic class are retained for the caller's follow-up validation
and must be removed by that run's owner after collecting the compact report.
"""
from __future__ import annotations
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

PINS = {
    'OpenAL-amd64.dll': '1e4fe04805bc7b9d2488250aaa12b01ea8230cc9b51a356bb1376e180e6b1816',
    'lwjgl-windows-amd64.dll': 'becee01761c27453aca454238b32fc44e7a30ca10ea3a59898c03259db7a4abf',
}
LWJGL_PIN = 'cce6470777f6503c8d6402f7998bd37620c06acde959cb96af2aa270ec65aff1'

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def ordinary(path):
    for candidate in (path, *path.parents):
        if candidate.exists() and candidate.lstat().st_file_attributes & 0x400:
            raise ValueError('Refusing a reparse point: '+str(candidate))

def run(app_root, output):
    if os.name != 'nt':
        raise ValueError('This reproduces the Windows LWJGL 2 native loader only')
    app_root=app_root.resolve(strict=True);output=output.resolve(strict=True)
    if not output.is_relative_to(app_root/'artifacts/agent-runs') or not (output/'RUN.json').is_file():
        raise ValueError('An owned marked artifacts/agent-runs output is required')
    ordinary(output)
    runtime=app_root/'Minecraft/Launcher'
    jdk=runtime/'JavaRuntimes/runtime/windows-x64/java-17'
    java=jdk/'bin/java.exe';javac=jdk/'bin/javac.exe'
    jar=runtime/'Shared/libraries/org/lwjgl/lwjgl/lwjgl/2.9.4+legacyfabric.17/lwjgl-2.9.4+legacyfabric.17.jar'
    native=runtime/'Shared/versions/legacyfabric-loader-0.18.2-1.6.4/natives'
    if sha(jar)!=LWJGL_PIN:
        raise ValueError('Unexpected LWJGL JAR; explicitly review pins before this exact-version test')
    classes=output/'classes';classes.mkdir(exist_ok=True)
    source=Path(__file__).with_name('ReproduceOpenALPath.java')
    subprocess.run([str(javac),'--release','17','-encoding','UTF-8','-d',str(classes),str(source)],check=True,timeout=30,creationflags=subprocess.CREATE_NO_WINDOW)
    for variant in ('ascii','Майнкруфт'):
        folder=output/variant;ordinary(folder);folder.mkdir(exist_ok=True)
        for filename,pin in PINS.items():
            origin=native/filename;target=folder/filename
            if sha(origin)!=pin:
                raise ValueError('Unexpected native input: '+filename)
            if target.exists():
                if sha(target)!=pin:raise ValueError('Existing test native differs: '+str(target))
            else:shutil.copyfile(origin,target)
            if sha(target)!=pin:raise ValueError('Native copy SHA mismatch')
    cases=[('ascii-default','ascii',None,'direct'),('ascii-utf8','ascii','UTF-8','direct'),
        ('unicode-default','Майнкруфт',None,'direct'),('unicode-utf8','Майнкруфт','UTF-8','direct'),
        ('unicode-1251','Майнкруфт','windows-1251','direct'),('unicode-utf8-preload','Майнкруфт','UTF-8','preload'),
        ('unicode-utf8-preload-child','Майнкруфт','UTF-8','preload-child')]
    results=[]
    for label,variant,encoding,mode in cases:
        folder=output/variant
        args=[str(java),'-XX:-UsePerfData','-Dorg.lwjgl.util.Debug=true','-Djava.library.path='+str(folder),'-Dorg.lwjgl.librarypath='+str(folder)]
        if encoding:args.append('-Dfile.encoding='+encoding)
        # A platform-parented URLClassLoader is independent of the system loader
        # that performs the optional pure OpenAL DLL preload. It approximates
        # loader separation; it is not a Fabric/Minecraft gameplay test.
        classpath=str(classes) if mode.endswith('-child') else str(classes)+';'+str(jar)
        args+=['-cp',classpath,'ReproduceOpenALPath',mode,str(folder/'OpenAL-amd64.dll'),str(jar)]
        process=subprocess.run(args,cwd=output,capture_output=True,timeout=20,creationflags=subprocess.CREATE_NO_WINDOW)
        (output/(label+'-private.log')).write_bytes(process.stdout+b'\n'+process.stderr)
        text=process.stdout.decode('utf-8',errors='replace')
        results.append({'case':label,'exitCode':process.returncode,'nativeLoad':'PASS' if 'AL.create=PASS' in text else 'FAIL',
            'optionalDeviceQuery':'FAIL' if 'deviceQuery=FAIL' in text else ('PASS' if 'deviceQuery=PASS' in text else 'NOT_RUN'),
            'mode':mode,'nativeDirectoryCharacters':len(str(folder)),
            'properties':[line for line in text.splitlines() if line.startswith(('file.encoding=','native.encoding=','sun.jnu.encoding=','Charset.defaultCharset=','System.load=','AL.create=','deviceQuery=','al.classLoader='))],
            'loadError126':'Could not load OpenAL library (126)' in process.stderr.decode('utf-8',errors='replace')})
    proof={'schemaVersion':1,'status':'COMPLETE_BOUNDED_MATRIX','windowsACP':ctypes.windll.kernel32.GetACP(),
        'javaSha256':sha(java),'lwjglJarSha256':sha(jar),'nativePins':PINS,'cases':results,
        'audioPlayed':False,'deviceSettingsChanged':False,'guestActualFixClaimed':False,
        'classLoaderLimit':'Independent platform-parented URLClassLoader, not a complete Knot/Minecraft run'}
    (output/'reproduction.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(proof,ensure_ascii=True,indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app-root',type=Path,required=True);parser.add_argument('--out',type=Path,required=True)
    args=parser.parse_args();run(args.app_root,args.out)
