"""Exact-byte source preparation for Better Than Wolves; never run by the launcher."""
from __future__ import annotations
import argparse, hashlib, json, re, shutil
from pathlib import Path, PurePosixPath
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

PACK_ID = 'better-than-wolves'
ROOTS = ('mods', 'config', 'resourcepacks', 'resources', 'launcher')
PIN = re.compile(r'[0-9a-f]{64}\Z')
HOSTS = {'cdn.modrinth.com', 'launcher.mojang.com', 'piston-data.mojang.com', 'piston-meta.mojang.com', 'maven.fabricmc.net'}

def require(ok, message):
    if not ok: raise ValueError(message)

def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'Duplicate JSON property: ' + key)
        result[key] = value
    return result

def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'), object_pairs_hook=unique)

def write_json(path, document):
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((json.dumps(document, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))

def digest(path):
    with Path(path).open('rb') as source: return hashlib.file_digest(source, 'sha256').hexdigest()

def fingerprint(path):
    return {'sizeBytes': Path(path).stat().st_size, 'sha256': digest(path)}

def relative(value):
    require(isinstance(value, str) and value and '\\' not in value and ':' not in value and not PurePosixPath(value).is_absolute(), 'Unsafe path')
    require(all(part not in {'', '.', '..'} and not part.endswith((' ', '.')) and not any(ord(c) < 32 or c in '<>"|?*' for c in part)
        and part.split('.')[0].lower() not in {'con','prn','aux','nul', *(f'com{i}' for i in range(1,10)), *(f'lpt{i}' for i in range(1,10))}
        for part in value.split('/')), 'Unsafe path: ' + value)
    return value

def safe(root, value):
    root = Path(root).resolve(); candidate = root / relative(value)
    for part in [candidate, *candidate.parents]:
        require(not part.is_symlink() and not (hasattr(part, 'is_junction') and part.is_junction()), 'Linked input: ' + value)
        if part == root: break
    require(candidate.resolve().is_relative_to(root), 'Escaping input')
    return candidate

def matches(path, row):
    return Path(path).is_file() and Path(path).stat().st_size == row['sizeBytes'] and digest(path) == row['sha256']

def check_rows(pack, rows):
    seen = set()
    for row in rows:
        rel = relative(row['path'])
        require(rel.casefold() not in seen, 'Duplicate active path: ' + rel); seen.add(rel.casefold())
        require(type(row['sizeBytes']) is int and row['sizeBytes'] >= 0 and PIN.fullmatch(row['sha256']), 'Invalid fingerprint')
        require(matches(safe(pack, rel), row), 'Missing/changed locked input: ' + rel)

def inventory(pack):
    pack = Path(pack).resolve(); result = []
    for root in ROOTS:
        for path in (pack / root).rglob('*'):
            rel = path.relative_to(pack).as_posix(); safe(pack, rel)
            if path.is_file(): result.append({'path': rel, **fingerprint(path)})
    for rel in ('portable-pack.json', 'minecraft-1.6.4-client.jar'):
        path = safe(pack, rel)
        if path.is_file(): result.append({'path': rel, **fingerprint(path)})
    return sorted(result, key=lambda row: row['path'])

def pack_name(pack):
    value = read_json(Path(pack) / 'library/content.json')['name']
    require(isinstance(value, str) and value.strip() == value and 0 < len(value) <= 120 and not any(ord(c) < 32 for c in value), 'Invalid display name')
    return value

def validate_source(pack):
    pack = Path(pack).resolve(); upstream = read_json(pack / 'tools/upstream-lock.json')
    require(upstream['schemaVersion'] == 1 and upstream['packId'] == PACK_ID, 'Wrong upstream identity')
    check_rows(pack, upstream['files'])
    runtime = read_json(pack / 'portable-pack.json')
    require(runtime == {'schemaVersion': 1, 'minecraftVersion': '1.6.4', 'loader': {'type': 'legacyfabric', 'version': '0.18.2'},
        'clientJar': 'minecraft-1.6.4-client.jar', 'javaMajorVersion': 17}, 'Unexpected platform; review explicit pins first')
    content = read_json(pack / 'library/content.json')
    require(content['id'] == PACK_ID and content['relativePath'] == 'Better Than Wolves', 'Changed stable identity')
    pack_name(pack)
    catalog = read_json(pack / 'tools/catalog-entry.json')
    require(catalog['id'] == PACK_ID and catalog['relativePath'] == content['relativePath'], 'Catalog identity differs')
    # The authored gameplay jars remain exact. Technical helpers are additional files.
    import zipfile
    required = {'btw': '3.1.1', 'nightmare': '1.1.6'}
    actual = {}
    for path in sorted((pack / 'mods').glob('*.jar')):
        with zipfile.ZipFile(path) as jar:
            metadata = json.loads(jar.read('fabric.mod.json'))
            require(metadata['id'] not in actual, 'Duplicate top-level mod ID')
            actual[metadata['id']] = metadata['version']
    require(all(actual.get(key) == version for key, version in required.items()), 'Required author mod missing or changed')
    require(set(actual) <= {*required, 'btw_lan_bridge'}, 'Unexpected top-level mod')
    return upstream

def verify(pack):
    pack = Path(pack).resolve(); validate_source(pack)
    lock = read_json(pack / 'tools/distribution-lock.json')
    require(lock['schemaVersion'] == 1 and lock['packId'] == PACK_ID, 'Wrong distribution identity')
    require(lock['upstreamLockSha256'] == digest(pack / 'tools/upstream-lock.json'), 'Upstream lock receipt differs')
    check_rows(pack, lock['files'])
    require(lock['files'] == inventory(pack), 'Unexpected or missing active content')
    require((pack / 'tools/overlay.json').is_file() and (pack / 'tools/overlay-receipt.json').is_file(), 'Mandatory overlay definition/receipt missing')
    from update_upstream import verify_receipt
    verify_receipt(pack)
    for row in lock.get('supportFiles', []):
        require(matches(safe(pack, row['path']), row), 'Source support file differs: ' + row['path'])
    import pack_library
    pack_library.build_library(pack, 'source-verification')
    return {'status': 'PASS', 'files': len(lock['files']), 'authorJarsUnchanged': True, 'packId': PACK_ID}

def download(row, target):
    parsed = urlsplit(row['sourceUrl'])
    require(parsed.scheme == 'https' and parsed.hostname in HOSTS and not parsed.username and not parsed.password, 'Nonofficial source URL')
    target = Path(target); target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_name(target.name + '.part')
    try:
        with urlopen(Request(row['sourceUrl'], headers={'User-Agent': 'LANMinecraft-BTW-source/1'}), timeout=90) as source, partial.open('wb') as output:
            count = 0
            while block := source.read(1024 * 1024):
                count += len(block); require(count <= row['sizeBytes'], 'Download too large'); output.write(block)
        require(matches(partial, row), 'Source download fingerprint differs')
        for algorithm in ('sha1', 'sha512'):
            if algorithm in row:
                with partial.open('rb') as source: require(hashlib.file_digest(source, algorithm).hexdigest() == row[algorithm], 'Official ' + algorithm + ' differs')
        partial.replace(target)
    finally: partial.unlink(missing_ok=True)

def prepare(pack, cache=None, refresh_pins=False):
    require(not refresh_pins, 'Restoration never refreshes committed pins')
    pack = Path(pack).resolve(); upstream = read_json(pack / 'tools/upstream-lock.json')
    for row in upstream['files']:
        target = safe(pack, row['path'])
        if target.exists(): require(matches(target, row), 'Refusing changed author file: ' + row['path'])
        else: restore_input(row, target, cache)
    # A generated technical bridge is reconstructed from its reviewed source recipe.
    bridge = pack / 'tools/legacy-bridge/build_bridge.py'
    lock_path = pack / 'tools/distribution-lock.json'
    if lock_path.is_file():
        rows = read_json(lock_path)['files']
        helper = [row for row in rows if row['path'].startswith('mods/btw-lan-bridge-')]
        if helper and not matches(safe(pack, helper[0]['path']), helper[0]):
            require(bridge.is_file(), 'Pinned helper requires its source build recipe')
            import subprocess, sys
            subprocess.run([sys.executable, str(bridge), '--pack', str(pack)], check=True)
    prepare_runtime_sources(pack, cache)
    return verify(pack)

def restore_input(row, target, cache=None):
    if cache is None:
        download(row, target)
        return
    cache = Path(cache).resolve(); cache.mkdir(parents=True, exist_ok=True)
    cached = safe(cache, row['sha256'])
    if not matches(cached, row): download(row, cached)
    require(matches(cached, row), 'Cached source input differs')
    target = Path(target); target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(cached, target)

def prepare_runtime_sources(pack, cache=None):
    import pack_library
    for row in pack_library.runtime_sources(pack):
        target = safe(pack, row['cachePath'])
        if not matches(target, row): restore_input(row, target, cache)

def freeze(pack):
    pack = Path(pack).resolve(); validate_source(pack)
    require((pack / 'tools/overlay.json').is_file() and (pack / 'tools/overlay-receipt.json').is_file(), 'Mandatory overlay definition/receipt missing')
    from update_upstream import verify_receipt
    verify_receipt(pack)
    support = ['library/content.json', 'library/mod-pages.json', 'library/preview.jpg', 'library/README.md']
    if (pack / 'library/runtime-sources.json').exists(): support.append('library/runtime-sources.json')
    if (pack / 'library/loaded-mods.json').exists(): support.append('library/loaded-mods.json')
    document = {'schemaVersion': 1, 'packId': PACK_ID, 'upstreamLockSha256': digest(pack / 'tools/upstream-lock.json'),
        'files': inventory(pack), 'supportFiles': [{'path': rel, **fingerprint(safe(pack, rel))} for rel in support]}
    write_json(pack / 'tools/distribution-lock.json', document)
    return verify(pack)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pack', type=Path, default=Path(__file__).resolve().parents[1]); parser.add_argument('--verify-only', action='store_true'); parser.add_argument('--freeze', action='store_true'); parser.add_argument('--cache', type=Path)
    args = parser.parse_args(); print(json.dumps(freeze(args.pack) if args.freeze else verify(args.pack) if args.verify_only else prepare(args.pack, args.cache), indent=2))

if __name__ == '__main__': main()
