// Self-contained ZIP reader: EOCD + Zip64 aware central-directory parser with
// on-demand raw inflate. Nothing is ever fully loaded into memory.
#include "mtx/zip.h"
#include "mtx/fs.h"

#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <cstring>
#include <ctime>
#include <zlib.h>

namespace mtx { namespace zipx {
namespace {

constexpr uint32_t SIG_EOCD    = 0x06054b50u;
constexpr uint32_t SIG_EOCD64  = 0x06064b50u;
constexpr uint32_t SIG_LOC64   = 0x07064b50u;
constexpr uint32_t SIG_CDIR    = 0x02014b50u;
constexpr uint32_t SIG_LFH     = 0x04034b50u;
constexpr size_t   MAX_CD      = 256u * 1024u * 1024u;

struct File {
    int fd = -1;
    int64_t size = 0;
    ~File() { if (fd >= 0) close(fd); }
};

Status openRead(const std::string& path, File& f) {
    f.fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (f.fd < 0) return fromErrno("open", path);
    struct stat st{};
    if (fstat(f.fd, &st) != 0) return fromErrno("fstat", path);
    f.size = (int64_t) st.st_size;
    if (f.size < 22) return Status::err(E_CORRUPT, "file too small to be a zip archive");
    return Status::good();
}

Status readAt(const File& f, int64_t off, size_t len, std::vector<uint8_t>& out) {
    if (off < 0 || off + (int64_t) len > f.size)
        return Status::err(E_CORRUPT, "read past end of archive (broken offsets)");
    out.assign(len, 0);
    size_t got = 0;
    while (got < len) {
        ssize_t r = pread(f.fd, out.data() + got, len - got, (off_t) (off + (int64_t) got));
        if (r < 0) {
            if (errno == EINTR) continue;
            return fromErrno("pread", "archive");
        }
        if (r == 0) return Status::err(E_CORRUPT, "unexpected end of archive");
        got += (size_t) r;
    }
    return Status::good();
}

int64_t dosToEpochMs(uint16_t time, uint16_t date) {
    struct tm tmv{};
    tmv.tm_year = ((date >> 9) & 0x7f) + 80;
    tmv.tm_mon  = ((date >> 5) & 0x0f) - 1;
    tmv.tm_mday = date & 0x1f;
    tmv.tm_hour = (time >> 11) & 0x1f;
    tmv.tm_min  = (time >> 5) & 0x3f;
    tmv.tm_sec  = (time & 0x1f) * 2;
    tmv.tm_isdst = -1;
    time_t t = mktime(&tmv);
    return t < 0 ? 0 : (int64_t) t * 1000;
}

// Locates the End Of Central Directory record by scanning the tail.
Status findEocd(const File& f, int64_t& cdOffset, int64_t& cdSize, int64_t& entries) {
    size_t tail = (size_t) (f.size < 66560 ? f.size : 66560);   // 64 KiB comment + 22
    std::vector<uint8_t> buf;
    Status s = readAt(f, f.size - (int64_t) tail, tail, buf);
    if (!s.ok()) return s;

    int64_t eocd = -1;
    for (size_t i = buf.size() >= 22 ? buf.size() - 22 : 0; ; i--) {
        uint32_t sig;
        if (rdU32(buf.data(), buf.size(), i, sig) && sig == SIG_EOCD) { eocd = (int64_t) i; break; }
        if (i == 0) break;
    }
    if (eocd < 0) return Status::err(E_CORRUPT, "no End Of Central Directory record: not a zip archive");

    uint16_t entries16 = 0;
    uint32_t cdSize32 = 0, cdOff32 = 0;
    rdU16(buf.data(), buf.size(), (size_t) eocd + 10, entries16);
    rdU32(buf.data(), buf.size(), (size_t) eocd + 12, cdSize32);
    rdU32(buf.data(), buf.size(), (size_t) eocd + 16, cdOff32);
    entries  = entries16;
    cdSize   = cdSize32;
    cdOffset = cdOff32;

    // Zip64 upgrade path.
    if (eocd >= 20) {
        uint32_t locSig = 0;
        if (rdU32(buf.data(), buf.size(), (size_t) eocd - 20, locSig) && locSig == SIG_LOC64) {
            uint64_t z64off = 0;
            rdU64(buf.data(), buf.size(), (size_t) eocd - 20 + 8, z64off);
            std::vector<uint8_t> z;
            if (readAt(f, (int64_t) z64off, 56, z).ok()) {
                uint32_t sig64 = 0;
                rdU32(z.data(), z.size(), 0, sig64);
                if (sig64 == SIG_EOCD64) {
                    uint64_t e = 0, cs = 0, co = 0;
                    rdU64(z.data(), z.size(), 32, e);
                    rdU64(z.data(), z.size(), 40, cs);
                    rdU64(z.data(), z.size(), 48, co);
                    entries  = (int64_t) e;
                    cdSize   = (int64_t) cs;
                    cdOffset = (int64_t) co;
                }
            }
        }
    }
    if (cdSize < 0 || cdOffset < 0 || cdOffset + cdSize > f.size)
        return Status::err(E_CORRUPT, "central directory offsets are out of range");
    if ((size_t) cdSize > MAX_CD)
        return Status::err(E_UNSUPPORTED, "central directory too large");
    return Status::good();
}

Status parseCentral(const File& f, std::vector<ZEntry>& out) {
    int64_t cdOff = 0, cdSize = 0, entries = 0;
    Status s = findEocd(f, cdOff, cdSize, entries);
    if (!s.ok()) return s;

    std::vector<uint8_t> cd;
    s = readAt(f, cdOff, (size_t) cdSize, cd);
    if (!s.ok()) return s;

    size_t pos = 0;
    while (pos + 46 <= cd.size()) {
        uint32_t sig = 0;
        if (!rdU32(cd.data(), cd.size(), pos, sig) || sig != SIG_CDIR) break;

        uint16_t flags = 0, method = 0, mtime = 0, mdate = 0, nameLen = 0, extraLen = 0, commentLen = 0;
        uint32_t crc = 0, csize32 = 0, usize32 = 0, lfh32 = 0;
        rdU16(cd.data(), cd.size(), pos + 8, flags);
        rdU16(cd.data(), cd.size(), pos + 10, method);
        rdU16(cd.data(), cd.size(), pos + 12, mtime);
        rdU16(cd.data(), cd.size(), pos + 14, mdate);
        rdU32(cd.data(), cd.size(), pos + 16, crc);
        rdU32(cd.data(), cd.size(), pos + 20, csize32);
        rdU32(cd.data(), cd.size(), pos + 24, usize32);
        rdU16(cd.data(), cd.size(), pos + 28, nameLen);
        rdU16(cd.data(), cd.size(), pos + 30, extraLen);
        rdU16(cd.data(), cd.size(), pos + 32, commentLen);
        rdU32(cd.data(), cd.size(), pos + 42, lfh32);

        size_t nameOff = pos + 46;
        if (nameOff + nameLen > cd.size()) break;

        ZEntry e;
        e.name.assign((const char*) cd.data() + nameOff, nameLen);
        e.method = method;
        e.crc32 = crc;
        e.compressedSize   = csize32;
        e.uncompressedSize = usize32;
        e.localOffset      = lfh32;
        e.mtime = dosToEpochMs(mtime, mdate);
        e.encrypted = (flags & 0x1) != 0;
        e.isDir = !e.name.empty() && e.name.back() == '/';

        // Zip64 extended information extra field.
        size_t ex = nameOff + nameLen;
        size_t exEnd = ex + extraLen;
        while (ex + 4 <= exEnd && exEnd <= cd.size()) {
            uint16_t id = 0, sz = 0;
            rdU16(cd.data(), cd.size(), ex, id);
            rdU16(cd.data(), cd.size(), ex + 2, sz);
            if (id == 0x0001) {
                size_t q = ex + 4;
                uint64_t v = 0;
                if (e.uncompressedSize == 0xffffffffu && rdU64(cd.data(), cd.size(), q, v)) {
                    e.uncompressedSize = v; q += 8;
                }
                if (e.compressedSize == 0xffffffffu && rdU64(cd.data(), cd.size(), q, v)) {
                    e.compressedSize = v; q += 8;
                }
                if (e.localOffset == 0xffffffffu && rdU64(cd.data(), cd.size(), q, v)) {
                    e.localOffset = v;
                }
            }
            ex += 4u + sz;
        }

        out.push_back(std::move(e));
        pos = exEnd + commentLen;
    }
    if (out.empty() && entries > 0)
        return Status::err(E_CORRUPT, "central directory could not be parsed");
    return Status::good();
}

// Resolves where the entry payload actually begins.
Status dataOffset(const File& f, const ZEntry& e, int64_t& off) {
    std::vector<uint8_t> lfh;
    Status s = readAt(f, (int64_t) e.localOffset, 30, lfh);
    if (!s.ok()) return s;
    uint32_t sig = 0;
    rdU32(lfh.data(), lfh.size(), 0, sig);
    if (sig != SIG_LFH) return Status::err(E_CORRUPT, "bad local header for entry: " + e.name);
    uint16_t nameLen = 0, extraLen = 0;
    rdU16(lfh.data(), lfh.size(), 26, nameLen);
    rdU16(lfh.data(), lfh.size(), 28, extraLen);
    off = (int64_t) e.localOffset + 30 + nameLen + extraLen;
    if (off + (int64_t) e.compressedSize > f.size)
        return Status::err(E_CORRUPT, "entry data extends past end of archive: " + e.name);
    return Status::good();
}

// Streams one entry, handing decompressed blocks to `emit`.
template <typename Emit>
Status streamEntry(int64_t job, const File& f, const ZEntry& e, Emit emit) {
    if (e.encrypted)
        return Status::err(E_UNSUPPORTED, "entry is encrypted: " + e.name);
    if (e.method != 0 && e.method != 8)
        return Status::err(E_UNSUPPORTED,
                           "compression method " + std::to_string(e.method) + " not supported for " + e.name);

    int64_t off = 0;
    Status s = dataOffset(f, e, off);
    if (!s.ok()) return s;

    std::vector<uint8_t> in(kChunk), outBuf(kChunk);
    uint64_t remaining = e.compressedSize;

    if (e.method == 0) {
        while (remaining > 0) {
            if (jobCancelled(job)) return Status::err(E_CANCELLED, "cancelled by user");
            size_t want = remaining < in.size() ? (size_t) remaining : in.size();
            ssize_t r = pread(f.fd, in.data(), want, (off_t) off);
            if (r <= 0) return Status::err(E_CORRUPT, "truncated stored entry: " + e.name);
            if (!emit(in.data(), (size_t) r)) return Status::err(E_IO, "write failed");
            off += r;
            remaining -= (uint64_t) r;
        }
        return Status::good();
    }

    z_stream zs{};
    if (inflateInit2(&zs, -MAX_WBITS) != Z_OK)
        return Status::err(E_INTERNAL, "inflateInit2 failed");

    Status result = Status::good();
    bool done = false;
    while (!done) {
        if (jobCancelled(job)) { result = Status::err(E_CANCELLED, "cancelled by user"); break; }
        size_t want = remaining < in.size() ? (size_t) remaining : in.size();
        ssize_t r = want > 0 ? pread(f.fd, in.data(), want, (off_t) off) : 0;
        if (r < 0) { result = fromErrno("pread", e.name); break; }
        off += r;
        remaining -= (uint64_t) r;
        zs.next_in = in.data();
        zs.avail_in = (uInt) r;

        do {
            zs.next_out = outBuf.data();
            zs.avail_out = (uInt) outBuf.size();
            int zr = inflate(&zs, r == 0 ? Z_FINISH : Z_NO_FLUSH);
            size_t produced = outBuf.size() - zs.avail_out;
            if (produced > 0 && !emit(outBuf.data(), produced)) {
                result = Status::err(E_IO, "write failed while extracting " + e.name);
                done = true;
                break;
            }
            if (zr == Z_STREAM_END) { done = true; break; }
            if (zr == Z_BUF_ERROR && r == 0) { done = true; break; }
            if (zr != Z_OK) {
                result = Status::err(E_CORRUPT,
                                     "deflate stream error " + std::to_string(zr) + " in " + e.name);
                done = true;
                break;
            }
        } while (zs.avail_out == 0);

        if (r == 0 && !done) { done = true; }
    }
    inflateEnd(&zs);
    return result;
}

// Rejects absolute paths, ".." segments and backslash tricks.
Status safeJoin(const std::string& outDir, const std::string& name, std::string& result) {
    if (name.empty()) return Status::err(E_CORRUPT, "empty entry name");
    if (name[0] == '/' || name.find(":\\") != std::string::npos)
        return Status::err(E_PERM, "unsafe absolute entry path blocked: " + name);

    std::string clean;
    size_t i = 0;
    while (i < name.size()) {
        size_t j = name.find_first_of("/\\", i);
        if (j == std::string::npos) j = name.size();
        std::string part = name.substr(i, j - i);
        if (part == "..")
            return Status::err(E_PERM, "path traversal blocked: " + name);
        if (!part.empty() && part != ".") clean = clean.empty() ? part : clean + "/" + part;
        i = j + 1;
    }
    if (clean.empty()) return Status::err(E_CORRUPT, "entry resolves to nothing: " + name);
    result = joinPath(outDir, clean);
    return Status::good();
}

} // namespace

Status listEntries(const std::string& zip, std::vector<ZEntry>& out) {
    File f;
    Status s = openRead(zip, f);
    if (!s.ok()) return s;
    return parseCentral(f, out);
}

Status readEntry(const std::string& zip, const std::string& name,
                 std::vector<uint8_t>& out, size_t maxBytes) {
    File f;
    Status s = openRead(zip, f);
    if (!s.ok()) return s;
    std::vector<ZEntry> entries;
    s = parseCentral(f, entries);
    if (!s.ok()) return s;

    for (const ZEntry& e : entries) {
        if (e.name != name) continue;
        if (e.uncompressedSize > maxBytes)
            return Status::err(E_RANGE, "entry is larger than the allowed in-memory limit: " + name);
        out.clear();
        out.reserve((size_t) e.uncompressedSize);
        bool overflow = false;
        Status r = streamEntry(0, f, e, [&](const uint8_t* d, size_t n) {
            if (out.size() + n > maxBytes) { overflow = true; return false; }
            out.insert(out.end(), d, d + n);
            return true;
        });
        if (overflow) return Status::err(E_RANGE, "entry exceeded the in-memory limit: " + name);
        return r;
    }
    return Status::err(E_NOENT, "entry not found in archive: " + name);
}

Status extract(int64_t job, const std::string& zip, const std::string& entryName,
               const std::string& outDir, Progress* p) {
    File f;
    Status s = openRead(zip, f);
    if (!s.ok()) return s;
    std::vector<ZEntry> entries;
    s = parseCentral(f, entries);
    if (!s.ok()) return s;

    s = fsx::mkdirs(outDir);
    if (!s.ok()) return s;

    int64_t total = 0, filesTotal = 0;
    for (const ZEntry& e : entries) {
        if (!entryName.empty() && e.name != entryName) continue;
        if (e.isDir) continue;
        total += (int64_t) e.uncompressedSize;
        filesTotal++;
    }
    if (filesTotal == 0 && !entryName.empty())
        return Status::err(E_NOENT, "entry not found in archive: " + entryName);

    int64_t done = 0, filesDone = 0, start = monotonicMs(), lastReport = 0;

    for (const ZEntry& e : entries) {
        if (jobCancelled(job)) return Status::err(E_CANCELLED, "cancelled by user");
        if (!entryName.empty() && e.name != entryName) continue;

        std::string target;
        s = safeJoin(outDir, e.name, target);
        if (!s.ok()) return s;

        if (e.isDir) {
            s = fsx::mkdirs(target);
            if (!s.ok()) return s;
            continue;
        }
        std::string parent = parentOf(target);
        if (!parent.empty()) {
            s = fsx::mkdirs(parent);
            if (!s.ok()) return s;
        }

        int fd = open(target.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0664);
        if (fd < 0) return fromErrno("create", target);

        uint32_t crc = crc32(0L, Z_NULL, 0);
        Status r = streamEntry(job, f, e, [&](const uint8_t* d, size_t n) {
            crc = crc32(crc, d, (uInt) n);
            size_t w = 0;
            while (w < n) {
                ssize_t k = write(fd, d + w, n - w);
                if (k < 0) {
                    if (errno == EINTR) continue;
                    return false;
                }
                w += (size_t) k;
            }
            done += (int64_t) n;
            if (p) {
                int64_t now = monotonicMs();
                if (now - lastReport >= kReportMs) {
                    lastReport = now;
                    int64_t el = now - start;
                    p->report(e.name.c_str(), done, total,
                              el > 0 ? done * 1000 / el : 0, filesDone, filesTotal);
                }
            }
            return true;
        });
        close(fd);

        if (!r.ok()) {
            unlink(target.c_str());
            return r;
        }
        if (e.crc32 != 0 && crc != e.crc32) {
            unlink(target.c_str());
            return Status::err(E_CORRUPT, "CRC mismatch, archive entry is damaged: " + e.name);
        }
        filesDone++;
        if (e.mtime > 0) {
            struct timespec times[2];
            times[0].tv_sec = (time_t) (e.mtime / 1000); times[0].tv_nsec = 0;
            times[1] = times[0];
            utimensat(AT_FDCWD, target.c_str(), times, 0);
        }
    }
    if (p) p->report("", done, total, 0, filesDone, filesTotal);
    return Status::good();
}

Status testArchive(int64_t job, const std::string& zip, int64_t& badEntries,
                   std::string& firstBadName, Progress* p) {
    badEntries = 0;
    firstBadName.clear();

    File f;
    Status s = openRead(zip, f);
    if (!s.ok()) return s;
    std::vector<ZEntry> entries;
    s = parseCentral(f, entries);
    if (!s.ok()) return s;

    int64_t total = 0, done = 0, idx = 0;
    for (const ZEntry& e : entries) total += (int64_t) e.uncompressedSize;

    for (const ZEntry& e : entries) {
        if (jobCancelled(job)) return Status::err(E_CANCELLED, "cancelled by user");
        idx++;
        if (e.isDir) continue;
        if (e.encrypted) {
            badEntries++;
            if (firstBadName.empty()) firstBadName = e.name + " (encrypted)";
            continue;
        }
        uint32_t crc = crc32(0L, Z_NULL, 0);
        Status r = streamEntry(job, f, e, [&](const uint8_t* d, size_t n) {
            crc = crc32(crc, d, (uInt) n);
            done += (int64_t) n;
            if (p) p->report(e.name.c_str(), done, total, 0, idx, (int64_t) entries.size());
            return true;
        });
        if (r.code == E_CANCELLED) return r;
        if (!r.ok() || (e.crc32 != 0 && crc != e.crc32)) {
            badEntries++;
            if (firstBadName.empty()) firstBadName = e.name;
        }
    }
    return Status::good();
}

bool looksLikeApk(const std::string& path) {
    std::vector<ZEntry> entries;
    if (!listEntries(path, entries).ok()) return false;
    bool manifest = false, dexOrArsc = false;
    for (const ZEntry& e : entries) {
        if (e.name == "AndroidManifest.xml") manifest = true;
        if (e.name == "resources.arsc" || hasSuffix(e.name, ".dex")) dexOrArsc = true;
        if (manifest && dexOrArsc) return true;
    }
    return manifest && dexOrArsc;
}

}} // namespace mtx::zipx
