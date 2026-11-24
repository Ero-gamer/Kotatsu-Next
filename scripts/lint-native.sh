#!/usr/bin/env bash
# Lint (and with --fix, auto-fix) the non-Kotlin code of Next: Rust (rustfmt + clippy) and Python (ruff).
# Needs: cargo (rustfmt, clippy), ruff.   Usage: scripts/lint-native.sh [--fix]
set -euo pipefail
cd "$(dirname "$0")/.."
FIX=0; [ "${1:-}" = "--fix" ] && FIX=1
crate=app/src/main/rust/ssivcodecs
if [ "$FIX" = 1 ]; then
  (cd "$crate"; cargo fmt; cargo clippy --all-targets --locked --fix --allow-dirty --allow-staged --allow-no-vcs -- -D warnings)
  ruff check --fix scripts
  ruff format scripts
else
  (cd "$crate"; cargo fmt --check; cargo clippy --all-targets --locked -- -D warnings)
  ruff check scripts
  ruff format --check scripts
fi
(cd "$crate"; cargo test --locked)
echo "native lint OK"
