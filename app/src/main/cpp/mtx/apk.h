#pragma once
#include "mtx/common.h"

namespace mtx { namespace apkx {

struct Component {
    std::string kind;      // activity | service | receiver | provider
    std::string name;
    bool exported = false;
    bool enabled = true;
    std::vector<std::string> intentActions;
    std::vector<std::string> intentCategories;
};

struct Info {
    std::string path;
    int64_t fileSize = 0;
    int64_t entryCount = 0;

    std::string packageName;
    std::string versionName;
    int64_t versionCode = -1;
    int minSdk = -1, targetSdk = -1, compileSdk = -1;
    std::string appLabel, appIcon, mainActivity, splitName, installLocation;
    bool debuggable = false;
    bool extractNativeLibs = true;
    bool usesCleartextTraffic = false;

    std::vector<std::string> permissions;
    std::vector<std::string> declaredPermissions;
    std::vector<std::string> features;
    std::vector<std::string> libraries;      // uses-library
    std::vector<Component> components;

    std::vector<std::string> dexFiles;
    std::vector<std::string> nativeLibs;     // lib/<abi>/<name>.so
    std::vector<std::string> abis;
    bool hasResourcesArsc = false;
    bool hasAssets = false;

    // Signature facts. A rebuilt APK is never assumed to keep its old signature.
    bool hasApkSigningBlock = false;
    bool schemeV2 = false, schemeV3 = false, schemeV31 = false;
    bool hasV1Files = false;                 // META-INF/*.RSA|DSA|EC + MANIFEST.MF
    std::vector<std::string> metaInfFiles;

    std::vector<std::string> warnings;
};

Status inspect(const std::string& apkPath, Info& out);

// Decoded AndroidManifest.xml as readable XML text.
Status manifestXml(const std::string& apkPath, std::string& xmlOut);

}} // namespace mtx::apkx
