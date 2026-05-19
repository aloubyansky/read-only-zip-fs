package io.github.aloubyansky.rozip;

/**
 * Little-endian byte-reading utilities for ZIP binary parsing.
 */
final class LittleEndian {

    private LittleEndian() {
    }

    static int readUint16(byte[] buf, int off) {
        return (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8);
    }

    static long readUint32(byte[] buf, int off) {
        return (buf[off] & 0xFFL)
                | ((buf[off + 1] & 0xFFL) << 8)
                | ((buf[off + 2] & 0xFFL) << 16)
                | ((buf[off + 3] & 0xFFL) << 24);
    }

    static int readInt32(byte[] buf, int off) {
        return (buf[off] & 0xFF)
                | ((buf[off + 1] & 0xFF) << 8)
                | ((buf[off + 2] & 0xFF) << 16)
                | ((buf[off + 3] & 0xFF) << 24);
    }

    // Returns a signed long; values with bit 63 set will appear negative.
    // In practice ZIP64 fields never exceed ~2^63, same limitation as the JDK's ZipUtils.
    static long readUint64(byte[] buf, int off) {
        return (buf[off] & 0xFFL)
                | ((buf[off + 1] & 0xFFL) << 8)
                | ((buf[off + 2] & 0xFFL) << 16)
                | ((buf[off + 3] & 0xFFL) << 24)
                | ((buf[off + 4] & 0xFFL) << 32)
                | ((buf[off + 5] & 0xFFL) << 40)
                | ((buf[off + 6] & 0xFFL) << 48)
                | ((buf[off + 7] & 0xFFL) << 56);
    }
}
