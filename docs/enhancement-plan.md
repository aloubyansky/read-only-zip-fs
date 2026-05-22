# read-only-zip-fs Enhancement Plan

## Objective

The read-only-zip-fs library (`rozip`) provides a Java NIO `FileSystem` implementation for reading ZIP/JAR archives using `RandomAccessFile` instead of `FileChannel`, making it immune to the JDK bug [JDK-8316882](https://bugs.openjdk.org/browse/JDK-8316882) where thread interrupts close shared channels and break the filesystem for all threads.

Quarkus adopted this library as the default mechanism for reading dependency JARs during build augmentation, dev mode, and continuous testing. Every Quarkus build opens dozens to hundreds of dependency JARs through this filesystem.

The goal of this enhancement effort is to **reduce build time, test execution time, and memory footprint** by optimizing how rozip reads, caches, and exposes ZIP entry data — guided by how Quarkus actually uses the filesystem. Because the real-world impact of each enhancement depends on how many entry reads actually occur in each scenario, the first step is **instrumentation and measurement** before committing to a priority order.

## Current State

### How the library works

- `ReadOnlyZipFileSystem.open(Path)` opens a `RandomAccessFile`, parses the ZIP central directory, and builds two in-memory maps: `entries` (name to `ZipEntryInfo`) and `directoryChildren` (directory to child names list).
- Entry data is read on demand via `readEntryData(String)`: seeks to the local header in the RAF, reads compressed bytes, inflates if DEFLATED, verifies CRC-32, and returns a `byte[]`.
- All RAF access is `synchronized` — reads are serialized across threads.
- An inflater pool (8 instances) reduces `Inflater` allocation overhead.
- No entry data is cached — every read decompresses from scratch.
- The full decompressed `byte[]` is materialized in memory (capped at 256 MB per entry).

### How Quarkus integrates it

- `ArchivePathTree` wraps a JAR path and opens a `ReadOnlyZipFileSystem` via `ZipUtils.newReadOnlyFileSystem()`.
- `SharedArchivePathTree` caches open filesystems in a static `ConcurrentHashMap<Path, SharedArchivePathTree>` with reference counting. The cache uses strong references — no `WeakReference` or `SoftReference`.
- Access goes through standard NIO APIs: `Files.walk()`, `Files.newInputStream()`, `Files.exists()`. These create `ReadOnlyZipPath` objects, go through `ReadOnlyZipFileSystemProvider`, and eventually call `readEntryData()`.
- `PathTreeWithManifest` lazily reads `META-INF/MANIFEST.MF` and computes multi-release JAR mappings by walking `META-INF/versions/`.
- `IndexingUtil` uses `java.util.jar.JarFile` separately for Jandex class indexing — a parallel code path that doesn't benefit from rozip.

### Quarkus classloading architecture

Quarkus splits the classpath into layers with different lifecycles:

**Dev mode:**
- The **base classloader** holds static dependency JARs and survives restarts. Its `ClassPathElement` instances are cached in `CuratedApplication.augmentationElements`.
- The **runtime classloader** is recreated on each restart with updated generated resources and transformed classes. It delegates to the base classloader for dependency classes.
- The **deployment classloader** is also recreated per restart (with `useCpeCache=false`) to re-run augmentation build steps. While it opens dependency JARs fresh, the underlying `ReadOnlyZipFileSystem` instances may be shared via `SharedArchivePathTree`.
- Jandex indices are cached in `liveReloadContext` across restarts. An instrumentation-based reload path (`ClassChangeAgent.redefineClasses()`) can skip full restart entirely when only method bodies change.
- The `ApplicationModel` (dependency graph) is computed once and reused across all restarts.

**JUnit test bootstrap (`@QuarkusTest`):**
- `CuratedApplication` instances are cached per test profile in a static map (`FacadeClassLoader.curatedApplications`), keyed by `"QuarkusTest-" + profile.getName()`.
- Multiple test classes with the same `@TestProfile` share one `CuratedApplication` — augmentation happens once per profile, not per test class.
- When a different profile or `@QuarkusTestResource` set is encountered, the running application is closed and a new one started, potentially triggering re-augmentation.
- `augmentationElements` caching applies within each `CuratedApplication`, so dependency JAR filesystem instances are reused across test classes sharing a profile.

**Deployment testing (`QuarkusUnitTest`):**
- Each test class creates its own `CuratedApplication` with full isolation — no sharing.
- Every test class triggers a complete augmentation cycle: all dependency JARs opened, central directories parsed, entries read.
- The Quarkus core repository has hundreds of `QuarkusUnitTest` tests, making this a significant source of repeated JAR reading within a single test suite run.

### Known limitations

1. **No entry caching** — the same entry (e.g., `META-INF/MANIFEST.MF`) is decompressed on every access.
2. **Full materialization** — entire entries are loaded into memory even when only a few bytes are needed.
3. **Serialized reads** — all threads sharing a filesystem block on the single `synchronized(raf)` lock.
4. **NIO overhead on the hot path** — Path object creation, provider dispatch, and stream wrapping add allocation pressure for what are ultimately HashMap lookups + byte array reads.
5. **Central directory memory** — the entries and directoryChildren maps are retained for the lifetime of the filesystem. For large JARs (thousands of entries) across hundreds of dependencies, this adds up.
6. **No multi-release awareness** — Quarkus must implement multi-release JAR resolution externally.

## Phase 0: Instrumentation (Do This First)

Before committing to enhancement priorities, we need to know where `readEntryData()` calls actually concentrate. The classloading architecture means different scenarios have very different read patterns, and assumptions about "which case benefits most" should be validated with data.

### What to instrument

Add optional lightweight counters in `ReadOnlyZipFileSystem`, controlled by a system property (e.g., `-Drozip.stats=true`), off by default:

| Counter | What it reveals |
|---|---|
| `readEntryData()` call count per entry name | Which entries are read repeatedly (cache candidates) |
| Unique vs. total `readEntryData()` calls per filesystem | Re-read ratio — high ratio = entry caching is valuable |
| Total bytes decompressed | I/O volume |
| Filesystem instances created / open / closed | Whether `SharedArchivePathTree` caching is effective |
| Time in `synchronized(raf)` blocks | Contention under concurrent access |
| Central directory entry count per filesystem | Memory footprint driver |

### Where to report

On `ReadOnlyZipFileSystem.close()`, dump a summary to stderr or a file (e.g., `target/rozip-stats.txt`). Include: archive path, entry count, total reads, unique reads, top-10 most-read entries, total bytes decompressed.

For long-lived filesystems (dev mode), also support on-demand dump via a system signal or JMX.

### Scenarios to profile

Run instrumentation across all benchmark scenarios (see below) and compare:

| Question | What it tells us |
|---|---|
| How many `readEntryData()` calls per cold build? | Baseline volume |
| How many are repeats of the same entry within one augmentation? | Entry caching value within a build |
| How many additional calls happen per dev mode restart? | Whether re-augmentation actually re-reads entries or relies on Jandex cache |
| How many calls per `QuarkusUnitTest` test class? | Per-test augmentation cost |
| How many calls across a full `@QuarkusTest` suite with N profiles? | Profile-switch cost |
| What's the re-read ratio for `QuarkusUnitTest` suites (total / unique)? | Upper bound on entry caching benefit |

## Benchmark Projects and Scenarios

### Projects

| Project | Location | Why |
|---|---|---|
| **quarkus-super-heroes / rest-fights** | `~/git/quarkus-super-heroes/rest-fights` | Heaviest single module: 49 dependencies, Kafka + gRPC + MongoDB + REST clients + fault tolerance + OpenTelemetry. Exercises the most JAR scanning per build. |
| **quarkus-super-heroes (full)** | `~/git/quarkus-super-heroes/` | 8 microservices, 31 unique Quarkus extensions, polyglot (Java + Kotlin + gRPC proto). Tests multi-module build aggregation. |
| **quarkus-quickstarts (selected)** | `~/git/quarkus-quickstarts/` | Range testing across dependency counts. Pick `getting-started` (baseline, ~4 deps), `context-propagation-quickstart` (medium, ~9 extensions), `kafka-streams-quickstart` (heavy, multi-module). |
| **quarkus-platform integration tests** | `~/git/quarkus-platform/` | 303 integration test modules, each a real Quarkus app with augmentation. Extreme stress test for aggregate measurements. |

### Scenarios

| Scenario | Project | What it exercises |
|---|---|---|
| **Cold build** | super-heroes `rest-fights` | Full augmentation: all dependency JARs opened, central directories parsed, entries read for class scanning and indexing. This is where all JAR reading happens for the first time. |
| **Multi-module build** | super-heroes (all 8 modules) | Aggregate JAR opening across modules; tests whether `SharedArchivePathTree` cache delivers reuse for shared dependencies. |
| **Dev mode first start** | super-heroes `rest-fights` | Initial augmentation + hot-reload infrastructure setup. Baseline for restart comparison. |
| **Dev mode restart (10 cycles)** | super-heroes `rest-fights` | Trigger 10 consecutive restarts by touching a source file. The base classloader and its dependency JARs survive restarts, but the deployment classloader is recreated per restart to re-run augmentation. Measures whether `SharedArchivePathTree` caching and any entry-level caching avoid redundant decompression during re-augmentation. |
| **Continuous testing (10 cycles)** | super-heroes `rest-fights` | 10 consecutive test cycles triggered by source changes. Adds test classpath scanning on top of restart overhead. Test mode may use flat classpath (`isFlatTestClassPath()`), affecting how JARs are loaded. |
| **`@QuarkusTest` suite** | super-heroes `rest-fights` | Full `mvn verify` run. Measures augmentation cost for test bootstrap — how many JAR reads happen during test startup vs. the build itself. |
| **`@QuarkusTest` multi-profile** | super-heroes (modules with different `@TestProfile`) | Measures the cost of profile switches — each distinct profile may trigger re-augmentation. |
| **`QuarkusUnitTest` suite** | quarkus core (`core/deployment`) | Quarkus's own deployment tests. Each test class creates its own `CuratedApplication` and triggers full augmentation. Hundreds of tests = hundreds of augmentation cycles against the same dependency JARs. Highest potential for entry caching benefit. |
| **Scaling curve** | quickstarts (3 modules) | Build time and memory vs. dependency count. Shows whether improvements scale linearly with classpath size. |

### Metrics

For each scenario, capture:

**Time metrics:**
- Wall-clock time (build, restart, test cycle)
- Per-build-step timing via `build-metrics.json` (auto-enabled in dev mode, enabled via `-Dquarkus.builder.metrics.enabled=true` for builds)

**Memory metrics:**
- Peak heap usage (JFR `jdk.GCHeapSummary` events or `jcmd <pid> GC.heap_info`)
- Retained heap after augmentation (heap histogram via `jcmd <pid> GC.class_histogram` filtered to `rozip|ZipEntry|ArchivePath|SharedArchive`)
- Heap growth trend across dev mode restarts (should be flat; growth indicates a leak or unbounded caching)
- Total allocations attributed to rozip classes (JFR `jdk.ObjectAllocationInNewTLAB` / `jdk.ObjectAllocationOutsideTLAB`)
- GC pause time and collection count (`-Xlog:gc*:file=gc.log:time,uptime,level,tags`)

**Rozip-specific metrics (from instrumentation):**
- `readEntryData()` total calls, unique entries, re-read ratio
- Filesystem instances created / reused / closed
- Total bytes decompressed
- Top-10 most-read entries

## Measurement Methodology

### Tools

| Tool | Purpose | How to use |
|---|---|---|
| **rozip instrumentation** | Entry-level read counts, re-read ratio, bytes decompressed | `-Drozip.stats=true` (see Phase 0) |
| **JFR** (Java Flight Recorder) | Comprehensive profiling: heap, allocations, GC, CPU | `-XX:StartFlightRecording=filename=build.jfr,settings=profile` as MAVEN_OPTS |
| **jcmd** | Point-in-time heap histograms | `jcmd <pid> GC.class_histogram` at key moments |
| **GC logging** | GC pause time, collection frequency, promotion rate | `-Xlog:gc*:file=gc.log:time,uptime,level,tags` as MAVEN_OPTS |
| **build-metrics.json** | Per-build-step wall-clock timing | `-Dquarkus.builder.metrics.enabled=true` (auto in dev mode) |
| **Async Profiler** | Allocation flamegraphs, CPU flamegraphs | `asprof -e alloc -d 60 -f alloc.html <pid>` |

### Comparison protocol

For each enhancement:

1. **Baseline** — run all scenarios 3 times on unmodified rozip, discard first run (JIT warmup), average remaining 2.
2. **Enhancement** — apply the enhancement, run the same scenarios 3 times with the same protocol.
3. **Compare** — report delta for each metric. Flag any metric that regresses (e.g., entry caching improving CPU but increasing retained heap).

Environment controls:
- Same JDK version (21+), same heap settings (`-Xmx`), same machine, no background load.
- Run `mvn clean` between cold build measurements.
- For dev mode, let the application fully start before triggering restarts.

## Where Impact Is Expected (Pre-Instrumentation Assessment)

Before instrumentation data is available, here is our best assessment of where each scenario falls on the impact spectrum, based on understanding of the classloading architecture:

### Highest expected impact

**Cold builds** — all dependency JARs are opened and scanned for the first time. No caching layer is warm. Every entry read is a fresh decompression. All enhancements apply at full strength.

**`QuarkusUnitTest` suites** — each test class triggers a full, isolated augmentation. Over hundreds of tests in the Quarkus core repo, the same dependency JARs are opened and scanned repeatedly. If `SharedArchivePathTree` keeps the filesystems alive across test classes (to be verified), entry caching could eliminate most re-decompression. If not, even filesystem-level caching would help.

**Steady-state memory (dev mode, long test runs)** — `SharedArchivePathTree` holds open filesystems with strong references for the duration of the JVM. CEN metadata (entries map + directoryChildren) is retained permanently. With 100+ dependency JARs, compact CEN representation directly reduces the permanent heap cost.

### Moderate expected impact

**`@QuarkusTest` with multiple profiles** — each profile switch may trigger re-augmentation. The number of re-reads depends on how many profiles exist and whether `SharedArchivePathTree` shares filesystems across `CuratedApplication` instances.

**Multi-module builds** — shared dependencies across modules benefit from `SharedArchivePathTree` caching. Entry caching would help if build steps within a single augmentation re-read the same entries.

### Lower expected impact (to be verified)

**Dev mode restarts** — the base classloader survives, Jandex indices are cached in `liveReloadContext`, and the `ApplicationModel` is reused. The deployment classloader is recreated per restart, but it's unclear how many actual `readEntryData()` calls this triggers. Instrumentation will reveal whether re-augmentation re-reads entries or mostly relies on cached indices.

**Continuous testing cycles** — similar to dev mode restarts. The test classpath adds scanning overhead, but flat classpath mode may simplify JAR loading.

## Planned Enhancements

Priorities below are provisional — to be adjusted based on Phase 0 instrumentation data.

### Entry-level data caching

**What:** Cache decompressed `byte[]` per entry using `SoftReference` so the JVM can reclaim under memory pressure.

**Why:** Quarkus reads the same entries repeatedly — within a single augmentation (multiple build steps reading `META-INF/MANIFEST.MF`, `META-INF/jandex.idx`, service loader configs) and across `QuarkusUnitTest` test classes (each triggering full augmentation against the same dependency JARs). The base classloader caches `ClassPathElement` instances and Jandex indices, but the deployment phase still accesses entry data through rozip, decompressing from scratch each time.

**Expected impact:**
- Time: significant reduction in CPU for repeated entry reads, especially for `QuarkusUnitTest` suites where hundreds of augmentation cycles hit the same JARs. For dev mode restarts, impact depends on how many entries are actually re-read (to be measured).
- Memory: retained heap increases (cached entries), but `SoftReference` ensures the JVM reclaims before OOM. Allocation rate decreases (fewer `byte[]` allocations + fewer `Inflater` borrows).

**Tradeoff:** Heap vs. CPU. The `SoftReference` approach makes this self-correcting — under memory pressure, the cache shrinks automatically. A size-bounded LRU is an alternative if more predictable memory behavior is preferred.

### Direct byte[] API

**What:** Expose `byte[] readEntryBytes(String entryName)` on the filesystem, bypassing NIO Path/Provider/Stream abstractions.

**Why:** The current path is: caller -> `Files.newInputStream(path)` -> Path resolution -> `provider.newInputStream()` -> `readEntryData()` -> `ByteArrayInputStream(bytes)` -> caller reads stream into another `byte[]`. The direct API eliminates Path allocation, provider dispatch, and the intermediate stream.

**Expected impact:**
- Time: modest improvement per call, but multiplied across thousands of entries per build.
- Memory: reduces transient object allocation (no `ReadOnlyZipPath`, no `ByteArrayInputStream`). No impact on retained heap.

**Tradeoff:** Adds a non-standard API alongside the NIO FileSystem interface. Callers must opt in explicitly.

### Streaming InputStream for large entries

**What:** Provide an `InputStream` that reads and inflates in chunks from the RAF, without materializing the entire entry in memory.

**Why:** The current 256 MB per-entry cap means a single large resource (e.g., a bundled native library or large data file) temporarily consumes up to 256 MB of heap. Concurrent reads multiply this.

**Expected impact:**
- Time: neutral to slight improvement (avoids allocating large byte arrays).
- Memory: significant reduction in peak heap for JARs containing large entries. Slight increase in transient allocations (buffer objects).

**Tradeoff:** Requires careful synchronization — either a second `RandomAccessFile` handle per stream, or a copy-to-buffer approach. The stream cannot be used concurrently with other reads if sharing the single RAF.

### Parallel read support

**What:** Pool of `RandomAccessFile` handles (similar to the existing inflater pool) to allow concurrent reads from the same archive.

**Why:** `SharedArchivePathTree` shares one filesystem across threads. All reads serialize on `synchronized(raf)`. During multi-threaded augmentation (Quarkus builder uses 8+ threads), this is a bottleneck.

**Expected impact:**
- Time: improved throughput for concurrent builds, especially in multi-module projects where multiple build steps read from the same JAR.
- Memory: slight increase (N open file handles instead of 1). No change to entry data memory.

**Tradeoff:** File handle consumption increases. On Linux, default ulimit is typically 1024; with hundreds of JARs and a pool of 4-8 handles each, this could approach limits. Pool size should be configurable.

### Compact central directory representation

**What:** Reduce per-entry memory cost of the `entries` map and `directoryChildren` structure. Options include: storing entry names as byte arrays (UTF-8), using a trie for directory structure, or lazy-parsing entries from stored CEN byte offsets.

**Why:** The `ArchivePathTree` code comments explicitly note that "the CEN is quite large." For a Quarkus build with 100+ dependency JARs, each with hundreds to thousands of entries, the aggregate metadata footprint is significant. This is a permanent cost for the lifetime of the JVM in dev mode and long test runs.

**Expected impact:**
- Time: neutral (may slightly slow lookups if using a more compact structure).
- Memory: significant reduction in retained heap. For a JAR with 1,000 entries, estimated savings of 40-60% of metadata overhead depending on approach.

**Tradeoff:** More complex code, potentially slower lookups. Lazy parsing trades memory for latency on first access to each entry.

### Built-in multi-release JAR support

**What:** Detect `Multi-Release: true` in the manifest during central directory parsing and build the version-to-path mapping as part of the directory structure.

**Why:** Quarkus currently implements this in `PathTreeWithManifest` by walking `META-INF/versions/` after the filesystem is open — a separate tree walk that could be done for free during CEN parsing since all entry names are already enumerated.

**Expected impact:**
- Time: eliminates one tree walk per multi-release JAR opened.
- Memory: slight increase (version mapping stored in the filesystem). Offset by removing the external mapping in Quarkus.

**Tradeoff:** Adds JAR-specific semantics to what is currently a generic ZIP filesystem. Could be opt-in via a flag.

### Visitor/callback API for tree walking

**What:** Expose `void walkEntries(String prefix, BiConsumer<String, ZipEntryInfo>)` directly on the filesystem, bypassing NIO directory stream and Path allocation.

**Why:** `ArchivePathTree.walk()` goes through `Files.walk()` -> NIO directory stream -> Path creation for every entry -> filter -> visitor. The filesystem already has all entries indexed in the `directoryChildren` map. A direct traversal avoids all intermediate object allocation.

**Expected impact:**
- Time: modest improvement for full tree walks.
- Memory: significant reduction in transient allocations (no `ReadOnlyZipPath` per entry, no `DirectoryStream` wrapper objects).

**Tradeoff:** Non-standard API. NIO `Files.walk()` remains available for compatibility; this is an opt-in fast path.

## Enhancement Impact Matrix

| Enhancement | Cold build | Test suites | Dev restart | Peak heap | Retained heap | Alloc rate | GC pressure |
|---|---|---|---|---|---|---|---|
| Entry caching | better | much better | TBD | ~same (SoftRef) | increases | better | better |
| Direct byte[] API | better | better | better | same | same | better | better |
| Streaming InputStream | ~same | ~same | ~same | much better | same | mixed | better |
| Parallel reads | better | better | better | slight increase | same | same | same |
| Compact CEN | same | same | same | same | much better | same | same |
| Multi-release built-in | slightly better | slightly better | slightly better | same | ~same | slightly better | same |
| Visitor API | better | better | better | same | same | much better | better |
