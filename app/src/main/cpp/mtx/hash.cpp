// Self-contained MD5 / SHA-1 / SHA-224 / SHA-256 with a streaming API.
// MD5 round constants are derived at runtime from sin() (RFC 1321 definition),
// which removes any risk of a hand-typed table being wrong.
#include "mtx/hash.h"

#include <fcntl.h>
#include <unistd.h>
#include <cmath>
#include <cstring>

namespace mtx { namespace hashx {
namespace {

inline uint32_t rotl32(uint32_t x, int c) { return (x << c) | (x >> (32 - c)); }
inline uint32_t rotr32(uint32_t x, int c) { return (x >> c) | (x << (32 - c)); }

// ---------------------------------------------------------------- MD5
struct Md5 {
    uint32_t h[4] = {0x67452301u, 0xefcdab89u, 0x98badcfeu, 0x10325476u};
    uint64_t len = 0;
    uint8_t  buf[64]{};
    size_t   have = 0;
    uint32_t K[64]{};
    static const int S[64];

    Md5() {
        for (int i = 0; i < 64; i++)
            K[i] = (uint32_t) (uint64_t) floor(fabs(sin((double) i + 1.0)) * 4294967296.0);
    }

    void block(const uint8_t* p) {
        uint32_t M[16];
        for (int i = 0; i < 16; i++)
            M[i] = (uint32_t) p[i * 4] | ((uint32_t) p[i * 4 + 1] << 8) |
                   ((uint32_t) p[i * 4 + 2] << 16) | ((uint32_t) p[i * 4 + 3] << 24);
        uint32_t A = h[0], B = h[1], C = h[2], D = h[3];
        for (int i = 0; i < 64; i++) {
            uint32_t F;
            int g;
            if (i < 16)      { F = (B & C) | (~B & D);          g = i; }
            else if (i < 32) { F = (D & B) | (~D & C);          g = (5 * i + 1) & 15; }
            else if (i < 48) { F = B ^ C ^ D;                   g = (3 * i + 5) & 15; }
            else             { F = C ^ (B | ~D);                g = (7 * i) & 15; }
            F += A + K[i] + M[g];
            A = D; D = C; C = B;
            B += rotl32(F, S[i]);
        }
        h[0] += A; h[1] += B; h[2] += C; h[3] += D;
    }

    void update(const uint8_t* d, size_t n) {
        len += n;
        while (n > 0) {
            size_t take = 64 - have < n ? 64 - have : n;
            memcpy(buf + have, d, take);
            have += take; d += take; n -= take;
            if (have == 64) { block(buf); have = 0; }
        }
    }

    void finish(uint8_t out[16]) {
        uint64_t bits = len * 8;
        uint8_t pad = 0x80;
        update(&pad, 1);
        uint8_t zero = 0;
        while (have != 56) update(&zero, 1);
        uint8_t tail[8];
        for (int i = 0; i < 8; i++) tail[i] = (uint8_t) (bits >> (8 * i));
        len -= 8;                       // length field itself is not hashed content
        update(tail, 8);
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) out[i * 4 + j] = (uint8_t) (h[i] >> (8 * j));
    }
};
const int Md5::S[64] = {
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5,  9, 14, 20, 5,  9, 14, 20, 5,  9, 14, 20, 5,  9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21};

// ---------------------------------------------------------------- SHA-1
struct Sha1 {
    uint32_t h[5] = {0x67452301u, 0xefcdab89u, 0x98badcfeu, 0x10325476u, 0xc3d2e1f0u};
    uint64_t len = 0;
    uint8_t  buf[64]{};
    size_t   have = 0;

    void block(const uint8_t* p) {
        uint32_t w[80];
        for (int i = 0; i < 16; i++)
            w[i] = ((uint32_t) p[i * 4] << 24) | ((uint32_t) p[i * 4 + 1] << 16) |
                   ((uint32_t) p[i * 4 + 2] << 8) | (uint32_t) p[i * 4 + 3];
        for (int i = 16; i < 80; i++)
            w[i] = rotl32(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
        uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4];
        for (int i = 0; i < 80; i++) {
            uint32_t f, k;
            if (i < 20)      { f = (b & c) | (~b & d);          k = 0x5a827999u; }
            else if (i < 40) { f = b ^ c ^ d;                   k = 0x6ed9eba1u; }
            else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8f1bbcdcu; }
            else             { f = b ^ c ^ d;                   k = 0xca62c1d6u; }
            uint32_t t = rotl32(a, 5) + f + e + k + w[i];
            e = d; d = c; c = rotl32(b, 30); b = a; a = t;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e;
    }

    void update(const uint8_t* d, size_t n) {
        len += n;
        while (n > 0) {
            size_t take = 64 - have < n ? 64 - have : n;
            memcpy(buf + have, d, take);
            have += take; d += take; n -= take;
            if (have == 64) { block(buf); have = 0; }
        }
    }

    void finish(uint8_t out[20]) {
        uint64_t bits = len * 8;
        buf[have++] = 0x80;
        if (have > 56) {
            while (have < 64) buf[have++] = 0;
            block(buf);
            have = 0;
        }
        while (have < 56) buf[have++] = 0;
        for (int i = 7; i >= 0; i--) buf[have++] = (uint8_t) (bits >> (8 * i));
        block(buf);
        for (int i = 0; i < 5; i++)
            for (int j = 0; j < 4; j++) out[i * 4 + j] = (uint8_t) (h[i] >> (24 - 8 * j));
    }
};

// ---------------------------------------------------------------- SHA-224 / SHA-256
const uint32_t SHA256_K[64] = {
    0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u,
    0x923f82a4u, 0xab1c5ed5u, 0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u,
    0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u, 0xe49b69c1u, 0xefbe4786u,
    0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
    0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u,
    0x06ca6351u, 0x14292967u, 0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u,
    0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u, 0xa2bfe8a1u, 0xa81a664bu,
    0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
    0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au,
    0x5b9cca4fu, 0x682e6ff3u, 0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u,
    0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u};

struct Sha256 {
    uint32_t h[8]{};
    uint64_t len = 0;
    uint8_t  buf[64]{};
    size_t   have = 0;
    bool     is224 = false;

