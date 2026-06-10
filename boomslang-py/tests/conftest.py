import pytest

from boomslang import Sandbox


@pytest.fixture(scope="session", autouse=True)
def warm_runtime():
    # Compile/load the module once up front so individual tests don't absorb
    # the cold-cache cost.
    from boomslang._engine import runtime

    runtime()


@pytest.fixture
def sandbox():
    with Sandbox() as sb:
        yield sb
