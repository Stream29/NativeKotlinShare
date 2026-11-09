#include "e1_c_sample.h"

int e1_add_scaled(int a, int b, int scale) {
    return (a + b) * scale;
}

const char* e1_greeting(void) {
    return "E1_CINTEROP_GREETING";
}
