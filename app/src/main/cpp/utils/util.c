/*
    This file is part of NetGuard.

    NetGuard is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    NetGuard is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2015-2024 by Marcel Bokhorst (M66B)
*/

#include "../athena.h"

extern int loglevel;

void *ng_malloc(size_t __byte_count, const char *tag) {
    void *ptr = malloc(__byte_count);
    ng_add_alloc(ptr, tag);
    return ptr;
}

void ng_add_alloc(void *ptr, const char *tag) {
#ifdef PROFILE_MEMORY
    if (ptr == NULL)
        return;

    if (alock == NULL) {
        alock = malloc(sizeof(pthread_mutex_t));
        if (pthread_mutex_init(alock, NULL))
            log_android(ANDROID_LOG_ERROR, "pthread_mutex_init failed");
    }

    if (pthread_mutex_lock(alock))
        log_android(ANDROID_LOG_ERROR, "pthread_mutex_lock failed");

    int c = 0;
    for (; c < allocs; c++)
        if (alloc[c].ptr == NULL)
            break;

    if (c >= allocs) {
        if (allocs == 0)
            alloc = malloc(sizeof(struct alloc_record));
        else
            alloc = realloc(alloc, sizeof(struct alloc_record) * (allocs + 1));
        c = allocs;
        allocs++;
    }

    alloc[c].tag = tag;
    alloc[c].time = time(NULL);
    alloc[c].ptr = ptr;
    balance++;

    if (pthread_mutex_unlock(alock))
        log_android(ANDROID_LOG_ERROR, "pthread_mutex_unlock failed");
#endif
}

void *ng_calloc(size_t __item_count, size_t __item_size, const char *tag) {
    void *ptr = calloc(__item_count, __item_size);
    ng_add_alloc(ptr, tag);
    return ptr;
}

void ng_free(void *__ptr, const char *file, int line) {
    ng_delete_alloc(__ptr, file, line);
    free(__ptr);
}

uint16_t calc_checksum(uint16_t start, const uint8_t *buffer, size_t length) {
    register uint32_t sum = start;
    register uint16_t *buf = (uint16_t *) buffer;
    register size_t len = length;

    while (len > 1) {
        sum += *buf++;
        len -= 2;
    }

    if (len > 0)
        sum += *((uint8_t *) buf);

    while (sum >> 16)
        sum = (sum & 0xFFFF) + (sum >> 16);

    return (uint16_t) sum;
}

int compare_u32(uint32_t s1, uint32_t s2) {
    if (s1 == s2)
        return 0;

    uint32_t i1 = s1;
    uint32_t i2 = s2;
    if ((i1 < i2 && i2 - i1 < 0x7FFFFFFF) ||
        (i1 > i2 && i1 - i2 > 0x7FFFFFFF))
        return -1;
    else
        return 1;
}

void log_android(int prio, const char *fmt, ...) {
    if (prio >= loglevel) {
        char line[1024];
        va_list argptr;
        va_start(argptr, fmt);
        vsnprintf(line, sizeof(line), fmt, argptr);
        __android_log_print(prio, TAG, "%s", line);
        va_end(argptr);
    }
}

char *hex(const u_int8_t *data, const size_t len) {
    char hex_str[] = "0123456789ABCDEF";

    char *hexout;
    hexout = (char *) ng_malloc(len * 3 + 1, "hex"); // TODO free

    for (size_t i = 0; i < len; i++) {
        hexout[i * 3 + 0] = hex_str[(data[i] >> 4) & 0x0F];
        hexout[i * 3 + 1] = hex_str[(data[i]) & 0x0F];
        hexout[i * 3 + 2] = ' ';
    }
    hexout[len * 3] = 0;

    return hexout;
}

int is_event(int fd, short event) {
    struct pollfd p;
    p.fd = fd;
    p.events = event;
    p.revents = 0;
    int r = poll(&p, 1, 0);
    if (r < 0) {
        log_android(ANDROID_LOG_ERROR, "poll readable error %d: %s", errno, strerror(errno));
        return 0;
    } else if (r == 0)
        return 0;
    else
        return (p.revents & event);
}

int is_readable(int fd) {
    return is_event(fd, POLLIN);
}

long long get_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000LL + ts.tv_nsec / 1e6;
}