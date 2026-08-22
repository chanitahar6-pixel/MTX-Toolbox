#include "mtx/hex.h"

#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <cstring>

namespace mtx { namespace hexx {

Status readPage(const std::string& path, int64_t offset, size_t len,
                std::vector<uint8_t>& out, int64_t& fileSize) {
    if (offset < 0) return Status::err(E_RANGE, "negative offset");
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return fromErrno("open", path);

    struct stat st{};
    if (fstat(fd, &st) != 0) { Status s = fromErrno("fstat", path); close(fd); return s; }
    fileSize = (int64_t) st.st_size;

    if (offset >= fileSize) { close(fd); out.clear(); return Status::good(); }
    if ((int64_t) len > fileSize - offset) len = (size_t) (fileSize - offset);

    out.assign(len, 0);
    size_t got = 0;
    while (got < len) {
        ssize_t r = pread(fd, out.data() + got, len - got, (off_t) (offset + (int64_t) got));
        if (r < 0) {
            if (errno == EINTR) continue;
            Status s = fromErrno("pread", path);
            close(fd);
            return s;
        }
        if (r == 0) break;
        got += (size_t) r;
    }
    out.resize(got);
    close(fd);
    return Status::good();
}

Status writeAt(const std::string& path, int64_t offset, const uint8_t* data, size_t len) {
    if (offset < 0) return Status::err(E_RANGE, "negative offset");
    int fd = open(path.c_str(), O_WRONLY | O_CLOEXEC);
    if (fd < 0) return fromErrno("open(rw)", path);

    struct stat st{};
    if (fstat(fd, &st) != 0) { Status s = fromErrno("fstat", path); close(fd); return s; }
    if (offset + (int64_t) len > (int64_t) st.st_size) {
        close(fd);
        return Status::err(E_RANGE, "edit would grow the file; in-place edit only");
    }

    size_t done = 0;
    while (done < len) {
        ssize_t w = pwrite(fd, data + done, len - done, (off_t) (offset + (int64_t) done));
        if (w < 0) {
            if (errno == EINTR) continue;
            Status s = fromErrno("pwrite", path);
            close(fd);
            return s;
        }
        done += (size_t) w;
    }
    if (fsync(fd) != 0 && errno != EINVAL) { Status s = fromErrno("fsync", path); close(fd); return s; }
    close(fd);
    return Status::good();
}

Status findBytes(int64_t job, const std::string& path, int64_t from,
                 const uint8_t* pattern, size_t plen, bool backwards,
                 int64_t& matchOffset, Progress* p) {
    matchOffset = -1;
    if (plen == 0) return Status::err(E_RANGE, "empty pattern");

    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return fromErrno("open", path);
    struct stat st{};
    if (fstat(fd, &st) != 0) { Status s = fromErrno("fstat", path); close(fd); return s; }
    int64_t size = (int64_t) st.st_size;
    if (plen > (size_t) size) { close(fd); return Status::good(); }

    const size_t window = kChunk;
    std::vector<uint8_t> buf(window + plen - 1);
    Status result = Status::good();

    if (!backwards) {
        int64_t pos = from < 0 ? 0 : from;
        while (pos < size) {
            if (jobCancelled(job)) { result = Status::err(E_CANCELLED, "cancelled by user"); break; }
            size_t want = buf.size();
            if ((int64_t) want > size - pos) want = (size_t) (size - pos);
            ssize_t r = pread(fd, buf.data(), want, (off_t) pos);
            if (r < 0) { result = fromErrno("pread", path); break; }
            if ((size_t) r < plen) break;
            for (size_t i = 0; i + plen <= (size_t) r; i++) {
                if (buf[i] == pattern[0] && memcmp(buf.data() + i, pattern, plen) == 0) {
                    matchOffset = pos + (int64_t) i;
                    break;
                }
            }
            if (matchOffset >= 0) break;
            pos += (int64_t) (window);
            if (p) p->report(path.c_str(), pos, size, 0, 0, 1);
        }
    } else {
        int64_t end = from < 0 || from > size ? size : from;
        while (end > 0) {
            if (jobCancelled(job)) { result = Status::err(E_CANCELLED, "cancelled by user"); break; }
            int64_t start = end - (int64_t) window;
            if (start < 0) start = 0;
            size_t want = (size_t) (end - start) + plen - 1;
            if (start + (int64_t) want > size) want = (size_t) (size - start);
            ssize_t r = pread(fd, buf.data(), want, (off_t) start);
            if (r < 0) { result = fromErrno("pread", path); break; }
            if ((size_t) r >= plen) {
                for (size_t i = (size_t) r - plen + 1; i-- > 0;) {
                    if (buf[i] == pattern[0] && memcmp(buf.data() + i, pattern, plen) == 0) {
                        matchOffset = start + (int64_t) i;
                        break;
                    }
                }
            }
            if (matchOffset >= 0) break;
            if (start == 0) break;
            end = start;
            if (p) p->report(path.c_str(), size - end, size, 0, 0, 1);
        }
    }
    close(fd);
    return result;
}

Status extractStrings(int64_t job, const std::string& path, size_t minLen,
                      size_t maxResults, RowSink* sink) {
    if (minLen < 2) minLen = 2;
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return fromErrno("open", path);

    std::vector<uint8_t> buf(kChunk);
    std::string cur;
    int64_t pos = 0, curStart = 0;
    size_t found = 0;
    Status result = Status::good();

    for (;;) {
        if (jobCancelled(job)) { result = Status::err(E_CANCELLED, "cancelled by user"); break; }
        ssize_t r = read(fd, buf.data(), buf.size());
        if (r < 0) {
            if (errno == EINTR) continue;
            result = fromErrno("read", path);
            break;
        }
        if (r == 0) break;
        for (ssize_t i = 0; i < r; i++) {
            uint8_t c = buf[i];
            bool printable = (c >= 0x20 && c < 0x7f) || c == '\t';
            if (printable) {
                if (cur.empty()) curStart = pos + i;
                cur.push_back((char) c);
            } else {
                if (cur.size() >= minLen && sink) {
                    sink->row(cur.c_str(), "", curStart, (int64_t) cur.size());
                    if (++found >= maxResults) { cur.clear(); goto done; }
                }
                cur.clear();
            }
        }
        pos += r;
    }
    if (cur.size() >= minLen && sink && found < maxResults)
        sink->row(cur.c_str(), "", curStart, (int64_t) cur.size());
done:
    close(fd);
    return result;
}

}} // namespace mtx::hexx
