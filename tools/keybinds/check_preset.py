"""Check the shipped preset and optionally compare native options.txt captures."""

import argparse
import json
from pathlib import Path

from build_preset import BINDINGS, PACK, PRESET, render


REVIEWED_JARS = {
    'minecraft-1.6.4-client.jar': 'f4513e51c766cdd1d32ca8acd0835d7e16e2851ebea3f2a3b4ce5ae696baf3ae',
    'mods/btwce-3.1.1.jar': 'ef2b16a0183d810f3c9c526758fc56a2ef3ef540346d2e1cc442f7f9f892739d',
    'mods/Nightmare-Mode-1.1.6.jar': 'be8528a5e834faac4d999cfac9038a85f144500521ba9f8e4c19c035b941d248',
    'mods/btw-lan-bridge-1.0.0.jar': '59e90f91412f3ade2afed296f75d25734585ba1d3532c58ae5fb30e3e3268162',
}


def read_options(path):
    mappings = {}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        if not line.startswith('key_'):
            continue
        name, separator, value = line.partition(':')
        if not separator or name in mappings:
            raise ValueError(f'Malformed or duplicate native binding: {name}')
        mappings[name] = int(value)
    return mappings


def check(captures):
    lock = json.loads((PACK / 'tools/distribution-lock.json').read_text(encoding='utf-8'))
    jars = {row['path']: row['sha256'] for row in lock['files'] if row['path'].endswith('.jar')}
    if jars != REVIEWED_JARS:
        raise ValueError('Runtime JARs differ from reviewed inputs. Capture and review native controls again.')
    if len(set(BINDINGS.values())) != len(BINDINGS):
        raise ValueError('The preset assigns one physical input to multiple registered bindings.')
    if not PRESET.is_file() or PRESET.read_bytes() != render():
        raise ValueError('Preset differs from the reviewed layout. Run tools/keybinds/build_preset.py.')
    for index, path in enumerate(captures, 1):
        if read_options(path) != BINDINGS:
            raise ValueError(f'Native options capture {index} differs from the reviewed layout.')
    print(f'PASS: {len(BINDINGS)} registered bindings, {len(jars)} JAR pins, {len(captures)} native options captures.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--options', type=Path, action='append', default=[])
    check(parser.parse_args().options)
