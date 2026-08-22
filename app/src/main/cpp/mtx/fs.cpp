#include "mtx/fs.h"

#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>

namespace mtx { namespace fsx {

namespace {

struct Ctx {
    Progress* p = nullptr;
    int64_t done = 0, total = -1;
    int64_t filesDone = 0, filesTotal = -1;
    int64_t start = monotonicMs();
    int64_t lastReport = 0;

    void tick(const char* current, bool force = false) {
        if (!p) return;
        int64_t now = monotonicMs();
        if (!force && now - lastReport < kReportMs) return;
        lastReport = now;
        int64_t el = now - start;
        int64_t speed = el > 0 ? (int64_t) ((double) done * 1000.0 / (double) el) : 0;
        p->report(current, done, total, speed, filesDone, filesTotal);
    }
};

bool isDirPath(const std::string& path) {
    struct stat st{};
    return stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

bool exists(const std::string& path) {
    struct stat st{};
    return lstat(path.c_str(), &st) == 0;
}

Status mkdirOne(const std::string& path) {
    if (mkdir(path.c_str(), 0775) == 0) return Status::good();
    if (errno == EEXIST) return isDirPath(path)
        ? Status::good()
        : Status::err(E_EXISTS, "path exists and is not a directory: " + path);
    return fromErrno("mkdir", path);
}

// Streaming copy. Constant memory, cancellable, progress-reporting.
Status copyFileStream(int64_t job, const std::string& src, const std::string& dst,
                      bool overwrite, Ctx& ctx) {
    if (!overwrite && exists(dst))
        return Status::err(E_EXISTS, "destination already exists: " + dst);

    int in = open(src.c_str(), O_RDONLY | O_CLOEXEC);
    if (in < 0) return fromErrno("open(src)", src);

    struct stat st{};
    if (fstat(in, &st) != 0) { Status s = fromErrno("fstat", src); close(in); return s; }

    int flags = O_WRONLY | O_CREAT | O_CLOEXEC | (overwrite ? O_TRUNC : O_EXCL);
    int out = open(dst.c_str(), flags, st.st_mode & 0777);
    if (out < 0) { Status s = fromErrno("open(dst)", dst); close(in); return s; }

#ifdef POSIX_FADV_SEQUENTIAL
    posix_fadvise(in, 0, 0, POSIX_FADV_SEQUENTIAL);
#endif

    std::vector<uint8_t> buf(kChunk);
    Status result = Status::good();
    for (;;) {
        if (jobCancelled(job)) { result = Status::err(E_CANCELLED, "cancelled by user"); break; }
        ssize_t r = read(in, buf.data(), buf.size());
        if (r < 0) {
            if (errno == EINTR) continue;
            result = fromErrno("read", src);
            break;
        }
        if (r == 0) break;
        ssize_t written = 0;
        while (written < r) {
            ssize_t w = write(out, buf.data() + written, (size_t) (r - written));
            if (w < 0) {
                if (errno == EINTR) continue;
                result = fromErrno("write", dst);
                break;
            }
            written += w;
        }
        if (!result.ok()) break;
        ctx.done += r;
        ctx.tick(src.c_str());
    }

    if (result.ok() && fsync(out) != 0 && errno != EINVAL) result = fromErrno("fsync", dst);
    close(in);
    close(out);

    if (!result.ok()) {
        // Never leave a half-written file behind.
        unlink(dst.c_str());
        return result;
    }
    struct timespec times[2];
    times[0] = st.st_atim;
    times[1] = st.st_mtim;
    utimensat(AT_FDCWD, dst.c_str(), times, 0);
    ctx.filesDone++;
    ctx.tick(src.c_str(), true);
    return Status::good();
}

Status copyInto(int64_t job, const std::string& src, const std::string& dstPath,
                bool overwrite, Ctx& ctx) {
    if (jobCancelled(job)) return Status::err(E_CANCELLED, "cancelled by user");

    struct stat st{};
    if (lstat(src.c_str(), &st) != 0) return fromErrno("lstat", src);

    if (S_ISLNK(st.st_mode)) {
        char target[4096];
        ssize_t n = readlink(src.c_str(), target, sizeof(target) - 1);
        if (n < 0) return fromErrno("readlink", src);
        target[n] = '\0';
        if (overwrite) unlink(dstPath.c_str());
        if (symlink(target, dstPath.c_str()) != 0) return fromErrno("symlink", dstPath);
        ctx.filesDone++;
        return Status::good();
    }

    if (S_ISDIR(st.st_mode)) {
        Status s = mkdirOne(dstPath);
        if (!s.ok()) return s;
        std::vector<Entry> kids;
        s = list(src, kids);
        if (!s.ok()) return s;
        for (const Entry& e : kids) {
            s = copyInto(job, joinPath(src, e.name), joinPath(dstPath, e.name), overwrite, ctx);
            if (!s.ok()) return s;
        }
        return Status::good();
    }

    if (!S_ISREG(st.st_mode))
        return Status::err(E_UNSUPPORTED, "unsupported file type: " + src);

    return copyFileStream(job, src, dstPath, overwrite, ctx);
}

} // namespace

Status list(const std::string& dir, std::vector<Entry>& out) {
    DIR* d = opendir(dir.c_str());
    if (!d) return fromErrno("opendir", dir);

    struct dirent* de;
    while ((de = readdir(d)) != nullptr) {
        if (!strcmp(de->d_name, ".") || !strcmp(de->d_name, "..")) continue;
        Entry e;
        e.name = de->d_name;
        std::string full = joinPath(dir, e.name);
        struct stat st{};
        if (lstat(full.c_str(), &st) == 0) {
            e.isLink = S_ISLNK(st.st_mode);
            if (e.isLink) {
                struct stat t{};
                if (stat(full.c_str(), &t) == 0) st = t;   // follow for display
            }
            e.isDir = S_ISDIR(st.st_mode);
            e.size  = e.isDir ? 0 : (int64_t) st.st_size;
            e.mtime = (int64_t) st.st_mtime * 1000;
            e.mode  = st.st_mode & 07777;
        } else {
            e.size = -1;   // unreadable entry: still listed, marked unknown
            e.readable = false;
        }
        e.readable = e.readable && access(full.c_str(), R_OK) == 0;
        e.writable = access(full.c_str(), W_OK) == 0;
        out.push_back(std::move(e));
    }
    closedir(d);
    return Status::good();
}

Status statOne(const std::string& path, Entry& out) {
    struct stat st{};
    if (lstat(path.c_str(), &st) != 0) return fromErrno("lstat", path);
    out.name   = baseName(path);
    out.isLink = S_ISLNK(st.st_mode);
    if (out.isLink) {
        struct stat t{};
        if (stat(path.c_str(), &t) == 0) st = t;
    }
    out.isDir    = S_ISDIR(st.st_mode);
    out.size     = (int64_t) st.st_size;
    out.mtime    = (int64_t) st.st_mtime * 1000;
    out.mode     = st.st_mode & 07777;
    out.readable = access(path.c_str(), R_OK) == 0;
    out.writable = access(path.c_str(), W_OK) == 0;
    return Status::good();
}

Status treeStats(int64_t job, const std::string& path,
                 int64_t& bytes, int64_t& files, int64_t& dirs, Progress* p) {
    if (jobCancelled(job)) return Status::err(E_CANCELLED, "cancelled by user");
    struct stat st{};
    if (lstat(path.c_str(), &st) != 0) return fromErrno("lstat", path);

    if (S_ISDIR(st.st_mode)) {
        dirs++;
        std::vector<Entry> kids;
        Status s = list(path, kids);
        if (!s.ok()) return s;              // permission denied on a subtree is reported, not fatal-crash
        for (const Entry& e : kids) {
            s = treeStats(job, joinPath(path, e.name), bytes, files, dirs, p);
            if (!s.ok() && s.code == E_CANCELLED) return s;
        }
        return Status::good();
    }
    files++;
    bytes += (int64_t) st.st_size;
    if (p && (files % 512 == 0)) p->report(path.c_str(), bytes, -1, 0, files, -1);
    return Status::good();
}

Status copyTree(int64_t job, const std::string& src, const std::string& dstDir,
                bool overwrite, Progress* p) {
    if (!isDirPath(dstDir)) {
        Status s = mkdirs(dstDir);
        if (!s.ok()) return s;
    }
    std::string dstPath = joinPath(dstDir, baseName(src));
    // Refuse to copy a directory into itself.
    if (dstPath == src || dstPath.compare(0, src.size() + 1, src + "/") == 0)
        return Status::err(E_UNSUPPORTED, "cannot copy a folder into itself");

    Ctx ctx;
    ctx.p = p;
    int64_t bytes = 0, files = 0, dirs = 0;
    treeStats(job, src, bytes, files, dirs, nullptr);
    ctx.total = bytes;
    ctx.filesTotal = files;
    ctx.tick(src.c_str(), true);

    Status s = copyInto(job, src, dstPath, overwrite, ctx);
    ctx.tick(src.c_str(), true);
    return s;
}

Status moveTree(int64_t job, const std::string& src, const std::string& dstDir,
                bool overwrite, Progress* p) {
    if (!isDirPath(dstDir)) {
        Status s = mkdirs(dstDir);
        if (!s.ok()) return s;
    }
    std::string dstPath = joinPath(dstDir, baseName(src));
    if (!overwrite && exists(dstPath))
        return Status::err(E_EXISTS, "destination already exists: " + dstPath);

    if (rename(src.c_str(), dstPath.c_str()) == 0) {
        if (p) p->report(src.c_str(), 1, 1, 0, 1, 1);
        return Status::good();
    }
    if (errno != EXDEV) return fromErrno("rename", src);

    // Cross-volume: copy then remove, and only remove after a fully successful copy.
    Status s = copyTree(job, src, dstDir, overwrite, p);
    if (!s.ok()) return s;
    return removeTree(job, src, nullptr);
}

Status removeTree(int64_t job, const std::string& path, Progress* p) {
    if (jobCancelled(job)) return Status::err(E_CANCELLED, "cancelled by user");
    struct stat st{};
    if (lstat(path.c_str(), &st) != 0) return fromErrno("lstat", path);

    if (S_ISDIR(st.st_mode) && !S_ISLNK(st.st_mode)) {
        std::vector<Entry> kids;
        Status s = list(path, kids);
        if (!s.ok()) return s;
        for (const Entry& e : kids) {
            s = removeTree(job, joinPath(path, e.name), p);
            if (!s.ok()) return s;
        }
        if (rmdir(path.c_str()) != 0) return fromErrno("rmdir", path);
    } else {
        if (unlink(path.c_str()) != 0) return fromErrno("unlink", path);
    }
    if (p) p->report(path.c_str(), 0, -1, 0, 0, -1);
    return Status::good();
}

Status mkdirs(const std::string& path) {
    if (path.empty()) return Status::err(E_RANGE, "empty path");
    std::string cur;
    if (path[0] == '/') cur = "/";
    size_t i = 0;
    while (i < path.size()) {
        size_t j = path.find('/', i);
        if (j == std::string::npos) j = path.size();
        std::string part = path.substr(i, j - i);
        if (!part.empty()) {
            cur = joinPath(cur, part);
            Status s = mkdirOne(cur);
            if (!s.ok()) return s;
        }
        i = j + 1;
    }
    return Status::good();
}

Status createFile(const std::string& path) {
    int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0664);
    if (fd < 0) return fromErrno("create", path);
    close(fd);
    return Status::good();
}

Status renameTo(const std::string& from, const std::string& to) {
    if (exists(to)) return Status::err(E_EXISTS, "name already used: " + baseName(to));
    if (rename(from.c_str(), to.c_str()) != 0) return fromErrno("rename", from);
    return Status::good();
}

Status diskUsage(const std::string& path, int64_t& total, int64_t& freeB, int64_t& availB) {
    struct statvfs vfs{};
    if (statvfs(path.c_str(), &vfs) != 0) return fromErrno("statvfs", path);
    total  = (int64_t) vfs.f_blocks * (int64_t) vfs.f_frsize;
    freeB  = (int64_t) vfs.f_bfree  * (int64_t) vfs.f_frsize;
    availB = (int64_t) vfs.f_bavail * (int64_t) vfs.f_frsize;
    return Status::good();
}

Status compare(int64_t job, const std::string& a, const std::string& b,
               int64_t& firstDiff, bool& identical, Progress* p) {
    firstDiff = -1;
    identical = false;

    int fa = open(a.c_str(), O_RDONLY | O_CLOEXEC);
    if (fa < 0) return fromErrno("open", a);
    int fb = open(b.c_str(), O_RDONLY | O_CLOEXEC);
    if (fb < 0) { Status s = fromErrno("open", b); close(fa); return s; }

    struct stat sa{}, sb{};
    fstat(fa, &sa);
    fstat(fb, &sb);

    std::vector<uint8_t> ba(kChunk), bb(kChunk);
    int64_t off = 0;
    int64_t total = sa.st_size < sb.st_size ? sa.st_size : sb.st_size;
    Status result = Status::good();
    int64_t start = monotonicMs(), lastReport = 0;

    for (;;) {
        if (jobCancelled(job)) { result = Status::err(E_CANCELLED, "cancelled by user"); break; }
        ssize_t ra = read(fa, ba.data(), ba.size());
        ssize_t rb = read(fb, bb.data(), bb.size());
        if (ra < 0) { result = fromErrno("read", a); break; }
        if (rb < 0) { result = fromErrno("read", b); break; }
        ssize_t n = ra < rb ? ra : rb;
        for (ssize_t i = 0; i < n; i++) {
            if (ba[i] != bb[i]) { firstDiff = off + i; break; }
        }
        if (firstDiff >= 0) break;
        if (ra != rb) { firstDiff = off + n; break; }   // one file ended early
        if (ra == 0) { identical = true; break; }
        off += n;
        if (p) {
            int64_t now = monotonicMs();
            if (now - lastReport >= kReportMs) {
                lastReport = now;
                int64_t el = now - start;
                p->report(a.c_str(), off, total, el > 0 ? off * 1000 / el : 0, 0, 2);
            }
        }
    }
    close(fa);
    close(fb);
    if (identical && sa.st_size != sb.st_size) { identical = false; firstDiff = total; }
    return result;
}

}} // namespace mtx::fsx
