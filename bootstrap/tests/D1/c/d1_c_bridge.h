#ifndef D1_C_BRIDGE_H
#define D1_C_BRIDGE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct D1Metric {
    int32_t id;
    int32_t count;
    int64_t weight;
} D1Metric;

typedef int32_t (*d1_transform_fn)(int32_t value, int32_t factor);

uint32_t d1_compute_checksum(const uint8_t* data, int32_t len, uint32_t seed);
void d1_combine_metrics_ptr(const D1Metric* a, const D1Metric* b, D1Metric* out);
int32_t d1_apply_transform(int32_t base, int32_t factor, d1_transform_fn fn);

#ifdef __cplusplus
}
#endif

#endif
