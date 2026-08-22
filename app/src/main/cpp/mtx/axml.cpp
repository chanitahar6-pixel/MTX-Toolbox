// Android binary XML (AXML) decoder. Bounds-checked everywhere: a corrupt or
// deliberately malformed manifest returns E_CORRUPT instead of crashing.
#include "mtx/axml.h"

#include <cstring>

namespace mtx { namespace axml {
namespace {

constexpr uint16_t RES_STRING_POOL   = 0x0001;
constexpr uint16_t RES_XML           = 0x0003;
constexpr uint16_t RES_XML_START_NS  = 0x0100;
constexpr uint16_t RES_XML_END_NS    = 0x0101;
constexpr uint16_t RES_XML_START_TAG = 0x0102;
constexpr uint16_t RES_XML_END_TAG   = 0x0103;
constexpr uint16_t RES_XML_CDATA     = 0x0104;
constexpr uint16_t RES_XML_RES_MAP   = 0x0180;

constexpr uint32_t UTF8_FLAG = 1u << 8;

// Types from ResValue.
constexpr uint8_t TYPE_NULL      = 0x00;
constexpr uint8_t TYPE_REFERENCE = 0x01;
constexpr uint8_t TYPE_ATTRIBUTE = 0x02;
constexpr uint8_t TYPE_STRING    = 0x03;
constexpr uint8_t TYPE_FLOAT     = 0x04;
constexpr uint8_t TYPE_DIMENSION = 0x05;
constexpr uint8_t TYPE_FRACTION  = 0x06;
constexpr uint8_t TYPE_INT_DEC   = 0x10;
constexpr uint8_t TYPE_INT_HEX   = 0x11;
constexpr uint8_t TYPE_INT_BOOL  = 0x12;

void appendUtf8(std::string& out, uint32_t cp) {
    if (cp < 0x80) out.push_back((char) cp);
    else if (cp < 0x800) {
        out.push_back((char) (0xC0 | (cp >> 6)));
        out.push_back((char) (0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out.push_back((char) (0xE0 | (cp >> 12)));
        out.push_back((char) (0x80 | ((cp >> 6) & 0x3F)));
        out.push_back((char) (0x80 | (cp & 0x3F)));
    } else {
        out.push_back((char) (0xF0 | (cp >> 18)));
        out.push_back((char) (0x80 | ((cp >> 12) & 0x3F)));
        out.push_back((char) (0x80 | ((cp >> 6) & 0x3F)));
        out.push_back((char) (0x80 | (cp & 0x3F)));
    }
}

class StringPool {
public:
    bool load(const uint8_t* d, size_t size, size_t chunkOff) {
        uint32_t chunkSize = 0, count = 0, flags = 0, stringsStart = 0;
        uint16_t headerSize = 0;
        if (!rdU16(d, size, chunkOff + 2, headerSize)) return false;
        if (!rdU32(d, size, chunkOff + 4, chunkSize)) return false;
        if (!rdU32(d, size, chunkOff + 8, count)) return false;
        if (!rdU32(d, size, chunkOff + 16, flags)) return false;
        if (!rdU32(d, size, chunkOff + 20, stringsStart)) return false;
        if (chunkOff + chunkSize > size) return false;

        base_ = d;
        size_ = size;
        chunk_ = chunkOff;
        count_ = count;
        utf8_ = (flags & UTF8_FLAG) != 0;
        dataStart_ = chunkOff + stringsStart;
        offsetsAt_ = chunkOff + headerSize;
        end_ = chunkOff + chunkSize;
        if (offsetsAt_ + (size_t) count * 4 > size) return false;
        cache_.assign(count_, std::string());
        loaded_.assign(count_, false);
        return true;
    }

    size_t count() const { return count_; }

    const std::string& get(uint32_t index) {
        static const std::string empty;
        if (index >= count_) return empty;
        if (loaded_[index]) return cache_[index];
        loaded_[index] = true;
        uint32_t off = 0;
        if (!rdU32(base_, size_, offsetsAt_ + (size_t) index * 4, off)) return cache_[index];
        size_t p = dataStart_ + off;
        if (p >= end_) return cache_[index];
        cache_[index] = utf8_ ? readUtf8(p) : readUtf16(p);
        return cache_[index];
    }

private:
    std::string readUtf8(size_t p) {
        // Two length fields (u16 char len, u8/u16 byte len) using the modified scheme.
        auto len8 = [&](size_t& q, uint32_t& v) -> bool {
            if (q >= end_) return false;
            uint8_t b = base_[q++];
            if (b & 0x80) {
                if (q >= end_) return false;
                v = (uint32_t) (((b & 0x7F) << 8) | base_[q++]);
            } else v = b;
            return true;
        };
        uint32_t charLen = 0, byteLen = 0;
        if (!len8(p, charLen) || !len8(p, byteLen)) return "";
        if (p + byteLen > end_) byteLen = (uint32_t) (end_ - p);
        return std::string((const char*) base_ + p, byteLen);
    }

    std::string readUtf16(size_t p) {
        uint16_t lenLo = 0;
        if (!rdU16(base_, size_, p, lenLo)) return "";
        size_t q = p + 2;
        uint32_t charLen = lenLo;
        if (lenLo & 0x8000) {
            uint16_t lenHi = 0;
            if (!rdU16(base_, size_, q, lenHi)) return "";
            charLen = (uint32_t) (((lenLo & 0x7FFF) << 16) | lenHi);
            q += 2;
        }
        std::string out;
        out.reserve(charLen);
        for (uint32_t i = 0; i < charLen; i++) {
            uint16_t u = 0;
            if (!rdU16(base_, size_, q, u)) break;
            q += 2;
            uint32_t cp = u;
            if (u >= 0xD800 && u <= 0xDBFF) {
                uint16_t lo = 0;
                if (rdU16(base_, size_, q, lo) && lo >= 0xDC00 && lo <= 0xDFFF) {
                    cp = 0x10000u + (((uint32_t) (u - 0xD800)) << 10) + (uint32_t) (lo - 0xDC00);
                    q += 2;
                    i++;
                }
            }
            appendUtf8(out, cp);
        }
        return out;
    }

    const uint8_t* base_ = nullptr;
    size_t size_ = 0, chunk_ = 0, dataStart_ = 0, offsetsAt_ = 0, end_ = 0;
    uint32_t count_ = 0;
    bool utf8_ = false;
    std::vector<std::string> cache_;
    std::vector<bool> loaded_;
};

std::string formatValue(StringPool& pool, uint8_t type, uint32_t data, uint32_t rawIndex) {
    char buf[64];
    switch (type) {
        case TYPE_STRING:
            return pool.get(rawIndex != 0xffffffffu ? rawIndex : data);
        case TYPE_INT_BOOL:
            return data != 0 ? "true" : "false";
        case TYPE_INT_DEC:
            snprintf(buf, sizeof(buf), "%d", (int32_t) data);
            return buf;
        case TYPE_INT_HEX:
            snprintf(buf, sizeof(buf), "0x%08x", data);
            return buf;
        case TYPE_FLOAT: {
            float f;
            memcpy(&f, &data, 4);
            snprintf(buf, sizeof(buf), "%g", (double) f);
            return buf;
        }
        case TYPE_REFERENCE:
            snprintf(buf, sizeof(buf), "@0x%08x", data);
            return buf;
        case TYPE_ATTRIBUTE:
            snprintf(buf, sizeof(buf), "?0x%08x", data);
            return buf;
        case TYPE_DIMENSION: {
            static const char* U[] = {"px", "dip", "sp", "pt", "in", "mm"};
            int unit = data & 0xF;
            double v = (double) (int32_t) (data >> 8);
            snprintf(buf, sizeof(buf), "%g%s", v, unit < 6 ? U[unit] : "");
            return buf;
        }
        case TYPE_FRACTION: {
            snprintf(buf, sizeof(buf), "%g%%", (double) (int32_t) (data >> 8) / 100.0);
            return buf;
        }
        case TYPE_NULL:
            return "";
        default:
            snprintf(buf, sizeof(buf), "0x%08x", data);
            return buf;
    }
}

class XmlWriter : public Handler {
public:
    explicit XmlWriter(std::string& out) : out_(out) {
        out_ = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";
    }
    void startTag(const std::string& name, const std::vector<Attr>& attrs, int line) override {
        indent();
        out_ += "<" + name;
        for (const Attr& a : attrs) {
            out_ += "\n";
            for (int i = 0; i <= depth_; i++) out_ += "    ";
            out_ += (a.ns.empty() ? a.name : nsPrefix(a.ns) + ":" + a.name);
            out_ += "=\"" + escape(a.value) + "\"";
        }
        out_ += ">\n";
        depth_++;
    }
    void endTag(const std::string& name) override {
        if (depth_ > 0) depth_--;
        indent();
        out_ += "</" + name + ">\n";
    }
    void text(const std::string& value) override {
        if (value.empty()) return;
        indent();
        out_ += escape(value) + "\n";
    }

private:
    static std::string nsPrefix(const std::string& uri) {
        if (uri == "http://schemas.android.com/apk/res/android") return "android";
        if (uri.find("apk/res-auto") != std::string::npos) return "app";
        return "ns";
    }
    static std::string escape(const std::string& s) {
        std::string o;
        for (char c : s) {
            switch (c) {
                case '&': o += "&amp;"; break;
                case '<': o += "&lt;"; break;
                case '>': o += "&gt;"; break;
                case '"': o += "&quot;"; break;
                default: o.push_back(c);
            }
        }
        return o;
    }
    void indent() { for (int i = 0; i < depth_; i++) out_ += "    "; }

    std::string& out_;
    int depth_ = 0;
};

} // namespace

Status parse(const uint8_t* d, size_t size, Handler* h) {
    if (!d || size < 8) return Status::err(E_CORRUPT, "binary XML too small");
    uint16_t type = 0, headerSize = 0;
    uint32_t totalSize = 0;
    rdU16(d, size, 0, type);
    rdU16(d, size, 2, headerSize);
    rdU32(d, size, 4, totalSize);
    if (type != RES_XML)
        return Status::err(E_CORRUPT, "not an Android binary XML file");
    if (totalSize > size) totalSize = (uint32_t) size;   // tolerate padded/truncated tails

    StringPool pool;
    bool havePool = false;
    size_t pos = headerSize >= 8 ? headerSize : 8;

    while (pos + 8 <= totalSize) {
        uint16_t cType = 0;
        uint32_t cSize = 0;
        rdU16(d, size, pos, cType);
        rdU32(d, size, pos + 4, cSize);
        if (cSize < 8 || pos + cSize > totalSize)
            return Status::err(E_CORRUPT, "corrupt chunk in binary XML");

        switch (cType) {
            case RES_STRING_POOL:
                if (!pool.load(d, size, pos))
                    return Status::err(E_CORRUPT, "corrupt string pool in binary XML");
                havePool = true;
                break;

            case RES_XML_START_TAG: {
                if (!havePool) return Status::err(E_CORRUPT, "start tag before string pool");
                uint32_t line = 0, nsIdx = 0, nameIdx = 0;
                uint16_t attrStart = 0, attrSize = 0, attrCount = 0;
                rdU32(d, size, pos + 8, line);
                rdU32(d, size, pos + 16, nsIdx);
                rdU32(d, size, pos + 20, nameIdx);
                rdU16(d, size, pos + 24, attrStart);
                rdU16(d, size, pos + 26, attrSize);
                rdU16(d, size, pos + 28, attrCount);
                if (attrSize == 0) attrSize = 20;

                std::vector<Attr> attrs;
                attrs.reserve(attrCount);
                for (uint16_t i = 0; i < attrCount; i++) {
                    size_t a = pos + attrStart + (size_t) i * attrSize;
                    if (a + 20 > pos + cSize) break;
                    uint32_t aNs = 0, aName = 0, aRaw = 0, aData = 0;
                    uint8_t aType = 0;
                    rdU32(d, size, a, aNs);
                    rdU32(d, size, a + 4, aName);
                    rdU32(d, size, a + 8, aRaw);
                    if (a + 15 < size) aType = d[a + 15];
                    rdU32(d, size, a + 16, aData);

                    Attr at;
                    at.ns   = aNs == 0xffffffffu ? "" : pool.get(aNs);
                    at.name = pool.get(aName);
                    at.rawType = aType;
                    at.rawData = aData;
                    at.value = formatValue(pool, aType, aData, aRaw);
                    attrs.push_back(std::move(at));
                }
                h->startTag(pool.get(nameIdx), attrs, (int) line);
                break;
            }

            case RES_XML_END_TAG: {
                uint32_t nameIdx = 0;
                rdU32(d, size, pos + 20, nameIdx);
                h->endTag(pool.get(nameIdx));
                break;
            }

            case RES_XML_CDATA: {
                uint32_t dataIdx = 0;
                rdU32(d, size, pos + 16, dataIdx);
                h->text(pool.get(dataIdx));
                break;
            }

            case RES_XML_START_NS:
            case RES_XML_END_NS:
            case RES_XML_RES_MAP:
            default:
                break;
        }
        pos += cSize;
    }
    return Status::good();
}

Status toXml(const uint8_t* data, size_t size, std::string& out) {
    XmlWriter w(out);
    return parse(data, size, &w);
}

}} // namespace mtx::axml
