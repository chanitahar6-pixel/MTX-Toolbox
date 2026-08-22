#pragma once
#include "mtx/common.h"

namespace mtx { namespace hashx {

enum Algo { MD5 = 0, SHA1 = 1, SHA224 = 2, SHA256 = 3 };

// Streaming, constant memory, cancellable. Returns lowercase hex.
// SHA-384/512 are provided by the Java layer (framework MessageDigest) because
// the NDK exposes no crypto library; everything else is implemented here.
Status hashFile(int64_t job, const std::string& path, Algo algo,
                std::string& hexOut, Progress* p);

Status hashBuffer(const uint8_t* data, size_t len, Algo algo, std::string& hexOut);

}} // namespace mtx::hashx
