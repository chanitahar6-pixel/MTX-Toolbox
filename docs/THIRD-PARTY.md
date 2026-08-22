# Third-party components and references

Every entry below was reviewed against the project rules:
1. read LICENSE, 2. read README, 3. take only the required files, 4. library vs full app,
5. integration method, 6. record source + version, 7. keep NOTICE/COPYRIGHT,
8. reject licenses incompatible with MTX distribution, 9. reject Kotlin-dependent libraries.

| Project | License | Use in MTX | Integration |
|---|---|---|---|
| [Apktool](https://github.com/iBotPeaches/Apktool) | Apache-2.0 | resource + manifest + smali decode, rebuild | **bundled JAR**, driven by an internal Job API (never raw shell). NOTICE retained. |
| [smali/baksmali](https://github.com/JesusFreke/smali) | BSD-3-Clause / Apache-2.0 (per module) | DEX ⇄ Smali, parsing, validation | **bundled JAR**, invoked from `SmaliBridge` (Java, no Kotlin). |
| [JADX](https://github.com/skylot/jadx) | Apache-2.0 | DEX → Java-like view | **bundled JAR**, isolated process, results rendered inside MTX viewer. JADX desktop UI is never launched. |
| [Shizuku](https://github.com/RikkaApps/Shizuku) | Apache-2.0 | optional privileged Android APIs | **optional AIDL/API only**, detected at runtime, full fallback when absent. |
| [Ghidra](https://github.com/NationalSecurityAgency/ghidra) | Apache-2.0 | reference only | **not bundled.** Native analysis is implemented directly in `cpp/mtx/elf.cpp`. |
| [Termux](https://github.com/termux/termux-app) | GPL-3.0 | reference only for terminal/PTY behaviour | **no code copied.** MTX terminal uses its own PTY layer in C++. |
| [OkHttp](https://github.com/square/okhttp) | Apache-2.0 (verify at integration time) | reference for HTTP client design | MTX ships its **own C++ HTTP layer**; OkHttp only considered if license/ownership check passes. |
| [Fuel](https://github.com/kittinunf/fuel) | — | **rejected** | Kotlin-oriented. Violates the no-Kotlin rule. |
| [XFiles](https://github.com/Local1stDotApp/XFiles) | **GPL-3.0-only** | design/behaviour reference for App Manager, splits, APKS/APKM/XAPK/AAB | **no code copied.** Copying would force GPL-3.0 on all of MTX. |
| [FileExplorer](https://github.com/SysAdminDoc/FileExplorer) | MIT | ideas: dual pane, tabs, storage analyzer, archive support | reference; if any code is reused, MIT notice is added to `third_party/`. |
| [HTTP Toolkit](https://github.com/httptoolkit/httptoolkit) | AGPL-3.0 (server components) | reference only | **no code copied.** |

## Kotlin policy

MTX source contains **zero** Kotlin files (`.kt`/`.kts` are not used; Gradle scripts are Groovy DSL).
AndroidX artifacts may carry a Kotlin **runtime** transitively; that is a prebuilt dependency, not
project source. Dependencies are kept minimal for that reason and any library whose *API* requires
Kotlin is rejected.

## JVM-tool policy

Apktool, smali and JADX are JVM tools. They cannot run from C++ directly, so they are isolated behind
a thin **Java bridge** (`app.mtx.toolbox.tools.*`) that exposes the same `Job` contract as native
engines: `input · workspace · options · progress · logs · output · error · cancel`. Everything that
can be native stays in C++.
