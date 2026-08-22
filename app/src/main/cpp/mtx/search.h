#pragma once
#include "mtx/common.h"

namespace mtx { namespace searchx {

enum Flags : int32_t {
    F_RECURSIVE  = 1 << 0,
    F_CASE       = 1 << 1,   // case sensitive
    F_CONTENT    = 1 << 2,   // grep inside files
    F_ARCHIVES   = 1 << 3,   // look inside zip/apk entry names
    F_HIDDEN     = 1 << 4,
    F_WHOLE_WORD = 1 << 5,
};

struct Options {
    int32_t flags = F_RECURSIVE;
    size_t  maxResults = 5000;
    int64_t maxFileSize = 64ll * 1024 * 1024;   // content search guard
};

// Streams every hit through `sink` as it is found: (path, preview, lineNo, size).
// Fully cancellable through the job id.
Status run(int64_t job, const std::string& root, const std::string& namePattern,
           const std::string& contentQuery, const Options& opt,
           RowSink* sink, Progress* p);

bool wildcardMatch(const std::string& pattern, const std::string& text, bool caseSensitive);

}} // namespace mtx::searchx
