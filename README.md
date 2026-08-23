# MTX Toolbox

**Professional Android file manager + tools workbench.** File Manager · APK · DEX · Smali · Archives · Text · Hex · Android Tools · Search · Storage · Network · Binary/Native.

> **Hard rule of this project: NO Kotlin.** Application code is **Java** + **C++17**.
> Builds on a desktop with an NDK **and** on the phone in **AndroidIDE**, with the same features either way.

---

## نظرة عامة (بالعربية)

MTX Toolbox تطبيق أندرويد احترافي مستوحى من تجربة MT Manager لكنه أوسع بكثير: مدير ملفات Dual-Pane حقيقي + أدوات APK/DEX/Archive/Hex/Binary في تطبيق واحد.

* المحركات مكتوبة مرتين: بـ **C++17** عبر NDK/JNI وبواسطة **Android.mk**، وبـ **Java** كبديل كامل يعمل بدون NDK.
* التطبيق يختار المحرك تلقائياً، والنتيجة واحدة في الحالتين، لذلك **يُبنَى داخل AndroidIDE على الهاتف مباشرة**.
* **ممنوع Kotlin** في أي جزء من المشروع.
* لا توجد أزرار شكلية: أي زر موجود مرتبط بتنفيذ حقيقي، وما لم يُنفَّذ بعد موجود في [ROADMAP](docs/ROADMAP.md).
* اللغة: عربي / إنجليزي من الإعدادات، والمظهر يتبع نظام الهاتف مع خيار ليلي/نهاري يدوي.

---

## Two engines, one behaviour

```
Java  (UI / Android Framework only)
  MainActivity (Dual Pane) · Drawer · Settings · Viewers/Editors · PackageManager
  OperationManager  → thread pool, progress, cancel, retry, logs
        |
        v
  Native  (dispatcher: picks whatever is present at runtime)
        |
        +--> NativeLib  →  libmtxcore.so     C++17, ndk-build, Android.mk
        |                  fs · hash · zip · axml · dex · apk · elf · hex · search · ftype
        |
        +--> JavaEngine · JavaZip · JavaAxml · JavaApk · JavaDex · JavaElf
             JavaFileType · JavaSearch        pure Java, no NDK required
```

Both sides return the same row and `key=value` payloads, so no screen knows which one
served it. `Settings → Native engine` tells you which is live.

Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## Workspace layout

Created on first launch; originals are never overwritten without explicit confirmation:

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

**On the phone (AndroidIDE)** — the default configuration, no NDK needed:
open the root folder, sync, build. Full guide: [docs/BUILD-ANDROIDIDE.md](docs/BUILD-ANDROIDIDE.md).

**On a desktop:**

```bash
git clone https://github.com/chanitahar6-pixel/MTX-Toolbox
cd MTX-Toolbox
gradle wrapper                                  # once
./gradlew :app:assembleDebug                    # Java engines
./gradlew :app:assembleDebug -Pmtx.nativeBuild=true   # + C++ core via Android.mk
```

JDK 17, AGP 8.1.4, Gradle 8.4, compileSdk 34, minSdk 21.
ABIs when native is on: `arm64-v8a`, `armeabi-v7a`, `x86_64`.
More: [docs/BUILD.md](docs/BUILD.md).

## Status

* [docs/ROADMAP.md](docs/ROADMAP.md) — what already runs, and what is next, tool by tool.
* [docs/AUDIT.md](docs/AUDIT.md) — the four-pass build audit: every issue found and how it was fixed.
* [docs/THIRD-PARTY.md](docs/THIRD-PARTY.md) — license handling for every referenced project.

## License

Apache-2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
