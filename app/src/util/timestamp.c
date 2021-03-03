// ADDED BY km.yang(2021.02.17): attach timestamp to filename
#include "timestamp.h"

long long
timespec_to_ns(struct timespec *tv) {
    return ((long long)tv->tv_sec*NSEC_PER_SEC) + tv->tv_nsec;
}

long int
timespec_to_ms(struct timespec *tv) {
    return (long int)(((long int)tv->tv_sec*NSEC_PER_SEC) + tv->tv_nsec) / 1e6;
}

long int
current_timestamp(void) {
    struct timespec tv;
    if(clock_gettime(CLOCK_REALTIME, &tv)) {
        return 0;
    }
    return timespec_to_ms(&tv);
}