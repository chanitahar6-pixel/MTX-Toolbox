#pragma once
#include "mtx/common.h"

namespace mtx { namespace axml {

struct Attr {
    std::string ns;
    std::string name;
    std::string value;      // already resolved to a printable form
    uint32_t rawType = 0;
    uint32_t rawData = 0;
};

struct Handler {
    virtual ~Handler() = default;
    virtual void startTag(const std::string& name, const std::vector<Attr>& attrs, int line) = 0;
    virtual void endTag(const std::string& name) = 0;
    virtual void text(const std::string& value) {}
};

// Decodes Android binary XML (AndroidManifest.xml and compiled res/*.xml).
Status parse(const uint8_t* data, size_t size, Handler* h);

// Convenience: pretty-printed XML text.
Status toXml(const uint8_t* data, size_t size, std::string& out);

}} // namespace mtx::axml