    explicit Sha256(bool sha224) : is224(sha224) {
        if (sha224) {
            const uint32_t iv[8] = {0xc1059ed8u, 0x367cd507u, 0x3070dd17u, 0xf70e5939u,
                                    0xffc00b31u, 0x68581511u, 0x64f98fa7u, 0xbefa4fa4u};
            memcpy(h, iv, sizeof(iv));
        } else {
            const uint32_t iv[8] = {0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
                                    0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u};
            memcpy(h, iv, sizeof(iv));
        }
    }

    void block(const uint8_t* p) {
        uint32_t w[64];
        for (int i = 0; i < 16; i++)
            w[i] = ((uint32_t) p[i * 4] << 24) | ((uint32_t) p[i * 4 + 1] << 16) |
                   ((uint32_t) p[i * 4 + 2] << 8) | (uint32_t) p[i * 4 + 3];
        for (int i = 16; i < 64; i++) {
            uint32_t s0 = rotr32(w[i - 15], 7) ^ rotr32(w[i - 15], 18) ^ (w[i - 15] >> 3);
            uint32_t s1 = rotr32(w[i - 2], 17) ^ rotr32(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint32_t a = h[0], b = h[1], c = h[2], d = h[3];
        uint32_t e = h[4], f = h[5], g = h[6], hh = h[7];
        for (int i = 0; i < 64; i++) {
            uint32_t S1 = rotr32(e, 6) ^ rotr32(e, 11) ^ rotr32(e, 25);
            uint32_t ch = (e & f) ^ (~e & g);
            uint32_t t1 = hh + S1 + ch + SHA256_K[i] + w[i];
            uint32_t S0 = rotr32(a, 2) ^ rotr32(a, 13) ^ rotr32(a, 22);
            uint32_t mj = (a & b) ^ (a & c) ^ (b & c);
            uint32_t t2 = S0 + mj;
            hh = g; g = f; f = e; e = d + t1;
            d = c; c = b; b = a; a = t1 + t2;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d;
        h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
    }

    void update(const uint8_t* d, size_t n) {
        len += n;
        while (n > 0) {
            size_t take = 64 - have < n ? 64 - have : n;
            memcpy(buf + have, d, take);
            have += take; d += take; n -= take;
            if (have == 64) { block(buf); have = 0; }
        }
    }

    size_t finish(uint8_t out[32]) {
        uint64_t bits = len * 8;
        buf[have++] = 0x80;
        if (have > 56) {
            while (have < 64) buf[have++] = 0;
            block(buf);
            have = 0;
        }
        while (have < 56) buf[have++] = 0;
        for (int i = 7; i >= 0; i--) buf[have++] = (uint8_t) (bits >> (8 * i));
        block(buf);
        int words = is224 ? 7 : 8;
        for (int i = 0; i < words; i++)
            for (int j = 0; j < 4; j++) out[i * 4 + j] = (uint8_t) (h[i] >> (24 - 8 * j));
        return (size_t) words * 4;
    }
};

struct AnyHash {
    Algo algo;
    Md5 md5;
    Sha1 sha1;
    Sha256 sha256;

    explicit AnyHash(Algo a) : algo(a), sha256(a == SHA224) {}

    void update(const uint8_t* d, size_t n) {
        switch (algo) {
            case MD5:    md5.update(d, n);    break;
            case SHA1:   sha1.update(d, n);   break;
            default:     sha256.update(d, n); break;
        }
    }

    std::string hex() {
        uint8_t out[32];
        size_t n;
        switch (algo) {
            case MD5:  md5.finish(out);  n = 16; break;
            case SHA1: sha1.finish(out); n = 20; break;
            default:   n = sha256.finish(out);   break;
        }
        return toHex(out, n);
    }
};

} // namespace

Status hashFile(int64_t job, const std::string& path, Algo algo,
                std::string& hexOut, Progress* p) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return fromErrno("open", path);

    struct stat st{};
    int64_t total = fstat(fd, &st) == 0 ? (int64_t) st.st_size : -1;

    AnyHash hasher(algo);
    std::vector<uint8_t> buf(kChunk);
    int64_t done = 0, start = monotonicMs(), lastReport = 0;
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
        hasher.update(buf.data(), (size_t) r);
        done += r;
        if (p) {
            int64_t now = monotonicMs();
            if (now - lastReport >= kReportMs) {
                lastReport = now;
                int64_t el = now - start;
                p->report(path.c_str(), done, total, el > 0 ? done * 1000 / el : 0, 0, 1);
            }
        }
    }
    close(fd);
    if (!result.ok()) return result;
    hexOut = hasher.hex();
    if (p) p->report(path.c_str(), done, total, 0, 1, 1);
    return Status::good();
}

Status hashBuffer(const uint8_t* data, size_t len, Algo algo, std::string& hexOut) {
    AnyHash hasher(algo);
    hasher.update(data, len);
    hexOut = hasher.hex();
    return Status::good();
}

}} // namespace mtx::hashx
