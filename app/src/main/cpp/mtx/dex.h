#pragma once
#include "mtx/common.h"

namespace mtx { namespace dexx {

struct Info {
    std::string version;        // 035 / 037 / 038 / 039
    std::string signatureHex;   // SHA-1 stored in the header
    uint32_t checksum = 0;
    int64_t  headerFileSize = 0;
    int64_t  actualFileSize = 0;
    uint32_t stringIds = 0, typeIds = 0, protoIds = 0, fieldIds = 0, methodIds = 0, classDefs = 0;
    uint32_t mapOff = 0;
    bool     endianReversed = false;
    bool     valid = false;
    std::vector<std::string> warnings;
};

Status inspect(const uint8_t* data, size_t size, Info& out);
Status inspectFile(const std::string& path, Info& out);

// Streams up to maxCount strings out of the DEX string pool.
Status readStrings(int64_t job, const uint8_t* data, size_t size,
                   size_t maxCount, RowSink* sink);

}} // namespace mtx::dexx
