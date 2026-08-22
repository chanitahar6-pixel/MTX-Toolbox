cmake_minimum_required(VERSION 3.22.1)
project(mtxcore C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(mtxcore SHARED
        jni_bridge.cpp
        mtx/util.cpp
        mtx/fs.cpp
        mtx/hash.cpp
        mtx/filetype.cpp
        mtx/hex.cpp
        mtx/zip.cpp
        mtx/axml.cpp
        mtx/dex.cpp
        mtx/apk.cpp
        mtx/elf.cpp
        mtx/search.cpp)

target_include_directories(mtxcore PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)
# zlib ships with the NDK; used for raw inflate of zip/apk entries.
target_link_libraries(mtxcore ${log-lib} z)

target_compile_options(mtxcore PRIVATE -Wall -Wextra -Wno-unused-parameter)
