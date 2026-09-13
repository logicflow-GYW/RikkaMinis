// Host-side tests for crash_report_fields.h — the pure parsing helpers the
// native crash handler uses to capture the death-state memory profile.
//
// Why host tests: the handler itself only runs on Android after a real fatal
// signal, so the parsing logic would otherwise ship unverified. Everything in
// the header is dependency-free, so the exact same source lines are compiled
// here by the host compiler and asserted against fixture /proc text.
//
// Run: scripts/native/crash_fields_host_test.sh   (needs only g++)
#include "crash_report_fields.h"

#include <cstdio>
#include <cstring>

static int failures = 0;

static void check(bool ok, const char* what) {
    if (ok) {
        std::printf("  ok   %s\n", what);
    } else {
        std::printf("  FAIL %s\n", what);
        failures++;
    }
}

// Fixture shaped like the real /proc/self/status of the 2026-09-13 death
// (VmRSS 6043MB, VmPeak 16.8GB) plus the anon/file split we now capture.
static const char* kStatus =
    "Name:\tcom.rikkaminis.app\n"
    "VmPeak:\t16831788 kB\n"
    "VmSize:\t16700000 kB\n"
    "RssAnon:\t5900000 kB\n"
    "RssFile:\t  148000 kB\n"
    "RssShmem:\t 11000 kB\n"
    "VmRSS:\t6043504 kB\n"
    "XRssAnon: 7 kB\n"
    "Threads:\t53\n"
    "VmSwap:\t0 kB\n";

int main() {
    char buf[64];

    std::printf("extractField\n");
    check(minis_crash::extractField(kStatus, "RssAnon", buf, sizeof(buf)) &&
              std::strcmp(buf, "5900000") == 0,
          "RssAnon extracted");
    check(minis_crash::extractField(kStatus, "RssFile", buf, sizeof(buf)) &&
              std::strcmp(buf, "148000") == 0,
          "leading blanks skipped (RssFile)");
    check(minis_crash::extractField(kStatus, "VmRSS", buf, sizeof(buf)) &&
              std::strcmp(buf, "6043504") == 0,
          "VmRSS extracted");
    check(minis_crash::extractField(kStatus, "VmPeak", buf, sizeof(buf)) &&
              std::strcmp(buf, "16831788") == 0,
          "VmPeak extracted");
    check(minis_crash::extractField(kStatus, "Threads", buf, sizeof(buf)) &&
              std::strcmp(buf, "53") == 0,
          "Threads extracted");
    check(minis_crash::extractField(kStatus, "XRssAnon", buf, sizeof(buf)) &&
              std::strcmp(buf, "7") == 0,
          "key at line start only (XRssAnon is its own key)");
    {
        // Line-start anchoring: a line that merely *contains* the key later on
        // must not be picked up (here the only line starts with "XRssAnon").
        static const char* prefixedOnly = "XRssAnon: 7 kB\nOther: 1\n";
        check(minis_crash::extractField(prefixedOnly, "RssAnon", buf, sizeof(buf)) == false,
              "no mid-line match (line must start with the key)");
    }
    check(minis_crash::extractField(kStatus, "VmSwap", buf, sizeof(buf)) &&
              std::strcmp(buf, "0") == 0,
          "zero value is a real hit, not a miss");
    check(minis_crash::extractField(kStatus, "MemAvailable", buf, sizeof(buf)) == false,
          "absent key -> false");
    check(buf[0] == '\0', "absent key clears the buffer");
    check(minis_crash::extractField(kStatus, "RssAnonX", buf, sizeof(buf)) == false,
          "key must be followed by ':' (RssAnonX vs RssAnon)");
    check(minis_crash::extractField("MemAvailable: 6440000 kB", "MemAvailable", buf, sizeof(buf)) &&
              std::strcmp(buf, "6440000") == 0,
          "single line, no trailing newline");
    check(minis_crash::extractField(kStatus, "RssAnon", buf, 4) && std::strcmp(buf, "590") == 0,
          "clamped to outsize and still NUL-terminated");
    check(minis_crash::extractField(kStatus, "RssAnon", buf, 0) == false, "outsize 0 -> false");
    check(minis_crash::extractField(nullptr, "RssAnon", buf, sizeof(buf)) == false,
          "null text -> false");
    check(minis_crash::extractField(kStatus, "", buf, sizeof(buf)) == false, "empty key -> false");

    std::printf("countLines\n");
    check(minis_crash::countLines("a\nb\n", 4) == 2, "trailing newline");
    check(minis_crash::countLines("a\nb", 3) == 2, "no trailing newline");
    check(minis_crash::countLines("", 0) == 0, "empty -> 0");
    check(minis_crash::countLines("\n", 1) == 1, "single newline -> 1");
    check(minis_crash::countLines("0100-0200 r-xp\n0200-0300 rw-p\n", 30) == 2, "maps-shaped text");

    std::printf("parseLeadingLong\n");
    check(minis_crash::parseLeadingLong("6440000 kB", -1) == 6440000, "value with unit");
    check(minis_crash::parseLeadingLong("   12345", -1) == 12345, "leading blanks");
    check(minis_crash::parseLeadingLong("abc", -1) == -1, "garbage -> fallback");
    check(minis_crash::parseLeadingLong("", -1) == -1, "empty -> fallback");
    check(minis_crash::parseLeadingLong(nullptr, -7) == -7, "null -> fallback");

    if (failures == 0) {
        std::printf("\nALL PASS\n");
        return 0;
    }
    std::printf("\nFAILED: %d\n", failures);
    return 1;
}
