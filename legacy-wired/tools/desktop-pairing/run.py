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
    parser.add_argument('mode', choices=['inspect', 'pair', 'session', 'iap2', 'self-test'], default='inspect', nargs='?')
    parser.add_argument('--studio', type=Path, default=Path('C:/Program Files/Android/Android Studio'))
    parser.add_argument('--auth-assets', type=Path, help='Private directory containing offline-mfi (iap2 mode only)')
    args = parser.parse_args()
    if args.mode == 'iap2' and not args.auth_assets:
        parser.error('iap2 requires --auth-assets pointing to your private authentication asset directory')
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
    # Use the same BC version declared by the Android app. The host JDK X.509 parser rejects
    # Lockdown's accepted empty-issuer profile before any TLS bytes can reach the phone.
    certificate_parser = private / 'bcprov-jdk18on-1.79.jar'
    if not certificate_parser.exists():
        url = 'https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk18on/1.79/' + certificate_parser.name
        data = urllib.request.urlopen(url, timeout=30).read()
        expected = urllib.request.urlopen(url + '.sha256', timeout=30).read().decode().split()[0]
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError('Certificate parser download checksum mismatch')
        certificate_parser.write_bytes(data)
    tls_dependencies = []
    for module in ['bcutil', 'bctls']:
        name = module + '-jdk18on'
        jar = private / (name + '-1.79.jar')
        if not jar.exists():
            url = 'https://repo.maven.apache.org/maven2/org/bouncycastle/' + name + '/1.79/' + jar.name
            data = urllib.request.urlopen(url, timeout=30).read()
            expected = urllib.request.urlopen(url + '.sha256', timeout=30).read().decode().split()[0]
            if hashlib.sha256(data).hexdigest() != expected:
                raise ValueError('Desktop TLS dependency checksum mismatch')
            jar.write_bytes(data)
        tls_dependencies.append(jar)
    transport = repo / 'shared/src/main/java/com/shilapi/xcertplay/transport'
    names = ['BlockingDuplexByteStream.kt', 'LockdownPlistChannel.kt', 'LockdownPairRecord.kt',
             'LockdownPairingClient.kt', 'LockdownTlsEngineFactory.kt', 'TlsDuplexChannel.kt',
             'LockdownCarKitClient.kt', 'Iap2LinkEngine.kt', 'Iap2LinkChannel.kt',
             'Iap2CsmChannel.kt', 'Iap2FileTransferReceiver.kt', 'Iap2IdentificationClient.kt',
             'Iap2VehicleStatus.kt', 'I2cTransport.kt']
    sources = [transport / name for name in names]
    common = transport.parent
    sources += sorted((common / 'iap2').rglob('*.kt'))
    sources += [common / 'mfi' / name for name in ['MfiAuthenticationClient.kt',
                'LocalMfiAuthenticationClient.kt', 'Iap2MfiAuthenticationClient.kt']]
    # The exception declarations share an Android-only file; stage their exact source text.
    original = (transport / 'IphoneUsbHost.kt').read_text(encoding='utf-8')
    declaration = original[original.index('sealed class IphoneUsbException('):]
    exceptions = private / 'IphoneUsbException.kt'
    exceptions.write_text('package com.shilapi.xcertplay.transport\nimport java.io.IOException\n' + declaration, encoding='utf-8')
    fingerprint = hashlib.sha256(b''.join(p.read_bytes() for p in sources)).hexdigest()
    output = private / 'desktop-pairing.jar'
    command = [str(java), '-cp', str(kotlin / 'lib/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-kotlin-home', str(kotlin), '-jvm-target', '17', '-classpath',
        os.pathsep.join(map(str, [dependency, certificate_parser])), '-d', str(output)]
    command += [str(p) for p in sources + [exceptions] + sorted((here / 'src').glob('*.kt'))]
    subprocess.run(command, check=True, cwd=repo)
    print('Compiled shared pairing source SHA256:', fingerprint, flush=True)
    classpath = os.pathsep.join(map(str, [output, dependency, certificate_parser,
        *tls_dependencies, kotlin / 'lib/kotlin-stdlib.jar']))
    invocation = [str(java), '-Ddiplay.python=' + sys.executable,
        '-Ddiplay.bridge=' + str(here / 'bridge.py'), '-cp', classpath,
        'com.shilapi.xcertplay.transport.DesktopPairingProbeKt', '--' + args.mode, str(private)]
    if args.auth_assets:
        invocation.append(str(args.auth_assets.resolve() / 'offline-mfi'))
    result = subprocess.run(invocation, cwd=repo)
    raise SystemExit(result.returncode)

if __name__ == '__main__':
    main()
