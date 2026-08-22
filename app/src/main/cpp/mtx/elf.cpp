// ELF / .so analyzer implemented directly on top of mmap. Bounds-checked, so a
// truncated or hostile binary yields E_CORRUPT rather than a segfault.
#include "mtx/elf.h"

#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <cstring>

namespace mtx { namespace elfx {
namespace {

const char* machineName(uint16_t m) {
    switch (m) {
        case 3:   return "Intel 80386";
        case 8:   return "MIPS";
        case 40:  return "ARM";
        case 62:  return "AMD x86-64";
        case 183: return "AArch64";
        case 243: return "RISC-V";
        default:  return "unknown";
    }
}

const char* abiName(uint16_t m, bool is64) {
    switch (m) {
        case 40:  return "armeabi-v7a";
        case 183: return "arm64-v8a";
        case 3:   return "x86";
        case 62:  return "x86_64";
        case 8:   return is64 ? "mips64" : "mips";
        default:  return "unknown";
    }
}

const char* sectionType(uint32_t t) {
    switch (t) {
        case 0:  return "NULL";
        case 1:  return "PROGBITS";
        case 2:  return "SYMTAB";
        case 3:  return "STRTAB";
        case 4:  return "RELA";
        case 5:  return "HASH";
        case 6:  return "DYNAMIC";
        case 7:  return "NOTE";
        case 8:  return "NOBITS";
        case 9:  return "REL";
        case 11: return "DYNSYM";
        case 14: return "INIT_ARRAY";
        case 15: return "FINI_ARRAY";
        case 0x6ffffff6: return "GNU_HASH";
        case 0x6fffffff: return "VERNEED";
        default: return "OTHER";
    }
}

const char* symType(uint8_t info) {
    switch (info & 0xF) {
        case 0: return "NOTYPE";
        case 1: return "OBJECT";
        case 2: return "FUNC";
        case 3: return "SECTION";
        case 4: return "FILE";
        case 6: return "TLS";
        default: return "OTHER";
    }
}

const char* symBind(uint8_t info) {
    switch (info >> 4) {
        case 0: return "LOCAL";
        case 1: return "GLOBAL";
        case 2: return "WEAK";
        default: return "OTHER";
    }
}

std::string strAt(const uint8_t* d, size_t size, size_t base, uint32_t off) {
    size_t p = base + off;
    if (p >= size) return "";
    size_t end = p;
    while (end < size && d[end] != 0) end++;
    return std::string((const char*) d + p, end - p);
}

} // namespace

Status analyze(const std::string& path, Info& out, size_t maxSymbols) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return fromErrno("open", path);
    struct stat st{};
    if (fstat(fd, &st) != 0) { Status s = fromErrno("fstat", path); close(fd); return s; }
    size_t size = (size_t) st.st_size;
    if (size < 64) { close(fd); return Status::err(E_CORRUPT, "file too small to be an ELF"); }

    void* map = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return fromErrno("mmap", path);
    const uint8_t* d = (const uint8_t*) map;

    auto finish = [&](Status s) { munmap(map, size); return s; };

    if (memcmp(d, "\x7f" "ELF", 4) != 0)
        return finish(Status::err(E_CORRUPT, "missing ELF magic"));

    out.is64 = d[4] == 2;
    out.littleEndian = d[5] == 1;
    if (!out.littleEndian)
        return finish(Status::err(E_UNSUPPORTED, "big-endian ELF files are not supported"));

    uint16_t eType = 0, eMachine = 0, shEntSize = 0, shNum = 0, shStrNdx = 0;
    uint64_t shOff = 0;
    rdU16(d, size, 16, eType);
    rdU16(d, size, 18, eMachine);

    if (out.is64) {
        uint64_t entry = 0;
        rdU64(d, size, 24, entry);
        out.entry = entry;
        rdU64(d, size, 40, shOff);
        rdU16(d, size, 58, shEntSize);
        rdU16(d, size, 60, shNum);
        rdU16(d, size, 62, shStrNdx);
    } else {
        uint32_t entry = 0, off32 = 0;
        rdU32(d, size, 24, entry);
        out.entry = entry;
        rdU32(d, size, 32, off32);
        shOff = off32;
        rdU16(d, size, 46, shEntSize);
        rdU16(d, size, 48, shNum);
        rdU16(d, size, 50, shStrNdx);
    }

    switch (eType) {
        case 1: out.fileType = "REL (relocatable)"; break;
        case 2: out.fileType = "EXEC (executable)"; break;
        case 3: out.fileType = "DYN (shared object / PIE)"; break;
        case 4: out.fileType = "CORE"; break;
        default: out.fileType = "unknown"; break;
    }
    out.machine = machineName(eMachine);
    out.abi = abiName(eMachine, out.is64);

    if (shOff == 0 || shNum == 0) {
        out.warnings.push_back("no section headers (fully stripped or packed binary)");
        return finish(Status::good());
    }
    if (shOff + (uint64_t) shNum * shEntSize > size)
        return finish(Status::err(E_CORRUPT, "section header table extends past end of file"));

