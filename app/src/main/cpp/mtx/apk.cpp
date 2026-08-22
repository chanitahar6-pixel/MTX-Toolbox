#include "mtx/apk.h"
#include "mtx/axml.h"
#include "mtx/zip.h"

#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <algorithm>
#include <cstring>

namespace mtx { namespace apkx {
namespace {

constexpr size_t MAX_MANIFEST = 32u * 1024u * 1024u;
const char* ANDROID_NS = "http://schemas.android.com/apk/res/android";

const std::string* findAttr(const std::vector<axml::Attr>& attrs, const char* name) {
    for (const axml::Attr& a : attrs)
        if (a.name == name && (a.ns.empty() || a.ns == ANDROID_NS)) return &a.value;
    return nullptr;
}

bool attrBool(const std::vector<axml::Attr>& attrs, const char* name, bool def) {
    const std::string* v = findAttr(attrs, name);
    if (!v) return def;
    return *v == "true" || *v == "1";
}

int attrInt(const std::vector<axml::Attr>& attrs, const char* name, int def) {
    const std::string* v = findAttr(attrs, name);
    if (!v || v->empty()) return def;
    char* end = nullptr;
    long n = strtol(v->c_str(), &end, v->compare(0, 2, "0x") == 0 ? 16 : 10);
    if (end == v->c_str()) return def;
    return (int) n;
}

// Expands ".MainActivity" into "<package>.MainActivity" the way Android does.
std::string qualify(const std::string& pkg, const std::string& name) {
    if (name.empty()) return name;
    if (name[0] == '.') return pkg + name;
    if (name.find('.') == std::string::npos) return pkg + "." + name;
    return name;
}

class ManifestHandler : public axml::Handler {
public:
    explicit ManifestHandler(Info& info) : info_(info) {}

    void startTag(const std::string& name, const std::vector<axml::Attr>& attrs, int line) override {
        stack_.push_back(name);

        if (name == "manifest") {
            if (const std::string* v = findAttr(attrs, "package")) info_.packageName = *v;
            if (const std::string* v = findAttr(attrs, "versionName")) info_.versionName = *v;
            if (const std::string* v = findAttr(attrs, "split")) info_.splitName = *v;
            if (const std::string* v = findAttr(attrs, "installLocation")) info_.installLocation = *v;
            info_.versionCode = attrInt(attrs, "versionCode", -1);
            info_.compileSdk = attrInt(attrs, "compileSdkVersion", -1);
        } else if (name == "uses-sdk") {
            info_.minSdk = attrInt(attrs, "minSdkVersion", info_.minSdk);
            info_.targetSdk = attrInt(attrs, "targetSdkVersion", info_.targetSdk);
        } else if (name == "uses-permission" || name == "uses-permission-sdk-23") {
            if (const std::string* v = findAttr(attrs, "name")) info_.permissions.push_back(*v);
        } else if (name == "permission") {
            if (const std::string* v = findAttr(attrs, "name")) info_.declaredPermissions.push_back(*v);
        } else if (name == "uses-feature") {
            if (const std::string* v = findAttr(attrs, "name")) info_.features.push_back(*v);
        } else if (name == "uses-library") {
            if (const std::string* v = findAttr(attrs, "name")) info_.libraries.push_back(*v);
        } else if (name == "application") {
            if (const std::string* v = findAttr(attrs, "label")) info_.appLabel = *v;
            if (const std::string* v = findAttr(attrs, "icon")) info_.appIcon = *v;
            info_.debuggable = attrBool(attrs, "debuggable", false);
            info_.extractNativeLibs = attrBool(attrs, "extractNativeLibs", true);
            info_.usesCleartextTraffic = attrBool(attrs, "usesCleartextTraffic", false);
        } else if (name == "activity" || name == "activity-alias" || name == "service" ||
                   name == "receiver" || name == "provider") {
            Component c;
            c.kind = (name == "activity-alias") ? "activity" : name;
            const std::string* n = findAttr(attrs, "name");
            c.name = qualify(info_.packageName, n ? *n : "");
            c.exported = attrBool(attrs, "exported", false);
            c.enabled = attrBool(attrs, "enabled", true);
            info_.components.push_back(std::move(c));
            current_ = (int) info_.components.size() - 1;
        } else if (name == "action" && current_ >= 0 && inIntentFilter()) {
            if (const std::string* v = findAttr(attrs, "name"))
                info_.components[current_].intentActions.push_back(*v);
        } else if (name == "category" && current_ >= 0 && inIntentFilter()) {
            if (const std::string* v = findAttr(attrs, "name"))
                info_.components[current_].intentCategories.push_back(*v);
        }
    }

    void endTag(const std::string& name) override {
        if (!stack_.empty()) stack_.pop_back();
        if (name == "activity" || name == "activity-alias" || name == "service" ||
            name == "receiver" || name == "provider")
            current_ = -1;
    }

private:
    bool inIntentFilter() const {
        for (const std::string& s : stack_) if (s == "intent-filter") return true;
        return false;
    }

    Info& info_;
    std::vector<std::string> stack_;
    int current_ = -1;
};

// APK Signing Block sits between the last entry and the central directory.
void probeSigningBlock(const std::string& path, Info& info) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    struct stat st{};
    if (fstat(fd, &st) != 0) { close(fd); return; }
    int64_t size = (int64_t) st.st_size;

