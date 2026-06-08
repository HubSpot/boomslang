#!/usr/bin/env bash
set -euo pipefail

repo="${BOOMSLANG_GITHUB_REPO:-HubSpot/boomslang}"
branch="${BOOMSLANG_GITHUB_BRANCH:-main}"
requested_sha="${BOOMSLANG_GITHUB_SHA:-}"
repo_root_arg="."

usage() {
  cat <<'EOF'
Usage: scripts/fetch-main-runtime-resources.sh [options] [repo-root]

Fetch a published Boomslang runtime release asset and install it into
core/src/main/resources/python.

Options:
  --branch <branch>    Fetch the latest published artifact for a branch (default: main)
  --sha <sha>          Fetch the published artifact for a specific commit SHA
  --repo <owner/repo>  GitHub repository to query (default: HubSpot/boomslang)
  -h, --help           Show this help

Environment defaults:
  BOOMSLANG_GITHUB_REPO
  BOOMSLANG_GITHUB_BRANCH
  BOOMSLANG_GITHUB_SHA

Examples:
  just fetch-main-wasm
  just fetch-main-wasm -- --sha 902169b138a4d18258ca180adbb177c851b59dff
  ./scripts/fetch-main-runtime-resources.sh --branch main
  ./scripts/fetch-main-runtime-resources.sh --sha 902169b138a4d18258ca180adbb177c851b59dff
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --branch)
      if [ "$#" -lt 2 ]; then
        echo "ERROR: --branch requires a value" >&2
        exit 1
      fi
      branch="$2"
      shift 2
      ;;
    --sha)
      if [ "$#" -lt 2 ]; then
        echo "ERROR: --sha requires a value" >&2
        exit 1
      fi
      requested_sha="$2"
      shift 2
      ;;
    --repo)
      if [ "$#" -lt 2 ]; then
        echo "ERROR: --repo requires a value" >&2
        exit 1
      fi
      repo="$2"
      shift 2
      ;;
    --workflow)
      # Kept as a no-op for compatibility with older docs/shell history.
      if [ "$#" -lt 2 ]; then
        echo "ERROR: --workflow requires a value" >&2
        exit 1
      fi
      shift 2
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    --)
      # Allows `just fetch-main-wasm -- --sha ...` to pass options through.
      shift
      ;;
    -*)
      echo "ERROR: Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
    *)
      if [ "$repo_root_arg" != "." ]; then
        echo "ERROR: Expected at most one repo-root argument" >&2
        exit 1
      fi
      repo_root_arg="$1"
      shift
      ;;
  esac
done

repo_root="$(git -C "$repo_root_arg" rev-parse --show-toplevel)"
runtime_root="$repo_root/core/src/main/resources/python"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "ERROR: $1 is required" >&2
    exit 1
  fi
}

require_command curl
require_command python3
require_command tar

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

github_api_get() {
  local path="$1"
  curl -fsSL \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "https://api.github.com$path"
}

url_encode() {
  python3 - "$1" <<'PY'
import sys
from urllib.parse import quote
print(quote(sys.argv[1], safe=''))
PY
}

resolve_branch_sha() {
  local encoded_ref
  encoded_ref="$(url_encode "$branch")"
  github_api_get "/repos/$repo/commits/$encoded_ref" | python3 -c 'import json, sys; print(json.load(sys.stdin)["sha"])'
}

if [ -n "$requested_sha" ]; then
  selected_sha="$requested_sha"
  echo "Looking for published runtime release asset for $repo@$selected_sha..."
else
  echo "Resolving $repo@$branch..."
  selected_sha="$(resolve_branch_sha)"
  echo "Looking for published runtime release asset for $repo@$branch ($selected_sha)..."
fi

download_dir="$tmp_dir/download"
mkdir -p "$download_dir"

runtime_asset="boomslang-runtime-$selected_sha.tar.gz"
checksum_asset="boomslang-$selected_sha.sha256"
release_tag="build-$selected_sha"
runtime_url="https://github.com/$repo/releases/download/$release_tag/$runtime_asset"
checksum_url="https://github.com/$repo/releases/download/$release_tag/$checksum_asset"
tarball="$download_dir/$runtime_asset"
checksum_file="$download_dir/$checksum_asset"

echo "Downloading $runtime_url"
if ! curl -fL --retry 3 --retry-delay 2 -o "$tarball" "$runtime_url"; then
  echo "ERROR: Could not download $runtime_asset from release $release_tag" >&2
  echo "Make sure the GitHub Actions release job has published runtime assets for $selected_sha." >&2
  exit 1
fi

if curl -fL --retry 3 --retry-delay 2 -o "$checksum_file" "$checksum_url"; then
  python3 - "$download_dir" "$checksum_file" <<'PY'
import hashlib
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
checksum_file = pathlib.Path(sys.argv[2])

for raw_line in checksum_file.read_text().splitlines():
    line = raw_line.strip()
    if not line:
        continue
    expected, filename = line.split(None, 1)
    path = root / filename
    if not path.exists():
        continue
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        raise SystemExit(f"Checksum mismatch for {filename}: expected {expected}, got {actual}")
PY
else
  echo "WARNING: Could not download checksum file $checksum_asset; continuing without checksum validation" >&2
fi

stage_dir="$tmp_dir/stage"
mkdir -p "$stage_dir"
tar xzf "$tarball" -C "$stage_dir"

if [ ! -f "$stage_dir/python/bin/boomslang.wasm" ]; then
  echo "ERROR: Runtime artifact did not contain python/bin/boomslang.wasm" >&2
  exit 1
fi

if [ ! -d "$stage_dir/python/usr/local/lib/python3.14" ]; then
  echo "ERROR: Runtime artifact did not contain python/usr/local/lib/python3.14" >&2
  exit 1
fi

rm -rf "$runtime_root/bin" "$runtime_root/usr"
rm -f "$runtime_root/stdlib.zip"
mkdir -p "$runtime_root"
cp -R "$stage_dir/python/bin" "$runtime_root/bin"
cp -R "$stage_dir/python/usr" "$runtime_root/usr"

python3 - "$runtime_root" <<'PY'
import pathlib
import sys

runtime_root = pathlib.Path(sys.argv[1])
wasm = runtime_root / "bin" / "boomslang.wasm"
stdlib = runtime_root / "usr" / "local" / "lib" / "python3.14"

if wasm.read_bytes()[:4] != b"\0asm":
    raise SystemExit("boomslang.wasm is not a WASM binary")
if not stdlib.is_dir():
    raise SystemExit("Python stdlib resource tree was not installed")
PY

wasm_size="$(wc -c < "$runtime_root/bin/boomslang.wasm" | tr -d ' ')"
stdlib_paths="$(find "$runtime_root/usr/local/lib/python3.14" | wc -l | tr -d ' ')"

echo "Installed runtime resources from GitHub release $release_tag"
echo "  sha: $selected_sha"
echo "  wasm: $runtime_root/bin/boomslang.wasm ($wasm_size bytes)"
echo "  stdlib: $runtime_root/usr/local/lib/python3.14 ($stdlib_paths paths)"
