// C extension init functions statically linked into libpython3.14.a
// These are registered via PyImport_AppendInittab so Python's import
// system finds them as builtins without needing .so files on disk.

unsafe extern "C" {
    pub fn PyInit__pydantic_core() -> *mut pyo3::ffi::PyObject;

    // numpy
    pub fn PyInit__multiarray_umath() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit__simd() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit__pocketfft_umath() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit__umath_linalg() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_lapack_lite() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit__mt19937() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit__philox() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit__pcg64() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit__sfc64() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit__common() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit__generator() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit__bounded_integers() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_bit_generator() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_mtrand() -> *mut pyo3::ffi::PyObject;

    // pandas
    pub fn PyInit__cyutility() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_algos() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_arrays() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_byteswap() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_groupby() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_hashing() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_hashtable() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_index() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_indexing() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_internals() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_interval() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_join() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_json() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_lib() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_missing() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_ops() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_ops_dispatch() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_pandas_datetime() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_pandas_parser() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_parsers() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_properties() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_reshape() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_sas() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_sparse() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_testing() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_tslib() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_writers() -> *mut pyo3::ffi::PyObject;
    // pandas._libs.tslibs.*
    pub fn PyInit_base() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_ccalendar() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_conversion() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_dtypes() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_fields() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_nattype() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_np_datetime() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_offsets() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_parsing() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_period() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_strptime() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_timedeltas() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_timestamps() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_timezones() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_tzconversion() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_vectorized() -> *mut pyo3::ffi::PyObject;
    // pandas._libs.window.*
    pub fn PyInit_aggregations() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_indexers() -> *mut pyo3::ffi::PyObject;

    // matplotlib
    pub fn PyInit__c_internal_utils() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit__path() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit_ft2font() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit__image() -> *mut pyo3::ffi::PyObject;
    pub fn PyInit__backend_agg() -> *mut pyo3::ffi::PyObject;

    // ijson
    pub fn PyInit__yajl2() -> *mut pyo3::ffi::PyObject;
}

