import importlib
import base64
import io
import re
import zipfile
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlparse

from .compatibility_layer import CompatibilityLayer


class CompatibilityNotInPyodide(CompatibilityLayer):

    # Vendored from packaging
    # TODO: use packaging APIs here instead?
    _canonicalize_regex = re.compile(r"[-_.]+")
    _remote_schemes = {"http", "https"}
    _chunk_size = 512 * 1024

    class loadedPackages(CompatibilityLayer.loadedPackages):
        @staticmethod
        def to_py():
            return {}

    @staticmethod
    def _encode(value: str) -> str:
        return base64.b64encode(value.encode()).decode("ascii")

    @staticmethod
    def _decode(value: str) -> str:
        return base64.b64decode(value.encode("ascii")).decode()

    @staticmethod
    def _local_path(url: str) -> Path:
        parsed = urlparse(url)
        if parsed.scheme in ("file", "emfs"):
            return Path(unquote(parsed.path))
        return Path(unquote(url))

    @staticmethod
    def _fetch_local(url: str) -> tuple[bytes, dict[str, str]]:
        path = CompatibilityNotInPyodide._local_path(url)
        return path.read_bytes(), {"content-type": "application/octet-stream"}

    @staticmethod
    def _host_call():
        try:
            from boomslang_host import call
        except Exception as e:
            raise OSError(
                "Boomslang micropip remote installs require the HostBridge "
                "extension with a micropip resolver. Configure "
                "HostBridge.builder().withMicropipResolver(...)."
            ) from e
        return call

    @staticmethod
    def _fetch_payload(url: str, kwargs: dict[str, Any]) -> str:
        lines = [url]
        headers = (kwargs or {}).get("headers") or {}
        for name, value in headers.items():
            lines.append(f"{name}\t{CompatibilityNotInPyodide._encode(str(value))}")
        return "\n".join(lines)

    @staticmethod
    def _parse_fetch_response(response: str) -> tuple[int, int, int, dict[str, str]]:
        lines = response.splitlines()
        if not lines:
            raise OSError("Boomslang micropip resolver returned an empty response")

        status = lines[0].split("\t")
        if status[0] == "ERR":
            detail = (
                CompatibilityNotInPyodide._decode(status[1])
                if len(status) > 1
                else "unknown resolver error"
            )
            raise OSError(detail)
        if len(status) != 4 or status[0] != "OK":
            raise OSError(f"Invalid Boomslang micropip resolver response: {lines[0]!r}")

        headers: dict[str, str] = {}
        for line in lines[1:]:
            if not line:
                continue
            name, value = line.split("\t", 1)
            headers[name.lower()] = CompatibilityNotInPyodide._decode(value)

        return int(status[1]), int(status[2]), int(status[3]), headers

    @staticmethod
    def _read_remote_bytes(call, token: int, length: int) -> bytes:
        chunks = []
        offset = 0
        while offset < length:
            chunk_len = min(CompatibilityNotInPyodide._chunk_size, length - offset)
            encoded = call("__micropip_chunk__", f"{token}\t{offset}\t{chunk_len}")
            if encoded.startswith("ERR\t"):
                raise OSError(CompatibilityNotInPyodide._decode(encoded.split("\t", 1)[1]))
            chunk = base64.b64decode(encoded.encode("ascii"))
            if not chunk and chunk_len > 0:
                raise OSError("Boomslang micropip resolver returned an empty chunk")
            chunks.append(chunk)
            offset += len(chunk)
        return b"".join(chunks)

    @staticmethod
    def _fetch_remote(url: str, kwargs: dict[str, Any]) -> tuple[bytes, dict[str, str]]:
        call = CompatibilityNotInPyodide._host_call()
        try:
            response = call(
                "__micropip_fetch__",
                CompatibilityNotInPyodide._fetch_payload(url, kwargs),
            )
        except Exception as e:
            raise OSError(
                "Boomslang micropip remote install failed before the host "
                "resolver responded. Configure "
                "HostBridge.builder().withMicropipResolver(...)."
            ) from e

        token, status, length, headers = CompatibilityNotInPyodide._parse_fetch_response(
            response
        )
        try:
            if status < 200 or status >= 300:
                raise OSError(f"HTTP {status} fetching {url}")
            data = CompatibilityNotInPyodide._read_remote_bytes(call, token, length)
            return data, headers
        finally:
            try:
                call("__micropip_cleanup__", str(token))
            except Exception:
                pass

    @staticmethod
    def _fetch(url: str, kwargs: dict[str, Any]) -> tuple[bytes, dict[str, str]]:
        parsed = urlparse(url)
        if parsed.scheme in CompatibilityNotInPyodide._remote_schemes:
            return CompatibilityNotInPyodide._fetch_remote(url, kwargs)
        return CompatibilityNotInPyodide._fetch_local(url)

    @staticmethod
    async def fetch_bytes(url: str, kwargs: dict[str, Any]) -> bytes:
        data, _ = CompatibilityNotInPyodide._fetch(url, kwargs=kwargs)
        return data

    @staticmethod
    async def fetch_string_and_headers(
        url: str, kwargs: dict[str, Any]
    ) -> tuple[str, dict[str, str]]:
        data, headers = CompatibilityNotInPyodide._fetch(url, kwargs=kwargs)
        return data.decode(), headers

    @staticmethod
    async def install(
        buffer: Any,
        filename: str,
        install_dir: str,
        metadata: dict[str, str] | None = None,
    ) -> None:
        """
        Install a package from a buffer to the specified directory.
        TODO: Remove host tests that depends on internal behavior of install (https://github.com/pyodide/micropip/issues/210)
              to make the compat code simpler
        """
        from micropip.metadata import wheel_dist_info_dir

        with zipfile.ZipFile(io.BytesIO(buffer)) as zf:
            zf.extractall(install_dir)
            pkgname = filename.split("-")[
                0
            ]  # the name will be canonicalized inside wheel_dist_info_dir, so don't care about case
            dist_dir = Path(install_dir) / wheel_dist_info_dir(zf, pkgname)

        if metadata:
            for k, v in metadata.items():
                (dist_dir / k).write_text(v)

        importlib.invalidate_caches()

    @staticmethod
    async def loadPackage(names: str | list[str]) -> None:
        pass

    @staticmethod
    def to_js(
        obj: Any,
        /,
        *,
        depth: int = -1,
        pyproxies=None,
        create_pyproxies: bool = True,
        dict_converter=None,
        default_converter=None,
    ) -> Any:
        return obj

    lockfile_info = {}

    lockfile_packages = {}

    lockfile_base_url = None
