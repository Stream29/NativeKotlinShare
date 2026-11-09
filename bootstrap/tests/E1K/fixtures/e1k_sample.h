#ifndef E1K_SAMPLE_H
#define E1K_SAMPLE_H

#include <stdint.h>

#define E1K_MAGIC_INT 42
#define E1K_MAGIC_HEX 0x1020
#define E1K_GREETING "hello-e1k"
#define E1K_EXCLUDED_MACRO 999

enum E1kMode {
    E1K_MODE_FAST = 0,
    E1K_MODE_SAFE = 1,
    E1K_MODE_CUSTOM = 7
};

typedef enum E1kStatus {
    E1K_STATUS_OK = 0,
    E1K_STATUS_RETRY = 2,
    E1K_STATUS_FAIL = -1
} E1kStatus;

typedef int32_t E1kCoord;

typedef struct E1kPoint {
    E1kCoord x;
    E1kCoord y;
} E1kPoint;

struct E1kOpaque;

typedef int32_t (*E1kTransformFn)(int32_t input, int32_t factor);

extern int32_t e1k_global_counter;
extern const int32_t e1k_immutable_version;

E1kPoint e1k_add_points(E1kPoint a, E1kPoint b);
int32_t e1k_apply_callback(E1kTransformFn fn, int32_t input, int32_t factor);
const char* e1k_status_name(E1kStatus status);
int32_t e1k_mode_shift(enum E1kMode mode, int32_t base);
int32_t e1k_excluded_helper(int32_t x);

#endif /* E1K_SAMPLE_H */
