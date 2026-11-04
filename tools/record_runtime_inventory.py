"""Record a reviewed native QA ModList after exact source/origin checks.

The evidence is private input. The output contains mod/project data and a label,
never paths to user files, skin registries or unprocessed logs. This is not a
replacement for the runtime acceptance decision recorded in TESTING-STATUS.
"""
from __future__ import annotations
import argparse, hashlib, json, zipfile
from pathlib import Path
import pack_library
from pack_pipeline import validate_source, read_json, write_json, require, fingerprint

EXCLUDED = {'minecraft': 'game platform', 'java': 'Java runtime', 'fabricloader': 'loader platform', 'e4steam': 'launcher-managed shared transport'}

def record(pack, evidence):
    pack=Path(pack).resolve();evidence=Path(evidence).resolve();validate_source(pack)
    audit=read_json(evidence/'audit.json')
    require(audit.get('exit',{}).get('exitCode')==0 and not audit.get('fatalMarkers'), 'Evidence lacks a clean native exit/fatal verdict')
    events={row['event'] for row in audit.get('nativeEvents',[])}
    require({'native_world_joined','native_saved_returned_to_menu'} <= events,'Evidence does not show native world entry/save')
    actual={}
    for line in (evidence/'loaded-mods.txt').read_text(encoding='utf-8-sig').splitlines():
        parts=line.split('|');require(len(parts)==2 and all(parts) and parts[0] not in actual,'Invalid/duplicate actual ModList entry');actual[parts[0]]=parts[1]
    origins={}
    for line in (evidence/'loaded-mod-origins.txt').read_text(encoding='utf-8-sig').splitlines():
        parts=line.split('|',2);require(len(parts)==3 and parts[0] not in origins,'Invalid/duplicate actual origin entry');origins[parts[0]]=(parts[1],parts[2])
    observed={row['file']:row for row in read_json(evidence/'mod-inventory.json')}
    jars=sorted((pack/'mods').glob('*.jar'));source_hashes={};metadata={}
    for jar in jars:
        pin=fingerprint(jar);seen=observed.get(jar.name)
        require(seen is not None and all(seen.get(key)==pin[key] for key in ('sha256','sizeBytes')), 'Actually launched source JAR differs: '+jar.name)
        source_hashes[jar.relative_to(pack).as_posix()]=pin['sha256']
        for mod in pack_library.read_mods(jar.read_bytes(),jar.name,loader='legacyfabric'):
            old=metadata.get(mod['id'])
            if old is None or mod['_depth'] < old['_depth']: metadata[mod['id']]=mod
    provided,runtime_hashes=pack_library.read_runtime_mods(pack,'legacyfabric');metadata.update({mid:mod for mid,mod in provided.items() if mid not in metadata})
    runtime_proof={}
    for row in pack_library.runtime_sources(pack):
        require(origins.get('fabricloader')==('PATH',Path(row['cachePath']).name+'@'+row['sha256']), 'Actually loaded runtime container differs')
        with zipfile.ZipFile(pack/row['cachePath']) as container:
            for mid in row['modIds']:
                kind,origin=origins.get(mid,('', ''));require(kind=='NESTED' and origin.startswith('fabricloader!'),'Runtime library has a different actual origin')
                entry=origin.split('!',1)[1];require(entry in container.namelist(),'Actual nested origin absent from pinned container')
                raw=container.read(entry)
                require(mid in {mod['id'] for mod in pack_library.read_mods(raw,entry,loader='legacyfabric')},'Nested origin metadata differs')
                runtime_proof[mid]={'kind':'loader-embedded','containerSha256':row['sha256'],'entry':entry,'entrySha256':hashlib.sha256(raw).hexdigest()}
    retained={mid:version for mid,version in actual.items() if mid not in EXCLUDED}
    require(set(retained)==set(metadata),'Actual ModList does not exactly match source and reviewed runtime library IDs')
    require(all(version==str(metadata[mid]['version']) for mid,version in retained.items()),'Actual selected versions differ from reviewed JAR metadata')
    runtime=read_json(pack/'portable-pack.json')
    document={'schemaVersion':1,'minecraftVersion':runtime['minecraftVersion'],'loader':runtime['loader'],'sourceJars':source_hashes,'runtimeSources':runtime_hashes,
        'mods':[{'id':mid,'name':metadata[mid]['name'],'version':retained[mid]} for mid in sorted(retained)],
        'capture':{'evidenceLabel':evidence.name,'actualLoaderRecords':len(actual),'excludedPlatformAndManagedIds':{mid:EXCLUDED[mid] for mid in sorted(actual) if mid in EXCLUDED},'runtimeOriginProof':runtime_proof,'runtimeVerdictIsSeparate':True}}
    write_json(pack/'library/loaded-mods.json',document)
    return {'status':'RECORDED_ACTUAL_INVENTORY','actualLoaderRecords':len(actual),'packModCount':len(retained),'runtimeSourceCount':len(runtime_hashes),'evidenceLabel':evidence.name}

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--pack',type=Path,default=Path(__file__).resolve().parents[1]);parser.add_argument('--evidence',type=Path,required=True);args=parser.parse_args();print(json.dumps(record(args.pack,args.evidence),indent=2))

if __name__=='__main__':main()
