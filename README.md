# MTX Toolbox

**Professional Android file manager + tools workbench.** File Manager · APK · DEX · Smali · Archives · Text · Hex · Android Tools · Search · Storage · Network · Binary/Native.

> **Hard rule of this project: NO Kotlin.** Application code is **Java** (Android Framework layer) + **C++17** (all engines, via NDK/JNI, built with **ndk-build / Android.mk**).

---

## نظرة عامة (بالعربية)

MTX Toolbox تطبيق أندرويد احترافي مستوحى من تجربة MT Manager لكنه أوسع بكثير: مدير ملفات Dual-Pane حقيقي + أدوات APK/DEX/Smali/Archive/Hex/Binary/Network في تطبيق واحد.

* كل المعالجة الثقيلة تُنفَّذ في **C++** عبر NDK/JNI وبواسطة **Android.mk** (بدون تحميل الملف كاملاً إلى الذاكرة).
* **Java** تُستخدم فقط عند الحاجة إلى Android Framework أو لمكتبة خارجية لا تعمل من C++ مباشرة.
* **ممنوع Kotlin** في أي جزء من المشروع.
* لا توجد أزرار شكلية: أي زر موجود في الواجهة مرتبط بتنفيذ حقيقي. ما لم يُنفَّذ بعد لا يظهر في الواجهة، بل يبقى في [ROADMAP](docs/ROADMAP.md).
* اللغة: عربي / إنجليزي قابلة للتغيير من الإعدادات.
* المظهر: يتبع نظام الهاتف افتراضياً، مع إمكانية اختيار ليلي/نهاري يدوياً.

---

## Architecture

```
Java  (UI / Android Framework only)
  MainActivity (Dual Pane) · Drawer · Settings · Viewers/Editors · PackageManager
  OperationManager  → thread pool, progress, cancel, retry, logs
        |
        |  JNI  (app.mtx.toolbox.core.Native)
        v
C++17 engines  (libmtxcore.so, built by ndk-build)
  fs        streaming/chunked copy·move·delete, stat, du
  hash      MD5 · SHA-1 · SHA-224 · SHA-256   (384/512 from the platform)
  zip       central-directory parser, raw inflate, virtual-folder browse
  axml      binary AndroidManifest.xml decoder
  dex       header/map validation, class·method·string counts, string pool
  apk       package info, signing block detection, components, libs, multi-dex
  elf       headers, sections, dynamic table, symbols, imports/exports, strings
  hex       paged read/write on huge files
  search    wildcard + content grep, streaming, cancelable
  ftype     magic bytes / MIME / tool suggestion
```

Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## Workspace layout

On first launch the app creates its workspace and never overwrites originals without explicit confirmation:

```
MTX/
├── Extracted/   pulled APKs and split APKs
├── APK/         imported / produced APKs
├── Projects/    decode workspaces:  MTX/Projects/<AppName>/
├── Backups/     pre-build snapshots
├── Signed/      signed output
├── Temp/        scratch, cleaned on demand
├── Logs/        technical logs for every failure
└── Exports/     CSV/JSON/text exports
```

## Build

Requires **JDK 17**, Android SDK 34 and any recent **NDK**. The native side uses
`app/src/main/cpp/Android.mk` (ndk-build); there is no CMake in this project.

```bash
git clone https://github.com/chanitahar6-pixel/MTX-Toolbox
cd MTX-Toolbox
gradle wrapper                    # once, or just open the folder in Android Studio
./gradlew :app:assembleDebug
```

ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`. minSdk 21, targetSdk 34.
Full instructions and troubleshooting: [docs/BUILD.md](docs/BUILD.md).

## Status

* [docs/ROADMAP.md](docs/ROADMAP.md) — what already runs, and what is next, tool by tool.
* [docs/AUDIT.md](docs/AUDIT.md) — the three-pass build audit: every issue found and how it was fixed.
* [docs/THIRD-PARTY.md](docs/THIRD-PARTY.md) — license handling for every referenced project.

## License

Apache-2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
