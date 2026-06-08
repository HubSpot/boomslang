#!/usr/bin/env bash
set -euo pipefail

repo="${BOOMSLANG_GITHUB_REPO:-HubSpot/boomslang}"
branch="${BOOMSLANG_GITHUB_BRANCH:-main}"
workflow="${BOOMSLANG_GITHUB_WORKFLOW:-build.yml}"
requested_sha="${BOOMSLANG_GITHUB_SHA:-}"
repo_root_arg="."
branch_was_explicit="false"

usage() {
  cat <<'EOF'
Usage: scripts/fetch-main-runtime-resources.sh [options] [repo-root]

Fetch a published Boomslang runtime artifact and install it into
core/src/main/resources/python.

Options:
  --branch <branch>    Fetch the latest artifact for a branch (default: main)
  --sha <sha>          Fetch the artifact for a specific commit SHA
  --repo <owner/repo>  GitHub repository to query (default: HubSpot/boomslang)
  --workflow <file>    Workflow file name or ID (default: build.yml)
  -h, --help           Show this help

Environment defaults:
  BOOMSLANG_GITHUB_REPO
  BOOMSLANG_GITHUB_BRANCH
  BOOMSLANG_GITHUB_SHA
  BOOMSLANG_GITHUB_WORKFLOW

Examples:
  just fetch-main-wasm
  just fetch-main-wasm -- --sha 8f1d2cd2cec1de555e9dcfb585ca2b29d84cb97d
  ./scripts/fetch-main-runtime-resources.sh --branch main
  ./scripts/fetch-main-runtime-resources.sh --sha 8f1d2cd2cec1de555e9dcfb585ca2b29d84cb97d
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
      branch_was_explicit="true"
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
      if [ "$#" -lt 2 ]; then
        echo "ERROR: --workflow requires a value" >&2
        exit 1
      fi
      workflow="$2"
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

require_command gh
require_command python3
require_command tar

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

runs_file="$tmp_dir/runs.tsv"
selected_run_id=""
selected_sha=""
selected_source=""
release_checked_sha=""
download_dir="$tmp_dir/download"

try_download_release() {
  local sha="$1"

  if [ "$release_checked_sha" = "$sha" ]; then
    return 1
  fi
  release_checked_sha="$sha"

  rm -rf "$download_dir"
  mkdir -p "$download_dir"

  echo "Trying release assets for $sha..."
  if gh release download "build-$sha" \
    --repo "$repo" \
    --pattern "boomslang-runtime-$sha.tar.gz" \
    --pattern "boomslang-$sha.sha256" \
    --dir "$download_dir" >/dev/null 2>&1; then
    selected_run_id=""
    selected_sha="$sha"
    selected_source="GitHub release build-$sha"
    return 0
  fi

  return 1
}

try_download_for_run() {
  local run_id="$1"
  local sha="$2"
  local conclusion="$3"

  if try_download_release "$sha"; then
    selected_run_id="$run_id"
    return 0
  fi

  rm -rf "$download_dir"
  mkdir -p "$download_dir"

  echo "Trying workflow artifact for $sha ($conclusion workflow run $run_id)..."
  if gh run download "$run_id" \
    --repo "$repo" \
    --name "boomslang-runtime-$sha" \
    --dir "$download_dir" >/dev/null 2>&1; then
    selected_run_id="$run_id"
    selected_sha="$sha"
    selected_source="GitHub Actions artifact boomslang-runtime-$sha from run $run_id"
    return 0
  fi

  return 1
}

query_runs() {
  local args=(
    --hostname github.com
    --method GET
    "/repos/$repo/actions/workflows/$workflow/runs"
    -f status=completed
    -f per_page=20
  )

  if [ -n "$requested_sha" ]; then
    args+=( -f head_sha="$requested_sha" )
    if [ "$branch_was_explicit" = "true" ]; then
      args+=( -f branch="$branch" )
    fi
  else
    args+=( -f branch="$branch" )
  fi

  gh api "${args[@]}" --jq '.workflow_runs[] | [.id, .head_sha, .conclusion] | @tsv' > "$runs_file"
}

if [ -n "$requested_sha" ]; then
  echo "Looking for runtime artifact for $repo@$requested_sha..."
  try_download_release "$requested_sha" || true
else
  echo "Looking for recent $branch runtime artifacts in $repo..."
fi

if [ -z "$selected_sha" ]; then
  query_runs

  if [ ! -s "$runs_file" ]; then
    if [ -n "$requested_sha" ]; then
      echo "ERROR: No completed $workflow runs found for $repo@$requested_sha" >&2
    else
      echo "ERROR: No completed $workflow runs found for $repo@$branch" >&2
    fi
    exit 1
  fi

  while IFS=$'\t' read -r run_id sha conclusion; do
    if [ -z "$run_id" ] || [ -z "$sha" ]; then
      continue
    fi
    if try_download_for_run "$run_id" "$sha" "$conclusion"; then
      break
    fi
  done < "$runs_file"
fi

if [ -z "$selected_sha" ]; then
  if [ -n "$requested_sha" ]; then
    echo "ERROR: Could not download runtime artifact for $repo@$requested_sha" >&2
  else
    echo "ERROR: Could not download runtime artifact from recent $branch workflow runs" >&2
  fi
  exit 1
fi

tarball="$download_dir/boomslang-runtime-$selected_sha.tar.gz"
if [ ! -f "$tarball" ]; then
  tarball="$(find "$download_dir" -name 'boomslang-runtime-*.tar.gz' -print -quit)"
fi

if [ -z "$tarball" ] || [ ! -f "$tarball" ]; then
  echo "ERROR: Downloaded artifact did not contain boomslang-runtime-$selected_sha.tar.gz" >&2
  exit 1
fi

checksum_file="$download_dir/boomslang-$selected_sha.sha256"
if [ -f "$checksum_file" ]; then
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

echo "Installed runtime resources from $selected_source"
if [ -n "$selected_run_id" ]; then
  echo "  run: $selected_run_id"
fi
echo "  sha: $selected_sha"
echo "  wasm: $runtime_root/bin/boomslang.wasm ($wasm_size bytes)"
echo "  stdlib: $runtime_root/usr/local/lib/python3.14 ($stdlib_paths paths)"
