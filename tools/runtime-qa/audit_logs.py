"""Bounded evidence summary; raw user-specific logs stay in artifacts."""
import argparse, collections, json, re
from pathlib import Path

def audit(evidence):
    errors=collections.Counter();fatal=collections.Counter();events=[]
    for p in [evidence/"stdout.log",evidence/"stderr.log"]:
        if not p.is_file():continue
        for line in p.open(encoding="utf-8",errors="replace"):
            if any(x in line for x in ["ERROR","SEVERE","Exception","Error:"]):
                normalized=re.sub(r"\b[0-9a-f]{8}-[0-9a-f-]{27}\b","<uuid>",line.strip())
                normalized=re.sub(r"[A-Z]:[\\/][^\s]+","<local-path>",normalized)
                normalized=re.sub(r"^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d ","",normalized)
                normalized=re.sub(r"^\[[^]]+\]", "",normalized).strip()
                errors[normalized[:400]]+=1
            for marker in ["Mixin apply failed","Mixin transformation","Caused by:","OutOfMemoryError","Crash report saved","Failed to start minecraft","Exception in thread","Could not find or load main class"]:
                if marker in line:fatal[marker]+=1
    p=evidence/"events.tsv"
    if p.is_file():
        for line in p.read_text().splitlines():
            parts=line.split("\t",2)
            if len(parts)==3:events.append({"time":parts[0],"event":parts[1],"detail":parts[2]})
    report={"exit":json.loads((evidence/"exit.json").read_text()) if (evidence/"exit.json").is_file() else None,"nativeEvents":events,"fatalMarkers":dict(fatal),"errorFamilies":[{"signature":k,"count":v} for k,v in errors.most_common(30)],"rawLogsPublished":False,"steamP2pVerdict":"NOT_RUN"}
    (evidence/"audit.json").write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))

if __name__=="__main__":
    p=argparse.ArgumentParser();p.add_argument("evidence",type=Path);audit(p.parse_args().evidence)
