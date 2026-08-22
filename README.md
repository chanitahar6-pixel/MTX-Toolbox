# MTX Toolbox

**Professional Android file manager + tools workbench.** File Manager · APK · DEX · Smali · Archives · Text · Hex · Android Tools · Search · Storage · Network · Binary/Native.

> **Hard rule of this project: NO Kotlin.** Application code is **Java** (Android Framework layer) + **C++17** (all engines, via NDK/CMake/JNI).

---

## نظرة عامة (بالعربية)

MTX Toolbox تطبيق أندرويد احترافي مستوحى من تجربة MT Manager لكنه أوسع بكثير: مدير ملفات Dual-Pane حقيقي + أدوات APK/DEX/Smali/Archive/Hex/Binary/Network في تطبيق واحد.

* كل المعالجة الثقيلة تُنفَّذ في **C++** عبر NDK/JNI (بدون تحميل الملف كاملاً إلى الذاكرة).
* **Java** تُستخدم فقط عند الحاجة إلى Android Framework أو لمكتبة خارجية لا تعمل من C++ مباشرة.
* **ممنوع Kotlin** في أي جزء من المشروع.
* لا توجد أزرار شكلية: أي زر موجود في الواجهة مرتبط بتنفيذ حقيقي. ما لم يُنفَّذ بعد لا يظهر في الواجهة، بل يبقى في [ROADMAP](docs/ROADMAP.md).
* اللغة: عربي / إنجليزي قابلة للتغيير من الإعدادات.
* المظهر: يتبع نظام الهاتف افتراضياً، مع إمكانية اختيار ليلي/نهاري يدوياً.

---

## Architecture

```
┌──────────────────────────── Java (UI / Framework only) ────────────────────────────┐
│ MainActivity (Dual Pane)  Drawer  Settings  Viewers/Editors  PackageManager access  │
│ OperationManager  ── thread pool, progress, cancel, retry, logs ──────────────────  │
└───────────────────────────────────────┬────────────────────────────────────────────┘
                                        │  JNI (app.mtx.toolbox.core.Native)
┌───────────────────────────────────────┴────────────────────────────────────────────┐
│                        C++17 engines  (libmtxcore.so)                              │
│  fs      streaming/chunked copy·move·delete, stat, du, mmap                         │
│  hash    MD5 · SHA-1 · SHA-224 · SHA-256 · SHA-384 · SHA-512                        │
│  zip     central-directory parser, raw inflate, virtual-folder browse, no-extract   │
│  axml    binary AndroidManifest.xml decoder                                         │
│  dex     header/map validation, class·method·string counts, string pool             │
│  apk     package info, signing block detection, components, libs, multi-dex         │
│  elf     headers, sections, dynamic table, symbols, imports/exports, strings        │
│  hex     paged read/write on huge files                                             │
│  search  wildcard + content grep, streaming, cancelable                             │
│  ftype   magic bytes / MIME / tool suggestion                                        │
└────────────────────────────────────────────────────────────────────────────────────┘
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

```bash
git clone https://github.com/chanitahar6-pixel/MTX-Toolbox
cd MTX-Toolbox
./gradlew :app:assembleDebug
```

Requirements: Android Studio (AGP 8.5+), JDK 17, NDK r26+, CMake 3.22+.
ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`. minSdk 21, targetSdk 34.

## Status

See [docs/ROADMAP.md](docs/ROADMAP.md) for the phase-by-phase status of every tool, and
[docs/THIRD-PARTY.md](docs/THIRD-PARTY.md) for license handling of every referenced project.

## License

Apache-2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
