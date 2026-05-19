
package io.github.aloubyansky.rozip;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author Alexey Loubyansky
 */
public class ZipUtils {

    private static final String JAR_URI_PREFIX = "jar:";
    private static final Map<String, Object> DEFAULT_OWNER_ENV = new HashMap<>();
    private static final Map<String, Object> CREATE_ENV = new HashMap<>();

    static {
        String user = System.getProperty("user.name");
        DEFAULT_OWNER_ENV.put("defaultOwner", user);
        DEFAULT_OWNER_ENV.put("defaultGroup", user);

        CREATE_ENV.putAll(DEFAULT_OWNER_ENV);
        CREATE_ENV.put("create", "true");
    }

    /**
     * Opens a read-only, non-interruptible {@link FileSystem} for the given ZIP/JAR file.
     * <p>
     * Unlike {@link #newFileSystem(Path)}, this implementation uses
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
