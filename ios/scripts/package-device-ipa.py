#!/usr/bin/env python3
"""Validate an unsigned iphoneos app and preserve its files/permissions in Payload/*.app."""
import argparse
import hashlib
import plistlib
import re
import shutil
import subprocess
import tempfile
from pathlib import Path
from zipfile import ZipFile


def package(app: Path, output: Path) -> None:
    app = app.resolve()
    output = output.resolve()
    if not app.is_dir() or app.suffix != '.app':
        raise ValueError(f'Missing device .app bundle: {app}')
    with (app / 'Info.plist').open('rb') as file:
        info = plistlib.load(file)
    if info.get('DTPlatformName') != 'iphoneos' or info.get('CFBundleSupportedPlatforms') != ['iPhoneOS']:
        raise ValueError('Only an iphoneos device bundle can be packaged; simulator bundles are rejected.')
    if info.get('CFBundleIdentifier') != 'com.izzyan.sgdeliveryplanner.ios':
        raise ValueError('Unexpected IZZ Delivery bundle identifier.')
    executable_name = info.get('CFBundleExecutable')
    if not executable_name or Path(executable_name).name != executable_name:
        raise ValueError('Invalid CFBundleExecutable.')
    executable = app / executable_name
    architectures = subprocess.check_output(['xcrun', 'lipo', '-archs', str(executable)], text=True).split()
    if architectures != ['arm64']:
        raise ValueError(f'Expected arm64 device executable; found {architectures}.')
    build_info = subprocess.check_output(['xcrun', 'vtool', '-show-build', str(executable)], text=True)
    if not re.search(r'^\s*platform\s+IOS\s*$', build_info, re.MULTILINE):
        raise ValueError('Mach-O executable does not target the iOS device platform.')
    if (app / 'embedded.mobileprovision').exists() or (app / '_CodeSignature').exists():
        raise ValueError('Expected an unsigned app without a provisioning profile or bundle signature.')
    output.parent.mkdir(parents=True, exist_ok=True)
    # Native ditto preserves executable modes and any bundle symlinks in the ZIP archive.
    with tempfile.TemporaryDirectory(prefix='izz-device-ipa-') as staging:
        payload = Path(staging) / 'Payload'
        payload.mkdir()
        subprocess.run(['/usr/bin/ditto', str(app), str(payload / app.name)], check=True)
        archive = Path(staging) / 'IZZ_Delivery_unsigned.ipa'
        subprocess.run(['/usr/bin/ditto', '-c', '-k', '--keepParent', str(payload), str(archive)], check=True)
        prefix = f'Payload/{app.name}/'
        with ZipFile(archive) as ipa:
            if ipa.testzip() is not None:
                raise ValueError('IPA failed its ZIP CRC check.')
            if prefix + 'Info.plist' not in ipa.namelist() or prefix + executable_name not in ipa.namelist():
                raise ValueError('IPA is missing its Payload application or executable.')
            archived_info = plistlib.loads(ipa.read(prefix + 'Info.plist'))
            if archived_info != info:
                raise ValueError('IPA bundle metadata changed during packaging.')
            if hashlib.sha256(ipa.read(prefix + executable_name)).digest() != hashlib.sha256(executable.read_bytes()).digest():
                raise ValueError('IPA executable changed during packaging.')
            executable_mode = ipa.getinfo(prefix + executable_name).external_attr >> 16
            if not executable_mode & 0o111:
                raise ValueError('IPA did not preserve executable permissions.')
        shutil.copyfile(archive, output)
    print(f'Verified unsigned arm64 iphoneos IPA: {output} ({output.stat().st_size:,} bytes)')
    print('Sign/provision this IPA with your personal sideloading tool before installing it on a device.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('app', type=Path)
    parser.add_argument('output', type=Path)
    arguments = parser.parse_args()
    package(arguments.app, arguments.output)
