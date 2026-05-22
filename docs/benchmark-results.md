# Benchmark Results

Measurements taken with rozip `0.0.4-SNAPSHOT` (compact CEN, opt-in entry caching) against Quarkus `999-SNAPSHOT` on JDK 21, Linux 7.0.9.

## Phase 0: Instrumentation Data

### Aggregate Stats by Scenario

| Metric | getting-started | hibernate-panache | kafka-streams | rest-fights |
|---|---|---|---|---|
| Filesystems opened | 421 | 553 | 679 | 1,344 |
| Total `readEntryData()` calls | 3,787 | 6,092 | 7,622 | 13,410 |
| Unique entry names | 3,300 | 5,292 | 3,797 | 10,997 |
| Re-read ratio | 1.15 | 1.15 | 2.01 | 1.22 |
| CEN entries in memory | 95,756 | 143,627 | 191,664 | 306,155 |

### Top Re-read Entry Names (across all JARs)

| Entry | getting-started | kafka-streams | rest-fights |
|---|---|---|---|
| `META-INF/MANIFEST.MF` | 240 | 372 | 766 |
| `META-INF/quarkus-extension.properties` | 39 | 99 | 119 |
| `META-INF/jandex.idx` | 12 | 23 | — |

### Per-JAR Re-read Ratios (highest, from kafka-streams)

| JAR | Re-read ratio | Detail |
|---|---|---|
| `jakarta.inject-api` | 4.71x | 7 unique entries read 33 times |
| `microprofile-config-api` | 3.83x | annotation classes read during CDI processing |
| `jakarta.interceptor-api` | 3.55x | small annotation JAR |
| `jakarta.annotation-api` | 3.40x | `@Priority`, `@PreDestroy` read 3x each |

## Compact Central Directory

Replaces `HashMap<String, ZipEntryInfo>` + `HashMap<String, List<String>>` with sorted parallel arrays and a concatenated UTF-8 name `byte[]`. CEN bytes are parsed directly into parallel arrays, avoiding intermediate `String` and `ZipEntryInfo` allocations.

### Memory Reduction (measured)

| Scenario | CEN entries | Compact (actual) | Old (estimated) | Reduction |
|---|---|---|---|---|
| getting-started | 95,756 | 9.9 MB | 25.5 MB | 61% |
| kafka-streams | 191,664 | 20.6 MB | 51.8 MB | 60% |
| rest-fights | 306,155 | 31.1 MB | 80.7 MB | 61% |

## Entry Caching (opt-in via `-Drozip.cache=true`)

SoftReference-based per-filesystem cache. Off by default — SoftReferences inflate peak heap without measurable build time improvement. Useful for workloads with high re-read ratios (e.g. `QuarkusUnitTest` suites, multi-module builds with many shared dependencies).

### Cache Effectiveness (when enabled)

| Metric | getting-started | kafka-streams | rest-fights |
|---|---|---|---|
| Cache hits | 210 (5.5%) | 3,392 (44.5%) | 1,453 (10.8%) |
| Bytes decompressed (no cache) | 13.6 MB | 28.7 MB | 71.5 MB |
| Bytes decompressed (with cache) | 13.2 MB | 16.0 MB | 67.7 MB |

### Cache Impact on Peak Heap (rest-fights)

| Metric | No cache | With cache | Delta |
|---|---|---|---|
| Peak heap | 476 MB | 538 MB | +13% |
| GC pause total | 69.6 ms | 64.8 ms | -7% |

The cache reduces decompression volume but adds peak heap. Decompression is fast enough that avoiding it doesn't produce measurable wall-clock savings.

## Rozip vs JDK ZipFileSystem

Comparison of rozip (default config, no entry cache) against the JDK's default `ZipFileSystem` (`FileSystems.newFileSystem()`). All values are **medians of 5 runs** with JFR `settings=profile`.

### getting-started (lightweight, ~4 Quarkus extensions)

| Metric | Rozip | JDK ZipFS | Delta |
|---|---|---|---|
| Build time | 5.01s | 5.24s | rozip -4% |
| Peak heap | 159 MB | 183 MB | **rozip -13%** |
| GC pause total | 22.9 ms | 24.2 ms | rozip -5% |
| Avg heap before GC | 97.9 MB | 102.6 MB | rozip -5% |

### kafka-streams (multi-module, 2 submodules)

| Metric | Rozip | JDK ZipFS | Delta |
|---|---|---|---|
| Build time | 9.78s | 9.89s | ~same |
| Peak heap | 262 MB | 278 MB | **rozip -6%** |
| GC pause total | 23.8 ms | 38.0 ms | **rozip -37%** |
| Avg heap before GC | 150.1 MB | 149.8 MB | ~same |

### rest-fights (heavy single module, 49 deps, Kafka+gRPC+MongoDB)

| Metric | Rozip | JDK ZipFS | Delta |
|---|---|---|---|
| Build time | 17.06s | 16.87s | ~same |
| Peak heap | 631 MB | 638 MB | ~same |
| GC pause total | 78.2 ms | 74.6 ms | ~same |
| Avg heap before GC | 229.5 MB | 246.9 MB | **rozip -7%** |

### Observations

- **Build time**: Comparable across all scenarios; rozip has a slight edge for lightweight builds.
- **Peak heap**: Rozip uses less peak heap in getting-started (-13%) and kafka-streams (-6%). Rest-fights is within noise. The compact CEN (61% smaller metadata) reduces rozip's permanent footprint, offsetting the overhead of maintaining its own ZIP structures alongside the RAF.
- **GC pauses**: Rozip shows a notable advantage in kafka-streams (-37%), likely due to fewer intermediate allocations during multi-module augmentation. Other scenarios are within noise.
- **Avg heap before GC**: Rozip is consistently equal or lower, confirming the compact CEN reduces the steady-state heap footprint.
- **Primary value**: Rozip's immunity to JDK-8316882 (thread interrupt closing shared file channels) is not measurable in these benchmarks but is the reason Quarkus adopted it.

### Peak Heap Variability (5 runs each)

| Scenario | Rozip (min–max) | JDK ZipFS (min–max) |
|---|---|---|
| getting-started | 155–199 MB | 165–203 MB |
| kafka-streams | 240–265 MB | 262–302 MB |
| rest-fights | 548–666 MB | 526–696 MB |

Peak heap varies significantly across runs (up to 28% range for rest-fights). Median-of-5 is used above for stability.

## Benchmark Projects

| Project | Location | Description |
|---|---|---|
| quarkus-quickstarts / getting-started | `~/git/quarkus-quickstarts/getting-started` | Minimal REST app, ~4 Quarkus extensions |
| quarkus-quickstarts / hibernate-panache | `~/git/quarkus-quickstarts/hibernate-orm-panache-quickstart` | ORM + Panache, medium dependency count |
| quarkus-quickstarts / kafka-streams | `~/git/quarkus-quickstarts/kafka-streams-quickstart` | Multi-module (producer + aggregator), Kafka Streams |
| quarkus-super-heroes / rest-fights | `~/git/quarkus-super-heroes/rest-fights` | Heaviest single module: 49 deps, Kafka+gRPC+MongoDB+REST+fault tolerance |

## Environment

- JDK: 21
- OS: Linux 7.0.9-104.fc43.x86_64
- Quarkus: 999-SNAPSHOT (local build)
- Rozip: 0.0.4-SNAPSHOT
- Heap: default (no -Xmx constraint)
- JFR settings: profile
- All metrics: median of 5 runs
