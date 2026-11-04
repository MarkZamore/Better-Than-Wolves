"""Fresh public download of latest launcher EXE and every declared delta.

This pack-owned acceptance tool verifies delivery bytes, not executable/runtime
features, antivirus status or game/Steam compatibility. No credentials are used.
"""
from __future__ import annotations
import argparse, concurrent.futures, hashlib, json, re, sys
from datetime import datetime, timezone
from pathlib import Path
from verify_published import (VerificationError, check, open_public, public_json, public_asset_rows,
    asset_index, server_digest, validate_uploaded_assets, REVISION, SHA256)
from pack_pipeline import unique, write_json

REPOSITORY = 'MarkZamore/LANMinecraft'
BASE = 'https://api.github.com/repos/' + REPOSITORY
MINIMUM_RELEASE = 445
MAX_POINTER_BYTES = 64 * 1024
MAX_ASSET_BYTES = 1024 * 1024 * 1024
MAX_TOTAL_BYTES = 3 * MAX_ASSET_BYTES + MAX_POINTER_BYTES
DELTA_NAME = re.compile(r'LANMinecraft(?:\.from-([1-9][0-9]*))?\.bsdiff\Z')

def validate_manifest(document, minimum_release=MINIMUM_RELEASE):
    check(isinstance(document, dict), 'Launcher update pointer is not a JSON object')
    check(type(minimum_release) is int and minimum_release >= MINIMUM_RELEASE, 'Minimum supported release cannot be lowered below 445')
    number = document.get('releaseNumber')
    check(type(document.get('schemaVersion')) is int and document['schemaVersion'] == 2 and type(number) is int and number >= minimum_release,
          'Public launcher release is below the required compatible release')
    check(document.get('version') == str(number) and isinstance(document.get('commitSha'),str) and REVISION.fullmatch(document['commitSha']) is not None,
          'Invalid launcher version or source commit')
    check(document.get('assetName') == 'LANMinecraft.exe', 'Unknown launcher executable name')
    def pin(row):
        check(type(row.get('sizeBytes')) is int and 0 < row['sizeBytes'] <= MAX_ASSET_BYTES and
              isinstance(row.get('sha256'), str) and SHA256.fullmatch(row['sha256']), 'Invalid or unbounded launcher payload fingerprint')
        return {key: row[key] for key in ('sizeBytes', 'sha256')}
    expected = {'LANMinecraft.exe': pin(document)}
    patches = document.get('deltaPatches')
    check(isinstance(patches, list) and len(patches) <= 2, 'Invalid launcher delta list')
    bases, base_hashes = set(), set()
    for row in patches:
        check(isinstance(row, dict), 'Invalid launcher delta entry')
        name, base = row.get('assetName'), row.get('baseReleaseNumber')
        match = DELTA_NAME.fullmatch(name) if isinstance(name, str) else None
        check(match is not None and name not in expected and type(base) is int and 0 < base < number and base not in bases,
              'Unsafe, duplicate or inconsistent launcher delta name/base')
        check(match.group(1) is None or int(match.group(1)) == base, 'Delta filename differs from its base release')
        check(row.get('algorithm') == 'bsdiff' and type(row.get('algorithmVersion')) is int and row['algorithmVersion'] == 1 and
              isinstance(row.get('baseCommitSha'),str) and REVISION.fullmatch(row['baseCommitSha']) is not None and
              isinstance(row.get('baseSha256'),str) and SHA256.fullmatch(row['baseSha256']) is not None, 'Unknown delta algorithm or invalid base fingerprint')
        check(row['baseCommitSha'] != document['commitSha'] and row['baseSha256'] not in base_hashes,
              'Delta target/base commit or duplicate base hash is invalid')
        expected[name] = pin(row); bases.add(base); base_hashes.add(row['baseSha256'])
    check(sum(row['sizeBytes'] for row in expected.values()) <= MAX_TOTAL_BYTES, 'Launcher delivery exceeds the total byte budget')
    return expected

def streamed_download(asset, expected, retain_small=False):
    check(asset['size'] == expected['sizeBytes'], 'Public asset size differs before download')
    check(0 < expected['sizeBytes'] <= (MAX_POINTER_BYTES if retain_small else MAX_ASSET_BYTES), 'Asset exceeds the download bound')
    hashes = {'sha256': hashlib.sha256()}; server = server_digest(asset)
    if server and server[0] not in hashes: hashes[server[0]] = hashlib.new(server[0])
    retained = bytearray() if retain_small else None; count = 0
    try:
        with open_public(asset['browser_download_url']) as response:
            check(response.status == 200, 'A fresh full HTTP200 download is required')
            length = response.headers.get('Content-Length')
            if length is not None: check(length.isdecimal() and int(length) == expected['sizeBytes'], 'HTTP content length differs')
            while block := response.read(1024 * 1024):
                count += len(block); check(count <= expected['sizeBytes'], 'Public asset exceeds its declared byte size')
                for digest in hashes.values(): digest.update(block)
                if retained is not None: retained.extend(block)
    except VerificationError: raise
    except Exception: raise VerificationError('Public payload stream did not complete') from None
    check(count == expected['sizeBytes'], 'Public payload stream ended before its declared size')
    actual = hashes['sha256'].hexdigest()
    if expected.get('sha256') is not None: check(actual == expected['sha256'], 'Fresh downloaded SHA256 differs')
    if server: check(hashes[server[0]].hexdigest() == server[1], 'Fresh bytes differ from the GitHub server digest')
    return {'name': asset['name'], 'sizeBytes': count, 'sha256': actual, 'serverDigestVerified': server is not None}, bytes(retained) if retained is not None else None

