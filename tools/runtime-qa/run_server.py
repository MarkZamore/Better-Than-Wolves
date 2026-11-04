"""Real dedicated baseline in a marked private loopback-only QA fixture."""
import argparse, hashlib, json, os, shutil, subprocess, threading, time, zipfile
from pathlib import Path

def main(fixture,label,timeout):
    fixture=fixture.resolve();r=json.loads((fixture/"runtime.json").read_text())
    if not (fixture/".btw-runtime-qa.json").is_file():raise ValueError("Unmarked fixture")
    server=fixture/"server";server.mkdir(exist_ok=True);(server/"mods").mkdir(exist_ok=True)
    included=[];excluded=[]
    for p in (fixture/"game/mods").glob("*.jar"):
        with zipfile.ZipFile(p) as z:meta=json.loads(z.read("fabric.mod.json"))
        record={"file":p.name,"sha256":hashlib.sha256(p.read_bytes()).hexdigest(),"modId":meta["id"],"environment":meta.get("environment","*")}
        if meta.get("environment")=="client":excluded.append(record);continue
        included.append(record)
        dest=server/"mods"/p.name
        if not dest.exists():
            try:os.link(p,dest)
            except OSError:shutil.copyfile(p,dest)
    (server/"server.properties").write_text("server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nlevel-name=BtwQaDedicated\nmax-players=1\nmotd=Private baseline QA\n")
    evidence=fixture/"evidence"/label
    if evidence.exists():raise ValueError("Use a fresh evidence label")
    evidence.mkdir(parents=True)
    (evidence/"server-composition.json").write_text(json.dumps({"included":included,"excludedClientOnly":excluded},indent=2))
    args=[r["java"],"-Xms512M","-Xmx2048M","-Djava.awt.headless=true","-Dfabric.gameJarPath="+str(fixture/"minecraft-1.6.4-server.jar"),"-cp",os.pathsep.join(r["classpath"]+[str(fixture/"minecraft-1.6.4-server.jar")]),"net.fabricmc.loader.impl.launch.knot.KnotServer","nogui"]
    done=threading.Event();started=time.time()
    (evidence/"launch-private.json").write_text(json.dumps({"arguments":args,"privateLoopbackOnly":True,"onlineAuthenticationFixtureOnly":False,"productionRoleAssigned":False},indent=2))
    p=subprocess.Popen(args,cwd=server,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding="utf-8",errors="replace",bufsize=1)
    (evidence/"process.json").write_text(json.dumps({"pid":p.pid}))
    def pump(stream,path):
        with path.open("w",encoding="utf-8") as f:
            for line in stream:
                f.write(line);f.flush()
                if "Done (" in line:done.set()
    readers=[threading.Thread(target=pump,args=(p.stdout,evidence/"stdout.log"),daemon=True),threading.Thread(target=pump,args=(p.stderr,evidence/"stderr.log"),daemon=True)]
    for t in readers:t.start()
    while time.time()-started<timeout and p.poll() is None and not done.wait(1):pass
    if p.poll() is None:
        if done.is_set():time.sleep(10)
        p.stdin.write("stop\n");p.stdin.flush()
    try:code=p.wait(timeout=120)
    except subprocess.TimeoutExpired:
        (evidence/"timeout.json").write_text(json.dumps({"pid":p.pid,"terminated":False}));raise
    for t in readers:t.join(5)
    report={"exitCode":code,"nativeDoneObserved":done.is_set(),"nativeStopRequested":True,"elapsedSeconds":round(time.time()-started,2),"steamPassClaimed":False,"fullClientPassClaimed":False}
    (evidence/"exit.json").write_text(json.dumps(report,indent=2));print(json.dumps(report))
    return 0 if code==0 and done.is_set() else 1

if __name__=="__main__":
    p=argparse.ArgumentParser();p.add_argument("fixture",type=Path);p.add_argument("--label",required=True);p.add_argument("--timeout",type=int,default=600);a=p.parse_args();raise SystemExit(main(a.fixture,a.label,a.timeout))
