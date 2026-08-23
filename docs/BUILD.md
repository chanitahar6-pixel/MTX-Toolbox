# Building MTX Toolbox

## Requirements

| Tool | Version |
|---|---|
| JDK | **17** (AGP 8.5 refuses anything older) |
| Android Gradle Plugin | 8.5.2 |
| Gradle | 8.7 |
| Android SDK | compileSdk 34, minSdk 21 |
| NDK | r25 or newer (any recent NDK works, the native build is **ndk-build**) |

## Native build: ndk-build, not CMake

The C++ core is built with **`Android.mk`** + **`Application.mk`**:

```
app/src/main/cpp/Android.mk        module definition (libmtxcore.so)
app/src/main/cpp/Application.mk    STL, ABIs, platform level
```

Gradle is wired to it in `app/build.gradle`:

```groovy
externalNativeBuild {
    ndkBuild {
        path 'src/main/cpp/Android.mk'
    }
}
```

There is no `CMakeLists.txt` in this project. Adding one back would make Gradle
try to configure two native build systems at once, which fails.

To build the library on its own, without Gradle:

```bash
cd app/src/main
$ANDROID_NDK_HOME/ndk-build NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=cpp/Android.mk NDK_APPLICATION_MK=cpp/Application.mk
```

## First build

The Gradle wrapper JAR is not committed (binary files are kept out of the repo),
so generate it once, then build normally:

```bash
gradle wrapper            # only needed once; or just open the project in Android Studio
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Android Studio users can skip the wrapper step entirely: open the project folder
and let Studio sync, it will fetch Gradle 8.7 and prompt for the NDK if missing.

## Release build

```bash
./gradlew :app:assembleRelease
```

R8 is enabled for release. `app/proguard-rules.pro` keeps the JNI entry points
(`app.mtx.toolbox.core.Native`) and the callback interfaces reachable from native
code; do not remove those rules or the engines will fail at runtime.

## Troubleshooting

| Symptom | Cause |
|---|---|
| `NDK not configured` | install any NDK from the SDK Manager; no version is pinned on purpose |
| `Unsupported class file major version` | you are on JDK 21+ or 11; use JDK 17 |
| `ndk-build: command not found` | only relevant for the standalone command above; Gradle finds it itself |
| `libmtxcore.so not found` at runtime | the ABI you are running was excluded; see `abiFilters` |
