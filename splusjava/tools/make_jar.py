#!/usr/bin/env python3
"""
Packs a directory of .class files into a jar and patches every class to major version 51 (Java 7).

javac on modern JDKs can only emit Java 8 (version 52) class files, but the code base does not use any
Java 8 language feature (no lambdas, no default methods, no invokedynamic) so the bytecode is valid for
version 51. Old Sketchware/dx toolchains reject version 52, which is why we patch it.
"""
import os
import struct
import sys
import zipfile


def patch(data):
    magic, minor, major = struct.unpack('>IHH', data[:8])
    assert magic == 0xCAFEBABE
    if major > 51:
        data = struct.pack('>IHH', magic, minor, 51) + data[8:]
    return data


def main(src, dst):
    if os.path.exists(dst):
        os.remove(dst)
    with zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\nCreated-By: splusjava\r\n\r\n')
        for root, _, files in os.walk(src):
            for name in sorted(files):
                if not name.endswith('.class'):
                    continue
                full = os.path.join(root, name)
                arc = os.path.relpath(full, src).replace(os.sep, '/')
                with open(full, 'rb') as f:
                    z.writestr(arc, patch(f.read()))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
