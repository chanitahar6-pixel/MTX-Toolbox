# Building MTX Toolbox

Two supported paths:

* **On the phone, in AndroidIDE** (no NDK): see [BUILD-ANDROIDIDE.md](BUILD-ANDROIDIDE.md).
  This is the default configuration of the repository.
* **On a desktop or in CI**, with the C++ core compiled: described below.

## Requirements

| Tool | Version |
|---|---|
| JDK | **17** |
| Android Gradle Plugin | 8.1.4 |
| Gradle | 8.4 |
| Android SDK | compileSdk 34, minSdk 21 |
| NDK | any recent release, **only** for `-Pmtx.nativeBuild=true` |

## Java-only build (default)

```bash
git clone https://github.com/chanitahar6-pixel/MTX-Toolbox
cd MTX-Toolbox
gradle wrapper          # once; Android Studio and AndroidIDE do this for you
./gradlew :app:assembleDebug
```

No NDK needed. The app runs on the pure-Java engines
(`app/src/main/java/app/mtx/toolbox/core/Java*.java`).

## Build with the C++ core

```bash
./gradlew :app:assembleDebug -Pmtx.nativeBuild=true
```

The native side is built with **ndk-build**:

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

There is no `CMakeLists.txt` in this project, and adding one back would make Gradle
try to configure two native build systems at once.

To build the library on its own, without Gradle:

```bash
cd app/src/main
$ANDROID_NDK_HOME/ndk-build \
    NDK_PROJECT_PATH=. \
    APP_BUILD_SCRIPT=cpp/Android.mk \
    NDK_APPLICATION_MK=cpp/Application.mk
```

ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`.

## How the two engines coexist

`app.mtx.toolbox.core.Native` is a dispatcher. At class-load time it checks whether
`libmtxcore.so` loaded:

* loaded  -> every call goes to `NativeLib`, the JNI declarations;
* missing -> every call goes to the Java engines.

Both return the same row and `key=value` payloads, so no screen and no tool has any
idea which engine served it. `Settings -> Native engine` shows which one is live.

## Release build

```bash
./gradlew :app:assembleRelease
```

R8 and resource shrinking are **off** by default so a release build also succeeds
on-device. If you turn `minifyEnabled` back on, keep the rules in
`app/proguard-rules.pro`: they preserve `NativeLib` and the JNI callback interfaces.

## Troubleshooting

| Symptom | Cause |
|---|---|
| `NDK not configured` | `mtx.nativeBuild=true` without an NDK installed |
| `Unsupported class file major version` | not on JDK 17 |
| `libmtxcore.so not found` at runtime | expected in a Java-only build; the Java engines take over |
| Native build succeeds but tools behave differently | file an issue, both engines must agree |
