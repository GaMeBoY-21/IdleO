package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** The names in a zip, read from its central directory by range (ADR 0022), never the whole file. */
class ZipDirectoryTests {

    /** A zip of these names, each with this many bytes of content that compresses badly. */
    private static byte[] zip(List<String> names, int contentBytes) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            var random = new java.util.Random(7);
            for (var name : names) {
                zip.putNextEntry(new ZipEntry(name));
                if (!name.endsWith("/")) {
                    var content = new byte[contentBytes];
                    random.nextBytes(content);
                    zip.write(content);
                }
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** A reader of these bytes that remembers how much it was asked for. */
    private static final class Counting implements ZipDirectory.Ranges {
        final byte[] bytes;
        long read;

        Counting(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public byte[] read(long from, int length) {
            read += length;
            return java.util.Arrays.copyOfRange(bytes, (int) from, (int) from + length);
        }
    }

    @Test
    void theFileNamesOfAZipAreReadFromItsDirectoryAloneAndFoldersAndMacJunkAreLeftOut() throws Exception {
        var bytes = zip(
                List.of(
                        "model/",
                        "model/config.json",
                        "model/model-00001-of-00002.safetensors",
                        "model/sub/tokenizer.json",
                        "__MACOSX/model/._config.json",
                        "model/.DS_Store"),
                200_000);
        var reader = new Counting(bytes);

        var names = ZipDirectory.names(reader, bytes.length);

        assertThat(names)
                .containsExactly(
                        "model/config.json", "model/model-00001-of-00002.safetensors", "model/sub/tokenizer.json");
        assertThat(reader.read)
                .as("the end of the file and the directory, not the %d bytes of content".formatted(bytes.length))
                .isLessThan(bytes.length / 10);
    }

    @Test
    void aZipWithACommentAfterItsEndRecordIsStillRead() throws Exception {
        var plain = zip(List.of("config.json"), 10);
        var comment = "made on a laptop".getBytes(StandardCharsets.UTF_8);
        var bytes = java.util.Arrays.copyOf(plain, plain.length + comment.length);
        System.arraycopy(comment, 0, bytes, plain.length, comment.length);
        // The end record's comment length, 2 bytes at offset 20 of the record.
        var le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        le.putShort(plain.length - 2, (short) comment.length);

        assertThat(ZipDirectory.names(new Counting(bytes), bytes.length)).containsExactly("config.json");
    }

    /** The same zip, written as zip64: the end record says "see the zip64 one", which holds the real numbers. */
    private static byte[] zip64(byte[] plain) {
        var le = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN);
        int end = plain.length - 22;
        long entries = Short.toUnsignedLong(le.getShort(end + 10));
        long length = Integer.toUnsignedLong(le.getInt(end + 12));
        long offset = Integer.toUnsignedLong(le.getInt(end + 16));
        var out = ByteBuffer.allocate(plain.length + 56 + 20).order(ByteOrder.LITTLE_ENDIAN);
        out.put(plain, 0, end);
        long record64 = out.position();
        out.putInt(0x06064b50)
                .putLong(44)
                .putShort((short) 45)
                .putShort((short) 45)
                .putInt(0)
                .putInt(0);
        out.putLong(entries).putLong(entries).putLong(length).putLong(offset);
        out.putInt(0x07064b50).putInt(0).putLong(record64).putInt(1);
        out.putInt(0x06054b50)
                .putShort((short) 0)
                .putShort((short) 0)
                .putShort((short) 0xFFFF)
                .putShort((short) 0xFFFF);
        out.putInt(-1).putInt(-1).putShort((short) 0);
        return out.array();
    }

    @Test
    void aZip64ZipIsRead() throws Exception {
        var bytes = zip64(zip(List.of("a/config.json", "a/w.safetensors"), 100));

        assertThat(ZipDirectory.names(new Counting(bytes), bytes.length))
                .containsExactly("a/config.json", "a/w.safetensors");
    }

    @Test
    void whatIsNotAZipIsUnreadableInEveryWay() throws Exception {
        var notZip = "%PDF-1.7 this is a paper, not a zip".repeat(20).getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ZipDirectory.names(new Counting(notZip), notZip.length))
                .isInstanceOf(ZipDirectory.Unreadable.class);

        assertThatThrownBy(() -> ZipDirectory.names(new Counting(new byte[5]), 5))
                .isInstanceOf(ZipDirectory.Unreadable.class);

        // A directory that points outside the file.
        var plain = zip(List.of("config.json"), 10);
        var lie = plain.clone();
        ByteBuffer.wrap(lie).order(ByteOrder.LITTLE_ENDIAN).putInt(lie.length - 22 + 16, lie.length + 1000);
        assertThatThrownBy(() -> ZipDirectory.names(new Counting(lie), lie.length))
                .isInstanceOf(ZipDirectory.Unreadable.class);

        // A damaged entry in the directory.
        var damaged = plain.clone();
        int directory = ByteBuffer.wrap(damaged).order(ByteOrder.LITTLE_ENDIAN).getInt(damaged.length - 22 + 16);
        damaged[directory] = 0;
        assertThatThrownBy(() -> ZipDirectory.names(new Counting(damaged), damaged.length))
                .isInstanceOf(ZipDirectory.Unreadable.class);
    }

    @Test
    void aThousandFilesAreListed() throws Exception {
        var names = new ArrayList<String>();
        for (int i = 0; i < 1_000; i++) {
            names.add("m/shard-%04d.bin".formatted(i));
        }
        var bytes = zip(names, 1);

        assertThat(ZipDirectory.names(new Counting(bytes), bytes.length)).hasSize(1_000);
    }
}
