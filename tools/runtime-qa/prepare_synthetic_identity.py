"""Create only explicit synthetic identities for an owned offline QA fixture."""
import argparse
import base64
import json
from pathlib import Path

def prepare(root):
    root = root.resolve(strict=True)
    marker = json.loads((root / '.btw-runtime-qa.json').read_text(encoding='utf-8-sig'))
    if 'artifacts' not in {part.lower() for part in root.parts} or marker.get('personalWorldsCopied') is not False:
        raise ValueError('Only an independent marked fixture may receive synthetic QA identities')
    registry = root / 'qa-identities.txt'
    rows = []
    for number, label in ((1, 'QaHost'), (2, 'QaGuest')):
        name = 's' + str(number).zfill(13)
        uuid = f'00000000-0000-4000-8000-{number:012d}'
        encoded = base64.b64encode(label.encode('utf-8')).decode('ascii')
        rows.append(f'{number}|{name}|{encoded}|{uuid}')
    registry.write_text('\n'.join(rows) + '\n', encoding='utf-8', newline='\n')
    result = {'purpose': 'Synthetic offline QA only; not accounts or Steam authentication',
        'nativeName': 's0000000000001', 'localUuid': '00000000-0000-4000-8000-000000000001',
        'registry': str(registry)}
    print(json.dumps(result, indent=2))
    return result

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('fixture', type=Path)
    prepare(parser.parse_args().fixture)
