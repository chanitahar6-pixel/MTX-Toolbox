#pragma once
#include "mtx/common.h"

namespace mtx { namespace ftype {

struct Info {
    std::string kind;        // stable id: apk, zip, dex, elf, png, json, xml, text, binary ...
    std::string mime;
    std::string description;
    std::string magicHex;    // first bytes, for display
    std::string encoding;    // utf-8 / utf-16le / utf-16be / binary / empty
    int64_t size = 0;
    // Tools MTX can legitimately open this file with, in priority order.
    std::vector<std::string> tools;
};

// Magic-bytes first, extension only as a tie-breaker. Never executes anything.
Status analyze(const std::string& path, Info& out);

}} // namespace mtx::ftype
