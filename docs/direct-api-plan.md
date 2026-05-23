# Direct API for rozip — bypassing NIO overhead

## Context

Quarkus accesses dependency JARs through the `PathTree`/`PathVisit` abstraction, which currently goes through NIO (`Files.exists()`, `Files.walk()`, `Files.readAllBytes()`, etc.). Each NIO call traverses the FileSystem provider dispatch, creates intermediate objects (ReadOnlyZipPath, ByteArrayInputStream, DirectoryStream), and does string-based path normalization — all to reach methods that rozip already exposes as package-private: `entryExists()`, `readEntryData()`, `getDirectoryChildren()`.

The goal is to expose a public direct API on `ReadOnlyZipFileSystem` so that Quarkus's `ArchivePathTree` can bypass NIO for the hot paths.

## What the NIO path costs today

For a `contains("com/example/Foo.class")` call:
```
OpenContainerPathTree.contains(resourceName)
  → resolveResource() → root.resolve(relativePath) → creates ReadOnlyZipPath
  → Files.exists(path) → provider.checkAccess(path)
    → toEntryName(path) → path string manipulation
    → fs.entryExists(entryName) → binary search (the actual work)
```
Direct call: `fs.entryExists("com/example/Foo.class")` — just the binary search.

For `walk()`:
```
Files.walk(root)
  → for each directory: newDirectoryStream() 
    → getDirectoryChildren() → list of String names
    → for each child: parent.resolve(name) → creates ReadOnlyZipPath
    → filter → readAttributes() → creates ReadOnlyZipAttributes
```
Direct walk: iterate sorted entries in CompactEntryTable, no Path/Stream/Attributes objects.

## Phase 1: rozip public API (this repo)

### 1a. Make existing methods public

These methods already exist as package-private on `ReadOnlyZipFileSystem`. Make them public:

- **`readEntryData(String entryName)`** — already used directly in tests
- **`entryExists(String entryName)`** — boolean existence check
- **`getDirectoryChildren(String entryName)`** — returns `List<String>`
- **`getEntryInfo(String entryName)`** — returns `ZipEntryInfo` metadata

### 1b. Add entry walk API

Add a direct entry iteration method that walks the sorted CompactEntryTable without creating Path objects:

```java
// On ReadOnlyZipFileSystem:
public void walkEntries(BiConsumer<String, ZipEntryInfo> visitor)
public void walkEntries(String prefix, BiConsumer<String, ZipEntryInfo> visitor)
```

Implementation: iterate the sorted `nameBytes`/`nameOffsets` arrays directly. For the prefix variant, use `lowerBound()` to jump to the right position. Construct `ZipEntryInfo` on the fly from the parallel arrays (same as `entryAt()`).

### 1c. Add typed factory method

```java
// On ZipUtils:
public static ReadOnlyZipFileSystem openReadOnly(Path path) throws IOException
```

Returns the concrete type so callers don't need to cast. Keeps `newReadOnlyFileSystem()` for backward compatibility.

## Phase 2: Quarkus integration (separate repo, future)

Where Quarkus would use the direct API:

| Method | Current (NIO) | Direct API | Savings |
|--------|--------------|------------|---------|
| `contains()` | `Files.exists(resolveResource())` | `fs.entryExists(name)` | Skip Path creation, provider dispatch |
| `apply()`/`accept()` existence check | `Files.exists(path)` | `fs.entryExists(name)` | Same |
| `walk()` | `Files.walk()` → DirectoryStream per dir | `fs.walkEntries(visitor)` | Skip all Path/Stream/Iterator allocation |
| `walkIfContains()` | `Files.walk(subdir)` | `fs.walkEntries(prefix, visitor)` | Same, scoped to prefix |
| Entry reads by callers | `Files.readAllBytes(visit.getPath())` | Needs `PathVisit` enhancement to expose `byte[]` directly | Skip ByteArrayInputStream wrapper |

The `OpenArchivePathTree` would hold a `ReadOnlyZipFileSystem` reference (via `ZipUtils.openReadOnly()` or cast) and use direct calls. The existing `PathVisit` contract (callers get a `Path`) would remain available as fallback.

## Files to modify (Phase 1 only)

- `src/main/java/io/github/aloubyansky/rozip/ReadOnlyZipFileSystem.java` — make methods public, add `walkEntries()`
- `src/main/java/io/github/aloubyansky/rozip/CompactEntryTable.java` — add `forEachEntry()` / `forEachEntry(prefix)` iteration support
- `src/main/java/io/github/aloubyansky/rozip/ZipUtils.java` — add `openReadOnly()` factory
- `src/test/java/.../ReadOnlyZipFileSystemTest.java` — tests for new public API

## Verification

- Existing test suite passes (no behavioral changes, only visibility changes)
- New tests for `walkEntries()` covering: full walk, prefix-scoped walk, empty prefix, nonexistent prefix, stop-walking
- New test for `openReadOnly()` factory method
- Benchmark: measure build times, peak heap, GC pauses before/after Quarkus integration
