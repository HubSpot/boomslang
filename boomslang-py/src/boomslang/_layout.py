"""Discovery of the guest filesystem layout baked into the runtime image.

wasi-libc populates its preopen table during Wizer pre-initialization and the
table is frozen into the memory snapshot. At runtime the guest resolves paths
against those baked names, bound positionally (by fd order) to whatever the
host preopens — the guest-path strings the host registers are ignored, and
preopens beyond the baked table are unreachable.

The baked table differs across runtime builds (it depends on the wasi-libc
version used):

- single-root: one entry "/" — the host provides one root directory shaped
  like the guest fs (usr/, lib/, work/, tmp/). This matches the Java host's
  rootPath contract and is what current builds produce.
- positional: one entry per wizer-fs subdirectory (/usr, /lib, /work, /tmp)
  in image-specific order — the host must register one directory per
  position. Older builds produce this.

Rather than hardcoding either, probe_layout() instantiates a throwaway
instance with marker directories and asks the guest what it sees.
"""

import json
import logging
import os
import shutil
import tempfile
from dataclasses import dataclass
from pathlib import Path

from wasmtime import FuncType, Linker, Store, ValType, WasiConfig

from .errors import RuntimeAssetsError

logger = logging.getLogger(__name__)

_PROBE_NAMES = ("/", "/usr", "/lib", "/work", "/tmp")
_PROBE_SLOTS = 8

_PROBE_SCRIPT = """
import os, json
mapping = {}
for name in (%r):
    try:
        entries = os.listdir(name)
    except OSError:
        continue
    for entry in entries:
        if entry.startswith('boomslang-probe-'):
            mapping[name] = int(entry.rsplit('-', 1)[1])
print(json.dumps(mapping))
""" % (_PROBE_NAMES,)


@dataclass(frozen=True)
class Layout:
    single_root: bool
    # For positional layouts: guest name for each preopen position (None =
    # position exists below a used one but maps to no known name; a filler
    # directory must be registered to keep later positions aligned).
    positions: tuple[str | None, ...] = ()


def probe_layout(engine, module, disarmed_deadline_ticks: int) -> Layout:
    with tempfile.TemporaryDirectory(prefix="boomslang-probe-") as tmp:
        root = Path(tmp)
        for i in range(_PROBE_SLOTS):
            slot = root / f"p{i}"
            slot.mkdir()
            (slot / f"boomslang-probe-{i}").write_text("")

        store = Store(engine)
        store.set_epoch_deadline(disarmed_deadline_ticks)
        wasi = WasiConfig()
        wasi.env = [("PYTHONHOME", "/usr/local")]
        for i in range(_PROBE_SLOTS):
            wasi.preopen_dir(str(root / f"p{i}"), f"/p{i}")
        wasi.stdout_file = str(root / "wasi-stdout.log")
        wasi.stderr_file = str(root / "wasi-stderr.log")
        store.set_wasi(wasi)

        linker = Linker(engine)
        linker.define_wasi()
        i32 = ValType.i32()
        linker.define_func(
            "boomslang", "call", FuncType([i32] * 6, [i32]), lambda *args: -1
        )
        linker.define_func(
            "boomslang", "log", FuncType([i32] * 3, []), lambda *args: None
        )
        instance = linker.instantiate(store, module)
        exports = instance.exports(store)
        memory = exports["memory"]

        code = _PROBE_SCRIPT.encode("utf-8")
        ptr = int(exports["alloc"](store, len(code))) & 0xFFFFFFFF
        memory.write(store, code, ptr)
        status = int(exports["execute"](store, ptr, len(code)))
        out_len = int(exports["get_stdout_len"](store))
        if status != 0 or out_len <= 0:
            raise RuntimeAssetsError(
                f"filesystem layout probe failed (status {status})"
            )
        buf = int(exports["alloc"](store, out_len)) & 0xFFFFFFFF
        exports["get_stdout"](store, buf, out_len)
        mapping: dict[str, int] = json.loads(
            bytes(memory.read(store, buf, buf + out_len)).decode("utf-8")
        )

    if not mapping:
        raise RuntimeAssetsError(
            "filesystem layout probe found no reachable preopens; "
            "the runtime image is incompatible with this host"
        )
    if "/" in mapping:
        return Layout(single_root=True)

    max_position = max(mapping.values())
    positions: list[str | None] = [None] * (max_position + 1)
    for name, position in mapping.items():
        positions[position] = name
    logger.debug("probed positional guest fs layout: %s", positions)
    return Layout(single_root=False, positions=tuple(positions))


