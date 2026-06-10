# Supported Python Libraries

The bundled runtime ships the CPython 3.14 standard library plus these third-party packages, including their native extensions compiled to WASI and statically linked:

```python
import ijson
import jinja2
import matplotlib
import numpy as np
import pandas as pd
from PIL import Image
from pydantic import BaseModel
```

Notes:

- **Matplotlib** renders to in-memory buffers / files (e.g. `savefig` to the instance filesystem); there is no display backend.
- **Pillow** supports reading and writing common formats (PNG round-trips are covered by integration tests).
- Packages with native code cannot be `pip install`ed into the runtime — WASI has no dynamic linking, so native extensions must be statically linked at build time. To add one, extend the [build pipeline](custom-python-builds.md).
- Pure-Python packages can be added without rebuilding anything via [in-memory modules or the resource pipeline](python-modules.md).
