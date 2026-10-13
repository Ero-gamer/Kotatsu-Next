#!/usr/bin/env python3
"""Print a Markdown table of the Rust crates (and licenses) linked into libssivcodecs.so.

Usage: python3 scripts/rust-licenses.py > rust-licenses.md   (needs cargo; uses Cargo.lock offline-safe)
"""
import json
import subprocess
import sys
from pathlib import Path

crate = Path(__file__).resolve().parent.parent / "app/src/main/rust/ssivcodecs"
out = subprocess.run(
    ["cargo", "metadata", "--locked", "--format-version", "1", "--filter-platform", "aarch64-linux-android"],
    cwd=crate, capture_output=True, text=True,
)
if out.returncode != 0:
    sys.exit(out.stderr)
meta = json.loads(out.stdout)
print("| Crate | Version | License | Source |\n|---|---|---|---|")
for p in sorted(meta["packages"], key=lambda p: p["name"]):
    if p["name"] == "ssivcodecs":
        continue
    print(f"| {p['name']} | {p['version']} | {p['license'] or 'UNKNOWN'} | {p['repository'] or ''} |")
