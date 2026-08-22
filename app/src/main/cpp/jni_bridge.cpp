// JNI surface of app.mtx.toolbox.core.Native.
// Rules honoured here:
//  * no C++ exception ever crosses into the JVM;
//  * every failure sets a thread-local message retrievable via lastError();
//  * every string handed to the JVM is sanitised to valid UTF-8 first, because
//    file names on Android can contain arbitrary bytes and NewStringUTF would abort.
#include <jni.h>
#include <string>
#include <vector>

#include "mtx/common.h"
#include "mtx/fs.h"
#include "mtx/hash.h"
#include "mtx/filetype.h"
#include "mtx/hex.h"
#include "mtx/zip.h"
#include "mtx/axml.h"
#include "mtx/dex.h"
#include "mtx/apk.h"
#include "mtx/elf.h"
#include "mtx/search.h"

using namespace mtx;

namespace {

constexpr char SEP = '\x01';       // field separator used with Java
thread_local std::string g_lastError;

void setError(const Status& s) { g_lastError = s.msg; }
void clearError() { g_lastError.clear(); }

std::string sanitize(const std::string& in) {
    std::string out;
    out.reserve(in.size());
    size_t i = 0;
    while (i < in.size()) {
        unsigned char c = (unsigned char) in[i];
        size_t extra = 0;
        if (c < 0x80) { out.push_back((char) c); i++; continue; }
        else if (c >= 0xC2 && c <= 0xDF) extra = 1;
        else if (c >= 0xE0 && c <= 0xEF) extra = 2;
        else if (c >= 0xF0 && c <= 0xF4) extra = 3;
        else { out.push_back('?'); i++; continue; }

        bool valid = i + extra < in.size();
        for (size_t k = 1; valid && k <= extra; k++)
            if (((unsigned char) in[i + k] & 0xC0) != 0x80) valid = false;
        if (!valid) { out.push_back('?'); i++; continue; }
        out.append(in, i, extra + 1);
        i += extra + 1;
    }
    return out;
}

jstring toJ(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(sanitize(s).c_str());
}

std::string fromJ(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    if (!c) return "";
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

jobjectArray toJArray(JNIEnv* env, const std::vector<std::string>& rows) {
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray((jsize) rows.size(), sc, nullptr);
    for (size_t i = 0; i < rows.size(); i++) {
        jstring js = toJ(env, rows[i]);
        env->SetObjectArrayElement(arr, (jsize) i, js);
        env->DeleteLocalRef(js);
    }
    env->DeleteLocalRef(sc);
    return arr;
}

// ---- callback adapters ----------------------------------------------------
class JProgress : public Progress {
public:
    JProgress(JNIEnv* env, jobject sink) : env_(env), sink_(sink) {
        if (!sink_) return;
        jclass c = env_->GetObjectClass(sink_);
        mid_ = env_->GetMethodID(c, "onProgress", "(Ljava/lang/String;JJJJJ)V");
        env_->DeleteLocalRef(c);
    }
    bool valid() const { return sink_ && mid_; }
    void report(const char* current, int64_t done, int64_t total,
                int64_t speed, int64_t filesDone, int64_t filesTotal) override {
        if (!valid()) return;
        jstring js = toJ(env_, current ? current : "");
        env_->CallVoidMethod(sink_, mid_, js, (jlong) done, (jlong) total,
                             (jlong) speed, (jlong) filesDone, (jlong) filesTotal);
        if (js) env_->DeleteLocalRef(js);
        if (env_->ExceptionCheck()) env_->ExceptionClear();
    }
private:
    JNIEnv* env_;
    jobject sink_;
    jmethodID mid_ = nullptr;
};

class JRows : public RowSink {
public:
    JRows(JNIEnv* env, jobject sink) : env_(env), sink_(sink) {
        if (!sink_) return;
        jclass c = env_->GetObjectClass(sink_);
        mid_ = env_->GetMethodID(c, "onRow", "(Ljava/lang/String;Ljava/lang/String;JJ)V");
        env_->DeleteLocalRef(c);
    }
    void row(const char* a, const char* b, int64_t n1, int64_t n2) override {
        if (!sink_ || !mid_) return;
        jstring ja = toJ(env_, a ? a : "");
        jstring jb = toJ(env_, b ? b : "");
        env_->CallVoidMethod(sink_, mid_, ja, jb, (jlong) n1, (jlong) n2);
        if (ja) env_->DeleteLocalRef(ja);
        if (jb) env_->DeleteLocalRef(jb);
        if (env_->ExceptionCheck()) env_->ExceptionClear();
    }
private:
    JNIEnv* env_;
    jobject sink_;
    jmethodID mid_ = nullptr;
};

void kv(std::string& out, const char* key, const std::string& value) {
    out += key;
    out += '=';
    out += value;
    out += '\n';
}
void kv(std::string& out, const char* key, int64_t value) {
    kv(out, key, std::to_string(value));
}
void kv(std::string& out, const char* key, bool value) {
    kv(out, key, std::string(value ? "true" : "false"));
}

} // namespace

extern "C" {

#define NM(name) Java_app_mtx_toolbox_core_Native_##name

JNIEXPORT jstring JNICALL NM(coreVersion)(JNIEnv* env, jclass) {
    return toJ(env, "mtx-core 0.1.0 (C++17, no Kotlin)");
}

JNIEXPORT jstring JNICALL NM(lastError)(JNIEnv* env, jclass) {
    return toJ(env, g_lastError);
}

JNIEXPORT jlong JNICALL NM(newJob)(JNIEnv*, jclass) { return (jlong) jobNew(); }
JNIEXPORT void  JNICALL NM(cancelJob)(JNIEnv*, jclass, jlong id) { jobCancel((int64_t) id); }
JNIEXPORT void  JNICALL NM(releaseJob)(JNIEnv*, jclass, jlong id) { jobRelease((int64_t) id); }

// ---------------------------------------------------------------- file system
JNIEXPORT jobjectArray JNICALL NM(listDir)(JNIEnv* env, jclass, jstring jpath) {
    clearError();
    std::vector<fsx::Entry> entries;
    Status s = fsx::list(fromJ(env, jpath), entries);
    if (!s.ok()) { setError(s); return nullptr; }

    std::vector<std::string> rows;
    rows.reserve(entries.size());
    for (const fsx::Entry& e : entries) {
        std::string r = e.name;
        r += SEP; r += e.isDir ? '1' : '0';
        r += SEP; r += std::to_string(e.size);
        r += SEP; r += std::to_string(e.mtime);
        r += SEP; r += std::to_string(e.mode);
        r += SEP; r += e.isLink ? '1' : '0';
        r += SEP; r += e.readable ? '1' : '0';
        r += SEP; r += e.writable ? '1' : '0';
        rows.push_back(std::move(r));
    }
    return toJArray(env, rows);
}

JNIEXPORT jstring JNICALL NM(statPath)(JNIEnv* env, jclass, jstring jpath) {
    clearError();
    fsx::Entry e;
    Status s = fsx::statOne(fromJ(env, jpath), e);
    if (!s.ok()) { setError(s); return nullptr; }
    std::string r = e.name;
    r += SEP; r += e.isDir ? '1' : '0';
    r += SEP; r += std::to_string(e.size);
    r += SEP; r += std::to_string(e.mtime);
    r += SEP; r += std::to_string(e.mode);
    r += SEP; r += e.isLink ? '1' : '0';
    r += SEP; r += e.readable ? '1' : '0';
    r += SEP; r += e.writable ? '1' : '0';
    return toJ(env, r);
}

JNIEXPORT jint JNICALL NM(copyPath)(JNIEnv* env, jclass, jlong job, jstring src, jstring dstDir,
                                    jboolean overwrite, jobject sink) {
    clearError();
    JProgress p(env, sink);
    Status s = fsx::copyTree((int64_t) job, fromJ(env, src), fromJ(env, dstDir),
                             overwrite == JNI_TRUE, p.valid() ? &p : nullptr);
    if (!s.ok()) setError(s);
    return s.code;
}

JNIEXPORT jint JNICALL NM(movePath)(JNIEnv* env, jclass, jlong job, jstring src, jstring dstDir,
                                    jboolean overwrite, jobject sink) {
    clearError();
    JProgress p(env, sink);
    Status s = fsx::moveTree((int64_t) job, fromJ(env, src), fromJ(env, dstDir),
                             overwrite == JNI_TRUE, p.valid() ? &p : nullptr);
    if (!s.ok()) setError(s);
    return s.code;
}

JNIEXPORT jint JNICALL NM(deletePath)(JNIEnv* env, jclass, jlong job, jstring path, jobject sink) {
    clearError();
    JProgress p(env, sink);
    Status s = fsx::removeTree((int64_t) job, fromJ(env, path), p.valid() ? &p : nullptr);
    if (!s.ok()) setError(s);
    return s.code;
}

JNIEXPORT jint JNICALL NM(mkdirs)(JNIEnv* env, jclass, jstring path) {
    clearError();
    Status s = fsx::mkdirs(fromJ(env, path));
    if (!s.ok()) setError(s);
    return s.code;
}

JNIEXPORT jint JNICALL NM(createFile)(JNIEnv* env, jclass, jstring path) {
    clearError();
    Status s = fsx::createFile(fromJ(env, path));
    if (!s.ok()) setError(s);
    return s.code;
}

JNIEXPORT jint JNICALL NM(renamePath)(JNIEnv* env, jclass, jstring from, jstring to) {
    clearError();
    Status s = fsx::renameTo(fromJ(env, from), fromJ(env, to));
    if (!s.ok()) setError(s);
    return s.code;
}

JNIEXPORT jlongArray JNICALL NM(diskUsage)(JNIEnv* env, jclass, jstring path) {
    clearError();
    int64_t total = 0, freeB = 0, availB = 0;
    Status s = fsx::diskUsage(fromJ(env, path), total, freeB, availB);
    if (!s.ok()) { setError(s); return nullptr; }
    jlong vals[3] = {(jlong) total, (jlong) freeB, (jlong) availB};
    jlongArray arr = env->NewLongArray(3);
    env->SetLongArrayRegion(arr, 0, 3, vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL NM(treeStats)(JNIEnv* env, jclass, jlong job, jstring path, jobject sink) {
    clearError();
    JProgress p(env, sink);
    int64_t bytes = 0, files = 0, dirs = 0;
    Status s = fsx::treeStats((int64_t) job, fromJ(env, path), bytes, files, dirs,
                              p.valid() ? &p : nullptr);
    if (!s.ok()) { setError(s); return nullptr; }
    jlong vals[3] = {(jlong) bytes, (jlong) files, (jlong) dirs};
    jlongArray arr = env->NewLongArray(3);
    env->SetLongArrayRegion(arr, 0, 3, vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL NM(compareFiles)(JNIEnv* env, jclass, jlong job, jstring a, jstring b,
                                              jobject sink) {
    clearError();
    JProgress p(env, sink);
    int64_t firstDiff = -1;
    bool identical = false;
    Status s = fsx::compare((int64_t) job, fromJ(env, a), fromJ(env, b), firstDiff, identical,
                            p.valid() ? &p : nullptr);
    if (!s.ok()) { setError(s); return nullptr; }
    jlong vals[2] = {(jlong) firstDiff, (jlong) (identical ? 1 : 0)};
    jlongArray arr = env->NewLongArray(2);
    env->SetLongArrayRegion(arr, 0, 2, vals);
    return arr;
}

// ---------------------------------------------------------------- hash / type
JNIEXPORT jstring JNICALL NM(hashFile)(JNIEnv* env, jclass, jlong job, jstring path, jint algo,
                                       jobject sink) {
    clearError();
    JProgress p(env, sink);
    std::string hex;
    Status s = hashx::hashFile((int64_t) job, fromJ(env, path), (hashx::Algo) algo, hex,
                               p.valid() ? &p : nullptr);
    if (!s.ok()) { setError(s); return nullptr; }
    return toJ(env, hex);
}

JNIEXPORT jstring JNICALL NM(analyzeType)(JNIEnv* env, jclass, jstring path) {
    clearError();
    ftype::Info info;
    Status s = ftype::analyze(fromJ(env, path), info);
    if (!s.ok()) { setError(s); return nullptr; }
    std::string out;
    kv(out, "kind", info.kind);
    kv(out, "mime", info.mime);
    kv(out, "description", info.description);
    kv(out, "magic", info.magicHex);
    kv(out, "encoding", info.encoding);
    kv(out, "size", info.size);
    for (const std::string& t : info.tools) kv(out, "tool", t);
    return toJ(env, out);
}

// ---------------------------------------------------------------- hex / binary
JNIEXPORT jbyteArray JNICALL NM(hexRead)(JNIEnv* env, jclass, jstring path, jlong offset, jint len) {
    clearError();
    if (len <= 0) return env->NewByteArray(0);
    std::vector<uint8_t> data;
    int64_t fileSize = 0;
    Status s = hexx::readPage(fromJ(env, path), (int64_t) offset, (size_t) len, data, fileSize);
    if (!s.ok()) { setError(s); return nullptr; }
    jbyteArray arr = env->NewByteArray((jsize) data.size());
    if (!data.empty())
        env->SetByteArrayRegion(arr, 0, (jsize) data.size(), (const jbyte*) data.data());
    return arr;
}

JNIEXPORT jint JNICALL NM(hexWrite)(JNIEnv* env, jclass, jstring path, jlong offset, jbyteArray data) {
    clearError();
    if (!data) return E_RANGE;
    jsize n = env->GetArrayLength(data);
    std::vector<uint8_t> buf((size_t) n);
    env->GetByteArrayRegion(data, 0, n, (jbyte*) buf.data());
    Status s = hexx::writeAt(fromJ(env, path), (int64_t) offset, buf.data(), buf.size());
    if (!s.ok()) setError(s);
    return s.code;
}

JNIEXPORT jlong JNICALL NM(hexFind)(JNIEnv* env, jclass, jlong job, jstring path, jlong from,
                                    jbyteArray pattern, jboolean backwards) {
    clearError();
    if (!pattern) { g_lastError = "empty pattern"; return -1; }
    jsize n = env->GetArrayLength(pattern);
    std::vector<uint8_t> pat((size_t) n);
    env->GetByteArrayRegion(pattern, 0, n, (jbyte*) pat.data());
    int64_t match = -1;
    Status s = hexx::findBytes((int64_t) job, fromJ(env, path), (int64_t) from, pat.data(),
                               pat.size(), backwards == JNI_TRUE, match, nullptr);
    if (!s.ok()) { setError(s); return -1; }
    return (jlong) match;
}

JNIEXPORT jint JNICALL NM(extractStrings)(JNIEnv* env, jclass, jlong job, jstring path,
                                          jint minLen, jint maxResults, jobject sink) {
    clearError();
    JRows rows(env, sink);
    Status s = hexx::extractStrings((int64_t) job, fromJ(env, path), (size_t) minLen,
                                    (size_t) maxResults, &rows);
    if (!s.ok()) setError(s);
    return s.code;
}

// ---------------------------------------------------------------- archives
JNIEXPORT jobjectArray JNICALL NM(zipList)(JNIEnv* env, jclass, jstring path) {
    clearError();
    std::vector<zipx::ZEntry> entries;
    Status s = zipx::listEntries(fromJ(env, path), entries);
    if (!s.ok()) { setError(s); return nullptr; }

    std::vector<std::string> rows;
    rows.reserve(entries.size());
    for (const zipx::ZEntry& e : entries) {
        std::string r = e.name;
        r += SEP; r += e.isDir ? '1' : '0';
        r += SEP; r += std::to_string(e.uncompressedSize);
        r += SEP; r += std::to_string(e.compressedSize);
        r += SEP; r += std::to_string(e.mtime);
        r += SEP; r += std::to_string(e.method);
        r += SEP; r += e.encrypted ? '1' : '0';
        r += SEP; r += std::to_string(e.crc32);
        rows.push_back(std::move(r));
    }
    return toJArray(env, rows);
}

JNIEXPORT jint JNICALL NM(zipExtract)(JNIEnv* env, jclass, jlong job, jstring zip, jstring entry,
                                      jstring outDir, jobject sink) {
    clearError();
    JProgress p(env, sink);
    Status s = zipx::extract((int64_t) job, fromJ(env, zip), fromJ(env, entry), fromJ(env, outDir),
                             p.valid() ? &p : nullptr);
    if (!s.ok()) setError(s);
    return s.code;
}

JNIEXPORT jbyteArray JNICALL NM(zipRead)(JNIEnv* env, jclass, jstring zip, jstring entry, jint maxBytes) {
    clearError();
    std::vector<uint8_t> data;
    Status s = zipx::readEntry(fromJ(env, zip), fromJ(env, entry), data, (size_t) maxBytes);
    if (!s.ok()) { setError(s); return nullptr; }
    jbyteArray arr = env->NewByteArray((jsize) data.size());
    if (!data.empty())
        env->SetByteArrayRegion(arr, 0, (jsize) data.size(), (const jbyte*) data.data());
    return arr;
}

JNIEXPORT jstring JNICALL NM(zipTest)(JNIEnv* env, jclass, jlong job, jstring zip, jobject sink) {
    clearError();
    JProgress p(env, sink);
    int64_t bad = 0;
    std::string firstBad;
    Status s = zipx::testArchive((int64_t) job, fromJ(env, zip), bad, firstBad,
                                 p.valid() ? &p : nullptr);
    if (!s.ok()) { setError(s); return nullptr; }
    std::string out;
    kv(out, "badEntries", bad);
    kv(out, "firstBad", firstBad);
    return toJ(env, out);
}

// ---------------------------------------------------------------- apk / dex / axml
JNIEXPORT jstring JNICALL NM(apkInfo)(JNIEnv* env, jclass, jstring path) {
    clearError();
    apkx::Info in;
    Status s = apkx::inspect(fromJ(env, path), in);
    // Partial results are still useful, so a failure returns what was parsed plus the error.
    std::string out;
    kv(out, "ok", s.ok());
    if (!s.ok()) { kv(out, "error", s.msg); setError(s); }
    kv(out, "path", in.path);
    kv(out, "fileSize", in.fileSize);
    kv(out, "entryCount", in.entryCount);
    kv(out, "package", in.packageName);
    kv(out, "versionName", in.versionName);
    kv(out, "versionCode", in.versionCode);
    kv(out, "minSdk", in.minSdk);
    kv(out, "targetSdk", in.targetSdk);
    kv(out, "compileSdk", in.compileSdk);
    kv(out, "label", in.appLabel);
    kv(out, "icon", in.appIcon);
    kv(out, "mainActivity", in.mainActivity);
    kv(out, "split", in.splitName);
    kv(out, "installLocation", in.installLocation);
    kv(out, "debuggable", in.debuggable);
    kv(out, "extractNativeLibs", in.extractNativeLibs);
    kv(out, "cleartextTraffic", in.usesCleartextTraffic);
    kv(out, "hasArsc", in.hasResourcesArsc);
    kv(out, "hasAssets", in.hasAssets);
    kv(out, "dexCount", (int64_t) in.dexFiles.size());
    kv(out, "signingBlock", in.hasApkSigningBlock);
    kv(out, "schemeV2", in.schemeV2);
    kv(out, "schemeV3", in.schemeV3);
    kv(out, "schemeV31", in.schemeV31);
    kv(out, "schemeV1Files", in.hasV1Files);
    for (const std::string& v : in.abis) kv(out, "abi", v);
    for (const std::string& v : in.dexFiles) kv(out, "dex", v);
    for (const std::string& v : in.nativeLibs) kv(out, "lib", v);
    for (const std::string& v : in.permissions) kv(out, "permission", v);
    for (const std::string& v : in.declaredPermissions) kv(out, "definesPermission", v);
    for (const std::string& v : in.features) kv(out, "feature", v);
    for (const std::string& v : in.libraries) kv(out, "usesLibrary", v);
    for (const std::string& v : in.metaInfFiles) kv(out, "metaInf", v);
    for (const apkx::Component& c : in.components) {
        std::string row = c.kind;
        row += SEP; row += c.name;
        row += SEP; row += c.exported ? "1" : "0";
        row += SEP; row += c.enabled ? "1" : "0";
        std::string filters;
        for (const std::string& a : c.intentActions) filters += (filters.empty() ? "" : " | ") + a;
        for (const std::string& a : c.intentCategories) filters += (filters.empty() ? "" : " | ") + a;
        row += SEP; row += filters;
        kv(out, "component", row);
    }
    for (const std::string& w : in.warnings) kv(out, "warning", w);
    return toJ(env, out);
}

JNIEXPORT jstring JNICALL NM(apkManifestXml)(JNIEnv* env, jclass, jstring apk) {
    clearError();
    std::string xml;
    Status s = apkx::manifestXml(fromJ(env, apk), xml);
    if (!s.ok()) { setError(s); return nullptr; }
    return toJ(env, xml);
}

JNIEXPORT jstring JNICALL NM(axmlToXml)(JNIEnv* env, jclass, jstring path) {
    clearError();
    std::vector<uint8_t> data;
    int64_t fileSize = 0;
    Status s = hexx::readPage(fromJ(env, path), 0, 32u * 1024u * 1024u, data, fileSize);
    if (!s.ok()) { setError(s); return nullptr; }
    std::string xml;
    s = axml::toXml(data.data(), data.size(), xml);
    if (!s.ok()) { setError(s); return nullptr; }
    return toJ(env, xml);
}

JNIEXPORT jstring JNICALL NM(dexInfo)(JNIEnv* env, jclass, jstring path) {
    clearError();
    dexx::Info in;
    Status s = dexx::inspectFile(fromJ(env, path), in);
    if (!s.ok()) { setError(s); return nullptr; }
    std::string out;
    kv(out, "version", in.version);
    kv(out, "signature", in.signatureHex);
    kv(out, "checksum", (int64_t) in.checksum);
    kv(out, "headerFileSize", in.headerFileSize);
    kv(out, "actualFileSize", in.actualFileSize);
    kv(out, "strings", (int64_t) in.stringIds);
    kv(out, "types", (int64_t) in.typeIds);
    kv(out, "protos", (int64_t) in.protoIds);
    kv(out, "fields", (int64_t) in.fieldIds);
    kv(out, "methods", (int64_t) in.methodIds);
    kv(out, "classes", (int64_t) in.classDefs);
    kv(out, "valid", in.valid);
    for (const std::string& w : in.warnings) kv(out, "warning", w);
    return toJ(env, out);
}

// ---------------------------------------------------------------- elf
JNIEXPORT jstring JNICALL NM(elfInfo)(JNIEnv* env, jclass, jstring path, jint maxSymbols) {
    clearError();
    elfx::Info in;
    Status s = elfx::analyze(fromJ(env, path), in, (size_t) (maxSymbols > 0 ? maxSymbols : 2000));
    if (!s.ok()) { setError(s); return nullptr; }
    std::string out;
    kv(out, "bits", in.is64 ? 64 : 32);
    kv(out, "type", in.fileType);
    kv(out, "machine", in.machine);
    kv(out, "abi", in.abi);
    kv(out, "entry", (int64_t) in.entry);
    kv(out, "soname", in.soname);
    kv(out, "interp", in.interp);
    kv(out, "stripped", in.stripped);
    for (const std::string& n : in.needed) kv(out, "needed", n);
    for (const elfx::Section& sec : in.sections) {
        std::string row = sec.name;
        row += SEP; row += sec.type;
        row += SEP; row += std::to_string(sec.addr);
        row += SEP; row += std::to_string(sec.offset);
        row += SEP; row += std::to_string(sec.size);
        kv(out, "section", row);
    }
    for (const elfx::Symbol& sym : in.symbols) {
        std::string row = sym.name;
        row += SEP; row += sym.type;
        row += SEP; row += sym.bind;
        row += SEP; row += std::to_string(sym.value);
        row += SEP; row += std::to_string(sym.size);
        row += SEP; row += sym.undefined ? "import" : "export";
        kv(out, "symbol", row);
    }
    for (const std::string& w : in.warnings) kv(out, "warning", w);
    return toJ(env, out);
}

// ---------------------------------------------------------------- search
JNIEXPORT jint JNICALL NM(search)(JNIEnv* env, jclass, jlong job, jstring root, jstring namePattern,
                                  jstring content, jint flags, jint maxResults,
                                  jobject rowSink, jobject progressSink) {
    clearError();
    JRows rows(env, rowSink);
    JProgress p(env, progressSink);
    searchx::Options opt;
    opt.flags = (int32_t) flags;
    opt.maxResults = (size_t) (maxResults > 0 ? maxResults : 5000);
    Status s = searchx::run((int64_t) job, fromJ(env, root), fromJ(env, namePattern),
                            fromJ(env, content), opt, &rows, p.valid() ? &p : nullptr);
    if (!s.ok()) setError(s);
    return s.code;
}

} // extern "C"
