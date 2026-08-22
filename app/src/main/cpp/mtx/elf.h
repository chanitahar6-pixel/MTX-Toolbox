#pragma once
#include "mtx/common.h"

namespace mtx { namespace elfx {

struct Section {
    std::string name;
    std::string type;
    uint64_t addr = 0, offset = 0, size = 0, flags = 0;
};

struct Symbol {
    std::string name;
    uint64_t value = 0, size = 0;
    std::string type;      // FUNC / OBJECT / NOTYPE ...
    std::string bind;      // GLOBAL / LOCAL / WEAK
    bool undefined = false;   // true => imported
};

struct Info {
    bool is64 = false;
    bool littleEndian = true;
    std::string fileType;   // EXEC / DYN (shared object) / REL / CORE
    std::string machine;
    std::string abi;        // arm64-v8a / armeabi-v7a / x86 / x86_64
    std::string interp;
    std::string soname;
    uint64_t entry = 0;
    bool stripped = true;
    std::vector<std::string> needed;
    std::vector<Section> sections;
    std::vector<Symbol> symbols;
    std::vector<std::string> warnings;
};

Status analyze(const std::string& path, Info& out, size_t maxSymbols);

}} // namespace mtx::elfx
