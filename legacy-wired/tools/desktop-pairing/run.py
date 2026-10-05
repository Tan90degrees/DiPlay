#!/usr/bin/env python3
"""Compile the unchanged shared pairing sources and run against a real USB iPhone on Windows."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import urllib.request

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['inspect', 'pair', 'self-test'], default='inspect', nargs='?')
    parser.add_argument('--studio', type=Path, default=Path('C:/Program Files/Android/Android Studio'))
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    repo = here.parents[2]
    private = repo / '.private' / 'desktop-pairing'
    private.mkdir(parents=True, exist_ok=True)
    java = args.studio / 'jbr/bin/java.exe'
    kotlin = args.studio / 'plugins/Kotlin/kotlinc'
    if not java.is_file() or not (kotlin / 'lib/kotlin-compiler.jar').is_file():
        parser.error('Android Studio JDK and Kotlin compiler not found; supply --studio')
    dependency = private / 'xpp3-1.1.4c.jar'
    base = 'https://repo.maven.apache.org/maven2/xpp3/xpp3/1.1.4c/'
    # Check the publisher checksum on first download; subsequent builds use the cached copy.
    if not dependency.exists():
        data = urllib.request.urlopen(base + dependency.name, timeout=30).read()
        expected = urllib.request.urlopen(base + dependency.name + '.sha1', timeout=30).read().decode().split()[0]
        if hashlib.sha1(data).hexdigest() != expected:
            raise ValueError('XML parser download checksum mismatch')
        dependency.write_bytes(data)
    transport = repo / 'shared/src/main/java/com/shilapi/xcertplay/transport'
    names = ['BlockingDuplexByteStream.kt', 'LockdownPlistChannel.kt', 'LockdownPairRecord.kt', 'LockdownPairingClient.kt']
    sources = [transport / name for name in names]
    # The exception declarations share an Android-only file; stage their exact source text.
    original = (transport / 'IphoneUsbHost.kt').read_text(encoding='utf-8')
    declaration = original[original.index('sealed class IphoneUsbException('):]
    exceptions = private / 'IphoneUsbException.kt'
    exceptions.write_text('package com.shilapi.xcertplay.transport\nimport java.io.IOException\n' + declaration, encoding='utf-8')
    fingerprint = hashlib.sha256(b''.join(p.read_bytes() for p in sources)).hexdigest()
    output = private / 'desktop-pairing.jar'
    command = [str(java), '-cp', str(kotlin / 'lib/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-kotlin-home', str(kotlin), '-jvm-target', '17', '-classpath', str(dependency), '-d', str(output)]
    command += [str(p) for p in sources + [exceptions] + sorted((here / 'src').glob('*.kt'))]
    subprocess.run(command, check=True, cwd=repo)
    print('Compiled shared pairing source SHA256:', fingerprint, flush=True)
    classpath = os.pathsep.join(map(str, [output, dependency, kotlin / 'lib/kotlin-stdlib.jar']))
    result = subprocess.run([str(java), '-Ddiplay.python=' + sys.executable,
        '-Ddiplay.bridge=' + str(here / 'bridge.py'), '-cp', classpath,
        'com.shilapi.xcertplay.transport.DesktopPairingProbeKt', '--' + args.mode, str(private)], cwd=repo)
    raise SystemExit(result.returncode)

if __name__ == '__main__':
    main()
