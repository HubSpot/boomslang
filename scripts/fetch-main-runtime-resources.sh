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
  --workflow <file>    Ignored compatibility option
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
  local headers=(
    -H "Accept: application/vnd.github+json"
    -H "X-GitHub-Api-Version: 2022-11-28"
  )

  if [ -n "${GITHUB_TOKEN:-}" ]; then
    headers+=( -H "Authorization: Bearer $GITHUB_TOKEN" )
  fi

  curl -fsSL "${headers[@]}" "https://api.github.com$path"
}

download_dir="$tmp_dir/download"
mkdir -p "$download_dir"
selected_sha=""
release_tag=""
runtime_asset=""
checksum_asset=""
runtime_url=""
checksum_url=""
tarball=""
checksum_file=""

set_release_vars() {
  selected_sha="$1"
  release_tag="build-$selected_sha"
  runtime_asset="boomslang-runtime-$selected_sha.tar.gz"
  checksum_asset="boomslang-$selected_sha.sha256"
  runtime_url="https://github.com/$repo/releases/download/$release_tag/$runtime_asset"
  checksum_url="https://github.com/$repo/releases/download/$release_tag/$checksum_asset"
  tarball="$download_dir/$runtime_asset"
  checksum_file="$download_dir/$checksum_asset"
}

download_release_assets() {
  local sha="$1"
  set_release_vars "$sha"
  rm -f "$tarball" "$checksum_file"

  echo "Checking $runtime_url"
  if ! curl -fsSLI "$runtime_url" >/dev/null; then
    return 1
  fi

  echo "Downloading $runtime_url"
  curl -fL --retry 3 --retry-delay 2 -o "$tarball" "$runtime_url"

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
}

find_latest_branch_release() {
  local releases_json releases_file
  releases_json="$tmp_dir/releases.json"
  releases_file="$tmp_dir/releases.tsv"

  github_api_get "/repos/$repo/releases?per_page=30" > "$releases_json"
  python3 - "$releases_json" <<'PY' > "$releases_file"
import json
import sys

with open(sys.argv[1]) as releases_json:
    releases = json.load(releases_json)

for release in releases:
    tag = release.get("tag_name", "")
    if release.get("draft") or not tag.startswith("build-"):
        continue
    sha = tag.removeprefix("build-")
    expected_asset = f"boomslang-runtime-{sha}.tar.gz"
    if any(asset.get("name") == expected_asset for asset in release.get("assets", [])):
        print(sha)
PY

  if [ ! -s "$releases_file" ]; then
    echo "ERROR: No published build-* runtime releases found for $repo" >&2
    return 1
  fi

  while IFS= read -r sha; do
    if [ -z "$sha" ]; then
      continue
    fi
    echo "Trying published runtime release from $sha..."
    if download_release_assets "$sha"; then
      return 0
    fi
  done < "$releases_file"

  echo "ERROR: No recent build-* releases had downloadable runtime assets" >&2
  return 1
}

if [ -n "$requested_sha" ]; then
  echo "Looking for published runtime release asset for $repo@$requested_sha..."
  if ! download_release_assets "$requested_sha"; then
    echo "ERROR: Could not download runtime release asset for $requested_sha" >&2
    echo "Make sure the GitHub Actions release job has published runtime assets for that commit." >&2
    exit 1
  fi
else
  echo "Looking for latest successful published runtime release asset for $repo@$branch..."
  if ! find_latest_branch_release; then
    exit 1
  fi
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
"$repo_root/scripts/install-micropip-runtime.sh" "$repo_root"

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
