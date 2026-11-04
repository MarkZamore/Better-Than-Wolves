"""Build an independent candidate with the mandatory reviewed pack overlay.

Inputs are an explicit exact-hash upstream lock and optional already-downloaded
input directory. Active sources and locks are never changed by this command.
"""
from __future__ import annotations
import argparse, json, shutil
from pathlib import Path
from pack_pipeline import PACK_ID, read_json, write_json, require, safe, matches, fingerprint, digest, download, freeze

def recipe_inventory(pack):
    pack = Path(pack).resolve(); rows = []
    root = pack / 'tools/legacy-bridge'
    for path in sorted(root.rglob('*'), key=lambda item: item.relative_to(root).as_posix()):
        if path.is_file() and not any(part in {'artifacts', '__pycache__'} for part in path.relative_to(root).parts) and path.suffix != '.pyc':
            rows.append({'path': path.relative_to(pack).as_posix(), **fingerprint(path)})
    return rows

def validate_overlay(pack, incoming=None):
    pack = Path(pack).resolve(); overlay = read_json(pack / 'tools/overlay.json')
    require(overlay.get('schemaVersion') == 1 and overlay.get('packId') == PACK_ID, 'Wrong overlay identity')
    rows = overlay.get('sourceFiles'); require(isinstance(rows, list) and {row['path'] for row in rows} >= {'portable-pack.json', 'launcher/pack-owned.txt'}, 'Mandatory descriptor/ownership overlay absent')
    for row in rows: require(matches(safe(pack, row['path']), row), 'Reviewed overlay source changed: ' + row['path'])
    current = {row['path']: row for row in read_json(pack / 'tools/upstream-lock.json')['files']}
    guards = overlay.get('reviewedBaseFiles', [])
    require({row['path'] for row in guards} >= {'mods/btwce-3.1.1.jar', 'mods/Nightmare-Mode-1.1.6.jar'}, 'Mandatory author patch-site guards absent')
    for guarded in guards:
        row = current.get(guarded['path'])
        require(row is not None and all(row.get(key) == guarded[key] for key in ('sizeBytes', 'sha256')) and matches(safe(pack, guarded['path']), guarded), 'Current guarded author/patch-site input changed; explicit source review required: ' + guarded['path'])
    bridge = overlay.get('bridge', {})
    require(bridge.get('status') == 'reviewed-final' and bridge.get('modId') == 'btw_lan_bridge' and bridge.get('path') == 'mods/btw-lan-bridge-1.0.0.jar', 'Bridge awaits final runtime review; preparation must not drop it')
    require(isinstance(bridge.get('sha256'), str) and bridge.get('recipeFiles') == recipe_inventory(pack), 'Bridge recipe differs from reviewed source')
    require(matches(safe(pack, bridge['path']), bridge), 'Reviewed bridge output differs or is missing')
    require(any(row['path'] == bridge.get('builder') for row in bridge['recipeFiles']), 'Bridge builder lacks source fingerprint')
    if incoming is not None:
        require(incoming.get('schemaVersion') == 1 and incoming.get('packId') == PACK_ID, 'Wrong candidate upstream identity')
        inputs = {row['path']: row for row in incoming['files']}
        require(len(inputs) == len(incoming['files']), 'Duplicate candidate upstream path')
        for guarded in guards:
            row = inputs.get(guarded['path'])
            require(row is not None and all(row.get(key) == guarded[key] for key in ('sizeBytes', 'sha256')), 'Guarded author/patch-site input changed; explicit source review required: ' + guarded['path'])
        destinations = {row['path'] for row in rows} | {bridge['path']}
        require(not destinations.intersection(inputs), 'Upstream attempts to replace the mandatory overlay')
    return overlay

def write_receipt(pack):
    pack = Path(pack).resolve(); overlay = validate_overlay(pack)
    operations = [{key: row[key] for key in ('path', 'sizeBytes', 'sha256')} for row in overlay['sourceFiles'] + [overlay['bridge']]]
    document = {'schemaVersion': 1, 'packId': PACK_ID, 'upstreamLockSha256': digest(pack / 'tools/upstream-lock.json'),
        'overlaySha256': digest(pack / 'tools/overlay.json'), 'operations': sorted(operations, key=lambda row: row['path'])}
    write_json(pack / 'tools/overlay-receipt.json', document); return document

def verify_receipt(pack):
    pack = Path(pack).resolve(); overlay = validate_overlay(pack)
    receipt = read_json(pack / 'tools/overlay-receipt.json')
    expected = sorted([{key: row[key] for key in ('path', 'sizeBytes', 'sha256')} for row in overlay['sourceFiles'] + [overlay['bridge']]], key=lambda row: row['path'])
    require(receipt.get('schemaVersion') == 1 and receipt.get('packId') == PACK_ID and receipt.get('upstreamLockSha256') == digest(pack / 'tools/upstream-lock.json') and receipt.get('overlaySha256') == digest(pack / 'tools/overlay.json') and receipt.get('operations') == expected, 'Mandatory overlay receipt differs')
    return receipt

