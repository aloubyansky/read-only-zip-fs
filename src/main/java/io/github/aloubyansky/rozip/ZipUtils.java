
package io.github.aloubyansky.rozip;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;

/**
 * ZIP-related utils
 */
public class ZipUtils {

    /**
     * Opens a read-only, non-interruptible {@link FileSystem} for the given ZIP/JAR file.
     * <p>
     * This implementation uses
     * {@link java.io.RandomAccessFile} instead of {@link java.nio.channels.FileChannel},
     * making it immune to thread-interrupt-induced channel closures
     * (<a href="https://bugs.openjdk.org/browse/JDK-8316882">JDK-8316882</a>).
     * <p>
     * Entry data is fully materialized in memory on each read (both the
     * compressed and uncompressed bytes). Individual entries are capped at
     * 256 MB uncompressed. Callers reading many entries concurrently against
     * the same filesystem should be aware of the resulting heap usage.
     *
     * @param path the ZIP or JAR file
     * @return a read-only {@link FileSystem} instance
     * @throws IOException if the file cannot be read or is not a valid ZIP archive
     */
    public static FileSystem newReadOnlyFileSystem(Path path) throws IOException {
        return ReadOnlyZipFileSystem.open(path);
    }
}