# ----------------------------------------------------------------------
# Single-root helpers

def protected_usr_copy(usr_source: Path) -> Path:
    """A shared read-only copy of the runtime's usr/ tree (files 0444,
    directories untouched). Cached in the system temp dir keyed by the source
    identity, so the 75 MB copy happens once per machine per runtime build,
    not per process. Sandboxes hardlink into it; the read-only file mode is
    what protects the shared content from guest writes."""
    source_stat = usr_source.stat()
    key = f"{source_stat.st_dev}-{source_stat.st_ino}-{int(source_stat.st_mtime)}"
    cache = Path(tempfile.gettempdir()) / f"boomslang-usr-{key}"
    marker = cache / ".boomslang-complete"
    if marker.is_file():
        return cache

    staging = Path(
        tempfile.mkdtemp(prefix="boomslang-usr-staging-", dir=tempfile.gettempdir())
    )
    try:
        target = staging / "usr"
        shutil.copytree(usr_source, target)
        for dirpath, _dirnames, filenames in os.walk(target):
            for filename in filenames:
                os.chmod(os.path.join(dirpath, filename), 0o444)
        (target / ".boomslang-complete").write_text("")
        try:
            target.rename(cache)
        except OSError:
            if not marker.is_file():
                raise
        return cache
    finally:
        shutil.rmtree(staging, ignore_errors=True)


def link_tree(source: Path, dest: Path) -> None:
    """Mirror source into dest using hardlinks (copy fallback), then make the
    mirrored directories read-only so the guest cannot create files in them."""
    created_dirs = []
    for dirpath, dirnames, filenames in os.walk(source):
        rel = os.path.relpath(dirpath, source)
        target_dir = dest if rel == "." else dest / rel
        target_dir.mkdir(exist_ok=True)
        created_dirs.append(target_dir)
        for filename in filenames:
            if filename == ".boomslang-complete":
                continue
            source_file = os.path.join(dirpath, filename)
            target_file = target_dir / filename
            try:
                os.link(source_file, target_file)
            except OSError:
                shutil.copy2(source_file, target_file)
                os.chmod(target_file, 0o444)
    for directory in reversed(created_dirs):
        os.chmod(directory, 0o555)


def unprotect_tree(root: Path) -> None:
    """Make directories under root writable again so cleanup can remove them."""
    for dirpath, _dirnames, _filenames in os.walk(root):
        try:
            os.chmod(dirpath, 0o755)
        except OSError:
            pass


def sync_dirs(source: Path, dest: Path) -> None:
    """One-way, name-based sync of files from source into dest. Hardlinks
    where possible so subsequent in-place writes propagate automatically;
    replaces when source is newer; never deletes. Used to emulate a bind
    mount of a user directory in single-root layouts."""
    if not source.is_dir():
        return
    for dirpath, _dirnames, filenames in os.walk(source):
        rel = os.path.relpath(dirpath, source)
        target_dir = dest if rel == "." else dest / rel
        target_dir.mkdir(parents=True, exist_ok=True)
        for filename in filenames:
            source_file = Path(dirpath) / filename
            target_file = target_dir / filename
            try:
                src_stat = source_file.stat()
                if target_file.exists():
                    dst_stat = target_file.stat()
                    if (
                        src_stat.st_dev == dst_stat.st_dev
                        and src_stat.st_ino == dst_stat.st_ino
                    ):
                        continue  # already the same file
                    if src_stat.st_mtime_ns <= dst_stat.st_mtime_ns:
                        continue
                    target_file.unlink()
                try:
                    os.link(source_file, target_file)
                except OSError:
                    shutil.copy2(source_file, target_file)
            except OSError:
                logger.debug(
                    "failed to sync %s -> %s", source_file, target_file, exc_info=True
                )


