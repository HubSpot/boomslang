// pybind11's locks protect concurrent access. Boomslang runs one thread per
// WASI instance; keep the call-once state while omitting unavailable OS locks.
#pragma once

#if !defined(__wasi__) || defined(__wasm_threads__) || defined(Py_GIL_DISABLED)
#error "The pybind11 WASI threading adapter requires single-threaded WASI with the GIL"
#endif

#include <cstdlib>
#include <utility>

namespace boomslang_wasi {
class mutex {
public:
    void lock() noexcept {}
    void unlock() noexcept {}
};

template <class Mutex> class lock_guard {
public:
    explicit lock_guard(Mutex &value) : value_(value) { value_.lock(); }
    ~lock_guard() { value_.unlock(); }
    lock_guard(const lock_guard &) = delete;
    lock_guard &operator=(const lock_guard &) = delete;
private:
    Mutex &value_;
};

struct once_flag {
    bool complete = false;
    bool running = false;
};

template <class Callable, class... Args>
void call_once(once_flag &flag, Callable &&callable, Args &&...args) {
    if (flag.complete) return;
    // Recursive initialization cannot complete, just as with std::call_once.
    if (flag.running) std::abort();
    flag.running = true;
    struct reset_running {
        once_flag &flag;
        ~reset_running() { flag.running = false; }
    } reset{flag};
    std::forward<Callable>(callable)(std::forward<Args>(args)...);
    flag.complete = true;
}
} // namespace boomslang_wasi
