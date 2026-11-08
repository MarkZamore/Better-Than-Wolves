"""Bounded native title-screen sound probe plus application-only WASAPI capture."""
import argparse
import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time


def ordinary_ancestry(path):
    for candidate in (path, *path.parents):
        if candidate.exists() and getattr(candidate.lstat(), 'st_file_attributes', 0) & 0x400:
            raise ValueError('QA path ancestry contains a reparse point')


def fixture_root(path):
    root = path.resolve(strict=True)
    ordinary_ancestry(root)
    ordinary_ancestry(root / '.btw-runtime-qa.json')
    ordinary_ancestry(root / 'evidence')
    marker = json.loads((root / '.btw-runtime-qa.json').read_text(encoding='utf-8-sig'))
    if marker.get('personalWorldsCopied') is not False or 'artifacts' not in {part.lower() for part in root.parts}:
        raise ValueError('Only a marked independent artifacts fixture is allowed')
    runtime = json.loads((root / 'runtime.json').read_text())
    game = Path(runtime['game']).resolve(strict=True)
    if not game.is_relative_to(root):
        raise ValueError('Mutable game directory must remain inside this independent fixture')
    ordinary_ancestry(game)
    return root


def wait_file(path, child, deadline):
    while not path.is_file():
        if child.poll() is not None:
            raise RuntimeError(f'Owned child exited before {path.name}; exit {child.returncode}')
        if time.monotonic() >= deadline:
            raise TimeoutError(f'Timed out waiting for {path.name}')
        time.sleep(.1)


