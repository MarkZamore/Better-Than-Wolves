"""Read-only diagnosis of saved legacy portal blocks, including owner-authorized worlds."""
import argparse
import gzip
import io
import json
import struct
import zlib
from pathlib import Path


class Nbt:
    def __init__(self, data): self.stream = io.BytesIO(data)
    def number(self, kind):
        size = struct.calcsize(kind)
        return struct.unpack('>' + kind, self.stream.read(size))[0]
    def text(self): return self.stream.read(self.number('H')).decode('utf-8', 'replace')
    def tag(self, kind):
        if kind in range(1, 7): return self.number({1:'b', 2:'h', 3:'i', 4:'q', 5:'f', 6:'d'}[kind])
        if kind == 7: return self.stream.read(self.number('i'))
        if kind == 8: return self.text()
        if kind == 9:
            child, count = self.number('b'), self.number('i')
            return [self.tag(child) for _ in range(count)]
        if kind == 10:
            result = {}
            while (child := self.number('b')) != 0:
                name = self.text()
                result[name] = self.tag(child)
            return result
        if kind in (11, 12): return [self.number('i' if kind == 11 else 'q') for _ in range(self.number('i'))]
        raise ValueError('Unsupported NBT tag: ' + str(kind))
    def root(self):
        kind = self.number('b')
        self.text()
        return self.tag(kind)


def compressed_nbt(path): return Nbt(gzip.decompress(path.read_bytes())).root()


def main(world):
    level = compressed_nbt(world / 'level.dat')['Data']
    players = []
    if 'Player' in level: players.append(level['Player'])
    for path in (world / 'players').glob('*.dat'):
        players.append(compressed_nbt(path))
    positions = [p['Pos'] for p in players if p.get('Dimension', 0) == 0 and 'Pos' in p]
    blocks, chunks, errors = {}, 0, []
    for region in (world / 'region').glob('*.mca'):
        try:
            data = region.read_bytes()
            for index in range(1024):
                start = int.from_bytes(data[index*4:index*4+3], 'big') * 4096
                if not start: continue
                length = int.from_bytes(data[start:start+4], 'big')
                payload, compression = data[start+5:start+4+length], data[start+4]
                decoded = zlib.decompress(payload) if compression == 2 else gzip.decompress(payload)
                chunk = Nbt(decoded).root()['Level']
                cx, cz = chunk['xPos'] * 16, chunk['zPos'] * 16
                if positions and not any(abs(cx-p[0]) < 128 and abs(cz-p[2]) < 128 for p in positions): continue
                chunks += 1
                for section in chunk.get('Sections', []):
                    low, high = section['Blocks'], section.get('Add', b'')
                    for offset, value in enumerate(low):
                        if high: value |= ((high[offset//2] >> (4*(offset%2))) & 15) << 8
                        x, y, z = cx+(offset & 15), section['Y']*16+(offset >> 8), cz+((offset >> 4) & 15)
                        if value: blocks[x, y, z] = value
        except (ValueError, KeyError, EOFError, struct.error, zlib.error) as error:
            errors.append({'region': region.name, 'error': str(error)})
    obsidian = {p for p, value in blocks.items() if value == 49}
    frames = []
    seeds = obsidian | {(x-1,y,z) for x,y,z in obsidian} | {(x,y,z-1) for x,y,z in obsidian}
    for x, y, z in sorted(seeds):
        for dx, dz in ((1, 0), (0, 1)):
            at = lambda a, b: (x+a*dx, y+b, z+a*dz)
            rim = [at(a,b) for a,b in [(1,0),(2,0),(0,1),(0,2),(0,3),(3,1),(3,2),(3,3),(1,4),(2,4)]]
            if not all(p in obsidian for p in rim): continue
            inside = [{'pos': at(a,b), 'id': blocks.get(at(a,b),0)} for a in (1,2) for b in (1,2,3)]
            frames.append({'origin': (x,y,z), 'axis': 'X' if dx else 'Z', 'inside': inside})
    remaining, clusters = set(obsidian), []
    while remaining:
        pending, cluster = [remaining.pop()], []
        while pending:
            p = pending.pop()
            cluster.append(p)
            for shift in ((1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)):
                q = tuple(a+b for a,b in zip(p,shift))
                if q in remaining: remaining.remove(q); pending.append(q)
        if len(cluster) >= 4:
            lower = tuple(min(p[i] for p in cluster) for i in range(3))
            upper = tuple(max(p[i] for p in cluster) for i in range(3))
            distance = min((sum((lower[i]-p[i])**2 for i in range(3)) for p in positions), default=0)
            clusters.append({'count': len(cluster), 'min': lower, 'max': upper, 'distanceSquared': distance})
    clusters.sort(key=lambda c: c['distanceSquared'])
    print(json.dumps({'savedDayTime': level.get('DayTime'), 'savedTime': level.get('Time'),
        'portalTime': level.get('PortalTime'), 'playerPositions': positions, 'chunksRead': chunks,
        'obsidianBlocks': len(obsidian), 'portalBlocks': sum(v == 90 for v in blocks.values()),
        'smallFrames': frames, 'nearestObsidianClusters': clusters[:12], 'errors': errors,
        'scope': 'Saved disk state only; unsaved live changes may differ.'}, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('world', type=Path)
    main(parser.parse_args().world.resolve())