def finalize_reviewed_overlay(pack):
    """Explicit owner action after runtime acceptance; normal update never calls it."""
    pack = Path(pack).resolve(); document = read_json(pack / 'tools/overlay.json')
    bridge = document['bridge']; bridge.update(fingerprint(safe(pack, bridge['path'])))
    bridge['recipeFiles'] = recipe_inventory(pack); bridge['status'] = 'reviewed-final'
    for row in document['sourceFiles']: row.update(fingerprint(safe(pack, row['path'])))
    write_json(pack / 'tools/overlay.json', document); write_receipt(pack)
    return {'status': 'REVIEW_RECEIPT_RECORDED', 'bridge': bridge['path'], 'sha256': bridge['sha256']}

def copy_scaffold(source, out):
    def ignore(directory, names): return {name for name in names if name in {'artifacts', '__pycache__', '.git', 'bin', 'obj', 'TestResults'} or name.endswith('.pyc')}
    for name in ('tools', 'library', '.github'):
        if (source / name).exists(): shutil.copytree(source / name, out / name, ignore=ignore)
    (out / 'library/loaded-mods.json').unlink(missing_ok=True)
    for name in ('AGENTS.md', 'README.md', 'SAFETY.md', 'TESTING-STATUS.md', '.gitignore', '.gitattributes'):
        if (source / name).is_file(): shutil.copyfile(source / name, out / name)

def prepare_update(source, incoming_lock, out, inputs=None):
    source = Path(source).resolve(); out = Path(out).resolve(); incoming_lock = Path(incoming_lock).resolve()
    require(not out.exists() and out != source and 'artifacts' in {part.lower() for part in out.parts}, 'Candidate must use a new independent artifacts directory')
    require(not out.is_relative_to(source) or 'artifacts' in out.relative_to(source).parts, 'Candidate cannot replace source files')
    incoming = read_json(incoming_lock); overlay = validate_overlay(source, incoming)
    from pack_pipeline import verify
    verify_receipt(source); verify(source)
    # Validate all lock rows and overlay conflict guards before allocating candidate files.
    seen = set()
    for row in incoming['files']:
        rel = row['path']; safe(source, rel); require(rel.casefold() not in seen, 'Duplicate candidate input'); seen.add(rel.casefold())
        require(rel.startswith(('mods/', 'config/', 'resources/', 'resourcepacks/')) or rel == 'minecraft-1.6.4-client.jar', 'Upstream path is not an author gameplay input')
        from urllib.parse import urlsplit
        from pack_pipeline import HOSTS, PIN
        address = urlsplit(row['sourceUrl']); require(address.scheme == 'https' and address.hostname in HOSTS and not address.username and not address.password, 'Nonofficial candidate source URL')
        require(type(row['sizeBytes']) is int and row['sizeBytes'] >= 0 and PIN.fullmatch(row['sha256']), 'Invalid candidate fingerprint')
    out.mkdir(parents=True); copy_scaffold(source, out)
    for row in incoming['files']:
        target = safe(out, row['path']); target.parent.mkdir(parents=True, exist_ok=True)
        cached = safe(Path(inputs), row['path']) if inputs is not None else safe(source, row['path'])
        if matches(cached, row): shutil.copyfile(cached, target)
        else: download(row, target)
    for row in overlay['sourceFiles'] + [overlay['bridge']]:
        target = safe(out, row['path']); target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(safe(source, row['path']), target)
    write_json(out / 'tools/upstream-lock.json', incoming)
    from pack_pipeline import prepare_runtime_sources
    prepare_runtime_sources(out)
    write_receipt(out); result = freeze(out)
    result.update(candidateOnly=True, actualRuntimeNotPerformed=True, mandatoryOverlayOperations=len(overlay['sourceFiles']) + 1)
    write_json(out / 'artifacts/update-preparation.json', result); return result

def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--pack', type=Path, default=Path(__file__).resolve().parents[1]); parser.add_argument('--upstream-lock', type=Path); parser.add_argument('--out', type=Path); parser.add_argument('--inputs', type=Path); parser.add_argument('--record-reviewed-overlay', action='store_true')
    args = parser.parse_args()
    if args.record_reviewed_overlay: result = finalize_reviewed_overlay(args.pack)
    else:
        require(args.upstream_lock is not None and args.out is not None, 'An explicit upstream lock and new candidate output are required')
        result = prepare_update(args.pack, args.upstream_lock, args.out, args.inputs)
    print(json.dumps(result, indent=2))

if __name__ == '__main__': main()
