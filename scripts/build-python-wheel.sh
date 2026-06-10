#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pkg_dir="$repo_root/boomslang-py"
version_file="$pkg_dir/src/boomslang/_version.py"

if [ ! -d "$pkg_dir/src/boomslang/_runtime" ]; then
  echo "ERROR: runtime assets are not staged. Run scripts/stage-python-runtime.sh first." >&2
  exit 1
fi

resolve_version() {
  if [ -n "${BOOMSLANG_WHEEL_VERSION:-}" ]; then
    echo "$BOOMSLANG_WHEEL_VERSION"
    return
  fi
  local tag
  tag="$(git -C "$repo_root" describe --tags --exact-match 2>/dev/null || true)"
  if [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "${tag#v}"
    return
  fi
  local sha
  sha="$(git -C "$repo_root" rev-parse --short=12 HEAD)"
  echo "0.0.0+g$sha"
}

version="$(resolve_version)"
echo "Building boomslang wheel version $version"

original_version_file="$(cat "$version_file")"
restore_version_file() {
  printf '%s' "$original_version_file" > "$version_file"
}
trap restore_version_file EXIT

printf '__version__ = "%s"\n' "$version" > "$version_file"

cd "$pkg_dir"
rm -rf dist
if command -v uv >/dev/null 2>&1; then
  uv build --wheel
else
  python3 -m build --wheel
fi

wheel="$(ls dist/*.whl)"
echo "Built $wheel ($(du -h "$wheel" | cut -f1))"
