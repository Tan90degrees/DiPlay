#!/usr/bin/env python3
"""Add externally supplied runtime assets to a tested source APK, align and sign locally.

Requires Python cryptography, a JRE and official Android SDK zipalign/apksigner.
Neither authentication files nor the Android signing key belongs in Git or CI.
"""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, utils
from cryptography.hazmat.primitives.serialization import load_der_private_key, pkcs7


def signature_entry(name):
    return bool(re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))', name, re.IGNORECASE))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('source-apk', 'assets', 'output', 'java', 'zipalign', 'apksigner-jar', 'keystore', 'password-file'):
        parser.add_argument('--' + option, required=True, type=Path)
    parser.add_argument('--alias', required=True)
    args = parser.parse_args()
    source = args.source_apk.resolve(strict=True)
    output = args.output.resolve()
    if output == source or output.exists():
        parser.error('Output must be a new file, separate from the tested source APK')
    assets = args.assets.resolve(strict=True)
    names = ('offline-mfi/identity.pk8', 'offline-mfi/certificate.p7b')
    actual = {f.relative_to(assets).as_posix() for f in assets.rglob('*') if f.is_file()}
    if actual != set(names):
        parser.error('Asset input must contain exactly the two offline-mfi runtime files')
    payload = {name: (assets / name).read_bytes() for name in names}
    if not all(1 <= len(data) <= 16_384 for data in payload.values()):
        parser.error('Runtime asset length is outside 1..16384')
    key = load_der_private_key(payload[names[0]], None)
    certs = pkcs7.load_der_pkcs7_certificates(payload[names[1]])
    if len(certs) != 1 or not isinstance(key, ec.EllipticCurvePrivateKey) or key.curve.name != 'secp256r1':
        parser.error('Expected one P-256 accessory certificate and private key')
    public = certs[0].public_key()
    if public.public_numbers() != key.public_key().public_numbers():
        parser.error('Runtime certificate/private key do not match')
    # MFi callers supply a 32-byte digest; verify without hashing that digest a second time.
    digest = hashlib.sha256(b'DiPlay local packaging consistency check').digest()
    public.verify(key.sign(digest, ec.ECDSA(utils.Prehashed(hashes.SHA256()))), digest,
                  ec.ECDSA(utils.Prehashed(hashes.SHA256())))
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='diplay-package-', dir=output.parent) as temp:
        unsigned = Path(temp) / 'unsigned.apk'
        aligned = Path(temp) / 'aligned.apk'
        with zipfile.ZipFile(source) as original, zipfile.ZipFile(unsigned, 'w') as target:
            if len(original.namelist()) != len(set(original.namelist())):
                parser.error('Source APK has duplicate ZIP entries')
            if any(name.startswith('assets/offline-mfi/') for name in original.namelist()):
                parser.error('Expected an identity-free source APK')
            for info in original.infolist():
                if not signature_entry(info.filename):
                    target.writestr(info, original.read(info.filename))
            for name, data in payload.items():
                target.writestr('assets/' + name, data, compress_type=zipfile.ZIP_STORED)
        subprocess.run([str(args.zipalign), '-p', '-f', '4', str(unsigned), str(aligned)], check=True)
        signer = [str(args.java), '-jar', str(args.apksigner_jar)]
        subprocess.run(signer + ['sign', '--ks', str(args.keystore), '--ks-type', 'PKCS12',
            '--ks-key-alias', args.alias, '--ks-pass', 'file:' + str(args.password_file),
            '--min-sdk-version', '19', '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true',
            '--v3-signing-enabled', 'false', '--v4-signing-enabled', 'false', '--out', str(output), str(aligned)], check=True)
        subprocess.run(signer + ['verify', '--verbose', '--min-sdk-version', '19', str(output)], check=True)
        subprocess.run([str(args.zipalign), '-c', '-p', '4', str(output)], check=True)
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(output) as package:
        expected = {n for n in original.namelist() if not signature_entry(n)}
        actual = {n for n in package.namelist() if not signature_entry(n)}
        if actual != expected | {'assets/' + n for n in names}:
            raise ValueError('Packaged APK has unexpected entries')
        for name in expected:
            if original.read(name) != package.read(name):
                raise ValueError('Source APK entry changed: ' + name)
        for name, data in payload.items():
            if package.read('assets/' + name) != data:
                raise ValueError('Packaged runtime asset does not match local input')
    print('Verified: source manifest/DEX/native/resources unchanged; exactly two matching runtime assets added.')
    print('APK:', output)
    print('SHA256:', hashlib.sha256(output.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()
