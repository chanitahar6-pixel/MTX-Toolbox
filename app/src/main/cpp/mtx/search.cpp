#include "mtx/search.h"
#include "mtx/fs.h"
#include "mtx/zip.h"

#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <cstring>

namespace mtx { namespace searchx {
namespace {

struct Runner {
    int64_t job;
    const Options& opt;
    RowSink* sink;
    Progress* p;
    std::string needle;      // already case-folded when insensitive
    std::string pattern;
    size_t hits = 0;
    int64_t scanned = 0;
    int64_t lastReport = 0;

    bool caseSensitive() const { return (opt.flags & F_CASE) != 0; }
    bool content() const { return (opt.flags & F_CONTENT) != 0 && !needle.empty(); }
    bool recursive() const { return (opt.flags & F_RECURSIVE) != 0; }
    bool archives() const { return (opt.flags & F_ARCHIVES) != 0; }
    bool hidden() const { return (opt.flags & F_HIDDEN) != 0; }

    void emit(const std::string& path, const std::string& preview, int64_t line, int64_t size) {
        if (sink) sink->row(path.c_str(), preview.c_str(), line, size);
        hits++;
    }

    void tick(const std::string& where) {
        if (!p) return;
        int64_t now = monotonicMs();
        if (now - lastReport < kReportMs) return;
        lastReport = now;
        p->report(where.c_str(), scanned, -1, 0, (int64_t) hits, -1);
    }

    // Streaming grep with an overlap window so hits on chunk borders survive.
    void grep(const std::string& path, int64_t fileSize) {
        int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) return;

        size_t plen = needle.size();
        std::vector<char> buf(kChunk + plen);
        size_t carry = 0;
        int64_t lineBase = 1;
        int64_t absPos = 0;

        while (hits < opt.maxResults) {
            if (jobCancelled(job)) break;
            ssize_t r = read(fd, buf.data() + carry, kChunk);
            if (r < 0) { if (errno == EINTR) continue; break; }
            size_t avail = carry + (size_t) (r > 0 ? r : 0);
            if (avail < plen) break;

            size_t limit = avail - plen + 1;
            for (size_t i = 0; i < limit && hits < opt.maxResults; i++) {
                bool match;
                if (caseSensitive()) {
                    match = buf[i] == needle[0] && memcmp(buf.data() + i, needle.data(), plen) == 0;
                } else {
                    match = true;
                    for (size_t k = 0; k < plen; k++) {
                        char c = buf[i + k];
                        if (c >= 'A' && c <= 'Z') c = (char) (c + 32);
                        if (c != needle[k]) { match = false; break; }
                    }
                }
                if (!match) continue;

                // Line number = lines before this buffer + newlines up to i.
                int64_t line = lineBase;
                for (size_t k = 0; k < i; k++) if (buf[k] == '\n') line++;

                size_t ls = i;
                while (ls > 0 && buf[ls - 1] != '\n') ls--;
                size_t le = i;
                while (le < avail && buf[le] != '\n' && le - ls < 300) le++;
                emit(path, std::string(buf.data() + ls, le - ls), line, absPos + (int64_t) i);
            }

            if (r <= 0) break;
            size_t consumed = limit;
            for (size_t k = 0; k < consumed; k++) if (buf[k] == '\n') lineBase++;
            carry = avail - consumed;
            memmove(buf.data(), buf.data() + consumed, carry);
            absPos += (int64_t) consumed;
            scanned += r;
            tick(path);
        }
        close(fd);
    }

    void searchArchive(const std::string& path) {
        std::vector<zipx::ZEntry> entries;
        if (!zipx::listEntries(path, entries).ok()) return;
        for (const zipx::ZEntry& e : entries) {
            if (jobCancelled(job) || hits >= opt.maxResults) return;
            std::string base = baseName(e.name);
            if (pattern.empty() || wildcardMatch(pattern, base, caseSensitive()))
                emit(path + "!/" + e.name, "archive entry", -1, (int64_t) e.uncompressedSize);
        }
    }

    void walk(const std::string& dir, int depth) {
        if (jobCancelled(job) || hits >= opt.maxResults) return;

        std::vector<fsx::Entry> kids;
        if (!fsx::list(dir, kids).ok()) return;   // unreadable dir: skip, never crash

        for (const fsx::Entry& e : kids) {
            if (jobCancelled(job) || hits >= opt.maxResults) return;
            if (!hidden() && !e.name.empty() && e.name[0] == '.') continue;
            std::string full = joinPath(dir, e.name);
            scanned++;
            tick(full);

            if (e.isDir) {
                if (pattern.empty() || wildcardMatch(pattern, e.name, caseSensitive()))
                    if (!content()) emit(full, "folder", -1, -1);
                if (recursive() && depth < 64) walk(full, depth + 1);
                continue;
            }

            bool nameHit = pattern.empty() || wildcardMatch(pattern, e.name, caseSensitive());
            if (!nameHit && !content()) continue;

            if (content()) {
                if (!nameHit && !pattern.empty()) continue;
                if (e.size >= 0 && e.size <= opt.maxFileSize) grep(full, e.size);
                continue;
            }

            emit(full, "", -1, e.size);

            if (archives()) {
                std::string ext = extOf(e.name);
                if (ext == "zip" || ext == "apk" || ext == "jar" || ext == "apks" || ext == "xapk")
                    searchArchive(full);
            }
        }
    }
};

} // namespace

bool wildcardMatch(const std::string& patternIn, const std::string& textIn, bool caseSensitive) {
    std::string pattern = caseSensitive ? patternIn : lower(patternIn);
    std::string text = caseSensitive ? textIn : lower(textIn);
    // Bare terms behave like *term*, which is what users expect from a search box.
    if (pattern.find('*') == std::string::npos && pattern.find('?') == std::string::npos)
        return text.find(pattern) != std::string::npos;

    const char* p = pattern.c_str();
    const char* s = text.c_str();
    const char* star = nullptr;
    const char* ss = s;
    while (*s) {
        if (*p == '?' || *p == *s) { p++; s++; continue; }
        if (*p == '*') { star = p++; ss = s; continue; }
        if (star) { p = star + 1; s = ++ss; continue; }
        return false;
    }
    while (*p == '*') p++;
    return *p == '\0';
}

Status run(int64_t job, const std::string& root, const std::string& namePattern,
           const std::string& contentQuery, const Options& opt,
           RowSink* sink, Progress* p) {
    struct stat st{};
    if (stat(root.c_str(), &st) != 0) return fromErrno("stat", root);
    if (namePattern.empty() && contentQuery.empty())
        return Status::err(E_RANGE, "nothing to search for");

    Runner r{job, opt, sink, p, "", namePattern};
    r.needle = (opt.flags & F_CASE) ? contentQuery : lower(contentQuery);

    if (S_ISDIR(st.st_mode)) r.walk(root, 0);
    else if (r.content()) r.grep(root, (int64_t) st.st_size);

    if (p) p->report("", r.scanned, r.scanned, 0, (int64_t) r.hits, (int64_t) r.hits);
    if (jobCancelled(job)) return Status::err(E_CANCELLED, "cancelled by user");
    return Status::good();
}

}} // namespace mtx::searchx
