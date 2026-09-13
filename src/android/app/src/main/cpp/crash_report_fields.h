// T283+ — pure /proc-text parsing helpers for the native crash handler.
//
// Kept free of JNI/Android dependencies so the logic can be compiled and
// executed on the host: scripts/native/crash_fields_host_test.cpp (run via
// crash_fields_host_test.sh, no NDK needed). crash_handler.cpp only calls
// these helpers, and it must stay async-signal-safe (no malloc/printf), so
// everything here works on caller-provided buffers only.
#pragma once

#include <cstddef>

namespace minis_crash {

// Byte-wise prefix compare that avoids pulling in <cstring> (which would be
// fine, but this keeps the header dependency-free and trivially inlined).
inline bool startsWith(const char* text, const char* key, std::size_t keylen) {
    for (std::size_t i = 0; i < keylen; i++) {
        if (text[i] != key[i]) return false;
    }
    return true;
}

// Copies the first token of a "<key>: value" line out of a /proc-style text
// blob into outbuf (NUL-terminated). Leading blanks/tabs after the ':' are
// skipped and the copy stops at the next blank/tab/newline, so both
// "RssAnon:\t5900000 kB" and "MemAvailable: 6440000 kB" yield a bare number
// (the report adds its own unit; copying the whole tail would print "kB kB").
// Returns false when the key is absent, the key is not followed by ':', or
// outsize == 0. The match is exact, so "RssAnon" never matches "RssAnonX:".
inline bool extractField(const char* text, const char* key, char* outbuf, std::size_t outsize) {
    if (text == nullptr || key == nullptr || outbuf == nullptr || outsize == 0) return false;
    outbuf[0] = '\0';
    std::size_t keylen = 0;
    while (key[keylen] != '\0') keylen++;
    if (keylen == 0) return false;

    const char* p = text;
    while (*p != '\0') {
        const char* line_end = p;
        while (*line_end != '\0' && *line_end != '\n') line_end++;
        const std::size_t line_len = static_cast<std::size_t>(line_end - p);
        if (line_len > keylen && p[keylen] == ':' && startsWith(p, key, keylen)) {
            const char* v = p + keylen + 1;
            while (v < line_end && (*v == ' ' || *v == '\t')) v++;
            const char* v_end = v;
            while (v_end < line_end && *v_end != ' ' && *v_end != '\t') v_end++;
            std::size_t vlen = static_cast<std::size_t>(v_end - v);
            if (vlen >= outsize) vlen = outsize - 1;
            for (std::size_t i = 0; i < vlen; i++) outbuf[i] = v[i];
            outbuf[vlen] = '\0';
            return true;
        }
        if (*line_end == '\0') break;
        p = line_end + 1;
    }
    return false;
}

// Counts lines in a text blob: the number of '\n' plus one when the blob is
// non-empty and does not end with '\n' ("a\nb" and "a\nb\n" both count 2).
// Used for the /proc/self/maps entry count — mapping/VMA pressure, which RSS
// alone hides (the 2026-09-13 death had VmRSS 6.0GB but VmPeak 16.8GB).
inline std::size_t countLines(const char* data, std::size_t len) {
    if (data == nullptr || len == 0) return 0;
    std::size_t lines = 0;
    for (std::size_t i = 0; i < len; i++) {
        if (data[i] == '\n') lines++;
    }
    if (data[len - 1] != '\n') lines++;
    return lines;
}

// Parses a leading decimal number ("6440000 kB" -> 6440000). Returns
// fallback when the text holds no digit (missing field, "--", garbage).
inline long parseLeadingLong(const char* text, long fallback) {
    if (text == nullptr) return fallback;
    const char* p = text;
    while (*p == ' ' || *p == '\t') p++;
    long value = 0;
    bool any = false;
    while (*p >= '0' && *p <= '9') {
        value = value * 10 + (*p - '0');
        any = true;
        p++;
    }
    return any ? value : fallback;
}

}  // namespace minis_crash
