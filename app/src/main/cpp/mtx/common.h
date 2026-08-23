// MTX Toolbox - shared native primitives.
// Licensed under the Apache License 2.0.
//
// Every engine includes this header, so the platform headers they all rely on
// (errno, stat, string and stdlib functions) are pulled in here once. That keeps
// each engine free of per-file include drift.
#pragma once

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <string>
#include <vector>

#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <android/log.h>

#define MTX_TAG "mtx-core"
#define MLOGI(...) __android_log_print(ANDROID_LOG_INFO,  MTX_TAG, __VA_ARGS__)
#define MLOGW(...) __android_log_print(ANDROID_LOG_WARN,  MTX_TAG, __VA_ARGS__)
#define MLOGE(...) __android_log_print(ANDROID_LOG_ERROR, MTX_TAG, __VA_ARGS__)

namespace mtx {

// Error taxonomy shared with Java (app.mtx.toolbox.core.OpResult).
enum Code : int32_t {
    OK            = 0,
    E_NOENT       = -2,
    E_IO          = -5,
    E_EXISTS      = -17,
    E_PERM        = -13,
    E_NOSPC       = -28,
    E_CANCELLED   = -1000,
    E_CORRUPT     = -1001,
    E_UNSUPPORTED = -1002,
    E_RANGE       = -1003,
    E_ENCODING    = -1004,
    E_BUSY        = -1005,
    E_INTERNAL    = -1006,
};

struct Status {
    int32_t code = OK;
    std::string msg;

    bool ok() const { return code == OK; }
    static Status good() { return Status{}; }
    static Status err(int32_t c, std::string m) { return Status{c, std::move(m)}; }
};

// Translates errno into the MTX taxonomy, keeping the technical detail in msg.
Status fromErrno(const char* op, const std::string& path);

// ---- cancellation registry -------------------------------------------------
// Java owns job ids; every engine checks the flag on chunk/entry boundaries.
int64_t jobNew();
void    jobCancel(int64_t id);
void    jobRelease(int64_t id);
bool    jobCancelled(int64_t id);   // false for id <= 0 (uncancellable call)

// ---- small helpers --------------------------------------------------------
std::string joinPath(const std::string& dir, const std::string& name);
std::string baseName(const std::string& p);
std::string parentOf(const std::string& p);
std::string extOf(const std::string& p);
std::string lower(std::string s);
std::string toHex(const uint8_t* data, size_t len);
std::string humanBytes(int64_t n);
bool        hasSuffix(const std::string& s, const std::string& suffix);
int64_t     monotonicMs();

// Reads little-endian values with bounds checking.
bool rdU16(const uint8_t* p, size_t size, size_t off, uint16_t& out);
bool rdU32(const uint8_t* p, size_t size, size_t off, uint32_t& out);
bool rdU64(const uint8_t* p, size_t size, size_t off, uint64_t& out);

constexpr size_t kChunk = 256 * 1024;      // copy / hash / search chunk
constexpr int64_t kReportMs = 120;         // progress throttle

// Progress reporting interface implemented by the JNI layer.
struct Progress {
    virtual ~Progress() = default;
    // current: file being handled, done/total in bytes (total < 0 when unknown),
    // speed: bytes per second, filesDone/filesTotal for tree operations.
    virtual void report(const char* current, int64_t done, int64_t total,
                        int64_t speed, int64_t filesDone, int64_t filesTotal) = 0;
};

// Emits one search / analysis row back to Java as it is found (streaming).
struct RowSink {
    virtual ~RowSink() = default;
    virtual void row(const char* a, const char* b, int64_t n1, int64_t n2) = 0;
};

} // namespace mtx
