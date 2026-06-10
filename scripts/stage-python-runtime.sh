#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
runtime_root="$repo_root/core/src/main/resources/python"
overlay_root="$repo_root/core/src/main/resources/python-overlay"
dest="$repo_root/boomslang-py/src/boomslang/_runtime"

usage() {
  cat <<'EOF'
Usage: scripts/stage-python-runtime.sh

Stage the built runtime resources (WASM binary + Python stdlib tree) into
boomslang-py/src/boomslang/_runtime so the Python wheel can bundle them.

Sources:
  core/src/main/resources/python/{bin,usr}   (from `just resources` or `just fetch-main-wasm`)
  core/src/main/resources/python-overlay/    (checked in, merged over the stdlib tree)
EOF
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

if [ ! -f "$runtime_root/bin/boomslang.wasm" ]; then
  echo "ERROR: $runtime_root/bin/boomslang.wasm not found." >&2
  echo "Run 'just fetch-main-wasm' (or 'just resources' after a local build) first." >&2
  exit 1
fi

if [ ! -d "$runtime_root/usr/local/lib/python3.14" ]; then
  echo "ERROR: $runtime_root/usr/local/lib/python3.14 not found." >&2
  echo "Run 'just fetch-main-wasm' (or 'just resources' after a local build) first." >&2
  exit 1
fi

mkdir -p "$dest"
rsync -a --delete \
  --exclude '__pycache__/' \
  --exclude '*.pyc' \
  "$runtime_root/bin" "$runtime_root/usr" "$dest/"

if [ -d "$overlay_root" ]; then
  rsync -a \
    --exclude '__pycache__/' \
    --exclude '*.pyc' \
    "$overlay_root/" "$dest/"
fi

python3 - "$dest" <<'PY'
import pathlib
import sys

dest = pathlib.Path(sys.argv[1])
wasm = dest / "bin" / "boomslang.wasm"
stdlib = dest / "usr" / "local" / "lib" / "python3.14"

if wasm.read_bytes()[:4] != b"\0asm":
    raise SystemExit("staged boomslang.wasm is not a WASM binary")
if not stdlib.is_dir():
    raise SystemExit("staged Python stdlib tree is missing")
if not (stdlib / "boomslang_host" / "__init__.py").is_file():
    raise SystemExit("staged stdlib is missing boomslang_host/__init__.py")
leftover = next(stdlib.rglob("__pycache__"), None)
if leftover is not None:
    raise SystemExit(f"staged stdlib contains {leftover}")
PY

wasm_size="$(wc -c < "$dest/bin/boomslang.wasm" | tr -d ' ')"
stdlib_paths="$(find "$dest/usr/local/lib/python3.14" | wc -l | tr -d ' ')"

echo "Staged Python runtime into $dest"
echo "  wasm: $dest/bin/boomslang.wasm ($wasm_size bytes)"
echo "  stdlib: $dest/usr/local/lib/python3.14 ($stdlib_paths paths)"