pub(crate) fn register_all() {
    unsafe {
        use pyo3::ffi::PyImport_AppendInittab;

        PyImport_AppendInittab(b"_pydantic_core\0".as_ptr() as _, Some(PyInit__pydantic_core));

        for (name, init) in [
            (b"numpy._core._multiarray_umath\0".as_slice(), PyInit__multiarray_umath as _),
            (b"numpy._core._simd\0".as_slice(), PyInit__simd as _),
            (b"numpy.fft._pocketfft_umath\0".as_slice(), PyInit__pocketfft_umath as _),
            (b"numpy.linalg._umath_linalg\0".as_slice(), PyInit__umath_linalg as _),
            (b"numpy.linalg.lapack_lite\0".as_slice(), PyInit_lapack_lite as _),
            (b"numpy.random._mt19937\0".as_slice(), PyInit__mt19937 as _),
            (b"numpy.random._philox\0".as_slice(), PyInit__philox as _),
            (b"numpy.random._pcg64\0".as_slice(), PyInit__pcg64 as _),
            (b"numpy.random._sfc64\0".as_slice(), PyInit__sfc64 as _),
            (b"numpy.random._common\0".as_slice(), PyInit__common as _),
            (b"numpy.random._generator\0".as_slice(), PyInit__generator as _),
            (b"numpy.random._bounded_integers\0".as_slice(), PyInit__bounded_integers as _),
            (b"numpy.random.bit_generator\0".as_slice(), PyInit_bit_generator as _),
            (b"numpy.random.mtrand\0".as_slice(), PyInit_mtrand as _),
        ] {
            PyImport_AppendInittab(name.as_ptr() as _, Some(init));
        }

        for (name, init) in [
            (b"pandas._libs._cyutility\0".as_slice(), PyInit__cyutility as _),
            (b"pandas._libs.algos\0".as_slice(), PyInit_algos as _),
            (b"pandas._libs.arrays\0".as_slice(), PyInit_arrays as _),
            (b"pandas._libs.byteswap\0".as_slice(), PyInit_byteswap as _),
            (b"pandas._libs.groupby\0".as_slice(), PyInit_groupby as _),
            (b"pandas._libs.hashing\0".as_slice(), PyInit_hashing as _),
            (b"pandas._libs.hashtable\0".as_slice(), PyInit_hashtable as _),
            (b"pandas._libs.index\0".as_slice(), PyInit_index as _),
            (b"pandas._libs.indexing\0".as_slice(), PyInit_indexing as _),
            (b"pandas._libs.internals\0".as_slice(), PyInit_internals as _),
            (b"pandas._libs.interval\0".as_slice(), PyInit_interval as _),
            (b"pandas._libs.join\0".as_slice(), PyInit_join as _),
            (b"pandas._libs.json\0".as_slice(), PyInit_json as _),
            (b"pandas._libs.lib\0".as_slice(), PyInit_lib as _),
            (b"pandas._libs.missing\0".as_slice(), PyInit_missing as _),
            (b"pandas._libs.ops\0".as_slice(), PyInit_ops as _),
            (b"pandas._libs.ops_dispatch\0".as_slice(), PyInit_ops_dispatch as _),
            (b"pandas._libs.pandas_datetime\0".as_slice(), PyInit_pandas_datetime as _),
            (b"pandas._libs.pandas_parser\0".as_slice(), PyInit_pandas_parser as _),
            (b"pandas._libs.parsers\0".as_slice(), PyInit_parsers as _),
            (b"pandas._libs.properties\0".as_slice(), PyInit_properties as _),
            (b"pandas._libs.reshape\0".as_slice(), PyInit_reshape as _),
            (b"pandas._libs.sas\0".as_slice(), PyInit_sas as _),
            (b"pandas._libs.sparse\0".as_slice(), PyInit_sparse as _),
            (b"pandas._libs.testing\0".as_slice(), PyInit_testing as _),
            (b"pandas._libs.tslib\0".as_slice(), PyInit_tslib as _),
            (b"pandas._libs.writers\0".as_slice(), PyInit_writers as _),
            (b"pandas._libs.tslibs.base\0".as_slice(), PyInit_base as _),
            (b"pandas._libs.tslibs.ccalendar\0".as_slice(), PyInit_ccalendar as _),
            (b"pandas._libs.tslibs.conversion\0".as_slice(), PyInit_conversion as _),
            (b"pandas._libs.tslibs.dtypes\0".as_slice(), PyInit_dtypes as _),
            (b"pandas._libs.tslibs.fields\0".as_slice(), PyInit_fields as _),
            (b"pandas._libs.tslibs.nattype\0".as_slice(), PyInit_nattype as _),
            (b"pandas._libs.tslibs.np_datetime\0".as_slice(), PyInit_np_datetime as _),
            (b"pandas._libs.tslibs.offsets\0".as_slice(), PyInit_offsets as _),
            (b"pandas._libs.tslibs.parsing\0".as_slice(), PyInit_parsing as _),
            (b"pandas._libs.tslibs.period\0".as_slice(), PyInit_period as _),
            (b"pandas._libs.tslibs.strptime\0".as_slice(), PyInit_strptime as _),
            (b"pandas._libs.tslibs.timedeltas\0".as_slice(), PyInit_timedeltas as _),
            (b"pandas._libs.tslibs.timestamps\0".as_slice(), PyInit_timestamps as _),
            (b"pandas._libs.tslibs.timezones\0".as_slice(), PyInit_timezones as _),
            (b"pandas._libs.tslibs.tzconversion\0".as_slice(), PyInit_tzconversion as _),
            (b"pandas._libs.tslibs.vectorized\0".as_slice(), PyInit_vectorized as _),
            (b"pandas._libs.window.aggregations\0".as_slice(), PyInit_aggregations as _),
            (b"pandas._libs.window.indexers\0".as_slice(), PyInit_indexers as _),
            (b"matplotlib._c_internal_utils\0".as_slice(), PyInit__c_internal_utils as _),
            (b"matplotlib._path\0".as_slice(), PyInit__path as _),
            (b"matplotlib.ft2font\0".as_slice(), PyInit_ft2font as _),
            (b"matplotlib._image\0".as_slice(), PyInit__image as _),
            (b"matplotlib.backends._backend_agg\0".as_slice(), PyInit__backend_agg as _),
            (b"ijson.backends._yajl2\0".as_slice(), PyInit__yajl2 as _),
        ] {
            PyImport_AppendInittab(name.as_ptr() as _, Some(init));
        }
    }
}
