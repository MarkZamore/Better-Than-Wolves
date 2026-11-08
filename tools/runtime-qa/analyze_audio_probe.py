"""Compare controlled native sound emissions with process-only PCM capture."""
import argparse
import array
from datetime import datetime
import hashlib
import json
import math
from pathlib import Path
import sys
import wave


def timestamp(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp()


def statistics(samples):
    if not samples:
        return {'samples': 0, 'nonzeroSamples': 0, 'aboveOneLsbSamples': 0, 'aboveTwoLsbSamples': 0, 'peak': 0, 'rms': 0}
    return {'samples': len(samples), 'nonzeroSamples': sum(value != 0 for value in samples),
        'aboveOneLsbSamples': sum(abs(value) > 1 for value in samples),
        'aboveTwoLsbSamples': sum(abs(value) > 2 for value in samples),
        'peak': max(abs(value) for value in samples) / 32768,
        'rms': math.sqrt(sum((value / 32768) ** 2 for value in samples) / len(samples))}


def ogg_length(path):
    data = path.read_bytes(); offset = 0; last_granule = 0; rate = 0
    while offset < len(data):
        if data[offset:offset + 4] != b'OggS':
            raise ValueError('Malformed OGG page')
        granule = int.from_bytes(data[offset + 6:offset + 14], 'little')
        count = data[offset + 26]
        lacing = data[offset + 27:offset + 27 + count]
        body = offset + 27 + count; end = body + sum(lacing)
        packet = data[body:end]
        if packet.startswith(b'\x01vorbis'):
            rate = int.from_bytes(packet[12:16], 'little')
        if granule != 0xffffffffffffffff:
            last_granule = max(last_granule, granule)
        offset = end
    if not rate:
        raise ValueError('No Vorbis sample rate')
    return {'file': path.name, 'sha1': hashlib.sha1(data).hexdigest(), 'sampleRate': rate, 'durationSeconds': last_granule / rate}


def inspect(evidence, wav):
    capture = json.loads(wav.with_suffix('.json').read_text(encoding='utf-8-sig'))
    if capture.get('status') != 'CAPTURED_QA_PROCESS_ONLY' or capture.get('microphoneOpened') is not False or capture.get('desktopFallbackPerformed') is not False:
        raise ValueError('Only successful application-only captures can be compared')
    with wave.open(str(wav), 'rb') as stream:
        if stream.getsampwidth() != 2 or stream.getnchannels() != 2:
            raise ValueError('Expected stereo PCM16 from the bounded capture tool')
        rate = stream.getframerate(); channels = stream.getnchannels()
        samples = array.array('h', stream.readframes(stream.getnframes()))
        if sys.byteorder != 'little':
            samples.byteswap()
    pool = {line.split('\t')[0]: int(line.split('\t')[1]) for line in (evidence / 'audio-pool.tsv').read_text().splitlines()}
    all_pools_path = evidence / 'audio-all-pools.tsv'
    all_pools = {line.split('\t')[0]: int(line.split('\t')[1]) for line in all_pools_path.read_text().splitlines()} if all_pools_path.exists() else {}
    emissions = []; shutdown = None
    for line in (evidence / 'events.tsv').read_text().splitlines():
        parts = line.split('\t', 2)
        if len(parts) == 3 and parts[1] == 'native_audio_probe_emit':
            fields = dict(field.split('=', 1) for field in parts[2].split(';') if '=' in field)
            emissions.append((timestamp(parts[0]), fields['key']))
        elif len(parts) == 3 and parts[1] == 'native_audio_probe_shutdown':
            shutdown = timestamp(parts[0])
    if len(emissions) not in (6, 7):
        raise ValueError('Expected six sound events and optional native music event')
    if any(key == 'music' for _, key in emissions):
        pool['music'] = all_pools.get('field_2262', 0)
    began = timestamp(capture['capturedAtUtc'])
    duration = len(samples) / (rate * channels)
    windows = []
    for index, (time, key) in enumerate(emissions):
        # capturedAtUtc precedes async activation by a small amount. A 250ms
        # leading allowance includes that latency; adjacent tails may overlap.
        start = max(0, time - began - .25)
        end = min(duration, emissions[index + 1][0] - began - .25 if index + 1 < len(emissions) else duration)
        window = samples[int(start * rate) * channels:int(end * rate) * channels]
        windows.append({'key': key, 'poolVariants': pool.get(key, 0), 'windowStartSeconds': start,
            'windowEndSeconds': end, **statistics(window)})
    overall = statistics(samples)
    if overall['nonzeroSamples'] != capture['nonzeroSamples']:
        raise ValueError('Independent PCM counts differ from capture summary')
    result = {'waveSha256': hashlib.sha256(wav.read_bytes()).hexdigest(), 'pcmDurationSeconds': duration,
        'poolCounts': pool, 'allNativePoolCounts': all_pools, 'overall': overall, 'eventWindows': windows,
        'windowLimit': 'Native log time minus250ms activation allowance; windows can contain earlier tails. Not isolated per-event stems.',
        'microphoneOpened': False, 'desktopFallbackPerformed': False}
    if 'music' in pool and shutdown:
        runtime = json.loads((evidence.parent.parent / 'runtime.json').read_text())
        rain_directory = Path(runtime['assetsDirectory']) / 'sound/ambient/weather'
        lengths = [ogg_length(path) for path in sorted(rain_directory.glob('rain*.ogg'))]
        if len(lengths) != 4:
            raise ValueError('Expected all four declared rain variants')
        rain_time = next(time for time, key in emissions if key == 'ambient.weather.rain')
        music_time = next(time for time, key in emissions if key == 'music')
        start = max(music_time - began + .5, rain_time - began + max(row['durationSeconds'] for row in lengths) + .5)
        end = min(duration, shutdown - began - .1)
        if end <= start:
            raise ValueError('No music-only tail after the latest rain sample can end')
        tail = samples[int(start * rate) * channels:int(end * rate) * channels]
        result['musicOnlyTail'] = {'windowStartSeconds': start, 'windowEndSeconds': end,
            'rainVariants': lengths, 'musicPoolKeys': pool['music'], **statistics(tail),
            'scope': 'Controlled native title-screen music after rain maximum duration plus500ms; author in-world music scheduling not tested.'}
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--positive-evidence', type=Path, required=True)
    parser.add_argument('--positive-wav', type=Path, required=True)
    parser.add_argument('--negative-evidence', type=Path)
    parser.add_argument('--negative-wav', type=Path)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    report = {'positive': inspect(args.positive_evidence, args.positive_wav), 'audibleDeviceOutputClaimed': False}
    if args.negative_evidence and args.negative_wav:
        report['negative'] = inspect(args.negative_evidence, args.negative_wav)
        report['expectedAssetPathContrast'] = (
            all(value > 0 for value in report['positive']['poolCounts'].values()) and
            all(window['aboveOneLsbSamples'] > 0 for window in report['positive']['eventWindows']) and
            all(value == 0 for value in report['negative']['poolCounts'].values()) and
            report['negative']['overall']['aboveOneLsbSamples'] == 0)
        report['negativeControlLimit'] = 'Raw nonzero samples may be PCM16 conversion dither; compare abs(integer)>1LSB and exact peak/RMS.'
    args.out.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2))
