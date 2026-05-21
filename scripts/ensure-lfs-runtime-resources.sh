#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git -C "${1:-.}" rev-parse --show-toplevel)"
cd "$repo_root"

stdlib="core/src/main/resources/python/stdlib.zip"
wasm="core/src/main/resources/python/bin/boomslang.wasm"

is_lfs_pointer() {
  head -c 80 "$1" | grep -q 'version https://git-lfs.github.com/spec'
}

if is_lfs_pointer "$stdlib" || is_lfs_pointer "$wasm"; then
  tmp_dir=$(mktemp -d)
  trap 'rm -rf "$tmp_dir"' EXIT
  curl --fail --location --retry 3 https://github.com/git-lfs/git-lfs/releases/download/v3.7.0/git-lfs-linux-amd64-v3.7.0.tar.gz | tar xz -C "$tmp_dir"
  export PATH="$tmp_dir/git-lfs-3.7.0:$PATH"
  git lfs install --local
  git lfs fetch origin --include="$stdlib,$wasm"
  git lfs checkout "$stdlib" "$wasm"
fi

python3 - <<'PY'
import pathlib
import zipfile

stdlib = pathlib.Path('core/src/main/resources/python/stdlib.zip')
wasm = pathlib.Path('core/src/main/resources/python/bin/boomslang.wasm')

if stdlib.read_bytes().startswith(b'version https://git-lfs.github.com/spec'):
    raise SystemExit('stdlib.zip is still a Git LFS pointer')
if wasm.read_bytes().startswith(b'version https://git-lfs.github.com/spec'):
    raise SystemExit('boomslang.wasm is still a Git LFS pointer')

with zipfile.ZipFile(stdlib) as zf:
    bad_file = zf.testzip()
if bad_file is not None:
    raise SystemExit(f'Invalid stdlib.zip entry: {bad_file}')

if wasm.read_bytes()[:4] != b'\x00asm':
    raise SystemExit('boomslang.wasm is not a WASM binary')
PY
