# Building MTX Toolbox inside AndroidIDE (on the phone)

This project is set up to compile **on-device** with
[AndroidIDE](https://github.com/AndroidIDEOfficial/AndroidIDE), with no NDK and no
desktop involved.

## Why a special setup is needed

AndroidIDE runs a real Gradle build on the phone, but it has **no NDK**: it cannot
compile C or C++. The MTX engines are written in C++, so a naive project would fail
at the `externalNativeBuild` step every single time.

The project solves this properly instead of removing features:

```
Native  (dispatcher)
  |
  +-- NativeLib  -> libmtxcore.so   (C++17, Android.mk, built on desktop/CI)
  |
  +-- JavaEngine, JavaZip, JavaApk, JavaAxml, JavaDex, JavaElf,
      JavaFileType, JavaSearch     (pure Java, always available)
```

`Native` picks whichever engine is present at runtime. Both produce **identical
payloads**, so every screen, every tool and every button behaves the same. Nothing
is stubbed out and nothing says "not supported in this build".

## Settings that matter

`gradle.properties` ships with:

```properties
mtx.nativeBuild=false
```

With that flag off, Gradle never looks at `Android.mk`, so no NDK is required.
Leave it as it is when building in AndroidIDE.

The rest of the toolchain is pinned to what AndroidIDE accepts:

| Item | Value |
|---|---|
| Android Gradle Plugin | 8.1.4 |
| Gradle | 8.4 |
| Java source/target | 11 |
| compileSdk / targetSdk | 34 |
| minSdk | 21 |
| R8 / resource shrinking | off, also in release |

## Steps

1. Install **AndroidIDE** and let it download its SDK and JDK 17 on first launch.
2. Clone the project (AndroidIDE has a built-in git client, or use Termux):
   `https://github.com/chanitahar6-pixel/MTX-Toolbox`
3. Open the **root** folder (the one containing `settings.gradle`), not `app/`.
4. Let the Gradle sync finish. It downloads Gradle 8.4 and the AndroidX artifacts,
   so the first sync needs a network connection and some patience.
5. Build the debug variant. Output:
   `app/build/outputs/apk/debug/app-debug.apk`

## What runs in a Java-only build

Everything in Phase 1 of the [roadmap](ROADMAP.md):

* dual-pane file manager, multi-select, sort, hidden files, bookmarks;
* copy / move / delete / rename / new file / new folder, streaming and cancellable;
* operations screen with progress, speed, ETA, cancel, retry and logs;
* APK inspector: manifest decoded by the Java AXML decoder, components, permissions,
  ABIs, DEX list, native libraries, and a real APK Signing Block probe (v2 / v3 / v3.1);
* archive browser with virtual folders, traversal-safe extraction and CRC testing;
* hex editor with paged reads, in-place byte writes and byte/text search;
* text editor, search engine, storage analyzer with SHA-256 duplicate detection;
* DEX header inspector, ELF/`.so` analyzer, hashes, file compare, device info.

Differences versus the C++ core, stated honestly:

| Aspect | C++ core | Java engines |
|---|---|---|
| Throughput on big files | faster | slower, same memory profile |
| ELF analysis | any size, mmap | files above 128 MB are refused |
| Hash algorithms | MD5/SHA-1/224/256 in-engine | delegated to the platform provider |
| Everything else | identical behaviour | identical behaviour |

## Getting the native core into the APK

On a desktop with an NDK installed, or in CI:

```bash
./gradlew :app:assembleDebug -Pmtx.nativeBuild=true
```

That builds `libmtxcore.so` from `app/src/main/cpp/Android.mk` with **ndk-build**
and packages it. `Native` then routes every call to C++ automatically, with no code
change anywhere.

The GitHub Actions workflow in `.github/workflows/build.yml` builds both variants on
every push, so a broken native build can never go unnoticed.

## If a sync fails

| Symptom | Fix |
|---|---|
| `NDK not configured` | `mtx.nativeBuild` was set to `true`; put it back to `false` |
| `Unsupported class file major version` | AndroidIDE's JDK is not 17; update AndroidIDE |
| `Could not find com.android.tools.build:gradle:8.1.4` | no network during sync, retry online |
| Out of memory during build | lower `org.gradle.jvmargs` to `-Xmx1024m` in `gradle.properties` |
| `plugin requires Gradle 8.x` | do not edit `gradle-wrapper.properties`; it must stay at 8.4 |
