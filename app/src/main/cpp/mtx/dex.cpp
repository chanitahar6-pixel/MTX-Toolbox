// DEX structural parser: header validation, section counts, string pool reader.
// Every read is bounds-checked so an invalid or truncated DEX is reported, not fatal.
#include "mtx/dex.h"

#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <cstring>

namespace mtx { namespace dexx {
namespace {

bool readUleb128(const uint8_t* d, size_t size, size_t& pos, uint32_t& out) {
    uint32_t result = 0;
    int shift = 0;
    for (int i = 0; i < 5; i++) {
        if (pos >= size) return false;
        uint8_t b = d[pos++];
        result |= (uint32_t) (b & 0x7f) << shift;
        if ((b & 0x80) == 0) { out = result; return true; }
        shift += 7;
    }
    return false;
}

} // namespace

Status inspect(const uint8_t* d, size_t size, Info& out) {
    out.actualFileSize = (int64_t) size;
    if (size < 0x70) return Status::err(E_CORRUPT, "file is smaller than a DEX header");
    if (memcmp(d, "dex\n", 4) != 0) return Status::err(E_CORRUPT, "missing DEX magic (dex\\n)");

    out.version.assign((const char*) d + 4, 3);

    uint32_t checksum = 0, headerSize = 0, endianTag = 0, fileSize = 0;
    rdU32(d, size, 8, checksum);
    out.checksum = checksum;
    out.signatureHex = toHex(d + 12, 20);
    rdU32(d, size, 32, fileSize);
    rdU32(d, size, 36, headerSize);
    rdU32(d, size, 40, endianTag);
    out.headerFileSize = fileSize;
    out.endianReversed = (endianTag == 0x78563412u);

    if (out.endianReversed)
        return Status::err(E_UNSUPPORTED, "byte-swapped DEX files are not supported");
    if (endianTag != 0x12345678u)
        out.warnings.push_back("unexpected endian tag");
    if (headerSize != 0x70)
        out.warnings.push_back("unusual header size: " + std::to_string(headerSize));
    if (fileSize != size)
        out.warnings.push_back("header file_size (" + std::to_string(fileSize) +
                               ") does not match the real size (" + std::to_string(size) + ")");

    rdU32(d, size, 56, out.stringIds);
    rdU32(d, size, 64, out.typeIds);
    rdU32(d, size, 72, out.protoIds);
    rdU32(d, size, 80, out.fieldIds);
    rdU32(d, size, 88, out.methodIds);
    rdU32(d, size, 96, out.classDefs);
    rdU32(d, size, 52, out.mapOff);

    uint32_t stringIdsOff = 0;
    rdU32(d, size, 60, stringIdsOff);
    if ((size_t) stringIdsOff + (size_t) out.stringIds * 4 > size)
        out.warnings.push_back("string_ids table extends past end of file");
    if (out.mapOff >= size)
        out.warnings.push_back("map_off points outside the file");

    out.valid = out.warnings.empty();
    return Status::good();
}

Status inspectFile(const std::string& path, Info& out) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return fromErrno("open", path);
    struct stat st{};
    if (fstat(fd, &st) != 0) { Status s = fromErrno("fstat", path); close(fd); return s; }
    size_t size = (size_t) st.st_size;
    if (size == 0) { close(fd); return Status::err(E_CORRUPT, "empty file"); }

    void* map = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return fromErrno("mmap", path);
    Status s = inspect((const uint8_t*) map, size, out);
    munmap(map, size);
    return s;
}

Status readStrings(int64_t job, const uint8_t* d, size_t size, size_t maxCount, RowSink* sink) {
    Info info;
    Status s = inspect(d, size, info);
    if (!s.ok()) return s;

    uint32_t idsOff = 0;
    rdU32(d, size, 60, idsOff);
    size_t count = info.stringIds;
    if (count > maxCount) count = maxCount;

    for (size_t i = 0; i < count; i++) {
        if (jobCancelled(job)) return Status::err(E_CANCELLED, "cancelled by user");
        uint32_t dataOff = 0;
        if (!rdU32(d, size, idsOff + i * 4, dataOff)) break;
        size_t pos = dataOff;
        uint32_t utf16len = 0;
        if (!readUleb128(d, size, pos, utf16len)) break;
        size_t end = pos;
        while (end < size && d[end] != 0) end++;
        if (sink) sink->row(std::string((const char*) d + pos, end - pos).c_str(), "",
                           (int64_t) i, (int64_t) utf16len);
    }
    return Status::good();
}

}} // namespace mtx::dexx
