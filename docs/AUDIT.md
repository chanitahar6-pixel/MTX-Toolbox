# Build audit

Every file in the project was reviewed three times, specifically for issues that
break the build rather than style problems. This is the complete list of what was
found and changed.

## Pass 1 — build system

| # | Problem | Effect | Fix |
|---|---|---|---|
| 1 | `gradle.properties` set `android.defaults.buildfeatures.buildconfig` | **Hard failure.** The option was removed in AGP 8.0 and Gradle aborts configuration. | option removed |
| 2 | Native build used CMake | requested change to ndk-build | `CMakeLists.txt` deleted, replaced by `app/src/main/cpp/Android.mk` + `Application.mk`, Gradle switched to `externalNativeBuild { ndkBuild { ... } }` |
| 3 | `ndkVersion '26.1.10909125'` pinned | build fails on any machine without that exact NDK | pin removed, any recent NDK works |
| 4 | `packagingOptions { jniLibs { useLegacyPackaging false } }` | Groovy could not resolve the property setter in the new nested DSL | renamed to `packaging { }` with explicit `=` assignments |
| 5 | `lint { abortOnError false }` / `vectorDrawables.useSupportLibrary true` | same missing-assignment issue | `=` added |
| 6 | `settings.gradle` declared `flatDir { dirs 'tools' }` for JARs that do not exist yet | pointless repository resolved on every build | removed until the Phase 2 JARs land |
| 7 | No Gradle wrapper metadata | `./gradlew` in the README could not work | `gradle/wrapper/gradle-wrapper.properties` added (Gradle 8.7) and the real procedure documented in `docs/BUILD.md` |
| 8 | `androidx.core` used directly (FileProvider) but only present transitively | breaks the moment appcompat changes its dependency tree | `androidx.core:core:1.12.0` pinned |
| 9 | `androidx.documentfile` declared but never used | dead dependency | removed |

## Pass 2 — native code (C++)

| # | Problem | Effect | Fix |
|---|---|---|---|
| 10 | `kv()` was overloaded for `int64_t` and `bool`, and called with plain `int` (`minSdk`, `targetSdk`, `compileSdk`, ELF `bits`) | **Hard failure.** Both conversions rank equally, so the call is ambiguous. | overloads replaced with three explicitly named helpers: `kvText`, `kvNum`, `kvBool` |
| 11 | `hash.cpp` used `struct stat` and `fstat` without `<sys/stat.h>` | **Hard failure**, incomplete type | platform headers centralised in `mtx/common.h`, which every engine already includes |
| 12 | `hash.cpp`, `hex.cpp`, `zip.cpp`, `search.cpp` used `errno`/`EINTR` without `<cerrno>` | fails depending on the NDK header layout | same fix as above |
| 13 | `apk.cpp` used `strtol` without `<cstdlib>` | same | same fix as above |
| 14 | `-Wsign-compare` on `raws[i].link` (uint32 vs `size_t`) and similar | noise that hides real warnings | `-Wno-sign-compare` in `Android.mk` |
| 15 | JNI row builders duplicated in several exports | drift risk between `listDir` and `statPath` | single `entryRow()` helper |
| 16 | Progress and row callbacks were attached even when the method lookup failed | silent no-op calls into the JVM | `JProgress::valid()` / `JRows::valid()` checked at every call site, pending JNI exceptions cleared |
| 17 | `zipRead` / `hexFind` accepted non-positive sizes | out-of-range native reads | guarded, they now return a clear error |

## Pass 3 — resources and Java

| # | Problem | Effect | Fix |
|---|---|---|---|
| 18 | Styles named `Mtx.Title`, `Mtx.Body`, `Mtx.Meta`, `Mtx.Mono` had no `parent` | **AAPT2 failure.** A dotted style name implies a parent style called `Mtx`, which does not exist. | explicit `parent=""` on all four |
| 19 | `Mtx.Meta` forced `singleLine` | two-line list rows were clipped | removed from the style, layouts decide |
| 20 | `META-INF/DEPENDENCIES` not excluded from packaging | duplicate-file failures once JVM tool JARs are bundled in Phase 2 | added to `packaging.resources.excludes` |

## Verified and deliberately left alone

* All 15 activities and the service declared in `AndroidManifest.xml` exist, and every
  activity they navigate to exists.
* Every `R.string`, `R.color`, `R.drawable`, `R.layout` and `R.id` reference in Java
  resolves, and `values/` and `values-ar/` contain the same key set.
* Every `native` method in `app.mtx.toolbox.core.Native` has a matching
  `Java_app_mtx_toolbox_core_Native_*` export with a matching signature.
* API-gated calls (`isExternalStorageManager`, `getLongVersionCode`,
  `startForegroundService`, adaptive icons) are all version-guarded.
* No `.kt` or `.kts` file exists anywhere in the project.
