#!/usr/bin/env python3
"""Reports the latest STABLE version of every library / plugin the build uses: the version catalog
(gradle/libs.versions.toml), coordinates written inline in *.gradle(.kts) files (e.g. ssiv-lib, buildscript classpath),
the Gradle wrapper(s), plus AGP, Room 3 and a few migration-relevant extras.

Reads the real maven-metadata.xml of Google Maven, Maven Central, the Gradle plugin portal and JitPack, so it must
run where those hosts are reachable (GitHub Actions). Standard library only (Python >= 3.11).

    python3 scripts/dep_versions.py [--toml gradle/libs.versions.toml] [--scan .] [--out dep-versions-report]

Writes <out>.md and <out>.json; with $GITHUB_STEP_SUMMARY set the markdown is appended to the job summary.
Repository base URLs can be overridden for tests: DEP_REPOS="google=http://...,central=http://..."
"""

from __future__ import annotations

import argparse
import concurrent.futures as cf
import functools
import json
import os
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

import tomllib

REPOS = {
    "google": "https://dl.google.com/dl/android/maven2",
    "central": "https://repo.maven.apache.org/maven2",
    "portal": "https://plugins.gradle.org/m2",
    "jitpack": "https://jitpack.io",
}
if os.environ.get("DEP_REPOS"):
    REPOS.update(dict(p.split("=", 1) for p in os.environ["DEP_REPOS"].split(",") if "=" in p))

# Searched in this order; the first repo that knows the artifact wins.
ORDER_DEFAULT = ["google", "central", "portal", "jitpack"]

# Not in the catalog, but needed for the planned migration (AGP 9, Room 3, Hilt, KSP).
EXTRA_MODULES = [
    "com.android.tools.build:gradle",  # AGP
    "androidx.room3:room3-runtime",
    "androidx.room3:room3-compiler",
    "androidx.room3:room3-gradle-plugin",
    "androidx.room3:room3-sqlite-wrapper",
    "androidx.sqlite:sqlite-bundled",
    "androidx.hilt:hilt-work",
    "androidx.hilt:hilt-compiler",
    "com.google.dagger:hilt-android-gradle-plugin",
    "com.google.devtools.ksp:symbol-processing-gradle-plugin",
    "org.jetbrains.kotlin:kotlin-gradle-plugin",
]

UNSTABLE = re.compile(
    r"(alpha|beta|rc|cr|m\d+|milestone|preview|eap|dev|snapshot|canary|nightly|pre|-b\d+|\.rc)",
    re.IGNORECASE,
)
TOKEN = re.compile(r"\d+|[A-Za-z]+")


def is_stable(version: str) -> bool:
    return not UNSTABLE.search(version)


def sort_key(version: str):
    # digit runs compare as numbers (1.10 > 1.9); words compare as text and sort below digits
    out = []
    for tok in TOKEN.findall(version):
        out.append((1, int(tok), "") if tok.isdigit() else (0, 0, tok.lower()))
    return out


def newest(versions: list[str], stable_only: bool) -> str | None:
    pool = [v for v in versions if is_stable(v)] if stable_only else versions
    return max(pool, key=sort_key) if pool else None


def fetch(url: str, retries: int = 3) -> bytes | None:
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "dep-versions/1.0"})
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code in (404, 410):
                return None
            err = f"HTTP {e.code}"
        except Exception as e:  # network hiccup: retry
            err = str(e)
        if attempt == retries - 1:
            print(f"warn: {url}: {err}", file=sys.stderr)
    return None


@functools.cache
def metadata_versions(group: str, name: str) -> tuple[str | None, list[str]]:
    path = f"{group.replace('.', '/')}/{name}/maven-metadata.xml"
    order = ORDER_DEFAULT
    if group.startswith("com.github."):
        order = ["jitpack"] + [r for r in ORDER_DEFAULT if r != "jitpack"]
    elif group.startswith("com.google.devtools.ksp"):
        order = ["central", "portal", "google", "jitpack"]  # Google Maven holds an ancient KSP 1.5.30
    elif name.endswith(".gradle.plugin"):
        # plugin markers: Google Maven only carries stale copies (e.g. KSP 1.5.30), the portal has the real ones
        order = ["portal", "central", "google", "jitpack"]
    for repo in order:
        if repo not in REPOS:
            continue
        body = fetch(f"{REPOS[repo]}/{path}")
        if not body:
            continue
        try:
            root = ET.fromstring(body)
        except ET.ParseError:
            continue
        versions = [v.text.strip() for v in root.iter("version") if v.text and v.text.strip()]
        if versions:
            return repo, versions
    return None, []


