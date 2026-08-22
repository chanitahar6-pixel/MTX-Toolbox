#pragma once
#include "mtx/common.h"

namespace mtx { namespace hexx {

// Paged random access: nothing is ever fully loaded, so multi-GB files are fine.
Status readPage(const std::string& path, int64_t offset, size_t len,
                std::vector<uint8_t>& out, int64_t& fileSize);

// In-place overwrite (pwrite). Does not change file length.
Status writeAt(const std::string& path, int64_t offset, const uint8_t* data, size_t len);

// Byte-pattern search over a streaming window with overlap so matches spanning
// chunk borders are still found. Returns first match at or after `from`, or -1.
Status findBytes(int64_t job, const std::string& path, int64_t from,
                 const uint8_t* pattern, size_t plen, bool backwards,
                 int64_t& matchOffset, Progress* p);

// Extract printable strings from a binary, streaming and cancellable.
Status extractStrings(int64_t job, const std::string& path, size_t minLen,
                      size_t maxResults, RowSink* sink);

}} // namespace mtx::hexx
