from boomslang import Sandbox


def test_work_mount_roundtrip(tmp_path):
    (tmp_path / "input.txt").write_text("from host")
    with Sandbox(work_dir=tmp_path) as sandbox:
        result = sandbox.execute(
            "print(open('/work/input.txt').read())\n"
            "open('/work/output.txt', 'w').write('from guest')"
        )
        assert result.stdout == "from host\n", result.stderr
    assert (tmp_path / "output.txt").read_text() == "from guest"


def test_managed_work_dir(sandbox):
    sandbox.execute("open('/work/file.txt', 'w').write('data')")
    assert (sandbox.work_dir / "file.txt").read_text() == "data"


def test_work_files_survive_reset(sandbox):
    sandbox.execute("open('/work/keep.txt', 'w').write('kept')")
    sandbox.reset()
    result = sandbox.execute("print(open('/work/keep.txt').read())")
    assert result.stdout == "kept\n", result.stderr


def test_lib_dir_module_import(tmp_path):
    lib_dir = tmp_path / "lib"
    lib_dir.mkdir()
    (lib_dir / "mylib.py").write_text("def greet():\n    return 'hi from /lib'\n")
    with Sandbox(lib_dir=lib_dir) as sandbox:
        result = sandbox.execute("import mylib\nprint(mylib.greet())")
        assert result.stdout == "hi from /lib\n", result.stderr


def test_stdlib_is_readonly(sandbox):
    result = sandbox.execute(
        "try:\n"
        "    open('/usr/local/lib/python3.14/evil.py', 'w').write('x')\n"
        "    print('wrote')\n"
        "except OSError:\n"
        "    print('blocked')"
    )
    assert result.stdout == "blocked\n", result.stderr


def test_tmp_is_writable(sandbox):
    result = sandbox.execute(
        "open('/tmp/scratch.txt', 'w').write('tmp')\n"
        "print(open('/tmp/scratch.txt').read())"
    )
    assert result.stdout == "tmp\n", result.stderr
