#!/usr/bin/env python3
"""Packs a kiff executable as a release attaches it.

Usage: pack-cli.py EXECUTABLE PLATFORM VERSION DIR

Writes DIR/kiff-VERSION-PLATFORM.tar.gz, or DIR/kiff-VERSION-PLATFORM.zip for a windows-* platform,
and prints its path. The archive holds one directory, kiff-VERSION-PLATFORM, with the executable as
kiff (kiff.exe for Windows), this repository's LICENSE and its README.md.

The same files give the same bytes on any machine: the entries are in a fixed order, owned by 0,
with fixed modes and the time SOURCE_DATE_EPOCH gives, by default the commit checked out, and the
gzip header has no name or time in it. kiff --use, install.sh and the action read this layout.
"""

import gzip
import io
import os
import subprocess
import sys
import tarfile
import time
import zipfile

REPOSITORY = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# The earliest time a zip can hold, 1 January 1980.
ZIP_EPOCH = 315532800


def source_date_epoch():
    value = os.environ.get('SOURCE_DATE_EPOCH')
    if value:
        return int(value)
    commit = subprocess.check_output(['git', '-C', REPOSITORY, 'log', '-1', '--format=%ct'])
    return int(commit.strip())


def write_tar_gz(path, root, files, when):
    with open(path, 'wb') as raw, \
            gzip.GzipFile(filename='', mode='wb', fileobj=raw, compresslevel=9, mtime=0) as gz, \
            tarfile.open(fileobj=gz, mode='w', format=tarfile.USTAR_FORMAT) as tar:
        for name, data, mode in [(root, None, 0o755)] + files:
            info = tarfile.TarInfo(name)
            info.mode = mode
            info.mtime = when
            info.uid = info.gid = 0
            info.uname = info.gname = ''
            if data is None:
                info.type = tarfile.DIRTYPE
                tar.addfile(info)
            else:
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))


def write_zip(path, root, files, when):
    stamp = time.gmtime(max(when, ZIP_EPOCH))[:6]
    with zipfile.ZipFile(path, 'w') as archive:
        for name, data, mode in [(root + '/', None, 0o755)] + files:
            info = zipfile.ZipInfo(name, stamp)
            # Unix, so the modes below mean the same wherever this runs.
            info.create_system = 3
            if data is None:
                info.external_attr = (0o040000 | mode) << 16 | 0x10
                archive.writestr(info, b'')
            else:
                info.external_attr = (0o100000 | mode) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, data, compresslevel=9)


def read(path):
    with open(path, 'rb') as file:
        return file.read()


def main(args):
    if len(args) != 4:
        sys.exit('Usage: pack-cli.py EXECUTABLE PLATFORM VERSION DIR')
    executable, platform, version, out = args
    root = f'kiff-{version}-{platform}'
    windows = platform.startswith('windows-')
    files = [
        (f'{root}/kiff.exe' if windows else f'{root}/kiff', read(executable), 0o755),
        (f'{root}/LICENSE', read(os.path.join(REPOSITORY, 'LICENSE')), 0o644),
        (f'{root}/README.md', read(os.path.join(REPOSITORY, 'README.md')), 0o644),
    ]
    os.makedirs(out, exist_ok=True)
    path = os.path.join(out, root + ('.zip' if windows else '.tar.gz'))
    (write_zip if windows else write_tar_gz)(path, root, files, source_date_epoch())
    print(path)


if __name__ == '__main__':
    main(sys.argv[1:])