def stop_owned_java(evidence):
    """Last resort after native stop.flag grace; never terminates an unchecked PID."""
    receipt_path = evidence / 'process.json'
    if not receipt_path.is_file():
        return 'NO_JAVA_RECEIPT'
    receipt = json.loads(receipt_path.read_text())
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    ntdll = ctypes.WinDLL('ntdll')
    kernel.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.CloseHandle.argtypes = (wintypes.HANDLE,)
    kernel.TerminateProcess.argtypes = (wintypes.HANDLE, wintypes.UINT)
    kernel.TerminateProcess.restype = wintypes.BOOL
    ntdll.NtQueryInformationProcess.argtypes = (wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.ULONG, ctypes.POINTER(wintypes.ULONG))
    ntdll.NtQueryInformationProcess.restype = ctypes.c_long
    kernel.GetProcessTimes.argtypes = (wintypes.HANDLE, *([ctypes.POINTER(wintypes.FILETIME)] * 4))
    kernel.GetProcessTimes.restype = wintypes.BOOL
    handle = kernel.OpenProcess(0x1001, False, int(receipt['pid']))
    if not handle:
        return 'ALREADY_EXITED_OR_UNAVAILABLE'
    try:
        required = wintypes.ULONG()
        ntdll.NtQueryInformationProcess(handle, 60, None, 0, ctypes.byref(required))
        if not 16 < required.value < 128 * 1024:
            return 'REFUSED_UNCONFIRMED_TARGET'
        storage = ctypes.create_string_buffer(required.value)
        if ntdll.NtQueryInformationProcess(handle, 60, storage, required.value, ctypes.byref(required)) < 0:
            return 'REFUSED_UNCONFIRMED_TARGET'
        length = ctypes.c_ushort.from_buffer(storage).value
        pointer = ctypes.c_void_p.from_buffer(storage, 8).value
        start = ctypes.addressof(storage)
        if not pointer or not start <= pointer <= start + len(storage) - length:
            return 'REFUSED_UNCONFIRMED_TARGET'
        command = ctypes.wstring_at(pointer, length // 2)
        created, exited, kernel_time, user_time = [wintypes.FILETIME() for _ in range(4)]
        if not kernel.GetProcessTimes(handle, ctypes.byref(created), ctypes.byref(exited), ctypes.byref(kernel_time), ctypes.byref(user_time)):
            return 'REFUSED_UNCONFIRMED_TARGET'
        timestamp = ((created.dwHighDateTime << 32) | created.dwLowDateTime) / 10_000_000 - 11644473600
        expected = '-Dbtw.qa.evidence=' + str(evidence)
        if expected not in command or '-Dbtw.qa.audioProbe=true' not in command or abs(timestamp - receipt['startedAtUnix']) > 5:
            return 'REFUSED_UNCONFIRMED_TARGET'
        if not kernel.TerminateProcess(handle, 143):
            raise OSError(ctypes.get_last_error(), 'Could not terminate this confirmed owned stalled QA process')
        return 'CONFIRMED_OWNED_STALLED_JAVA_TERMINATED_AFTER_NATIVE_STOP_GRACE'
    finally:
        kernel.CloseHandle(handle)


def run(args):
    root = fixture_root(args.fixture)
    if not re.fullmatch(r'[A-Za-z0-9_.-]+', args.label):
        raise ValueError('Label must be a simple new evidence name')
    if not 10 <= args.seconds <= 20:
        raise ValueError('Capture duration must be 10..20 seconds for all six emissions')
    evidence = root / 'evidence' / args.label
    ordinary_ancestry(evidence)
    if evidence.exists():
        raise ValueError('Use a new evidence label')
    driver = root / 'evidence' / (args.label + '.driver.stdout.log')
    driver_error = root / 'evidence' / (args.label + '.driver.stderr.log')
    command = [sys.executable, '-B', str(Path(__file__).with_name('run_native.py')), str(root),
        '--mode', 'fresh', '--label', args.label, '--jdk', str(args.jdk.resolve(strict=True)), '--timeout', '120',
        '--username', 'BtwQaAudio', '--property', 'btw.qa.audioProbe=true', '--property', 'btw.qa.hiddenWindow=true']
    for prop in args.property:
        command.extend(('--property', prop))
    if args.omit_assets_dir:
        command.append('--omit-assets-dir')
    runner = capture = None
    cleanup = 'NO_CHILDREN_STARTED'
    result = {'status': 'INCOMPLETE', 'label': args.label, 'omitAssetsDirNegativeControl': args.omit_assets_dir,
        'noWorldOpenedByProbe': True, 'microphoneOpened': False, 'desktopFallbackPerformed': False}
    deadline = time.monotonic() + 120
    try:
        with driver.open('xb') as native_out, driver_error.open('xb') as native_err:
            runner = subprocess.Popen(command, stdout=native_out, stderr=native_err)
            wait_file(evidence / 'audio-ready', runner, min(deadline, time.monotonic() + 65))
            receipt = json.loads((evidence / 'process.json').read_text())
            wav = evidence / 'capture.wav'
            with (evidence / 'capture.stdout.log').open('xb') as captured_out, (evidence / 'capture.stderr.log').open('xb') as captured_err:
                capture = subprocess.Popen([str(args.capture_tool.resolve(strict=True)), '--pid', str(receipt['pid']),
                    '--seconds', str(args.seconds), '--out', str(wav)], stdout=captured_out, stderr=captured_err)
                wait_file(Path(str(wav) + '.ready'), capture, min(deadline, time.monotonic() + 9))
                (evidence / 'emit.flag').write_text('emit six native sound keys\n')
                capture_exit = capture.wait(timeout=args.seconds + 8)
            native_exit = runner.wait(timeout=max(1, deadline - time.monotonic()))
            capture_report = json.loads(wav.with_suffix('.json').read_text())
            result.update(nativeExitCode=native_exit, captureExitCode=capture_exit, capture=capture_report)
            if native_exit != 0 or capture_exit != 0 or capture_report.get('status') != 'CAPTURED_QA_PROCESS_ONLY':
                raise RuntimeError('Native lifecycle or application-only capture failed')
            if capture_report.get('microphoneOpened') is not False or capture_report.get('desktopFallbackPerformed') is not False:
                raise RuntimeError('Unexpected capture scope')
            result['status'] = 'PASS_NATIVE_AUDIO_CAPTURE_LIFECYCLE'
            cleanup = 'NORMAL_NATIVE_SHUTDOWN'
    except Exception as error:
        result.update(status='FAILED_OR_UNAVAILABLE', reason=type(error).__name__ + ': ' + str(error))
    finally:
        if evidence.is_dir() and runner is not None and runner.poll() is None:
            (evidence / 'stop.flag').write_text('normal QA shutdown requested by orchestrator\n')
            try:
                runner.wait(timeout=15)
                cleanup = 'NATIVE_STOP_FLAG_EXIT'
            except subprocess.TimeoutExpired:
                cleanup = stop_owned_java(evidence)
                try:
                    runner.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    runner.terminate()  # This is our own still-held Python child, never a PID lookup.
                    runner.wait(timeout=5)
        if capture is not None and capture.poll() is None:
            capture.terminate()  # Our owned diagnostic capture process; closes only its capture.
            capture.wait(timeout=5)
        result['cleanup'] = cleanup
        if evidence.is_dir():
            for source, name in ((driver, 'driver.stdout.log'), (driver_error, 'driver.stderr.log')):
                if source.exists():
                    source.replace(evidence / name)
            (evidence / 'audio-orchestration.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    return 0 if result['status'].startswith('PASS_') else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('fixture', type=Path)
    parser.add_argument('--label', required=True)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--capture-tool', type=Path, required=True)
    parser.add_argument('--seconds', type=int, default=16)
    parser.add_argument('--omit-assets-dir', action='store_true')
    parser.add_argument('--property', action='append', default=[])
    raise SystemExit(run(parser.parse_args()))
