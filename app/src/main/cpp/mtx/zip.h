#pragma once
#include "mtx/common.h"

namespace mtx { namespace zipx {

struct ZEntry {
    std::string name;
    uint64_t compressedSize   = 0;
    uint64_t uncompressedSize = 0;
    uint64_t localOffset      = 0;
    uint32_t crc32            = 0;
    uint16_t method           = 0;   // 0 = store, 8 = deflate
    int64_t  mtime            = 0;   // epoch millis
    bool     isDir            = false;
    bool     encrypted        = false;
};

// Reads only the central directory. A 4 GB archive costs a few hundred KB here.
Status listEntries(const std::string& zip, std::vector<ZEntry>& out);

// Decompresses a single entry into memory. Refuses anything above maxBytes so a
// hostile or broken archive cannot blow up the heap (zip-bomb guard).
Status readEntry(const std::string& zip, const std::string& name,
                 std::vector<uint8_t>& out, size_t maxBytes);

// entryName empty => extract everything. Blocks path traversal and symlink escapes.
Status extract(int64_t job, const std::string& zip, const std::string& entryName,
               const std::string& outDir, Progress* p);

// Full CRC verification of every entry.
Status testArchive(int64_t job, const std::string& zip, int64_t& badEntries,
                   std::string& firstBadName, Progress* p);

// Cheap probe used by the file type analyzer (central directory only).
bool looksLikeApk(const std::string& path);

}} // namespace mtx::zipx
