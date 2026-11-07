"""Prepare an isolated, SHA-checked native 1.6.4 / Legacy Fabric QA fixture.

This is test infrastructure, never pack payload. Existing installations are only
read as content-addressed caches; no personal instance or world is opened.
"""
from __future__ import annotations
import argparse, concurrent.futures, hashlib, json, os, shutil, threading, time, urllib.request, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
EPOCH = "2026-11-04"

def digest(path, algorithm="sha256"):
    h=hashlib.new(algorithm)
    with path.open("rb") as f:
        for b in iter(lambda:f.read(1024*1024),b""):h.update(b)
    return h.hexdigest()

def get(url):
    for attempt in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(url,headers={"User-Agent":"LANMinecraft-BTW-QA/1"}), timeout=90) as r:return r.read()
        except Exception:
            if attempt==2:raise
            time.sleep(2)

def fetch(url, target, sha1=None, size=None, candidates=()):
    target.parent.mkdir(parents=True, exist_ok=True)
    def valid(p):return p.is_file() and (size is None or p.stat().st_size==size) and (sha1 is None or digest(p,"sha1")==sha1)
    if valid(target):return target
    for candidate in candidates:
        if valid(candidate):
            try:os.link(candidate,target)
            except OSError:shutil.copyfile(candidate,target)
            return target
    data=get(url)
    if size is not None and len(data)!=size:raise ValueError(f"Size mismatch: {url}")
    if sha1 and hashlib.sha1(data).hexdigest()!=sha1:raise ValueError(f"SHA1 mismatch: {url}")
    temp=target.with_suffix(target.suffix+".download");temp.write_bytes(data);temp.replace(target)
    return target

def allowed(lib):
    rules=lib.get("rules")
    if rules is None:return True
    result=False
    for rule in rules:
        platform=rule.get("os",{})
        if platform.get("name","windows")=="windows":result=rule["action"]=="allow"
    return result

def artifact(lib, classifier=None):
    group,name,version=lib["name"].split(":")[:3]
    rel=f"{group.replace('.','/')}/{name}/{version}/{name}-{version}"+(f"-{classifier}" if classifier else "")+".jar"
    dl=lib.get("downloads",{}).get("classifiers",{}).get(classifier,{}) if classifier else lib.get("downloads",{}).get("artifact",{})
    url=dl.get("url",lib.get("url","https://libraries.minecraft.net/")+rel)
    sha=dl.get("sha1")
    if not sha:
        try:sha=get(url+".sha1").decode().strip().split()[0]
        except Exception:sha=None
    return rel,url,sha,dl.get("size")

def prepare(source, fixture, java, caches):
    source=source.resolve();fixture=fixture.resolve()
    if not any(x.lower()=="artifacts" for x in fixture.parts):raise ValueError("Fixture must be under artifacts")
    marker=fixture/".btw-runtime-qa.json"
    if fixture.exists() and not marker.exists():raise ValueError("Unmarked existing directory")
    fixture.mkdir(parents=True,exist_ok=True)
    marker.write_text(json.dumps({"schemaVersion":1,"purpose":"Independent native BTW runtime QA","personalWorldsCopied":False},indent=2))
    meta=fixture/"metadata";meta.mkdir(exist_ok=True)
    versions=json.loads(get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
    version=next(v for v in versions["versions"] if v["id"]=="1.6.4")
    base_bytes=get(version["url"])
    if hashlib.sha1(base_bytes).hexdigest()!=version["sha1"]:raise ValueError("Version metadata SHA1 mismatch")
    base=json.loads(base_bytes);(meta/"1.6.4.json").write_bytes(base_bytes)
    profile_bytes=get("https://meta.legacyfabric.net/v2/versions/loader/1.6.4/0.18.2/profile/json")
    profile=json.loads(profile_bytes);(meta/"legacyfabric.json").write_bytes(profile_bytes)
    libraries={":".join(l["name"].split(":")[:2]):l for l in base["libraries"] if allowed(l)}
    libraries.update({":".join(l["name"].split(":")[:2]):l for l in profile["libraries"] if allowed(l)})
    cp=[];native=fixture/"natives";native.mkdir(exist_ok=True)
    for lib in libraries.values():
        print("Library",lib["name"],flush=True)
        classifiers=[None] if not lib.get("natives") else []
        if lib.get("natives",{}).get("windows"):classifiers.append(lib["natives"]["windows"].replace("${arch}","64"))
        for classifier in classifiers:
            rel,url,sha,size=artifact(lib,classifier)
            p=fetch(url,fixture/"libraries"/rel,sha,size,[c/"libraries"/rel for c in caches])
            if classifier:
                with zipfile.ZipFile(p) as z:
                    for member in z.infolist():
                        if member.filename.lower().endswith(".dll"):
                            (native/Path(member.filename).name).write_bytes(z.read(member))
            else:cp.append(str(p))
    for side in ["client","server"]:
        d=base["downloads"][side];p=fetch(d["url"],fixture/f"minecraft-1.6.4-{side}.jar",d["sha1"],d["size"],[source/f"minecraft-1.6.4-{side}.jar"])
    game=fixture/"game";game.mkdir(exist_ok=True);(game/"mods").mkdir(exist_ok=True)
    for p in sorted((source/"mods").glob("*.jar")):
        if p.name not in ["btwce-3.1.1.jar","Nightmare-Mode-1.1.6.jar"]:continue
        target=game/"mods"/p.name
        if not target.exists():shutil.copyfile(p,target)
    ai=base["assetIndex"];index=fetch(ai["url"],fixture/"assets/indexes/legacy.json",ai["sha1"],ai["size"])
    objects=json.loads(index.read_text())["objects"]
    asset_locks = {record['hash']: threading.Lock() for record in objects.values()}
    def one(item):
        name,record=item;h=record["hash"];rel=f"objects/{h[:2]}/{h}"
        with asset_locks[h]:
            p=fetch(f"https://resources.download.minecraft.net/{h[:2]}/{h}",fixture/"assets"/rel,h,record["size"],[c/"assets"/rel for c in caches])
        v=fixture/"assets/virtual/legacy"/name;v.parent.mkdir(parents=True,exist_ok=True)
        if not v.exists():
            try:os.link(p,v)
            except OSError:shutil.copyfile(p,v)
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:list(pool.map(one,objects.items()))
    result={"schemaVersion":1,"minecraftVersion":"1.6.4","loader":"Legacy Fabric","loaderVersion":"0.18.2","java":str(java.resolve()),"fixture":str(fixture),"game":str(game),"classpath":cp,"nativeDirectory":str(native),"assetsDirectory":str(fixture/"assets/virtual/legacy"),"mainClass":profile["mainClass"],"sourceMods":[{"path":p.name,"sha256":digest(p),"sizeBytes":p.stat().st_size} for p in sorted((game/"mods").glob("*.jar"))],"libraryPins":[{"path":str(p.relative_to(fixture)),"sha256":digest(p),"sizeBytes":p.stat().st_size} for p in sorted((fixture/"libraries").rglob("*.jar"))]}
    (fixture/"runtime.json").write_text(json.dumps(result,indent=2))
    print(f"Prepared native fixture: {len(cp)} libraries, {len(objects)} assets; no personal world input")

if __name__=="__main__":
    a=argparse.ArgumentParser();a.add_argument("--source",type=Path,required=True);a.add_argument("--fixture",type=Path,required=True);a.add_argument("--java",type=Path,required=True);a.add_argument("--cache",type=Path,action="append",default=[]);p=a.parse_args();prepare(p.source,p.fixture,p.java,p.cache)
