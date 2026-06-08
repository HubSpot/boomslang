#!/usr/bin/env bash
set -euo pipefail

repo_root="${1:-}"
if [ -z "$repo_root" ]; then
  repo_root="$(git rev-parse --show-toplevel)"
fi
repo_root="$(cd "$repo_root" && pwd)"

submodule_dir="$repo_root/third_party/micropip"
packaging_dir="$submodule_dir/micropip/_vendored/packaging/src/packaging"
target_lib="$repo_root/core/src/main/resources/python/usr/local/lib/python3.14"
dist_info="micropip-0.11.1.dist-info"

if [ ! -d "$submodule_dir/micropip" ] || [ ! -d "$packaging_dir" ]; then
  echo "Initializing micropip submodule..."
  git -C "$repo_root" submodule update --init --checkout --recursive third_party/micropip
fi

if [ ! -d "$submodule_dir/micropip" ]; then
  echo "ERROR: micropip submodule is missing. Run: git submodule update --init --recursive third_party/micropip" >&2
  exit 1
fi

if [ ! -d "$packaging_dir" ]; then
  echo "ERROR: micropip nested packaging submodule is missing. Run: git submodule update --init --recursive third_party/micropip" >&2
  exit 1
fi

if [ ! -d "$submodule_dir/$dist_info" ]; then
  echo "ERROR: micropip submodule is missing $dist_info on the boomslang branch" >&2
  exit 1
fi

mkdir -p "$target_lib"
rm -rf "$target_lib/micropip" "$target_lib"/micropip-*.dist-info
cp -R "$submodule_dir/micropip" "$target_lib/micropip"
cp -R "$submodule_dir/$dist_info" "$target_lib/$dist_info"

echo "Installed micropip runtime package from third_party/micropip"
