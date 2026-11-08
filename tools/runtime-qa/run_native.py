"""Run a marked independent fixture; capture raw logs privately under artifacts."""
import argparse, hashlib, json, os, subprocess, tempfile, time, zipfile
from pathlib import Path

def build(fixture,jdk,asm=None):
    here=Path(__file__).resolve().parent;out=fixture/"instrumentation";out.mkdir(exist_ok=True)
    tool_home=fixture/'profile';tool_temp=fixture/'temp'
    tool_home.mkdir(exist_ok=True);tool_temp.mkdir(exist_ok=True)
    if asm is None:asm=next((fixture/"libraries/org/ow2/asm/asm").rglob("asm-*.jar"))
    jar=out/"btw-qa-agent.jar"
    with tempfile.TemporaryDirectory(prefix="qa-classes-",dir=out) as temporary:
        classes=Path(temporary).resolve()
        if not classes.is_relative_to(out.resolve()):raise ValueError("QA compilation escaped instrumentation folder")
        subprocess.run([str(jdk/"bin/javac.exe"),'-J-Duser.home='+str(tool_home),'-J-Djava.io.tmpdir='+str(tool_temp),"--release","17","-encoding","UTF-8","-g:none","-cp",str(asm),"-d",str(classes),str(here/"BtwQaAgent.java")],check=True)
        with zipfile.ZipFile(jar,"w",compression=zipfile.ZIP_STORED) as z:
            def put(name,data):
                info=zipfile.ZipInfo(name,(1980,1,1,0,0,0));info.create_system=3;info.external_attr=0o100644<<16;z.writestr(info,data)
            put("META-INF/MANIFEST.MF",b"Manifest-Version: 1.0\nPremain-Class: BtwQaAgent\n\n")
            for p in sorted(classes.glob("*.class")):put(p.name,p.read_bytes())
    return jar

def run(fixture, mode,label,jdk,timeout,properties=(),world="BtwQaBaseline",username="BtwQaHost",publish=False,context=None,extended=False,omit_assets_dir=False):
    fixture=fixture.resolve();r=json.loads((fixture/"runtime.json").read_text());evidence=fixture/"evidence"/label
    if evidence.exists():raise ValueError("Use new evidence label")
    evidence.mkdir(parents=True);agent=build(fixture,jdk)
    if context:
        ctx=json.loads(context.read_text(encoding="utf-8-sig"));username=ctx["nativeName"]
        extra=ctx["jvmProperties"]
        properties=list(properties)+([str(k)+"="+str(v) for k,v in extra.items()] if isinstance(extra,dict) else list(extra))
        properties.append("btw.qa.expectedSkinModel="+ctx.get("selectedSkinModel","classic"))
        properties.append("btw.qa.expectedSkinPng="+str((context.parent/"selected-skin.png").resolve()))
    args=[r["java"],"-Xms512M","-Xmx2048M","-Djava.library.path="+r["nativeDirectory"],"-Dorg.lwjgl.librarypath="+r["nativeDirectory"],"-Dfabric.gameJarPath="+str(fixture/"minecraft-1.6.4-client.jar"),"-Dbtw.qa.root="+str(fixture),"-Dbtw.qa.game="+r["game"],"-Dbtw.qa.evidence="+str(evidence),"-Dbtw.qa.world="+world,"-Dbtw.qa.mode="+mode]
    if publish:args.append("-Dbtw.qa.publish=true")
    if extended:args += ["-Dbtw.qa.dynamicSkin=true","-Dbtw.qa.discovery=true","-Dbtw.qa.rawTcpNegative=true","-Dbtw.qa.inventoryRender=true"]
    qa_home=fixture/'profile';qa_temp=fixture/'temp'
    for directory in (qa_home,qa_temp,qa_home/'AppData/Roaming',qa_home/'AppData/Local'):directory.mkdir(parents=True,exist_ok=True)
    args += ['-Duser.home='+str(qa_home),'-Djava.io.tmpdir='+str(qa_temp),'-XX:ErrorFile='+str(evidence/'jvm-crash-%p.log')]
    for prop in properties:
        if "=" not in prop:raise ValueError("Property needs key=value")
        args.append("-D"+prop)
    args += ["-javaagent:"+str(agent),"-cp",os.pathsep.join(r["classpath"]+[str(fixture/"minecraft-1.6.4-client.jar")]),r["mainClass"],"--username",username,"--session","0","--version","1.6.4","--gameDir",r["game"]]
    if not omit_assets_dir:args += ["--assetsDir",r["assetsDirectory"]]
    args += ["--width","854","--height","480"]
    (evidence/"launch-private.json").write_text(json.dumps({"arguments":args,"purpose":"Native lifecycle fixture; actual component hashes recorded separately"},indent=2))
    (evidence/"mod-inventory.json").write_text(json.dumps([{"file":p.name,"sizeBytes":p.stat().st_size,"sha256":hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted((Path(r["game"])/"mods").glob("*.jar"))],indent=2))
    started=time.time()
    with (evidence/"stdout.log").open("wb") as stdout,(evidence/"stderr.log").open("wb") as stderr:
        environment=os.environ.copy()
        environment.update(USERPROFILE=str(qa_home),HOME=str(qa_home),APPDATA=str(qa_home/'AppData/Roaming'),
                           LOCALAPPDATA=str(qa_home/'AppData/Local'),TEMP=str(qa_temp),TMP=str(qa_temp))
        process=subprocess.Popen(args,cwd=r["game"],stdout=stdout,stderr=stderr,env=environment)
        (evidence/"process.json").write_text(json.dumps({"pid":process.pid,"startedAtUnix":started}))
        try:code=process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            # Keep a stalled client available for diagnosis and a normal native stop.
            (evidence/"timeout.json").write_text(json.dumps({"pid":process.pid,"timeoutSeconds":timeout,"terminated":False}))
            raise
    receipt={"exitCode":code,"elapsedSeconds":round(time.time()-started,2),"qaAgentSha256":hashlib.sha256(agent.read_bytes()).hexdigest(),"mode":mode}
    (evidence/"exit.json").write_text(json.dumps(receipt,indent=2));print(json.dumps(receipt))
    return code

if __name__=="__main__":
    p=argparse.ArgumentParser();p.add_argument("fixture",type=Path);p.add_argument("--mode",choices=["fresh","reenter"],required=True);p.add_argument("--label",required=True);p.add_argument("--jdk",type=Path,required=True);p.add_argument("--timeout",type=int,default=900);p.add_argument("--property",action="append",default=[]);p.add_argument("--world",default="BtwQaBaseline");p.add_argument("--username",default="BtwQaHost");p.add_argument("--publish",action="store_true");p.add_argument("--context",type=Path);p.add_argument("--extended",action="store_true");p.add_argument("--omit-assets-dir",action="store_true",help="Negative control: reproduce native default asset path");a=p.parse_args();raise SystemExit(run(a.fixture,a.mode,a.label,a.jdk,a.timeout,a.property,a.world,a.username,a.publish,a.context,a.extended,a.omit_assets_dir))