def gradle_versions() -> list[str]:
    body = fetch("https://services.gradle.org/versions/all")
    if not body:
        return []
    try:
        # nightlies / snapshots carry plain build timestamps, only their flags tell them apart
        skip = ("broken", "snapshot", "nightly", "releaseNightly")
        return [v["version"] for v in json.loads(body) if not any(v.get(k) for k in skip)]
    except (ValueError, KeyError, TypeError):
        return []


COORD = re.compile(r"""["']([A-Za-z][\w.\-]*):([A-Za-z][\w.\-]*):(\d[\w.\-+]*)["']""")
SKIP_DIRS = {"build", ".git", ".gradle", "node_modules", "target", "bin"}


def scan_inline(root: str) -> tuple[list[dict], list[tuple[str, str]]]:
    """Inline "group:name:version" coordinates of *.gradle(.kts) files and the Gradle wrapper(s) under [root]."""
    rows, wrappers, seen = [], [], set()
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            path = os.path.join(dirpath, name)
            if name.endswith((".gradle", ".gradle.kts")):
                with open(path, encoding="utf-8", errors="replace") as f:
                    text = f.read()
                for m in COORD.finditer(text):
                    group, artifact, version = m.groups()
                    key = (path, group, artifact, version)
                    if key in seen:
                        continue
                    seen.add(key)
                    rows.append(
                        {
                            "kind": "inline",
                            "alias": os.path.relpath(path, root),
                            "module": f"{group}:{artifact}",
                            "ref": None,
                            "current": version,
                        }
                    )
            elif name == "gradle-wrapper.properties":
                with open(path, encoding="utf-8", errors="replace") as f:
                    m = re.search(r"gradle-([\w.\-]+?)-(?:bin|all)\.zip", f.read())
                wrappers.append((os.path.relpath(path, root), m.group(1) if m else "?"))
    return rows, sorted(wrappers)


def collect(toml_path: str | None) -> list[dict]:
    cat: dict = {}
    if toml_path and os.path.exists(toml_path):
        with open(toml_path, "rb") as f:
            cat = tomllib.load(f)
    versions = cat.get("versions", {})

    def resolve(spec) -> tuple[str | None, str | None]:
        """(version key or None, version text) of a catalog entry's version / version.ref / rich version."""
        if isinstance(spec, str):
            return None, spec
        v = spec.get("version")
        if isinstance(v, str):
            return None, v
        if isinstance(v, dict):  # `version.ref = "x"` is parsed as {"version": {"ref": "x"}}
            ref = v.get("ref")
            if ref:
                return ref, versions.get(ref)
            return None, v.get("strictly") or v.get("require") or v.get("prefer")
        return None, None

    rows = []
    for alias, spec in cat.get("libraries", {}).items():
        if isinstance(spec, str):
            module, ref, cur = spec.rsplit(":", 1)[0], None, spec.rsplit(":", 1)[-1]
        else:
            module = spec.get("module") or f"{spec['group']}:{spec['name']}"
            ref, cur = resolve(spec)
        rows.append({"kind": "lib", "alias": alias, "module": module, "ref": ref, "current": cur})
    for alias, spec in cat.get("plugins", {}).items():
        if isinstance(spec, str):
            pid, cur, ref = spec.rsplit(":", 1)[0], spec.rsplit(":", 1)[-1], None
        else:
            pid = spec["id"]
            ref, cur = resolve(spec)
        rows.append(
            {"kind": "plugin", "alias": alias, "module": f"{pid}:{pid}.gradle.plugin", "ref": ref, "current": cur}
        )
    for module in EXTRA_MODULES:
        rows.append({"kind": "extra", "alias": module, "module": module, "ref": None, "current": None})
    return rows


