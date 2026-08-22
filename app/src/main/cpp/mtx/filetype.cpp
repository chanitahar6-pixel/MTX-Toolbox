#include "mtx/filetype.h"
#include "mtx/zip.h"

#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <cstring>

namespace mtx { namespace ftype {
namespace {

bool starts(const uint8_t* d, size_t n, const char* sig, size_t len) {
    return n >= len && memcmp(d, sig, len) == 0;
}

// Heuristic text/encoding sniffing over the first block only.
std::string sniffEncoding(const uint8_t* d, size_t n, bool& textLike) {
    textLike = false;
    if (n == 0) { textLike = true; return "empty"; }
    if (n >= 3 && d[0] == 0xEF && d[1] == 0xBB && d[2] == 0xBF) { textLike = true; return "utf-8-bom"; }
    if (n >= 2 && d[0] == 0xFF && d[1] == 0xFE) { textLike = true; return "utf-16le"; }
    if (n >= 2 && d[0] == 0xFE && d[1] == 0xFF) { textLike = true; return "utf-16be"; }

    size_t nul = 0, ctrl = 0, i = 0;
    bool validUtf8 = true;
    while (i < n) {
        uint8_t c = d[i];
        if (c == 0) nul++;
        if (c < 0x09 || (c > 0x0D && c < 0x20)) ctrl++;
        size_t extra = 0;
        if (c >= 0xC2 && c <= 0xDF) extra = 1;
        else if (c >= 0xE0 && c <= 0xEF) extra = 2;
        else if (c >= 0xF0 && c <= 0xF4) extra = 3;
        else if (c >= 0x80) { validUtf8 = false; }
        for (size_t k = 1; k <= extra; k++) {
            if (i + k >= n) break;                       // truncated at block edge: ignore
            if ((d[i + k] & 0xC0) != 0x80) { validUtf8 = false; break; }
        }
        i += extra + 1;
    }
    if (nul == 0 && ctrl * 100 < n * 5) {
        textLike = true;
        return validUtf8 ? "utf-8" : "ascii/8-bit";
    }
    return "binary";
}

void textSubtype(const uint8_t* d, size_t n, Info& out) {
    size_t i = 0;
    while (i < n && (d[i] == ' ' || d[i] == '\n' || d[i] == '\r' || d[i] == '\t' || d[i] == 0xEF ||
                     d[i] == 0xBB || d[i] == 0xBF)) i++;
    std::string ext = out.kind;   // holds extension at this point
    if (i < n && (d[i] == '{' || d[i] == '[')) {
        out.kind = "json"; out.mime = "application/json"; out.description = "JSON document";
        out.tools = {"json", "text", "hex"};
        return;
    }
    if (i < n && d[i] == '<') {
        out.kind = "xml"; out.mime = "text/xml"; out.description = "XML document";
        out.tools = {"xml", "text", "hex"};
        return;
    }
    if (ext == "smali") {
        out.kind = "smali"; out.mime = "text/x-smali"; out.description = "Smali source";
        out.tools = {"smali", "text", "hex"};
        return;
    }
    out.kind = "text";
    out.mime = "text/plain";
    out.description = "Text file";
    out.tools = {"text", "hex"};
}

} // namespace

Status analyze(const std::string& path, Info& out) {
    struct stat st{};
    if (stat(path.c_str(), &st) != 0) return fromErrno("stat", path);
    out.size = (int64_t) st.st_size;

    if (S_ISDIR(st.st_mode)) {
        out.kind = "folder";
        out.mime = "inode/directory";
        out.description = "Folder";
        out.encoding = "";
        out.tools = {"open"};
        return Status::good();
    }

    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return fromErrno("open", path);
    uint8_t head[4096];
    ssize_t n = read(fd, head, sizeof(head));
    close(fd);
    if (n < 0) return fromErrno("read", path);
    size_t hn = (size_t) n;

    out.magicHex = toHex(head, hn < 16 ? hn : 16);
    std::string ext = extOf(path);

    // ---- binary signatures first -----------------------------------------
    if (starts(head, hn, "PK\x03\x04", 4) || starts(head, hn, "PK\x05\x06", 4) ||
        starts(head, hn, "PK\x07\x08", 4)) {
        bool apk = zipx::looksLikeApk(path);
        if (apk) {
            out.kind = "apk";
            out.mime = "application/vnd.android.package-archive";
            out.description = "Android package (APK)";
            out.tools = {"apk", "archive", "hex"};
        } else if (ext == "jar") {
            out.kind = "jar"; out.mime = "application/java-archive";
            out.description = "Java archive"; out.tools = {"archive", "hex"};
        } else if (ext == "apks" || ext == "apkm" || ext == "xapk") {
            out.kind = "apk-bundle"; out.mime = "application/octet-stream";
            out.description = "Split APK bundle (" + ext + ")";
            out.tools = {"archive", "apk", "hex"};
        } else if (ext == "aab") {
            out.kind = "aab"; out.mime = "application/octet-stream";
            out.description = "Android App Bundle"; out.tools = {"archive", "hex"};
        } else {
            out.kind = "zip"; out.mime = "application/zip";
            out.description = "ZIP archive"; out.tools = {"archive", "hex"};
        }
        out.encoding = "binary";
        return Status::good();
    }
    if (starts(head, hn, "dex\n", 4)) {
        out.kind = "dex"; out.mime = "application/x-dex";
        out.description = "Dalvik executable";
        out.encoding = "binary";
        out.tools = {"dex", "hex"};
        return Status::good();
    }
    if (starts(head, hn, "\x7f" "ELF", 4)) {
        out.kind = "elf"; out.mime = "application/x-sharedlib";
        out.description = "ELF binary / shared object";
        out.encoding = "binary";
        out.tools = {"elf", "binary", "hex"};
        return Status::good();
    }
    if (hn >= 4 && head[0] == 0x03 && head[1] == 0x00 && head[2] == 0x08 && head[3] == 0x00) {
        out.kind = "axml"; out.mime = "application/octet-stream";
        out.description = "Android binary XML";
        out.encoding = "binary";
        out.tools = {"axml", "hex"};
        return Status::good();
    }
    if (hn >= 4 && head[0] == 0x02 && head[1] == 0x00 && head[2] == 0x0C && head[3] == 0x00) {
        out.kind = "arsc"; out.mime = "application/octet-stream";
        out.description = "Android resource table (resources.arsc)";
        out.encoding = "binary";
        out.tools = {"hex"};
        return Status::good();
    }
    struct Sig { const char* sig; size_t len; const char* kind; const char* mime; const char* desc; };
    static const Sig sigs[] = {
        {"\x89PNG\r\n\x1a\n", 8, "png",  "image/png",  "PNG image"},
        {"\xff\xd8\xff",      3, "jpeg", "image/jpeg", "JPEG image"},
        {"GIF8",              4, "gif",  "image/gif",  "GIF image"},
        {"RIFF",              4, "riff", "application/octet-stream", "RIFF container (WAV/WEBP/AVI)"},
        {"%PDF",              4, "pdf",  "application/pdf", "PDF document"},
        {"SQLite format 3",  14, "sqlite", "application/vnd.sqlite3", "SQLite database"},
        {"7z\xbc\xaf\x27\x1c", 6, "7z", "application/x-7z-compressed", "7-Zip archive"},
        {"\x1f\x8b",          2, "gz",   "application/gzip", "GZIP stream"},
        {"BZh",               3, "bz2",  "application/x-bzip2", "BZIP2 archive"},
        {"\xfd" "7zXZ",       6, "xz",   "application/x-xz", "XZ archive"},
        {"\x28\xb5\x2f\xfd",  4, "zstd", "application/zstd", "Zstandard stream"},
        {"Rar!",              4, "rar",  "application/vnd.rar", "RAR archive"},
        {"OggS",              4, "ogg",  "audio/ogg", "OGG media"},
        {"ID3",               3, "mp3",  "audio/mpeg", "MP3 audio"},
    };
    for (const Sig& s : sigs) {
        if (starts(head, hn, s.sig, s.len)) {
            out.kind = s.kind; out.mime = s.mime; out.description = s.desc;
            out.encoding = "binary";
            if (out.kind == "png" || out.kind == "jpeg" || out.kind == "gif")
                out.tools = {"image", "hex"};
            else if (out.kind == "sqlite") out.tools = {"sqlite", "hex"};
            else if (out.kind == "7z" || out.kind == "gz" || out.kind == "bz2" ||
                     out.kind == "xz" || out.kind == "zstd" || out.kind == "rar")
                out.tools = {"archive", "hex"};
            else out.tools = {"hex", "binary"};
            return Status::good();
        }
    }
    // ftyp box at offset 4 -> ISO base media (mp4/3gp)
    if (hn >= 12 && memcmp(head + 4, "ftyp", 4) == 0) {
        out.kind = "mp4"; out.mime = "video/mp4"; out.description = "ISO media (MP4/3GP)";
        out.encoding = "binary"; out.tools = {"media", "hex"};
        return Status::good();
    }

    bool textLike = false;
    out.encoding = sniffEncoding(head, hn, textLike);
    if (textLike) {
        out.kind = ext;             // temporarily carry the extension
        textSubtype(head, hn, out);
        return Status::good();
    }

    out.kind = "binary";
    out.mime = "application/octet-stream";
    out.description = "Binary data";
    out.tools = {"hex", "binary"};
    return Status::good();
}

}} // namespace mtx::ftype
