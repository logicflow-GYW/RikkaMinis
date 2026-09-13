// T283 — native crash → file (NDK signal handler).
//
// Registered at app startup from MinisApp.onCreate via JNI. Catches
// fatal signals raised inside JNI / proot / pty_bridge / any other
// native code, writes a one-shot text report to the configured logs
// dir, then restores the default handler and re-raises so the system
// tombstone is also generated and the app exits like normal.
//
// Strict async-signal-safety: only signal-safe libc calls inside the
// handler (open/write/close/snprintf are safe; printf/malloc are not).

#include <jni.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstring>
#include <cstdio>
#include <ctime>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <android/log.h>
#include "crash_report_fields.h"

#define LOG_TAG "MinisCrashHandler"

// Plenty of headroom for "<logs_dir>/native-crash-YYYY-MM-DD_HH-MM-SS.log".
static char g_log_dir[512] = {0};

// Reentrancy guard. If the handler crashes itself, we want the second
// signal to skip straight to SIG_DFL rather than recursing.
static volatile sig_atomic_t g_in_handler = 0;

// ---- async-signal-safe helpers for richer crash context ----
//
// Everything here avoids malloc/printf/fopen: only open/read/write/close
// (async-signal-safe per POSIX) plus manual integer formatting.

// ---- async-signal-safe /proc readers ----
//
// One read of /proc/self/status now feeds every field we report (the
// previous per-key reader opened the file once per field). The parsing
// itself lives in crash_report_fields.h so the same source lines are
// host-testable (scripts/native/crash_fields_host_test.sh).

// Reads up to bufsize-1 bytes of a text file into buf (NUL-terminated).
// Returns the number of bytes read (0 on failure). open/read/close only.
static size_t read_proc_text(const char* path, char* buf, size_t bufsize) {
    if (buf == nullptr || bufsize == 0) return 0;
    buf[0] = '\0';
    int fd = open(path, O_RDONLY);
    if (fd < 0) return 0;
    size_t total = 0;
    while (total + 1 < bufsize) {
        ssize_t r = read(fd, buf + total, bufsize - 1 - total);
        if (r <= 0) break;
        total += (size_t)r;
    }
    close(fd);
    buf[total] = '\0';
    return total;
}

// Counts lines in a /proc file without buffering it (/proc/self/maps can run
// to hundreds of KB; the handler must not allocate). -1 on failure.
static long count_proc_lines(const char* path) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    char chunk[1024];
    long lines = 0;
    bool trailing_nl = true;  // empty file => 0 lines
    ssize_t r;
    while ((r = read(fd, chunk, sizeof(chunk))) > 0) {
        for (ssize_t i = 0; i < r; i++) {
            if (chunk[i] == '\n') lines++;
        }
        trailing_nl = (chunk[r - 1] == '\n');
    }
    close(fd);
    if (!trailing_nl) lines++;
    return lines;
}

// Reads /proc/self/cmdline (NUL-separated argv) and joins with spaces.
// Returns true on success (even an empty cmdline is "success").
static bool read_cmdline(char* outbuf, size_t outsize) {
    int fd = open("/proc/self/cmdline", O_RDONLY);
    if (fd < 0) return false;
    ssize_t r = read(fd, outbuf, outsize - 1);
    close(fd);
    if (r < 0) return false;
    outbuf[r] = '\0';
    for (ssize_t i = 0; i < r; i++) {
        if (outbuf[i] == '\0') outbuf[i] = ' ';
    }
    return true;
}

// Signal name lookup — strsignal() is NOT async-signal-safe on all
// libc implementations, so use a hardcoded table.
static const char* signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS:  return "SIGBUS";
        case SIGFPE:  return "SIGFPE";
        case SIGILL:  return "SIGILL";
        case SIGSYS:  return "SIGSYS";
        case SIGTRAP: return "SIGTRAP";
        default:      return "UNKNOWN";
    }
}

