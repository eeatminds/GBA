// Host-side stand-in for <android/log.h> so netpacket.cpp can be unit tested on a PC.
#pragma once
#include <cstdio>
enum { ANDROID_LOG_VERBOSE = 2, ANDROID_LOG_DEBUG, ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR, ANDROID_LOG_FATAL };
#define __android_log_print(prio, tag, ...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr), 0)
