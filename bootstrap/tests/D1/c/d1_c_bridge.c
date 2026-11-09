#include "d1_c_bridge.h"

uint32_t d1_compute_checksum(const uint8_t* data, int32_t len, uint32_t seed) {
    uint32_t h = seed ^ 0x811c9dc5u;
    if (data != 0 && len > 0) {
        for (int32_t i = 0; i < len; ++i) {
            h ^= (uint32_t)data[i];
            h *= 0x01000193u;
        }
    }
    return h;
}

void d1_combine_metrics_ptr(const D1Metric* a, const D1Metric* b, D1Metric* out) {
    if (a == 0 || b == 0 || out == 0) {
        return;
    }
    out->id = a->id * 100 + b->id;
    out->count = a->count + b->count;
    out->weight = a->weight * 3 + b->weight * 5;
}

int32_t d1_apply_transform(int32_t base, int32_t factor, d1_transform_fn fn) {
    if (fn == 0) {
        return base + factor;
    }
    return fn(base, factor) + 7;
}
