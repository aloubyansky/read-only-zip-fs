package io.github.aloubyansky.rozip;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

final class RozipStats {

    static boolean ENABLED = Boolean.getBoolean("rozip.stats");

    private static final AtomicLong instancesCreated = new AtomicLong();
    private static final AtomicInteger currentlyOpen = new AtomicInteger();
    private static final List<Snapshot> completed = Collections.synchronizedList(new ArrayList<>());

    static {
        if (ENABLED) {
            Runtime.getRuntime().addShutdownHook(new Thread(RozipStats::dumpReport, "rozip-stats"));
        }
    }

    static Tracker onOpen(Path archivePath, int centralDirectoryEntryCount,
            long cenMemoryBytes, long cenOldEstimateBytes, long openNanos) {
        instancesCreated.incrementAndGet();
        currentlyOpen.incrementAndGet();
        return new Tracker(archivePath.toString(), centralDirectoryEntryCount,
                cenMemoryBytes, cenOldEstimateBytes, openNanos);
    }

    static void onClose(Tracker tracker) {
        currentlyOpen.decrementAndGet();
        completed.add(tracker.snapshot());
    }

    static List<Snapshot> completedSnapshots() {
        return List.copyOf(completed);
    }

    static void reset() {
        instancesCreated.set(0);
        currentlyOpen.set(0);
        completed.clear();
    }

    static final class Tracker {
        private final String archivePath;
        private final int centralDirectoryEntryCount;
        private final long cenMemoryBytes;
        private final long cenOldEstimateBytes;
        private final long openNanos;
        private final ConcurrentHashMap<String, LongAdder> entryReadCounts = new ConcurrentHashMap<>();
        private final LongAdder totalBytesDecompressed = new LongAdder();
        private final LongAdder cacheHits = new LongAdder();
        private final LongAdder rafSyncNanos = new LongAdder();

        Tracker(String archivePath, int centralDirectoryEntryCount,
                long cenMemoryBytes, long cenOldEstimateBytes, long openNanos) {
            this.archivePath = archivePath;
            this.centralDirectoryEntryCount = centralDirectoryEntryCount;
            this.cenMemoryBytes = cenMemoryBytes;
            this.cenOldEstimateBytes = cenOldEstimateBytes;
            this.openNanos = openNanos;
        }

        void recordRead(String entryName) {
            entryReadCounts.computeIfAbsent(entryName, k -> new LongAdder()).increment();
        }

        void recordBytes(long bytes) {
            totalBytesDecompressed.add(bytes);
        }

        void recordCacheHit() {
            cacheHits.increment();
        }

        void recordRafNanos(long nanos) {
            rafSyncNanos.add(nanos);
        }

        Snapshot snapshot() {
            Map<String, Long> counts = new HashMap<>();
            long totalReads = 0;
            for (var e : entryReadCounts.entrySet()) {
                long v = e.getValue().sum();
                counts.put(e.getKey(), v);
                totalReads += v;
            }
            return new Snapshot(
                    archivePath,
                    centralDirectoryEntryCount,
                    totalReads,
                    counts.size(),
                    totalBytesDecompressed.sum(),
                    cacheHits.sum(),
                    rafSyncNanos.sum(),
                    openNanos,
                    cenMemoryBytes,
                    cenOldEstimateBytes,
                    counts);
        }
    }

    record Snapshot(
            String archivePath,
            int centralDirectoryEntryCount,
            long totalReads,
            long uniqueReads,
            long totalBytesDecompressed,
            long cacheHits,
            long rafSyncNanos,
            long openNanos,
            long cenMemoryBytes,
            long cenOldEstimateBytes,
            Map<String, Long> entryReadCounts) {
    }

