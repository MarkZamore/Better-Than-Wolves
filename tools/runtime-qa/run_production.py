"""Execute the exact private CmlLib production context plus QA-only observation.

Never prints arguments, account IDs, tokens, registry rows or private paths.
The context must have been prepared by the launcher's LegacyRuntimeQa host.
"""
import argparse, hashlib, json, os, shutil, subprocess, time
from pathlib import Path
from run_native import build

def run(context,fixture,label,mode,world,jdk,asm,selected_png,extended,progress,name_mechanics):
    context=context.resolve();fixture=fixture.resolve();c=json.loads(context.read_text(encoding="utf-8-sig"))
    if not any(p.lower()=="artifacts" for p in fixture.parts):raise ValueError("QA root must be below artifacts")
    game=Path(c["workingDirectory"]).resolve()
    if not game.is_relative_to(fixture):raise ValueError("Production game escaped fixture")
    if not c["actualCmlLibNativeDirectoryVerified"] or not c["liveAccountRevalidated"]:raise ValueError("Incomplete production context")
    evidence=fixture/"evidence"/label
    if evidence.exists():raise ValueError("Use a new evidence label")
    evidence.mkdir(parents=True)
    (fixture/".btw-runtime-qa.json").write_text(json.dumps({"schemaVersion":1,"purpose":"Production launcher native QA fixture","personalWorldsCopied":False}))
    png=fixture/"private/selected-skin-qa.png";shutil.copyfile(selected_png,png)
    props=["-Dbtw.qa.root="+str(fixture),"-Dbtw.qa.game="+str(game),"-Dbtw.qa.evidence="+str(evidence),"-Dbtw.qa.world="+world,"-Dbtw.qa.mode="+mode,"-Dbtw.qa.expectedSkinPng="+str(png)]
    # The model comes from the exact production registry row for the bound UUID.
    args=list(c["arguments"]);skin_registry=next(x.split("=",1)[1] for x in args if x.startswith("-Dminecraft.portable.skin.registry="))
    rows=[line.split("|") for line in Path(skin_registry).read_text(encoding="utf-8-sig").splitlines()]
    selected=next(row for row in rows if len(row)==4 and row[0].lower()==c["canonicalUuid"].lower())
    props.append("-Dbtw.qa.expectedSkinModel="+selected[2])
    if extended:props += ["-Dbtw.qa.publish=true","-Dbtw.qa.dynamicSkin=true","-Dbtw.qa.discovery=true","-Dbtw.qa.rawTcpNegative=true","-Dbtw.qa.inventoryRender=true"]
    if progress:props.append("-Dbtw.qa.progress="+progress)
    if name_mechanics:props.append("-Dbtw.qa.nameMechanics=true")
    agent=build(fixture,jdk,asm);props.append("-javaagent:"+str(agent))
    main=args.index("net.fabricmc.loader.impl.launch.knot.KnotClient");args[main:main]=props
    env=os.environ.copy()
    for key in c["removeEnvironmentKeys"]:env.pop(key,None)
    for key,value in c["environment"].items():
        if value is None:env.pop(key,None)
        else:env[key]=str(value)
    # Full launch details remain private, alongside the original private context.
    (evidence/"launch-private.json").write_text(json.dumps({"java":c["java"],"arguments":args},indent=2))
    (evidence/"mod-inventory.json").write_text(json.dumps([{"file":p.name,"sizeBytes":p.stat().st_size,"sha256":hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted((game/"mods").glob("*.jar"))],indent=2))
    started=time.time()
    with (evidence/"stdout.log").open("wb") as out,(evidence/"stderr.log").open("wb") as err:
        process=subprocess.Popen([c["java"],*args],cwd=game,env=env,stdout=out,stderr=err)
        request=Path(c["bindProcessRequestPath"]);tmp=request.with_suffix(".qa-temp")
        tmp.write_text(json.dumps({"pid":process.pid}));os.replace(tmp,request)
        (evidence/"process.json").write_text(json.dumps({"pid":process.pid,"noticeBindRequested":True}))
        try:code=process.wait(timeout=900)
        except subprocess.TimeoutExpired:
            (evidence/"stop.flag").write_text("Native gated cleanup after timeout")
            raise
    result={"exitCode":code,"elapsedSeconds":round(time.time()-started,2),"productionCmlLibArgumentsUsed":True,"environmentApplied":True,"noticeBindRequested":True,"qaOnlyAdditionalAgent":True,"launcherAssemblySha256":c["launcherAssemblySha256"],"qaAgentSha256":hashlib.sha256(agent.read_bytes()).hexdigest(),"actualSteamP2pTwoAccounts":"NOT_RUN"}
    (evidence/"exit.json").write_text(json.dumps(result,indent=2));print(json.dumps(result));return code

if __name__=="__main__":
    p=argparse.ArgumentParser();p.add_argument("--context",type=Path,required=True);p.add_argument("--fixture",type=Path,required=True);p.add_argument("--label",required=True);p.add_argument("--mode",choices=["fresh","reenter"],required=True);p.add_argument("--world",default="BtwQaProduction");p.add_argument("--jdk",type=Path,required=True);p.add_argument("--asm",type=Path,required=True);p.add_argument("--selected-png",type=Path,required=True);p.add_argument("--extended",action="store_true");p.add_argument("--progress",choices=["seed","verify"]);p.add_argument("--name-mechanics",action="store_true");a=p.parse_args();raise SystemExit(run(a.context,a.fixture,a.label,a.mode,a.world,a.jdk,a.asm,a.selected_png,a.extended,a.progress,a.name_mechanics))
