#ifndef _C1S_STRING_H
#define _C1S_STRING_H

typedef unsigned long size_t;

void* memcpy(void* dst, const void* src, size_t n);
void* memset(void* dst, int c, size_t n);
size_t strlen(const char* s);

#endif /* _C1S_STRING_H */
