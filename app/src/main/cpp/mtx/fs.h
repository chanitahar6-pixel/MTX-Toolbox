#pragma once
#include "mtx/common.h"

namespace mtx { namespace fsx {

struct Entry {
    std::string name;
    bool  isDir  = false;
    bool  isLink = false;
    bool  readable = true;
    bool  writable = false;
    int64_t size  = 0;
    int64_t mtime = 0;   // epoch millis
    uint32_t mode = 0;
};

Status list(const std::string& dir, std::vector<Entry>& out);
Status statOne(const std::string& path, Entry& out);

// Recursive, chunked, cancellable. dstDir is a directory; the source basename is
// appended. Never overwrites unless overwrite == true.
Status copyTree(int64_t job, const std::string& src, const std::string& dstDir,
                bool overwrite, Progress* p);
Status moveTree(int64_t job, const std::string& src, const std::string& dstDir,
                bool overwrite, Progress* p);
Status removeTree(int64_t job, const std::string& path, Progress* p);

Status mkdirs(const std::string& path);
Status createFile(const std::string& path);
Status renameTo(const std::string& from, const std::string& to);
Status treeStats(int64_t job, const std::string& path,
                 int64_t& bytes, int64_t& files, int64_t& dirs, Progress* p);
Status diskUsage(const std::string& path, int64_t& total, int64_t& freeB, int64_t& availB);

// Byte-exact comparison of two files; firstDiff = -1 when identical.
Status compare(int64_t job, const std::string& a, const std::string& b,
               int64_t& firstDiff, bool& identical, Progress* p);

}} // namespace mtx::fsx
