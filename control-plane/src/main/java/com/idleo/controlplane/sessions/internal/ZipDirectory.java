package com.idleo.controlplane.sessions.internal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The file names inside a zip, read from its central directory alone (ADR 0022): the end of the file, where the directory's
 * place is written, and then the directory. Never the whole zip, which can be tens of gigabytes. Zip64 (a zip over 4 GiB, or
 * with over 65,535 files) is read too. Folders, and the files macOS leaves in a zip, are left out.
 */
final class ZipDirectory {

    /** Reads {@code length} bytes of the zip from {@code from}. */
    interface Ranges {
        byte[] read(long from, int length);
    }

    /** The zip can't be read as one. */
    static final class Unreadable extends Exception {
        private static final long serialVersionUID = 1L;

        Unreadable(String why) {
            super(why);
        }
    }

    private static final int END_RECORD = 0x06054b50;
    private static final int END_RECORD_64 = 0x06064b50;
    private static final int LOCATOR_64 = 0x07064b50;
    private static final int ENTRY = 0x02014b50;
    /** The end record is 22 bytes and may be followed by a comment of up to 65,535. */
    private static final int TAIL = 22 + 65_535;
    /** A directory larger than this is refused rather than read: 1,000 files take well under 1 MiB. */
    private static final int MOST_DIRECTORY_BYTES = 8 << 20;

    private static final int MOST_NAMES = 20_000;

    private ZipDirectory() {}

    static List<String> names(Ranges zip, long size) throws Unreadable {
        if (size < 22) {
            throw new Unreadable("too small to be a zip");
        }
        int tailLength = (int) Math.min(size, TAIL);
        long tailStart = size - tailLength;
        var tail = le(zip.read(tailStart, tailLength));
        int end = -1;
        for (int at = tailLength - 22; at >= 0; at--) {
            if (tail.getInt(at) == END_RECORD) {
                end = at;
                break;
            }
        }
        if (end < 0) {
            throw new Unreadable("no end of central directory");
        }
        long entries = Short.toUnsignedLong(tail.getShort(end + 10));
        long length = Integer.toUnsignedLong(tail.getInt(end + 12));
        long offset = Integer.toUnsignedLong(tail.getInt(end + 16));
        if (entries == 0xFFFF || length == 0xFFFF_FFFFL || offset == 0xFFFF_FFFFL) {
            // Zip64: the end record's locator is just before it, and points at the zip64 end record.
            if (end < 20 || tail.getInt(end - 20) != LOCATOR_64) {
                throw new Unreadable("a zip64 zip without its locator");
            }
            long record64 = tail.getLong(end - 20 + 8);
            if (record64 < 0 || record64 + 56 > size) {
                throw new Unreadable("the zip64 end record is outside the file");
            }
            var record = le(zip.read(record64, 56));
            if (record.getInt(0) != END_RECORD_64) {
                throw new Unreadable("the zip64 end record isn't there");
            }
            entries = record.getLong(32);
            length = record.getLong(40);
            offset = record.getLong(48);
        }
        if (length < 0 || offset < 0 || offset + length > size || length > MOST_DIRECTORY_BYTES) {
            throw new Unreadable("the central directory is outside the file, or too large");
        }
        var directory = le(zip.read(offset, (int) length));
        var names = new ArrayList<String>();
        int at = 0;
        for (long seen = 0; seen < entries; seen++) {
            if (at + 46 > directory.limit() || directory.getInt(at) != ENTRY) {
                throw new Unreadable("the central directory is damaged");
            }
            int nameLength = Short.toUnsignedInt(directory.getShort(at + 28));
            int extraLength = Short.toUnsignedInt(directory.getShort(at + 30));
            int commentLength = Short.toUnsignedInt(directory.getShort(at + 32));
            if (at + 46 + nameLength > directory.limit()) {
                throw new Unreadable("the central directory is damaged");
            }
            var name = new String(directory.array(), at + 46, nameLength, StandardCharsets.UTF_8).replace('\\', '/');
            at += 46 + nameLength + extraLength + commentLength;
            if (!name.endsWith("/") && !name.startsWith("__MACOSX/") && !name.endsWith(".DS_Store")) {
                names.add(name);
                if (names.size() > MOST_NAMES) {
                    throw new Unreadable("more files than an upload holds");
                }
            }
        }
        return names;
    }

    private static ByteBuffer le(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
}
