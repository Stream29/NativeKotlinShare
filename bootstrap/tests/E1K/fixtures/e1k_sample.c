#include "e1k_sample.h"

int32_t e1k_global_counter = 100;
const int32_t e1k_immutable_version = 204;

E1kPoint e1k_add_points(E1kPoint a, E1kPoint b) {
    E1kPoint out;
    out.x = a.x + b.x;
    out.y = a.y + b.y;
    return out;
}

int32_t e1k_apply_callback(E1kTransformFn fn, int32_t input, int32_t factor) {
    if (!fn) return -1;
    e1k_global_counter += 1;
    return fn(input, factor);
}

const char* e1k_status_name(E1kStatus status) {
    switch (status) {
        case E1K_STATUS_OK: return "OK";
        case E1K_STATUS_RETRY: return "RETRY";
        case E1K_STATUS_FAIL: return "FAIL";
        default: return "UNKNOWN";
    }
}

int32_t e1k_mode_shift(enum E1kMode mode, int32_t base) {
    return base + (int32_t)mode * 10;
}

int32_t e1k_excluded_helper(int32_t x) {
    return x - 1;
}