    private static void dumpReport() {
        String filePath = System.getProperty("rozip.stats.file");
        if (filePath != null) {
            try (PrintStream out = new PrintStream(
                    Files.newOutputStream(Path.of(filePath),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
                writeReport(out);
            } catch (IOException e) {
                System.err.println("[rozip] Failed to write stats to " + filePath + ": " + e.getMessage());
                writeReport(System.err);
            }
        } else {
            writeReport(System.err);
        }
    }

    private static void writeReport(PrintStream out) {
        List<Snapshot> snapshots;
        synchronized (completed) {
            snapshots = new ArrayList<>(completed);
        }

        long totalFsOpened = instancesCreated.get();
        int stillOpen = currentlyOpen.get();

        long grandTotalReads = 0;
        long grandTotalBytes = 0;
        long grandTotalCacheHits = 0;
        long grandTotalRafNanos = 0;
        long grandTotalOpenNanos = 0;
        long grandTotalCenEntries = 0;
        long grandTotalCenMemory = 0;
        long grandTotalCenOldEstimate = 0;
        Map<String, Long> globalEntryCounts = new HashMap<>();

        for (Snapshot s : snapshots) {
            grandTotalReads += s.totalReads;
            grandTotalBytes += s.totalBytesDecompressed;
            grandTotalCacheHits += s.cacheHits;
            grandTotalRafNanos += s.rafSyncNanos;
            grandTotalOpenNanos += s.openNanos;
            grandTotalCenEntries += s.centralDirectoryEntryCount;
            grandTotalCenMemory += s.cenMemoryBytes;
            grandTotalCenOldEstimate += s.cenOldEstimateBytes;
            for (var e : s.entryReadCounts.entrySet()) {
                globalEntryCounts.merge(e.getKey(), e.getValue(), Long::sum);
            }
        }

        out.println("[rozip] === Aggregate Stats ===");
        out.println("[rozip]   filesystems opened: " + totalFsOpened
                + ", closed: " + snapshots.size()
                + ", still open at shutdown: " + stillOpen);
        out.println("[rozip]   total readEntryData() calls: " + grandTotalReads
                + " (" + globalEntryCounts.size() + " unique entry names)");
        if (globalEntryCounts.size() > 0) {
            out.printf("[rozip]   re-read ratio: %.2f%n",
                    grandTotalReads / (double) globalEntryCounts.size());
        }
        if (grandTotalReads > 0) {
            out.printf("[rozip]   cache hits: %d / %d (%.1f%%)%n",
                    grandTotalCacheHits, grandTotalReads,
                    grandTotalCacheHits * 100.0 / grandTotalReads);
        }
        out.println("[rozip]   total bytes decompressed: " + formatBytes(grandTotalBytes));
        out.printf("[rozip]   total open time: %.1f ms%n", grandTotalOpenNanos / 1_000_000.0);
        out.printf("[rozip]   total raf sync time: %.1f ms%n", grandTotalRafNanos / 1_000_000.0);
        out.println("[rozip]   total central directory entries: " + grandTotalCenEntries);
        out.println("[rozip]   CEN memory: " + formatBytes(grandTotalCenMemory)
                + " (estimated " + formatBytes(grandTotalCenOldEstimate) + " with old representation, "
                + (grandTotalCenOldEstimate > 0
                        ? String.format("%.0f%% reduction", (1 - grandTotalCenMemory / (double) grandTotalCenOldEstimate) * 100)
                        : "N/A")
                + ")");

        if (!globalEntryCounts.isEmpty()) {
            out.println("[rozip]   top-10 most-read entries (across all JARs):");
            globalEntryCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long> comparingByValue().reversed())
                    .limit(10)
                    .forEach(e -> out.println("[rozip]     " + e.getKey() + ": " + e.getValue()));
        }

        List<Snapshot> withReads = snapshots.stream()
                .filter(s -> s.totalReads > 0)
                .sorted((a, b) -> Long.compare(b.totalReads, a.totalReads))
                .toList();

        if (!withReads.isEmpty()) {
            out.println("[rozip]");
            out.println("[rozip] === Per-Filesystem Breakdown (" + withReads.size() + " with reads) ===");
            for (Snapshot s : withReads) {
                out.println("[rozip]   " + s.archivePath);
                out.println("[rozip]     CEN entries: " + s.centralDirectoryEntryCount
                        + ", reads: " + s.totalReads
                        + " (" + s.uniqueReads + " unique)"
                        + ", re-read ratio: " + String.format("%.2f",
                                s.uniqueReads > 0 ? s.totalReads / (double) s.uniqueReads : 0));
                out.printf("[rozip]     cache hits: %d / %d (%.1f%%)%n",
                        s.cacheHits, s.totalReads,
                        s.totalReads > 0 ? s.cacheHits * 100.0 / s.totalReads : 0);
                out.println("[rozip]     bytes decompressed: " + formatBytes(s.totalBytesDecompressed)
                        + ", open time: " + String.format("%.1f ms", s.openNanos / 1_000_000.0)
                        + ", raf sync time: " + String.format("%.1f ms", s.rafSyncNanos / 1_000_000.0));
                if (!s.entryReadCounts.isEmpty()) {
                    out.print("[rozip]     top reads: ");
                    s.entryReadCounts.entrySet().stream()
                            .sorted(Map.Entry.<String, Long> comparingByValue().reversed())
                            .limit(5)
                            .forEach(e -> out.print(e.getKey() + " (" + e.getValue() + ") "));
                    out.println();
                }
            }
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
    }

    private RozipStats() {
    }
}
