#!/usr/bin/env python3
"""Checks that the native libraries of an APK (or loose .so files / folders) are ready for 16 KB page-size devices.

Every PT_LOAD segment of a 64-bit library must have p_align >= 16384 (Android 15+ with a 16 KB kernel refuses to
load anything else). 32-bit ARM libraries are exempt: 16 KB pages are only used on 64-bit. Stored (uncompressed)
.so entries of an APK must additionally start on a 16 KB boundary of the zip.

Our own libraries (libssivcodecs*, libssiv_jpeg_decoder*) must pass; others are reported as warnings unless
--strict is given.   Usage: scripts/check-elf-alignment.py [--strict] <apk | .so | dir>...
"""

import struct
import sys
import zipfile
from pathlib import Path

PAGE = 16384
PT_LOAD = 1
OWN_PREFIXES = ("libssivcodecs", "libssiv_jpeg_decoder")


def elf_alignment(data: bytes):
    """Returns (is_64bit, [p_align of every PT_LOAD]) or None when `data` is not an ELF file."""
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[5] != 1:  # little endian only (all Android ABIs)
        return None
    is64 = data[4] == 2
    if is64:
        phoff = struct.unpack_from("<Q", data, 0x20)[0]
        phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    else:
        phoff = struct.unpack_from("<I", data, 0x1C)[0]
        phentsize, phnum = struct.unpack_from("<HH", data, 0x2A)
    aligns = []
    for i in range(phnum):
        off = phoff + i * phentsize
        if off + phentsize > len(data):
            return None
        p_type = struct.unpack_from("<I", data, off)[0]
        if p_type != PT_LOAD:
            continue
        fmt, at = ("<Q", 0x30) if is64 else ("<I", 0x1C)
        aligns.append(struct.unpack_from(fmt, data, off + at)[0])
    return is64, aligns


def check_blob(name: str, data: bytes, zip_offset=None):
    """Returns a list of problems for one library."""
    info = elf_alignment(data)
    if info is None:
        return [f"{name}: not an ELF file"]
    is64, aligns = info
    problems = []
    if is64 and any(a < PAGE for a in aligns):
        problems.append(f"{name}: LOAD segment alignment {min(aligns)} < {PAGE}")
    if is64 and zip_offset is not None and zip_offset % PAGE:
        problems.append(f"{name}: stored in the zip at offset {zip_offset}, not {PAGE}-aligned")
    return problems


def libs_in(path: Path):
    """Yields (name, bytes, zip_offset_or_None) for every .so under `path`."""
    if path.is_dir():
        for so in sorted(path.rglob("*.so")):
            yield str(so), so.read_bytes(), None
    elif path.suffix in (".apk", ".aab", ".zip"):
        with zipfile.ZipFile(path) as z:
            for info in z.infolist():
                if info.filename.endswith(".so"):
                    offset = None
                    if info.compress_type == zipfile.ZIP_STORED:
                        # Local header: 30 bytes + name + extra, then the data.
                        with open(path, "rb") as raw:
                            raw.seek(info.header_offset + 26)
                            n, m = struct.unpack("<HH", raw.read(4))
                        offset = info.header_offset + 30 + n + m
                    yield f"{path.name}!{info.filename}", z.read(info), offset
    elif path.suffix == ".so":
        yield str(path), path.read_bytes(), None


def main(argv):
    strict = "--strict" in argv
    paths = [Path(a) for a in argv if not a.startswith("--")]
    if not paths:
        print(__doc__)
        return 2
    failed = checked = 0
    for path in paths:
        if not path.exists():
            print(f"missing: {path}")
            failed += 1
            continue
        for name, data, offset in libs_in(path):
            checked += 1
            problems = check_blob(name, data, offset)
            own = Path(name.split("!")[-1]).name.startswith(OWN_PREFIXES)
            for p in problems:
                print(("FAIL " if own or strict else "WARN ") + p)
                if own or strict:
                    failed += 1
    print(f"checked {checked} libraries, {failed} failure(s)")
    if checked == 0:
        print("no native libraries found")
        return 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
