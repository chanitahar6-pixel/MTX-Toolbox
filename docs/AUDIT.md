# Build audit

Every file in the project was reviewed three times, specifically for issues that
break the build rather than style problems. This is the complete list of what was
found and changed, in the order the passes were run.

## Pass 1 - build system

| # | Problem | Effect | Fix |
|---|---|---|---|
| 1 | `gradle.properties` set `android.defaults.buildfeatures.buildconfig` | **Hard failure.** Removed in AGP 8.0, Gradle aborts configuration. | option removed |
| 2 | Native build used CMake | requested change to ndk-build | `CMakeLists.txt` deleted, replaced by `app/src/main/cpp/Android.mk` + `Application.mk`, Gradle switched to `externalNativeBuild { ndkBuild { ... } }` |
| 3 | `ndkVersion` pinned to one exact NDK | build fails on any machine without that build | pin removed |
| 4 | `packagingOptions { jniLibs { useLegacyPackaging false } }` | Groovy could not resolve the setter in the nested DSL | renamed to `packaging { }` with explicit `=` assignments |
| 5 | `lint { abortOnError false }`, `vectorDrawables.useSupportLibrary true` | same missing-assignment issue | `=` added |
| 6 | `settings.gradle` declared `flatDir { dirs 'tools' }` for JARs that do not exist yet | pointless repository resolved on every build | removed until the Phase 2 JARs land |
| 7 | No Gradle wrapper metadata | `./gradlew` in the README could not work | `gradle/wrapper/gradle-wrapper.properties` added |
| 8 | `androidx.core` used directly (FileProvider) but only present transitively | breaks when appcompat changes its dependency tree | pinned explicitly |
| 9 | `androidx.documentfile` declared but never used | dead dependency | removed |

## Pass 2 - native code (C++)

| # | Problem | Effect | Fix |
|---|---|---|---|
| 10 | `kv()` overloaded for `int64_t` and `bool`, called with plain `int` (`minSdk`, `targetSdk`, `compileSdk`, ELF `bits`) | **Hard failure.** Both conversions rank equally, the call is ambiguous. | replaced with `kvText`, `kvNum`, `kvBool` |
| 11 | `hash.cpp` used `struct stat` / `fstat` without `<sys/stat.h>` | **Hard failure**, incomplete type | platform headers centralised in `mtx/common.h` |
| 12 | `hash.cpp`, `hex.cpp`, `zip.cpp`, `search.cpp` used `errno`/`EINTR` without `<cerrno>` | fails depending on NDK header layout | same fix |
| 13 | `apk.cpp` used `strtol` without `<cstdlib>` | same | same fix |
| 14 | `-Wsign-compare` noise (`raws[i].link` vs `size_t`) | hides real warnings | `-Wno-sign-compare` in `Android.mk` |
| 15 | JNI row builders duplicated across exports | drift between `listDir` and `statPath` | single `entryRow()` helper |
| 16 | Progress/row callbacks invoked even when the method lookup failed | silent no-op calls into the JVM | `valid()` checked at every call site, pending JNI exceptions cleared |
| 17 | `zipRead` / `hexFind` / `hexWrite` accepted non-positive sizes | out-of-range native reads | guarded with clear errors |
| 18 | **AXML attribute offset wrong**: `attributeStart` was added to the chunk start | attributes were read 16 bytes early, so manifest values were garbage | corrected to chunk + 16 (`ResXMLTree_attrExt` base), found while porting the decoder to Java |

## Pass 3 - resources and Java

| # | Problem | Effect | Fix |
|---|---|---|---|
| 19 | Styles `Mtx.Title`, `Mtx.Body`, `Mtx.Meta`, `Mtx.Mono` had no `parent` | **AAPT2 failure.** A dotted style name implies a parent style `Mtx`, which does not exist. | explicit `parent=""` on all four |
| 20 | `Mtx.Meta` forced `singleLine` | two-line list rows were clipped | removed from the style, layouts decide |
| 21 | `META-INF/DEPENDENCIES` not excluded | duplicate-file failures once JVM tool JARs are bundled in Phase 2 | added to `packaging.resources.excludes` |

## Pass 4 - on-device buildability (AndroidIDE)

| # | Problem | Effect | Fix |
|---|---|---|---|
| 22 | The project always required an NDK | **AndroidIDE cannot build it at all**: there is no C++ toolchain on the phone | native build made opt-in via `mtx.nativeBuild`, default `false` |
| 23 | All engines lived only in C++ | with the NDK off, the app would have had no engines and every tool would be dead | full **pure-Java engine set** added (`JavaEngine`, `JavaZip`, `JavaAxml`, `JavaApk`, `JavaDex`, `JavaElf`, `JavaFileType`, `JavaSearch`) producing byte-identical payloads |
| 24 | `Native` mixed `native` declarations with the public API, so a fallback was impossible | a `native` method cannot have a body | split into `NativeLib` (JNI) and `Native` (dispatcher); JNI exports renamed to `Java_app_mtx_toolbox_core_NativeLib_*` |
| 25 | AGP 8.5.2 / Gradle 8.7 | not accepted by AndroidIDE | AGP 8.1.4 / Gradle 8.4, Java 11 source level |
| 26 | `minifyEnabled true` in release | R8 on-device is slow and fragile | off by default, ProGuard rules kept for anyone re-enabling it |
| 27 | `Native.isAvailable()` meant "native library loaded", and the UI showed a fatal "engine missing" banner | Java-only builds would look broken | `isAvailable()` now means "an engine is usable" (always true); `isNativeCore()` reports which one, shown in Settings |
| 28 | No automated check that either variant compiles | regressions land unnoticed | `.github/workflows/build.yml` builds the Java-only and the native APK on every push |

## Verified and deliberately left alone

* All 15 activities and the service declared in `AndroidManifest.xml` exist, and every
  activity they navigate to exists.
* Every `R.string`, `R.color`, `R.drawable`, `R.layout` and `R.id` reference in Java
  resolves, and `values/` and `values-ar/` contain the same key set.
* Every method in `NativeLib` has a matching `Java_app_mtx_toolbox_core_NativeLib_*`
  export with a matching signature, and a matching Java fallback in `Native`.
* API-gated calls (`isExternalStorageManager`, `getLongVersionCode`,
  `startForegroundService`, adaptive icons) are all version-guarded.
* No `.kt` or `.kts` file exists anywhere in the project.
