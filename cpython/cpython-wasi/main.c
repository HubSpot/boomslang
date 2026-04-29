#include <Python.h>

extern PyObject* PyInit__pydantic_core(void);

int main(int argc, char** argv) {
    PyImport_AppendInittab("_pydantic_core", PyInit__pydantic_core);
    return Py_BytesMain(argc, argv);
}
