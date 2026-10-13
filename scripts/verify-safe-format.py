#!/usr/bin/env python3
"""Fail if a formatter run changed anything but layout.

Compares every modified *.kt/*.kts file in the working tree with HEAD:
  1. identical after removing all whitespace and all import lines, and
  2. every triple-quoted string literal is byte-identical.
Exit code 1 (and a message) on the first violation, so the workflow never commits it.
"""
import re
import subprocess
import sys

RAW = re.compile(r'"""(.*?)"""', re.S)
IMPORT = re.compile(r"^\s*import\s+.*$", re.M)


def sh(*args):
    return subprocess.run(args, capture_output=True, text=True, check=True).stdout


def skeleton(text):
    return re.sub(r"\s+", "", IMPORT.sub("", text))


changed = [p for p in sh("git", "diff", "--name-only", "HEAD").splitlines() if p.endswith((".kt", ".kts"))]
bad = []
for path in changed:
    old = sh("git", "show", f"HEAD:{path}")
    with open(path, encoding="utf-8") as f:
        new = f.read()
    if skeleton(old) != skeleton(new):
        bad.append(f"{path}: non-layout change")
    elif RAW.findall(old) != RAW.findall(new):
        bad.append(f"{path}: raw string literal changed")
print(f"checked {len(changed)} changed file(s)")
if bad:
    print("\n".join(bad))
    sys.exit(1)
