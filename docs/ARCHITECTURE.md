# Architecture

## Layering rules

1. **No engine logic in Java.** Java may call the framework (PackageManager, SAF, Intents, Views) and
   marshal data. Parsing, IO, hashing, compression, analysis all live in C++.
2. **Every tool is a callable function**, not a screen. The UI is one of several callers.
3. **Every operation returns a result object**: `success | failure`, `progress`, `output`, `error`,
   `logs`, `cancelled`. See `OpResult`.
4. **No blocking the UI thread.** All engine calls go through `OperationManager`.
5. **No crash is acceptable.** Native code never throws across JNI; errors become `errno`-style codes
   plus a message, and are written to `MTX/Logs/`.

## Tool contract

Internal API (stable names, callable from any caller):

```
openFile()        extractArchive()  decodeApk()     buildApk()    signApk()
analyzeDex()      decompileDex()    assembleSmali() hashFile()    searchFiles()
searchArchive()   compareFiles()    analyzeElf()    httpRequest()
```

## Native job + cancellation model

```
long job = Native.newJob();          // registers an atomic<bool> cancel flag
Native.copyPath(job, src, dst, ...); // long-running, reports through a ProgressSink
Native.cancelJob(job);               // sets the flag; engines check it every chunk
Native.releaseJob(job);              // always, in finally{}
```

Cancellation is checked on every chunk boundary (256 KiB), every directory entry and every archive
entry, so cancel latency stays bounded even on huge trees.

## Large-file strategy

| Case | Strategy |
|---|---|
| copy / move | 256 KiB chunked `read`/`write`, `posix_fadvise` hints, sparse-safe |
| hashing | streaming, single pass, constant memory |
| hex editor | paged reads at arbitrary offsets, in-place `pwrite` |
| zip browse | central directory only, entries inflated on demand |
| dex / elf / axml | incremental structural parsing, bounds-checked, never full load |
| content search | sliding window with overlap so matches on chunk borders are not lost |
| thumbnails | decoded off-thread with sample-size limits |

## Error taxonomy

`OK`, `E_NOENT`, `E_PERM`, `E_IO`, `E_NOSPC`, `E_CANCELLED`, `E_CORRUPT`, `E_UNSUPPORTED`,
`E_RANGE`, `E_ENCODING`, `E_BUSY`, `E_INTERNAL`. Each is mapped to a localized user-facing message,
while the technical detail goes to `MTX/Logs/<date>.log`.

## Security rules baked into the engines

* Zip/tar extraction rejects `..` and absolute paths (**path traversal blocked**) and refuses symlink
  escapes outside the destination root.
* Nothing is ever executed just because a file was opened. APKs are parsed, never installed silently.
* SQLite databases open read-only by default.
* JWT `decode` is explicitly labelled as *decode*, never *verify*.
* Rebuilt APKs are always treated as unsigned until the signing stage runs.
