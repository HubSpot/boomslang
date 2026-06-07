import functools
from importlib.metadata import Distribution
from pathlib import Path
from sysconfig import get_config_var

from ._vendored.packaging.src.packaging.requirements import (
    InvalidRequirement,
    Requirement,
)
from ._vendored.packaging.src.packaging.tags import Tag
from ._vendored.packaging.src.packaging.tags import sys_tags as sys_tags_orig
from ._vendored.packaging.src.packaging.utils import (
    BuildTag,
    InvalidWheelFilename,
    canonicalize_name,
)
from ._vendored.packaging.src.packaging.utils import (
    parse_wheel_filename as parse_wheel_filename_orig,
)
from ._vendored.packaging.src.packaging.version import InvalidVersion, Version


def get_dist_info(dist: Distribution) -> Path:
    """
    Get the .dist-info directory of a distribution.
    """
    return dist._path  # type: ignore[attr-defined]


def get_root(dist: Distribution) -> Path:
    """
    Get the root directory where a package is installed.
    This is normally the site-packages directory.
    """
    return get_dist_info(dist).parent


def get_files_in_distribution(dist: Distribution) -> set[Path]:
    """
    Get a list of files in a distribution, using the metadata.

    Parameters
    ----------
    dist
        Distribution to get files from.

    Returns
    -------
    A list of files in the distribution.
    """

    root = get_root(dist)
    dist_info = get_dist_info(dist)

    files_to_remove = set()
    pkg_files = dist.files or []
    metadata_files = dist_info.glob("*")

    for file in pkg_files:
        abspath = (root / file).resolve()
        files_to_remove.add(abspath)

    # Also add all files in the .dist-info directory.
    # Since micropip adds some extra files there, we need to remove them too.
    files_to_remove.update(metadata_files)

    return files_to_remove


@functools.cache
def sys_tags() -> tuple[Tag, ...]:
    new_tags: list[Tag] = []

    abi_version = get_config_var("PYEMSCRIPTEN_PLATFORM_VERSION")
    if not abi_version:
        # Fallback to PYODIDE_ABI_VERSION for compatibility
        abi_version = get_config_var("PYODIDE_ABI_VERSION")

    pyodide_platform_tags = [
        # PEP 783
        f"pyemscripten_{abi_version}_wasm32",
        # for backward compatibility
        f"pyodide_{abi_version}_wasm32",
    ]
    for tag in sys_tags_orig():
        if "emscripten" in tag.platform:
            new_tags.extend(
                Tag(tag.interpreter, tag.abi, pyodide_platform_tag)
                for pyodide_platform_tag in pyodide_platform_tags
            )
        new_tags.append(tag)
    return tuple(new_tags)


@functools.cache
def parse_wheel_filename(
    filename: str,
) -> tuple[str, Version, BuildTag, frozenset[Tag]]:
    return parse_wheel_filename_orig(filename)


# TODO: Move these helper functions back to WheelInfo
def parse_version(filename: str) -> Version:
    return parse_wheel_filename(filename)[1]


def parse_tags(filename: str) -> frozenset[Tag]:
    return parse_wheel_filename(filename)[3]


def best_compatible_tag_index(tags: frozenset[Tag]) -> int | None:
    """Get the index of the first tag in ``packaging.tags.sys_tags()`` that a wheel has.

    Since ``packaging.tags.sys_tags()`` is sorted from most specific ("best") to most
    general ("worst") compatibility, this index doubles as a priority rank: given two
    compatible wheels, the one whose best index is closer to zero should be installed.

    Parameters
    ----------
    tags
        The tags to check.

    Returns
    -------
    The index, or ``None`` if this wheel has no compatible tags.
    """
    for index, tag in enumerate(sys_tags()):
        if tag in tags:
            return index
    return None


def is_package_compatible(filename: str) -> tuple[bool, int | None]:
    """
    Check if a package is compatible with the current platform.

    Parameters
    ----------
    filename
        Filename of the package to check.
    """

    if not filename.endswith(".whl"):
        return False, None

    try:
        tags = parse_tags(filename)
    except (InvalidVersion, InvalidWheelFilename):
        return False, None

    pure_python_tags = frozenset(
        tag for tag in tags if tag.abi == "none" and tag.platform == "any"
    )
    tag_index = best_compatible_tag_index(pure_python_tags)
    return (tag_index is not None), tag_index


def check_compatible(filename: str) -> None:
    """
    Check if a package is compatible with the current platform.
    If not, raise an exception with a error message that explains why.
    """
    compatible, _ = is_package_compatible(filename)
    if compatible:
        return

    # Not compatible, now we need to figure out why.

    try:
        tags = parse_tags(filename)
    except InvalidWheelFilename:
        raise ValueError(f"Wheel filename is invalid: {filename!r}") from None
    except InvalidVersion:
        raise ValueError(f"Wheel version is invalid: {filename!r}") from None

    tag_descriptions = ", ".join(
        sorted(f"{tag.interpreter}-{tag.abi}-{tag.platform}" for tag in tags)
    )
    raise ValueError(
        "Boomslang micropip only supports pure-Python wheels compatible with "
        f"py3-none-any. Native or platform-specific wheels must be built into "
        f"the WASI runtime image instead. Wheel {filename!r} has tags: "
        f"{tag_descriptions}."
    )


def validate_constraints(
    constraints: list[str] | None,
    environment: dict[str, str] | None = None,
) -> tuple[dict[str, Requirement], dict[str, list[str]]]:
    """Build a validated ``Requirement`` dictionary from raw constraint strings.

    Parameters
    ----------
    constraints (list):
        A list of PEP-508 dependency specs, expected to contain both a package
        name and at least one specifier.

    environment (optional dict):
        The markers for the current environment, such as OS, Python implementation.
        If ``None``, the current execution environment will be used.

    Returns
    -------
        A 2-tuple of:
        - a dictionary of ``Requirement`` objects, keyed by canonical name
        - a dictionary of message strings, keyed by constraint
    """
    reqs: dict[str, Requirement] = {}
    all_messages: dict[str, list[str]] = {}

    for raw_constraint in constraints or []:
        messages: list[str] = []

        try:
            req = Requirement(raw_constraint)
            req.name = canonicalize_name(req.name)
        except InvalidRequirement as err:
            all_messages[raw_constraint] = [f"failed to parse: {err}"]
            continue

        if req.extras:
            messages.append("may not provide [extras]")

        if not (req.url or len(req.specifier)):
            messages.append("no version or URL")

        if req.marker and not req.marker.evaluate(environment):
            messages.append(f"not applicable: {req.marker}")

        if messages:
            all_messages[raw_constraint] = messages
        elif req.name in reqs:
            all_messages[raw_constraint] = [
                f"updated existing constraint for {req.name}"
            ]
            reqs[req.name] = constrain_requirement(req, reqs)
        else:
            reqs[req.name] = req

    return reqs, all_messages


def constrain_requirement(
    requirement: Requirement, constrained_requirements: dict[str, Requirement]
) -> Requirement:
    """Modify or replace a requirement based on a set of constraints.

    Parameters
    ----------
    requirement (Requirement):
        A ``Requirement`` to constrain.

    constrained_requirements (dict):
        A dictionary of ``Requirement`` objects, keyed by canonical name.

    Returns
    -------
        A constrained ``Requirement``.
    """
    # URLs cannot be merged
    if requirement.url:
        return requirement

    constrained = constrained_requirements.get(canonicalize_name(requirement.name))

    if constrained:
        if constrained.url:
            return constrained
        requirement.specifier = requirement.specifier & constrained.specifier

    return requirement
