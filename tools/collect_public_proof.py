"""Collect only bounded public reports, manifest and verified library preview."""
from __future__ import annotations
import argparse, hashlib, json, os, zipfile
from pathlib import Path
from pack_pipeline import read_json, write_json, unique
from verify_published import check, NAME, REVISION, SHA256, server_digest
from verify_launcher_delivery import validate_manifest, REPOSITORY as LAUNCHER_REPOSITORY

MAX_FILE_BYTES=8*1024*1024
MAX_TOTAL_BYTES=16*1024*1024

def bind_downloaded_pins(rows,expected):
    check(isinstance(rows,list) and len(rows)==len(expected),'Downloaded asset receipt count differs')
    seen=set()
    for row in rows:
        name=row.get('name');check(isinstance(name,str) and name in expected and name not in seen,'Downloaded asset receipt names differ')
        check(type(row.get('sizeBytes')) is int and isinstance(row.get('sha256'),str) and SHA256.fullmatch(row['sha256']) and
              all(row.get(key)==expected[name][key] for key in ('sizeBytes','sha256')),'Downloaded receipt fingerprint differs from copied public pointer')
        seen.add(name)

def bind_launcher(report,raw):
    pointer=json.loads(raw.decode('utf-8-sig'),object_pairs_hook=unique);expected=validate_manifest(pointer)
    check(report.get('repository')==LAUNCHER_REPOSITORY and report.get('tag')=='latest' and
          report.get('releaseNumber')==pointer['releaseNumber'] and report.get('commitSha')==pointer['commitSha'] and
          report.get('allSnapshotAssetIdsAndReleaseUnchanged') is True,'Launcher report identity/snapshot differs from copied pointer')
    expected['update.json']={'sizeBytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()}
    bind_downloaded_pins(report.get('downloadedAssets'),expected)
    check(report.get('downloadedBytes')==sum(row['sizeBytes'] for row in expected.values()),'Launcher downloaded byte count differs')

def bind_pack(report,public,manifest,raw_manifest,metadata):
    check(manifest.get('schemaVersion')==2 and isinstance(manifest.get('assets'),list) and isinstance(manifest.get('files'),list),'Copied pack manifest is incomplete')
    expected={}
    for row in manifest['assets']+[manifest['library']]:
        name=row.get('name')
        check(isinstance(name,str) and NAME.fullmatch(name) and name not in expected and name!='pack-manifest.json' and
              type(row.get('sizeBytes')) is int and row['sizeBytes']>=0 and isinstance(row.get('sha256'),str) and SHA256.fullmatch(row['sha256']),'Invalid copied pack asset pin')
        expected[name]={key:row[key] for key in ('sizeBytes','sha256')}
    expected['pack-manifest.json']={'sizeBytes':len(raw_manifest),'sha256':hashlib.sha256(raw_manifest).hexdigest()}
    bind_downloaded_pins(report.get('downloadedAssetPins'),expected)
    check(report.get('manifestSha256')==expected['pack-manifest.json']['sha256'] and report.get('name')==manifest.get('name') and
          report.get('activeFiles')==len(manifest['files']) and report.get('payloadAssets')==len(manifest['assets']) and
          report.get('allUploadedAssetsVerified')==len(expected) and report.get('downloadedBytes')==sum(row['sizeBytes'] for row in expected.values()),'Pack report counters or manifest fingerprint differ')
    mods=metadata.get('mods');check(isinstance(mods,list) and all(isinstance(mod,dict) and isinstance(mod.get('id'),str) and type(mod.get('isLoadedMod')) is bool for mod in mods),'Copied library mod inventory is incomplete')
    check(len({mod['id'] for mod in mods})==len(mods),'Copied library has duplicate mod IDs')
    count=sum(mod['isLoadedMod'] for mod in mods)
    check(metadata.get('schemaVersion')==1 and metadata.get('revision')==manifest['revision'] and manifest['library'].get('revision')==manifest['revision'] and
          all(metadata.get(left)==manifest.get(right) for left,right in (('id','packId'),('relativePath','relativePath'),('name','name'))) and
          type(manifest.get('modCount')) is int and type(report.get('modCount')) is int and manifest['modCount']==count and report['modCount']==count and report.get('libraryMetadataRows')==len(mods),'Copied library identity/counter differs from verified manifest/report')
    check(public.get('revision')==manifest['revision'] and public.get('repository')==report.get('repository') and public.get('tag')==report.get('tag')=='pack-latest','Copied public release identity differs')
    uploaded=public.get('assets');check(isinstance(uploaded,list) and len(uploaded)==len(expected),'Copied public API asset count differs')
    seen,ids=set(),set()
    for row in uploaded:
        name=row.get('name');check(name in expected and name not in seen and row.get('state')=='uploaded' and type(row.get('id')) is int and row['id']>0 and row['id'] not in ids and row.get('size')==expected[name]['sizeBytes'],'Copied public API assets differ')
        digest=server_digest(row)
        if digest and digest[0]=='sha256':check(digest[1]==expected[name]['sha256'],'Copied public API digest differs')
        seen.add(name);ids.add(row['id'])

def collect(launcher,pack,out,revision):
    launcher,pack,out=Path(launcher).resolve(),Path(pack).resolve(),Path(out).resolve()
    check(REVISION.fullmatch(revision) is not None,'Use a full source revision')
    check(not out.exists() and 'artifacts' in {part.lower() for part in out.parts},'Use a new proof directory below artifacts')
    inputs={'launcher-report.json':launcher/'verification-report.json','launcher-update.json':launcher/'update.json','pack-report.json':pack/'verification-report.json','pack-manifest.json':pack/'pack-manifest.json','pack-public-release.json':pack/'public-release.json'}
    for path in inputs.values():check(path.is_file() and path.stat().st_size<=MAX_FILE_BYTES,'Public proof input is absent or too large')
    launcher_report=read_json(launcher/'verification-report.json');pack_report=read_json(pack/'verification-report.json');manifest=read_json(pack/'pack-manifest.json')
    check(launcher_report.get('status')=='PASS_PUBLIC_DELIVERY_ONLY' and launcher_report.get('allPublishedExeAndDeltasFullyDownloaded') is True and
          type(launcher_report.get('releaseNumber')) is int and launcher_report['releaseNumber']>=445 and
          launcher_report.get('runtimeAcceptanceClaimed') is False and launcher_report.get('antivirusVerdictClaimed') is False,'Launcher public download is not verified')
    check(pack_report.get('status')=='PASS' and pack_report.get('revision')==revision and manifest.get('revision')==revision,'Pack public download revision is not verified')
    files={}
    for name,path in inputs.items():files[name]=path.read_bytes()
    bind_launcher(launcher_report,files['launcher-update.json'])
    library=manifest.get('library',{});name=library.get('name')
    check(isinstance(name,str) and NAME.fullmatch(name),'Invalid verified library name')
    path=pack/'assets'/name
    check(path.is_file() and path.stat().st_size==library.get('sizeBytes') and path.stat().st_size<=MAX_TOTAL_BYTES,'Verified library size differs')
    check(hashlib.sha256(path.read_bytes()).hexdigest()==library.get('sha256'),'Verified library SHA256 differs')
    with zipfile.ZipFile(path) as archive:
        check(sorted(archive.namelist())==['library.json','preview.jpg'],'Only the verified metadata and preview may enter the proof')
        for member in ('library.json','preview.jpg'):
            check(archive.getinfo(member).file_size<=MAX_FILE_BYTES,'Library proof member is too large');files[member]=archive.read(member)
    bind_pack(pack_report,read_json(pack/'public-release.json'),manifest,files['pack-manifest.json'],json.loads(files['library.json'].decode('utf-8-sig'),object_pairs_hook=unique))
    check(sum(len(raw) for raw in files.values())<=MAX_TOTAL_BYTES,'Public proof bundle exceeds its size bound')
    out.mkdir(parents=True,exist_ok=False)
    for name,raw in files.items():(out/name).write_bytes(raw)
    summary={'status':'PASS_PUBLIC_DELIVERY_ONLY','sourceCommit':revision,'launcherReleaseNumber':launcher_report['releaseNumber'],'launcherCommitSha':launcher_report['commitSha'],
        'launcherDownloadedAssets':len(launcher_report['downloadedAssets']),'packDownloadedAssets':pack_report['allUploadedAssetsVerified'],'packModCount':pack_report['modCount'],
        'executionEnvironment':'github-actions' if os.environ.get('GITHUB_ACTIONS')=='true' else 'local','runtimeAcceptanceClaimed':False,'antivirusVerdictClaimed':False,
        'files':[{'name':name,'sizeBytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()} for name,raw in sorted(files.items())]}
    write_json(out/'summary.json',summary);return summary

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--launcher',type=Path,required=True);parser.add_argument('--pack',type=Path,required=True);parser.add_argument('--out',type=Path,required=True);parser.add_argument('--revision',required=True);args=parser.parse_args();print(json.dumps(collect(args.launcher,args.pack,args.out,args.revision),indent=2))
if __name__=='__main__':main()