def lookup(row: dict) -> dict:
    group, _, name = row["module"].partition(":")
    repo, versions = metadata_versions(group, name)
    row["repo"] = repo
    row["latest_stable"] = newest(versions, True)
    row["latest_any"] = newest(versions, False)
    return row


def status(row: dict) -> str:
    cur, stable = row["current"], row["latest_stable"]
    if not row["repo"]:
        return "NOT FOUND"
    if not cur:
        return "info"
    if not stable:
        return "no stable"
    if cur == stable:
        return "up to date"
    # current may be a pre-release newer than any stable (alpha lines): don't call that outdated
    return "OUTDATED" if sort_key(cur) < sort_key(stable) else "ahead of stable"


def render(rows: list[dict], gradle: list[str], wrappers: list[tuple[str, str]]) -> str:
    out = ["# Dependency versions report", ""]
    out.append(
        f"Latest stable Gradle: `{newest(gradle, True) or '?'}`; latest Gradle pre-release: "
        f"`{newest(gradle, False) or '?'}`"
    )
    out += [f"- wrapper `{path}`: `{version}`" for path, version in wrappers]
    out.append("")
    outdated = [r for r in rows if status(r) == "OUTDATED"]
    out.append(
        f"**{len(outdated)} outdated** of {len(rows)} entries "
        f"({sum(1 for r in rows if status(r) == 'NOT FOUND')} not found in any repository)."
    )
    out.append("")
    out.append("| status | artifact | alias | version key | current | latest stable | latest any | repo |")
    out.append("|---|---|---|---|---|---|---|---|")
    order = {"OUTDATED": 0, "NOT FOUND": 1, "no stable": 2, "ahead of stable": 3, "info": 4, "up to date": 5}
    for r in sorted(rows, key=lambda r: (order[status(r)], r["module"])):
        out.append(
            "| {s} | `{m}` | {a} | {k} | {c} | {l} | {n} | {p} |".format(
                s=status(r),
                m=r["module"],
                a={"extra": "(extra)", "inline": f"inline: {r['alias']}"}.get(r["kind"], r["alias"]),
                k=r["ref"] or "",
                c=r["current"] or "",
                l=r["latest_stable"] or "",
                n=r["latest_any"] or "",
                p=r["repo"] or "",
            )
        )
    # one version key feeding modules whose latest versions differ (bom / family bumps need a human look)
    by_ref: dict[str, set[str]] = {}
    for r in rows:
        if r["ref"] and r["latest_stable"]:
            by_ref.setdefault(r["ref"], set()).add(r["latest_stable"])
    split = {k: sorted(v, key=sort_key) for k, v in by_ref.items() if len(v) > 1}
    if split:
        out += ["", "## Version keys whose modules disagree on the latest version", ""]
        out += [f"- `{k}`: {', '.join(v)}" for k, v in sorted(split.items())]
    return "\n".join(out) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--toml", default="gradle/libs.versions.toml", help="version catalog (skipped if missing)")
    ap.add_argument("--scan", action="append", help="dir scanned for inline coordinates + wrappers (default: .)")
    ap.add_argument("--out", default="dep-versions-report")
    args = ap.parse_args()

    rows = collect(args.toml)
    wrappers: list[tuple[str, str]] = []
    for root in args.scan or ["."]:
        inline, found = scan_inline(root)
        rows += inline
        wrappers += [(os.path.normpath(os.path.join(root, p)), v) for p, v in found]
    with cf.ThreadPoolExecutor(max_workers=12) as pool:
        gradle_future = pool.submit(gradle_versions)
        rows = list(pool.map(lookup, rows))
        gradle = gradle_future.result()
    md = render(rows, gradle, wrappers)
    with open(args.out + ".md", "w") as f:
        f.write(md)
    with open(args.out + ".json", "w") as f:
        json.dump(
            {"gradle_wrappers": dict(wrappers), "gradle_latest_stable": newest(gradle, True), "entries": rows},
            f,
            indent=1,
        )
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write(md)
    print(md)
    # every lookup failing means the network (not the catalog) is broken: fail the job
    return 1 if not any(r["repo"] for r in rows) else 0


if __name__ == "__main__":
    sys.exit(main())
