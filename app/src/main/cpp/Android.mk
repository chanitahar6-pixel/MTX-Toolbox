# MTX Toolbox native core - ndk-build (Android.mk) build script.
# Builds libmtxcore.so: all file, archive, APK, DEX, ELF, hash, hex and search
# engines. C++17, no Kotlin anywhere in this project.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := mtxcore

LOCAL_SRC_FILES := \
    jni_bridge.cpp \
    mtx/util.cpp \
    mtx/fs.cpp \
    mtx/hash.cpp \
    mtx/filetype.cpp \
    mtx/hex.cpp \
    mtx/zip.cpp \
    mtx/axml.cpp \
    mtx/dex.cpp \
    mtx/apk.cpp \
    mtx/elf.cpp \
    mtx/search.cpp

# Headers are included as "mtx/<name>.h", so the module root is the include root.
LOCAL_C_INCLUDES := $(LOCAL_PATH)
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)

LOCAL_CPP_FEATURES := exceptions rtti

LOCAL_CPPFLAGS := \
    -std=c++17 \
    -O2 \
    -fvisibility=hidden \
    -Wall \
    -Wextra \
    -Wno-unused-parameter \
    -Wno-sign-compare

# log for MtxLog output, z for the raw inflate used by the ZIP/APK engine.
LOCAL_LDLIBS := -llog -lz

include $(BUILD_SHARED_LIBRARY)
