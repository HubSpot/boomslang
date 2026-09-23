# Testing guide — lxml-wasi + python-pptx

This guide covers how to validate the `lxml-wasi` artifact and the `python-pptx` integration
in the Boomslang WASM CPython image (HubSpotEngineering/breeze-artifacts#261).

---

## What you can and cannot test locally without Blazar

| Step | Local? | Notes |
|------|--------|-------|
| Dockerfile syntax / logic review | ✅ | No build infra needed |
| `blazar build lxml-wasi` | ❌ | Requires Blazar CI pod (32 vCPU / 64 GB) |
| `blazar build cpython-wasi` | ❌ | Requires Blazar CI pod + all sub-artifacts |
| Import smoke test in sandbox | ❌ | Requires built `python-*.wasm` |
| python-pptx round-trip in sandbox | ❌ | Same |
| Run skill scripts in sandbox | ❌ | Same |
| Run skill scripts with local Python (non-sandbox) | ✅ | Quick confidence check before CI |

**Everything below assumes Blazar infrastructure unless the step says "local only".**

---

## Part 1 — Build lxml-wasi artifact

```bash
# From the repo root, trigger a Blazar build of just the lxml-wasi module:
blazar build lxml-wasi
# Estimated time: 15–25 min (dominated by libxslt autoconf + make)
# Pod size: 32 vCPU / 64 GB (set in lxml-wasi/.blazar.yaml)
```

### What to watch for

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| `./autogen.sh: command not found` | `autoconf`/`automake` not in Blazar base image | Add `apt-get install -y autoconf automake libtool` to Dockerfile |
| `configure: error: XML library not found` | libxml2 include path wrong | Verify `/build/wasi-libs/include/libxml2/libxml/parser.h` exists |
| `undefined reference to pthread_*` | libxslt assumes POSIX threads | Add `--without-threads` to libxslt configure |
| `error: implicit declaration of function 'getpid'` | Missing WASI emulation flag | Add `-D_WASI_EMULATED_GETPID` to `WASI_CFLAGS` |
| `PyInit_etree NOT found in archive` | lxml etree.c failed to compile | Check preceding `Compiling lxml/etree.c...` step for errors |
| `lxml/etree.c: No such file or directory` | sdist extraction path mismatch | Check `ls /build/staging/lxml-*/src/lxml/` |

### Expected successful output

```
==> OK   lxml_etree: PyInit_etree (...)
==> OK   lxml_objectify: PyInit_objectify (...)
==> DONE. Manifest:
lxml_etree PyInit_etree ...
lxml_objectify PyInit_objectify ...
==> Archives:
... lib_lxml_etree.a
... lib_lxml_objectify.a
```

---

## Part 2 — Build cpython-wasi (full image)

```bash
blazar build cpython-wasi
# Estimated time: 20–30 min on a 32-vCPU pod
# This will download all sub-artifacts (numpy, pandas, pillow, ijson, lxml, …)
# then run cpython-wasi/build.sh which links them all together.
```

### What to watch for

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| `ERROR: Could not download lxml-wasi artifact` | lxml-wasi build hasn't run yet | Build lxml-wasi first (or Blazar `depends:` handles ordering) |
| `addlib …/lib_lxml_etree.a` fails in `ar -M` merge | Archive not found | Check that `lxml-wasi.tgz` unpacks to `lib/wasm32-wasi/lib_lxml_*.a` |
| `pip3 download … python-pptx` fails | Blazar pip mirror not reachable | pip.conf is mounted as a Blazar build secret; check the mirror config |
| Wheel extract fails for `pptx` | Wrong python version / pure-Python check | Confirm the wheel is `py3-none-any` (pure Python) |

---

## Part 3 — Import smoke test in the sandbox runtime

Refer to `docs/src/guide/running-python.md` for the exact command to start the Boomslang
sandbox. The typical invocation (from that guide) is:

```bash
# Adjust the path to your local boomslang build output:
wasmtime run --dir /tmp --mapdir /usr::/path/to/output/usr \
    /path/to/output/bin/python-3.14.5.wasm -- -c "
import lxml
print('lxml version:', lxml.__version__)
from lxml import etree
doc = etree.fromstring('<a x=\"1\"><b/></a>')
print('etree OK:', etree.tostring(doc))
result = doc.xpath('/a/@x')
print('xpath OK:', result)
import pptx
print('pptx version:', pptx.__version__)
print('All imports OK')
"
```

Expected output:
```
lxml version: 5.3.x
etree OK: b'<a x="1"><b/></a>'
xpath OK: ['1']
pptx version: 1.0.x
All imports OK
```

---

## Part 4 — python-pptx round-trip test

Run this inside the sandbox to confirm the full python-pptx stack works end to end:

```python
from pptx import Presentation
from pptx.util import Inches, Pt
import io

# Build a deck
prs = Presentation()                              # opens the default template (uses lxml)
slide_layout = prs.slide_layouts[1]
slide = prs.slides.add_slide(slide_layout)
slide.shapes.title.text = "Hello from Boomslang"
slide.placeholders[1].text = "generated in-sandbox"

# Serialize to BytesIO
buf = io.BytesIO()
prs.save(buf)                                     # serialize (lxml writes XML)
print(f"Saved: {len(buf.getvalue())} bytes")

# Reopen and assert round-trip
buf.seek(0)
prs2 = Presentation(buf)                          # reopen (lxml parses XML)
title = prs2.slides[0].shapes.title.text
assert title == "Hello from Boomslang", f"round-trip failed: got {title!r}"
print(f"Round-trip OK — title: {title!r}")
```

Expected output:
```
Saved: <N> bytes
Round-trip OK — title: 'Hello from Boomslang'
```

---

## Part 5 — Run the actual skill scripts (local, non-sandbox)

For a quick confidence check **before** CI, you can run the skill scripts with your
system Python (requires `pip install python-pptx lxml` locally):

```bash
# From HubSpotEngineering/breeze-canvas (draft PR #97):
# Skill 1 — template extraction
python3 pptx-template-extraction/scripts/extract.py <path/to/template.pptx>

# Skill 2 — deck generation
python3 pptx-deck-generation/scripts/generate.py <topic> <template_map.json>
```

This validates the skill logic without Boomslang. To run inside the actual sandbox,
replace `python3` with the Boomslang sandbox runner from the running-python guide.

---

## Part 6 — CI gate

Opening or pushing to the PR triggers Blazar CI on `HubSpot/boomslang`, which will:

1. Build `lxml-wasi` (new module)
2. Build `cpython-wasi` (uses `depends: [lxml-wasi]` in `.blazar.yaml`)

A green `cpython-wasi` build is the acceptance gate. The build log for step B6 in
`cpython-wasi/build.sh` should show:

```
==> lxml archives: 2 files, ...
lxml_etree PyInit_etree ...
lxml_objectify PyInit_objectify ...
==> Downloading python-pptx and its pure-Python deps...
Extracting /tmp/wheels-pptx/python_pptx-....whl
Extracting /tmp/wheels-pptx/XlsxWriter-....whl
```

---

## Known unknowns / risks

1. **libxslt WASI compatibility** — the main open risk. `libxslt` uses POSIX file I/O
   and (optionally) threads. The `--without-crypto --without-plugins` flags reduce the
   surface, but a POSIX-specific call in a hot path may cause a `configure` check to fail
   or produce a linker error. Mitigation: add `--without-threads` if pthread symbols appear.

2. **CPython 3.14 ABI** — the sandbox is CPython 3.14.5 (not 3.11). lxml ≥ 5.x requires
   Python ≥ 3.9, so there is no version conflict, but lxml's Cython-generated C uses
   CPython internal APIs that may differ between 3.11 and 3.14. If `PyInit_etree` links
   but crashes at runtime, check for deprecated `PyObject_*` API usage.

3. **lxml shim files** — lxml's `__init__.py` and some submodules (e.g. `lxml._elementpath`)
   do `from lxml.etree import ...`. Because `lxml.etree` is a builtin registered via
   `PyImport_AppendInittab`, Python's BuiltinImporter handles it — no `.py` shim needed
   (same as `numpy._core._multiarray_umath`). If imports fail, check that the dotted name
   in `builtins.rs` exactly matches what lxml expects (`lxml.etree`, not `etree`).

4. **fonttools** — currently a transitive harvest from `matplotlib-wasi`. If `pptx-deck-generation`
   relies on `fonttools` for text metric fitting independently of matplotlib, it may already
   be present. If not, `cpython/fonttools-wasi/` is a separate follow-up (small, pure-Python
   harvest, no new C extensions).