static void crash_signal_handler(int sig, siginfo_t* info, void* ctx) {
    // Reentrancy: if we're already in the handler, just restore default
    // and re-raise. Avoids infinite loop when the handler itself faults.
    if (g_in_handler) {
        signal(sig, SIG_DFL);
        raise(sig);
        return;
    }
    g_in_handler = 1;

    if (g_log_dir[0] == 0) {
        signal(sig, SIG_DFL);
        raise(sig);
        return;
    }

    // Build the per-crash filename. We timestamp in UTC and mark it "Z"
    // so the filename always matches wall-clock / file mtime regardless
    // of device timezone; the report body prints both UTC and local time.
    time_t now = time(nullptr);
    struct tm tm_utc;
    gmtime_r(&now, &tm_utc);

    char path[640];
    snprintf(path, sizeof(path),
        "%s/native-crash-%04d-%02d-%02dT%02d-%02d-%02dZ.log",
        g_log_dir,
        tm_utc.tm_year + 1900, tm_utc.tm_mon + 1, tm_utc.tm_mday,
        tm_utc.tm_hour, tm_utc.tm_min, tm_utc.tm_sec);

    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        signal(sig, SIG_DFL);
        raise(sig);
        return;
    }

    // [fix/voice-crash-observability] Sentinel write BEFORE gathering any
    // context. If the process is SIGKILL'd mid-handler (lmkd / kernel OOM
    // killer — uncatchable, no tombstone, no dialog), the file is at least
    // non-empty: the sentinel proves "signal reached the handler, then the
    // process was externally killed before we could write the report". A
    // 0-byte file means the handler never even ran its write path — which
    // by itself is a decisive signal we could not capture before.
    static const char SENTINEL[] = "SIGNAL-REACHED\n";
    ssize_t sentinel_written = write(fd, SENTINEL, sizeof(SENTINEL) - 1);
    (void)sentinel_written; // best-effort; ignore partial write

    // Gather process identity + memory context (async-signal-safe reads).
    char cmdline[256] = {0};
    read_cmdline(cmdline, sizeof(cmdline));

    // One status read feeds every field below. RssAnon/RssFile/RssShmem are
    // what make the death state readable: on 2026-09-13 the process died at
    // VmRSS 6.0GB, but a same-day probe run showed the split matters — file
    // pages sit at a constant ~150MB regardless of pressure, so VmRSS alone
    // hides how much of the report is actually private anon memory (the part
    // the memory gate now thresholds on, and the part the kernel cannot
    // reclaim). static buffers: never grow the crashing thread's stack.
    static char status_text[4096];
    read_proc_text("/proc/self/status", status_text, sizeof(status_text));

    char vm_rss[32] = {0};
    char vm_peak[32] = {0};
    char vm_size[32] = {0};
    char threads[32] = {0};
    char rss_anon[32] = {0};
    char rss_file[32] = {0};
    char rss_shmem[32] = {0};
    minis_crash::extractField(status_text, "VmRSS", vm_rss, sizeof(vm_rss));
    minis_crash::extractField(status_text, "VmPeak", vm_peak, sizeof(vm_peak));
    minis_crash::extractField(status_text, "VmSize", vm_size, sizeof(vm_size));
    minis_crash::extractField(status_text, "Threads", threads, sizeof(threads));
    minis_crash::extractField(status_text, "RssAnon", rss_anon, sizeof(rss_anon));
    minis_crash::extractField(status_text, "RssFile", rss_file, sizeof(rss_file));
    minis_crash::extractField(status_text, "RssShmem", rss_shmem, sizeof(rss_shmem));

    // System-wide headroom at the moment of death: was the device tight too,
    // or did the process die while the phone still had GBs free?
    static char meminfo_text[2048];
    char mem_available[32] = {0};
    if (read_proc_text("/proc/meminfo", meminfo_text, sizeof(meminfo_text)) > 0) {
        minis_crash::extractField(meminfo_text, "MemAvailable", mem_available,
                                  sizeof(mem_available));
    }

    // Mapping count: VMAs are invisible in RSS, yet the same crash had
    // VmPeak 16.8GB against VmRSS 6.0GB.
    long map_lines = count_proc_lines("/proc/self/maps");

    char buf[2048];
    int off = 0;
    off += snprintf(buf + off, sizeof(buf) - (size_t)off,
        "=== Minis Native Crash ===\n"
        "Signal: %d (%s)\n"
        "si_code: %d\n"
        "Fault addr: %p\n"
        "PID: %d  TID: %d\n"
        "UTC: %04d-%02d-%02dT%02d:%02d:%02dZ\n",
        sig, signal_name(sig),
        info ? info->si_code : -1,
        info ? info->si_addr : nullptr,
        getpid(), (int)syscall(SYS_gettid),
        tm_utc.tm_year + 1900, tm_utc.tm_mon + 1, tm_utc.tm_mday,
        tm_utc.tm_hour, tm_utc.tm_min, tm_utc.tm_sec);

    // Local time (for humans reading the report on the device).
    struct tm tm_local;
    localtime_r(&now, &tm_local);
    off += snprintf(buf + off, sizeof(buf) - (size_t)off,
        "Local: %04d-%02d-%02d %02d:%02d:%02d\n",
        tm_local.tm_year + 1900, tm_local.tm_mon + 1, tm_local.tm_mday,
        tm_local.tm_hour, tm_local.tm_min, tm_local.tm_sec);

    if (cmdline[0] != '\0') {
        off += snprintf(buf + off, sizeof(buf) - (size_t)off,
            "Process: %s\n", cmdline);
    }
    if (vm_rss[0] != '\0') {
        off += snprintf(buf + off, sizeof(buf) - (size_t)off,
            "VmRSS: %s kB\n", vm_rss);
    }
    if (vm_peak[0] != '\0') {
        off += snprintf(buf + off, sizeof(buf) - (size_t)off,
            "VmPeak: %s kB\n", vm_peak);
    }
    if (threads[0] != '\0') {
        off += snprintf(buf + off, sizeof(buf) - (size_t)off,
            "Threads: %s\n", threads);
    }
    // Private-anon split + address-space pressure. RssAnon is the metric the
    // memory gate now thresholds on (soft 450MB / hard 1200MB anon); the 1s
    // probe curve that led to this death lives in
    // files/logs/memspike-<date>.log (columns anon/resid/native/java/peak).
    if (rss_anon[0] != '\0') {
        off += snprintf(buf + off, sizeof(buf) - (size_t)off,
            "RssAnon: %s kB\n", rss_anon);
    }
    if (rss_file[0] != '\0') {
        off += snprintf(buf + off, sizeof(buf) - (size_t)off,
            "RssFile: %s kB\n", rss_file);
    }
    if (rss_shmem[0] != '\0') {
        off += snprintf(buf + off, sizeof(buf) - (size_t)off,
            "RssShmem: %s kB\n", rss_shmem);
    }
    if (vm_size[0] != '\0') {
        off += snprintf(buf + off, sizeof(buf) - (size_t)off,
            "VmSize: %s kB\n", vm_size);
    }
    if (mem_available[0] != '\0') {
        off += snprintf(buf + off, sizeof(buf) - (size_t)off,
            "MemAvailable: %s kB\n", mem_available);
    }
    if (map_lines >= 0) {
        off += snprintf(buf + off, sizeof(buf) - (size_t)off,
            "Maps: %ld entries\n", map_lines);
    }
    off += snprintf(buf + off, sizeof(buf) - (size_t)off,
        "\n"
        "(Full backtrace + abort message: run `logcat -b crash -d` and "
        "find this PID/TID.)\n");

    if (off > 0) {
        ssize_t written = 0;
        while (written < off) {
            ssize_t w = write(fd, buf + written, (size_t)off - (size_t)written);
            if (w <= 0) break;
            written += w;
        }
    }
    close(fd);

    // Re-raise with default handler so Android still produces a tombstone
    // and ActivityManager handles process-death the normal way.
    struct sigaction sa{};
    sa.sa_handler = SIG_DFL;
    sigemptyset(&sa.sa_mask);
    sigaction(sig, &sa, nullptr);
    raise(sig);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rikkaminis_app_crash_NativeCrashHandler_nativeInstall(
        JNIEnv* env, jobject /*thiz*/, jstring jLogDir) {
    if (jLogDir == nullptr) return;
    const char* dir = env->GetStringUTFChars(jLogDir, nullptr);
    if (dir == nullptr) return;
    strncpy(g_log_dir, dir, sizeof(g_log_dir) - 1);
    g_log_dir[sizeof(g_log_dir) - 1] = 0;
    env->ReleaseStringUTFChars(jLogDir, dir);

    // mkdir is fine here — we're on the JVM thread, not in a signal.
    mkdir(g_log_dir, 0755);

    struct sigaction sa{};
    sa.sa_sigaction = crash_signal_handler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);

    // Register for the signals that map to JNI/native bugs we actually
    // want to capture. SIGABRT covers __android_log_assert / abort()
    // from libc; SIGSEGV/BUS/ILL cover most JNI memory bugs; SIGFPE
    // covers integer div-by-zero. SIGSYS catches seccomp violations
    // (proot occasionally trips these on new kernels).
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGABRT, &sa, nullptr);
    sigaction(SIGBUS,  &sa, nullptr);
    sigaction(SIGFPE,  &sa, nullptr);
    sigaction(SIGILL,  &sa, nullptr);
    sigaction(SIGSYS,  &sa, nullptr);

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
        "installed: dir=%s", g_log_dir);
}
