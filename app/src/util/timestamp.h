// ADDED BY km.yang(2021.02.17): attach timestamp to filename
#ifndef TIMESTAMP_H
#define TIMESTAMP_H

#include <stdbool.h>
#include <stddef.h>

#define _POSIX_C_SOURCE 200809L
#define ROUND(x) ((x)>=0?(double)((x)+0.5):(double)((x)-0.5))
#define NSEC_PER_SEC 1000000000L
#define MSEC_PER_SEC 1000L

#include <time.h>
#include <sys/time.h>

#include "config.h"

long long
timespec_to_ns(struct timespec *tv);

long int
timespec_to_ms(struct timespec *tv);

long int
current_timestamp(void);

#endif