def verify_delivery(out, minimum_release=MINIMUM_RELEASE):
    out = Path(out).resolve()
    check(not out.exists() and 'artifacts' in {part.lower() for part in out.parts}, 'Use a new output directory below artifacts')
    check(all(not parent.is_symlink() and not (hasattr(parent, 'is_junction') and parent.is_junction()) for parent in [out, *out.parents]), 'Linked output paths are refused')
    release = public_json(BASE + '/releases/tags/latest')
    check(release.get('tag_name') == 'latest' and release.get('draft') is False and release.get('prerelease') is False and
          type(release.get('id')) is int and release['id'] > 0, 'Expected the public stable launcher latest release')
    uploaded = asset_index(public_asset_rows(BASE, release['id']), 'MarkZamore', 'LANMinecraft', 'latest')
    check('update.json' in uploaded and 0 < uploaded['update.json']['size'] <= MAX_POINTER_BYTES, 'Public launcher update pointer is absent or too large')
    pointer_result, raw = streamed_download(uploaded['update.json'], {'sizeBytes': uploaded['update.json']['size']}, retain_small=True)
    manifest = json.loads(raw.decode('utf-8-sig'), object_pairs_hook=unique)
    expected = validate_manifest(manifest, minimum_release)
    check(release.get('target_commitish') == manifest['commitSha'], 'Release target differs from the update pointer commit')
    validate_uploaded_assets(uploaded, {**expected, 'update.json': pointer_result})
    results = [pointer_result]
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        futures = [pool.submit(streamed_download, uploaded[name], pin) for name, pin in expected.items()]
        for future in concurrent.futures.as_completed(futures): results.append(future.result()[0])
    final_release = public_json(BASE + '/releases/tags/latest')
    check(all(final_release.get(key) == release.get(key) for key in ('id', 'tag_name', 'target_commitish')), 'Public launcher release changed during the download')
    final_uploaded = asset_index(public_asset_rows(BASE, release['id']), 'MarkZamore', 'LANMinecraft', 'latest')
    validate_uploaded_assets(final_uploaded, {**expected, 'update.json': pointer_result})
    check(all(final_uploaded[name]['id'] == uploaded[name]['id'] for name in uploaded), 'Public launcher assets were replaced during verification')
    for result in results:
        digest = server_digest(final_uploaded[result['name']])
        if digest and digest[0] == 'sha256': check(result['sha256'] == digest[1], 'Final server digest differs from downloaded bytes')
    report = {'status': 'PASS_PUBLIC_DELIVERY_ONLY', 'repository': REPOSITORY, 'tag': 'latest', 'releaseId': release['id'],
        'releaseNumber': manifest['releaseNumber'], 'commitSha': manifest['commitSha'], 'minimumCompatibleRelease': minimum_release,
        'checkedAtUtc': datetime.now(timezone.utc).isoformat(), 'allPublishedExeAndDeltasFullyDownloaded': True,
        'downloadedAssets': sorted(results, key=lambda row: row['name']), 'downloadedBytes': sum(row['sizeBytes'] for row in results),
        'allSnapshotAssetIdsAndReleaseUnchanged': True, 'parallelDownloadsMaximum': 3,
        'runtimeAcceptanceClaimed': False, 'antivirusVerdictClaimed': False}
    out.mkdir(parents=True, exist_ok=False); (out/'update.json').write_bytes(raw); write_json(out/'verification-report.json', report)
    return report

def main():
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('--out',type=Path,required=True); parser.add_argument('--minimum-release',type=int,default=MINIMUM_RELEASE); args=parser.parse_args()
    try: report=verify_delivery(args.out,args.minimum_release)
    except Exception as failure:
        reason=str(failure).replace(str(args.out.resolve()),'<artifacts>'); reason=re.sub(r'[A-Za-z]:[\\/][^\s]+','<local-path>',reason)
        print(json.dumps({'status':'FAIL','reason':reason or type(failure).__name__}),file=sys.stderr);return 1
    print(json.dumps(report,indent=2));return 0

if __name__=='__main__':raise SystemExit(main())
