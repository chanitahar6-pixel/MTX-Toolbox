#include "mtx/common.h"

#include <cerrno>
#include <cstring>
#include <ctime>
#include <mutex>
#include <unordered_map>
#include <memory>
#include <algorithm>

namespace mtx {

Status fromErrno(const char* op, const std::string& path) {
    int e = errno;
    int32_t code;
    switch (e) {
        case ENOENT:  code = E_NOENT;  break;
        case EACCES:
        case EPERM:    code = E_PERM;   break;
        case EEXIST:   code = E_EXISTS; break;
        case ENOSPC:
        case EDQUOT:   code = E_NOSPC;  break;
        case ENOTDIR:
        case EISDIR:   code = E_UNSUPPORTED; break;
        default:       code = E_IO;     break;
    }
    char buf[512];
    snprintf(buf, sizeof(buf), "%s failed: %s (errno %d) [%s]", op, strerror(e), e, path.c_str());
    return Status::err(code, buf);
}

// ---- job registry ---------------------------------------------------------
namespace {
struct Job { std::atomic<bool> cancelled{false}; };
std::mutex g_mutex;
std::unordered_map<int64_t, std::shared_ptr<Job>> g_jobs;
int64_t g_next = 1;

std::shared_ptr<Job> lookup(int64_t id) {
    std::lock_guard<std::mutex> lk(g_mutex);
    auto it = g_jobs.find(id);
    return it == g_jobs.end() ? nullptr : it->second;
}
} // namespace

int64_t jobNew() {
    std::lock_guard<std::mutex> lk(g_mutex);
    int64_t id = g_next++;
    g_jobs[id] = std::make_shared<Job>();
    return id;
}

void jobCancel(int64_t id) {
    auto j = lookup(id);
    if (j) j->cancelled.store(true, std::memory_order_relaxed);
}

void jobRelease(int64_t id) {
    std::lock_guard<std::mutex> lk(g_mutex);
    g_jobs.erase(id);
}

bool jobCancelled(int64_t id) {
    if (id <= 0) return false;
    auto j = lookup(id);
    return j && j->cancelled.load(std::memory_order_relaxed);
}

// ---- helpers --------------------------------------------------------------
std::string joinPath(const std::string& dir, const std::string& name) {
    if (dir.empty()) return name;
    if (dir.back() == '/') return dir + name;
    return dir + "/" + name;
}

std::string baseName(const std::string& p) {
    if (p.empty() || p == "/") return p;
    std::string s = p;
    while (s.size() > 1 && s.back() == '/') s.pop_back();
    size_t i = s.find_last_of('/');
    return i == std::string::npos ? s : s.substr(i + 1);
}

std::string parentOf(const std::string& p) {
    std::string s = p;
    while (s.size() > 1 && s.back() == '/') s.pop_back();
    size_t i = s.find_last_of('/');
    if (i == std::string::npos) return "";
    if (i == 0) return "/";
    return s.substr(0, i);
}

std::string extOf(const std::string& p) {
    std::string b = baseName(p);
    size_t i = b.find_last_of('.');
    if (i == std::string::npos || i == 0 || i + 1 >= b.size()) return "";
    return lower(b.substr(i + 1));
}

std::string lower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(),
                   [](unsigned char c) { return (char) ((c >= 'A' && c <= 'Z') ? c + 32 : c); });
    return s;
}

std::string toHex(const uint8_t* data, size_t len) {
    static const char* H = "0123456789abcdef";
    std::string out;
    out.resize(len * 2);
    for (size_t i = 0; i < len; i++) {
        out[i * 2]     = H[(data[i] >> 4) & 0xF];
        out[i * 2 + 1] = H[data[i] & 0xF];
    }
    return out;
}

std::string humanBytes(int64_t n) {
    static const char* U[] = {"B", "KB", "MB", "GB", "TB", "PB"};
    double v = (double) n;
    int u = 0;
    while (v >= 1024.0 && u < 5) { v /= 1024.0; u++; }
    char buf[64];
    snprintf(buf, sizeof(buf), u == 0 ? "%.0f %s" : "%.2f %s", v, U[u]);
    return buf;
}

bool hasSuffix(const std::string& s, const std::string& suffix) {
    return s.size() >= suffix.size() &&
           s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
}

int64_t monotonicMs() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

bool rdU16(const uint8_t* p, size_t size, size_t off, uint16_t& out) {
    if (off + 2 > size) return false;
    out = (uint16_t) (p[off] | (p[off + 1] << 8));
    return true;
}

bool rdU32(const uint8_t* p, size_t size, size_t off, uint32_t& out) {
    if (off + 4 > size) return false;
    out = (uint32_t) p[off] | ((uint32_t) p[off + 1] << 8) |
          ((uint32_t) p[off + 2] << 16) | ((uint32_t) p[off + 3] << 24);
    return true;
}

bool rdU64(const uint8_t* p, size_t size, size_t off, uint64_t& out) {
    uint32_t lo, hi;
    if (!rdU32(p, size, off, lo) || !rdU32(p, size, off + 4, hi)) return false;
    out = (uint64_t) lo | ((uint64_t) hi << 32);
    return true;
}

} // namespace mtx
