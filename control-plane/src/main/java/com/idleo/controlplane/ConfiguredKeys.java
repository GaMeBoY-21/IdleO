package com.idleo.controlplane;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * A 256-bit key from the configuration: base64 of 32 bytes, or a {@code file:} location holding that (the local
 * stack's keys live under the gitignored private/dev-ca/, made by {@code scripts/dev-ca.sh}). The admin console's TOTP
 * key, the codes' HMAC key and the PAN key (ADR 0040) are read here.
 */
public final class ConfiguredKeys {

    public static final int KEY_BYTES = 32;

    private ConfiguredKeys() {}

    /** The key's bytes; {@code setting} names it in the message when it can't be read. */
    public static byte[] read(String configured, String setting) {
        var text = configured.strip();
        if (text.startsWith("file:")) {
            try {
                text = Files.readString(Path.of(text.substring("file:".length())))
                        .strip();
            } catch (IOException e) {
                throw new UncheckedIOException(setting + " names a file that can't be read", e);
            }
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(setting + " must be 32 bytes in base64", e);
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException(setting + " must be 32 bytes in base64; it is " + bytes.length + " bytes");
        }
        return bytes;
    }
}
