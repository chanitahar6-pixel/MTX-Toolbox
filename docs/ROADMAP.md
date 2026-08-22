# Roadmap and status

Legend: **[x] shipped & wired to real execution** · **[~] in progress** · **[ ] planned**

Rule: a tool only appears in the UI once it really runs. No `TODO`, no `Coming Soon` buttons.

## Phase 1 — Core + File Manager + native engines
- [x] Gradle + CMake + JNI skeleton, Java/C++ only
- [x] `fs` engine: list, stat, chunked copy/move, recursive delete, mkdir, rename, disk usage
- [x] Operations engine: queue, progress, speed, ETA, cancel, retry, background, logs
- [x] Dual-pane file manager, independent paths, multi-select, sort, hidden files, bookmarks
- [x] MTX workspace bootstrap (`Extracted/APK/Projects/Backups/Signed/Temp/Logs/Exports`)
- [x] File type analyzer (magic bytes + MIME + tool suggestion)
- [x] Hash tools (MD5/SHA-1/SHA-224/SHA-256/SHA-384/SHA-512), file + folder + compare
- [x] Hex editor: paged view, byte/text search, in-place edit, go-to-offset
- [x] Text editor: UTF-8/16, line numbers, search/replace, go-to-line, large-file guard
- [x] Search engine: wildcards, content search, streaming, cancelable
- [x] Storage analyzer: usage, biggest files/folders, type breakdown, SHA-256 duplicates
- [x] Archive engine (ZIP): browse as virtual folder, extract, test, traversal-safe
- [x] APK inspector: package/version/SDK/ABI/permissions/components/libs/dex count/signature block
- [x] Installed apps: list, info, extract base + split APKs, launch, uninstall intent
- [x] ELF/`.so` analyzer: headers, sections, symbols, imports/exports, strings
- [x] File compare (binary) + device info + theme/language settings

## Phase 2 — APK project pipeline
- [ ] Apktool job bridge: decode → `MTX/Projects/<AppName>/`, progress, logs, cancel
- [ ] Resource/XML/manifest editing with validation before build
- [ ] Build → validate output → sign (apksigner), certificate details, SHA-1/SHA-256, schemes
- [ ] Backup snapshot before build

## Phase 3 — DEX / Smali
- [ ] baksmali/smali bridge, Smali editor with highlighting, validation, rebuild
- [ ] JADX viewer in isolated process, class/method/field navigation, find usage
- [ ] APK compare (manifest, permissions, dex, resources, hashes)

## Phase 4 — Android access layers
- [ ] Shizuku optional layer with runtime detection and fallback
- [ ] Root (optional): `su` detection, status, privileged ops
- [ ] ADB tools, Logcat, dumpsys, properties, process info
- [ ] Terminal with own PTY layer

## Phase 5 — Archives+ and data tools
- [ ] 7z, TAR, GZ, BZ2, XZ, Zstandard (+ RAR only with a license-clean library)
- [ ] JSON / XML / Regex / Base64 / URL / JWT tool set
- [ ] SQLite browser (read-only default), CSV/JSON export

## Phase 6 — Network
- [ ] HTTP client (GET/POST/PUT/PATCH/DELETE/HEAD), headers, cookies, timing
- [ ] WebSocket console, DNS lookup, URL parser

## Definition of done (per tool)
Tested against: small file · huge file · corrupt file · Arabic filenames · very long path ·
real APK · multi-DEX APK · split APK · large ZIP · UTF-8 text · binary blob.
Plus: build, install, runtime, memory, cancellation, permission denial, low storage, rotation,
background execution.