    // Find EOCD to learn where the central directory starts.
    size_t tail = (size_t) (size < 66560 ? size : 66560);
    std::vector<uint8_t> buf(tail);
    if (pread(fd, buf.data(), tail, (off_t) (size - (int64_t) tail)) <= 0) { close(fd); return; }
    int64_t eocd = -1;
    for (size_t i = buf.size() >= 22 ? buf.size() - 22 : 0;; i--) {
        uint32_t sig = 0;
        if (rdU32(buf.data(), buf.size(), i, sig) && sig == 0x06054b50u) { eocd = (int64_t) i; break; }
        if (i == 0) break;
    }
    if (eocd < 0) { close(fd); return; }
    uint32_t cdOff32 = 0;
    rdU32(buf.data(), buf.size(), (size_t) eocd + 16, cdOff32);
    int64_t cdOff = cdOff32;
    if (cdOff < 24) { close(fd); return; }

    uint8_t footer[24];
    if (pread(fd, footer, 24, (off_t) (cdOff - 24)) != 24) { close(fd); return; }
    if (memcmp(footer + 8, "APK Sig Block 42", 16) != 0) { close(fd); return; }

    info.hasApkSigningBlock = true;
    uint64_t blockSize = 0;
    rdU64(footer, 24, 0, blockSize);
    int64_t blockStart = cdOff - (int64_t) blockSize - 8;
    if (blockStart < 0 || blockSize > 64u * 1024u * 1024u) { close(fd); return; }

    std::vector<uint8_t> block((size_t) blockSize + 8);
    if (pread(fd, block.data(), block.size(), (off_t) blockStart) <= 0) { close(fd); return; }
    close(fd);

    // Walk the ID-value pairs.
    size_t pos = 8;
    size_t limit = block.size() >= 24 ? block.size() - 24 : 0;
    while (pos + 12 <= limit) {
        uint64_t pairLen = 0;
        uint32_t id = 0;
        rdU64(block.data(), block.size(), pos, pairLen);
        rdU32(block.data(), block.size(), pos + 8, id);
        if (pairLen < 4 || pos + 8 + pairLen > block.size()) break;
        switch (id) {
            case 0x7109871au: info.schemeV2 = true; break;   // v2
            case 0xf05368c0u: info.schemeV3 = true; break;   // v3
            case 0x1b93ad61u: info.schemeV31 = true; break;  // v3.1
            default: break;
        }
        pos += 8 + (size_t) pairLen;
    }
}

} // namespace

Status inspect(const std::string& apkPath, Info& out) {
    out.path = apkPath;
    struct stat st{};
    if (stat(apkPath.c_str(), &st) != 0) return fromErrno("stat", apkPath);
    out.fileSize = (int64_t) st.st_size;

    std::vector<zipx::ZEntry> entries;
    Status s = zipx::listEntries(apkPath, entries);
    if (!s.ok()) return s;
    out.entryCount = (int64_t) entries.size();

    bool manifestPresent = false;
    for (const zipx::ZEntry& e : entries) {
        if (e.name == "AndroidManifest.xml") manifestPresent = true;
        else if (e.name == "resources.arsc") out.hasResourcesArsc = true;
        else if (e.name.compare(0, 7, "assets/") == 0) out.hasAssets = true;
        else if (hasSuffix(e.name, ".dex") && e.name.find('/') == std::string::npos)
            out.dexFiles.push_back(e.name);
        else if (e.name.compare(0, 4, "lib/") == 0 && hasSuffix(e.name, ".so")) {
            out.nativeLibs.push_back(e.name);
            size_t a = 4, b = e.name.find('/', 4);
            if (b != std::string::npos) {
                std::string abi = e.name.substr(a, b - a);
                if (std::find(out.abis.begin(), out.abis.end(), abi) == out.abis.end())
                    out.abis.push_back(abi);
            }
        } else if (e.name.compare(0, 9, "META-INF/") == 0) {
            out.metaInfFiles.push_back(e.name);
            std::string up = lower(e.name);
            if (hasSuffix(up, ".rsa") || hasSuffix(up, ".dsa") || hasSuffix(up, ".ec"))
                out.hasV1Files = true;
        }
    }
    std::sort(out.dexFiles.begin(), out.dexFiles.end());

    if (!manifestPresent) {
        out.warnings.push_back("no AndroidManifest.xml: this ZIP is not an APK");
        probeSigningBlock(apkPath, out);
        return Status::err(E_CORRUPT, "AndroidManifest.xml missing: not a valid APK");
    }

    std::vector<uint8_t> manifest;
    s = zipx::readEntry(apkPath, "AndroidManifest.xml", manifest, MAX_MANIFEST);
    if (!s.ok()) {
        out.warnings.push_back("AndroidManifest.xml could not be read: " + s.msg);
        probeSigningBlock(apkPath, out);
        return s;
    }

    ManifestHandler h(out);
    s = axml::parse(manifest.data(), manifest.size(), &h);
    if (!s.ok()) {
        out.warnings.push_back("manifest decode failed: " + s.msg);
        probeSigningBlock(apkPath, out);
        return s;
    }

    for (const Component& c : out.components) {
        bool main = false, launcher = false;
        for (const std::string& a : c.intentActions)
            if (a == "android.intent.action.MAIN") main = true;
        for (const std::string& a : c.intentCategories)
            if (a == "android.intent.category.LAUNCHER") launcher = true;
        if (main && launcher && c.kind == "activity") { out.mainActivity = c.name; break; }
    }

    probeSigningBlock(apkPath, out);

    if (out.dexFiles.empty())
        out.warnings.push_back("no classes.dex found (resource-only or split APK)");
    if (!out.hasApkSigningBlock && !out.hasV1Files)
        out.warnings.push_back("no signature found: this APK is unsigned");
    return Status::good();
}

Status manifestXml(const std::string& apkPath, std::string& xmlOut) {
    std::vector<uint8_t> manifest;
    Status s = zipx::readEntry(apkPath, "AndroidManifest.xml", manifest, MAX_MANIFEST);
    if (!s.ok()) return s;
    return axml::toXml(manifest.data(), manifest.size(), xmlOut);
}

}} // namespace mtx::apkx
