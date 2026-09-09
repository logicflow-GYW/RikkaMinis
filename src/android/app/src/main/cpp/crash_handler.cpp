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

// Reads /proc/self/status and copies the value (rest of line) for a
// requested "Key:" into outbuf. Returns true if the key was found.
// Async-signal-safe: open/read/close only.
static bool read_status_field(const char* key, char* outbuf, size_t outsize) {
    int fd = open("/proc/self/status", O_RDONLY);
    if (fd < 0) return false;
    char chunk[1024];
    size_t keylen = 0;
    while (key[keylen] != '\0') keylen++;

    char pending[128];      // tail of previous chunk (to catch keys split across reads)
    size_t pending_len = 0;
    bool found = false;

    ssize_t r;
    while ((r = read(fd, chunk, sizeof(chunk))) > 0) {
        // Build a combined buffer: pending tail + this chunk.
        char scan[sizeof(pending) + sizeof(chunk)];
        memcpy(scan, pending, pending_len);
        memcpy(scan + pending_len, chunk, (size_t)r);
        size_t total = pending_len + (size_t)r;

        size_t i = 0;
        while (i < total) {
            // Find line start (after a '\n').
            size_t line_start = i;
            // Find line end.
            size_t line_end = line_start;
            while (line_end < total && scan[line_end] != '\n') line_end++;
            size_t line_len = line_end - line_start;
            if (line_len >= keylen && memcmp(scan + line_start, key, keylen) == 0 &&
                line_start + keylen < line_end && scan[line_start + keylen] == ':') {
                // Value begins after key, skip spaces/tabs (status uses ": ").
                size_t v = line_start + keylen;
                if (v < line_end && scan[v] == ':') v++;
                while (v < line_end && (scan[v] == ' ' || scan[v] == '\t')) v++;
                size_t val_len = line_end - v;
                if (val_len >= outsize) val_len = outsize - 1;
                memcpy(outbuf, scan + v, val_len);
                outbuf[val_len] = '\0';
                found = true;
                close(fd);
                return true;
            }
            i = line_end + 1;
        }

        // Preserve a partial line tail for the next read.
        // Find last '\n' in scan.
        size_t last_nl = total;
        while (last_nl > 0 && scan[last_nl - 1] != '\n') last_nl--;
        // scan[last_nl .. total) is a partial line; keep up to pending capacity.
        size_t tail_len = total - last_nl;
        if (tail_len >= sizeof(pending)) {
            // Partial line is already longer than our buffer: it cannot be a
            // short key like "VmRSS:", so drop it — those keys are short.
            pending_len = 0;
        } else {
            memcpy(pending, scan + last_nl, tail_len);
            pending_len = tail_len;
        }
    }
    close(fd);
    return found;
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

    char vm_rss[32] = {0};
    read_status_field("VmRSS", vm_rss, sizeof(vm_rss));

    char threads[32] = {0};
    read_status_field("Threads", threads, sizeof(threads));

    char vm_peak[32] = {0};
    read_status_field("VmPeak", vm_peak, sizeof(vm_peak));

    char buf[1400];
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