    // Section name string table.
    size_t shStrBase = 0;
    if (shStrNdx < shNum) {
        size_t sh = (size_t) shOff + (size_t) shStrNdx * shEntSize;
        if (out.is64) { uint64_t o = 0; rdU64(d, size, sh + 24, o); shStrBase = (size_t) o; }
        else          { uint32_t o = 0; rdU32(d, size, sh + 16, o); shStrBase = o; }
    }

    struct Raw { uint32_t nameOff, type, link, entsize32; uint64_t addr, off, sz, flags, entsize; };
    std::vector<Raw> raws;
    raws.reserve(shNum);

    for (uint16_t i = 0; i < shNum; i++) {
        size_t sh = (size_t) shOff + (size_t) i * shEntSize;
        Raw r{};
        rdU32(d, size, sh, r.nameOff);
        rdU32(d, size, sh + 4, r.type);
        if (out.is64) {
            rdU64(d, size, sh + 8, r.flags);
            rdU64(d, size, sh + 16, r.addr);
            rdU64(d, size, sh + 24, r.off);
            rdU64(d, size, sh + 32, r.sz);
            rdU32(d, size, sh + 40, r.link);
            rdU64(d, size, sh + 56, r.entsize);
        } else {
            uint32_t f = 0, a = 0, o = 0, s2 = 0, es = 0;
            rdU32(d, size, sh + 8, f);
            rdU32(d, size, sh + 12, a);
            rdU32(d, size, sh + 16, o);
            rdU32(d, size, sh + 20, s2);
            rdU32(d, size, sh + 24, r.link);
            rdU32(d, size, sh + 36, es);
            r.flags = f; r.addr = a; r.off = o; r.sz = s2; r.entsize = es;
        }
        raws.push_back(r);

        Section sec;
        sec.name = strAt(d, size, shStrBase, r.nameOff);
        sec.type = sectionType(r.type);
        sec.addr = r.addr;
        sec.offset = r.off;
        sec.size = r.sz;
        sec.flags = r.flags;
        if (sec.name == ".symtab") out.stripped = false;
        out.sections.push_back(std::move(sec));
    }

    // .interp
    for (size_t i = 0; i < out.sections.size(); i++) {
        if (out.sections[i].name == ".interp" && raws[i].off < size)
            out.interp = strAt(d, size, (size_t) raws[i].off, 0);
    }

    // DYNAMIC: DT_NEEDED (1) and DT_SONAME (14) resolved through the linked strtab.
    for (size_t i = 0; i < raws.size(); i++) {
        if (raws[i].type != 6) continue;
        size_t strBase = 0;
        if (raws[i].link < raws.size()) strBase = (size_t) raws[raws[i].link].off;
        size_t entSize = out.is64 ? 16 : 8;
        size_t count = raws[i].sz / entSize;
        for (size_t k = 0; k < count; k++) {
            size_t p = (size_t) raws[i].off + k * entSize;
            uint64_t tag = 0, val = 0;
            if (out.is64) { rdU64(d, size, p, tag); rdU64(d, size, p + 8, val); }
            else { uint32_t t = 0, v = 0; rdU32(d, size, p, t); rdU32(d, size, p + 4, v); tag = t; val = v; }
            if (tag == 0) break;
            if (tag == 1) out.needed.push_back(strAt(d, size, strBase, (uint32_t) val));
            else if (tag == 14) out.soname = strAt(d, size, strBase, (uint32_t) val);
        }
    }

    // Symbols from .dynsym first (imports/exports), then .symtab when present.
    for (size_t i = 0; i < raws.size() && out.symbols.size() < maxSymbols; i++) {
        if (raws[i].type != 11 && raws[i].type != 2) continue;
        size_t strBase = 0;
        if (raws[i].link < raws.size()) strBase = (size_t) raws[raws[i].link].off;
        size_t entSize = out.is64 ? 24 : 16;
        if (raws[i].entsize > 0) entSize = (size_t) raws[i].entsize;
        size_t count = entSize ? raws[i].sz / entSize : 0;

        for (size_t k = 0; k < count && out.symbols.size() < maxSymbols; k++) {
            size_t p = (size_t) raws[i].off + k * entSize;
            uint32_t nameOff = 0;
            uint8_t info = 0;
            uint16_t shndx = 0;
            uint64_t value = 0, sz = 0;
            if (out.is64) {
                rdU32(d, size, p, nameOff);
                if (p + 4 < size) info = d[p + 4];
                rdU16(d, size, p + 6, shndx);
                rdU64(d, size, p + 8, value);
                rdU64(d, size, p + 16, sz);
            } else {
                uint32_t v = 0, s2 = 0;
                rdU32(d, size, p, nameOff);
                rdU32(d, size, p + 4, v);
                rdU32(d, size, p + 8, s2);
                if (p + 12 < size) info = d[p + 12];
                rdU16(d, size, p + 14, shndx);
                value = v; sz = s2;
            }
            Symbol sym;
            sym.name = strAt(d, size, strBase, nameOff);
            if (sym.name.empty()) continue;
            sym.value = value;
            sym.size = sz;
            sym.type = symType(info);
            sym.bind = symBind(info);
            sym.undefined = (shndx == 0);
            out.symbols.push_back(std::move(sym));
        }
    }
    if (out.symbols.size() >= maxSymbols)
        out.warnings.push_back("symbol list truncated at " + std::to_string(maxSymbols));

    return finish(Status::good());
}

}} // namespace mtx::elfx
