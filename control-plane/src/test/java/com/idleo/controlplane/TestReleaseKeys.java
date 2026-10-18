package com.idleo.controlplane;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;

/**
 * A release signing key for tests (ADR 0034), made once per test run: the control plane trusts its public half
 * ({@code hosts.releases.public-key}), and tests sign manifests with its private half, as {@code idleo-release sign}
 * does.
 */
public final class TestReleaseKeys {

    private static final TestReleaseKeys INSTANCE = new TestReleaseKeys();

    private final KeyPair keys;

    private TestReleaseKeys() {
        try {
            keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static TestReleaseKeys get() {
        return INSTANCE;
    }

    /** A fresh key pair, for a manifest signed with the wrong key. */
    public static KeyPair another() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The raw 32-byte public key in base64, as {@code agent/release-keys/release.pub} holds one. */
    public String publicKeyBase64() {
        return rawBase64(keys);
    }

    public static String rawBase64(KeyPair pair) {
        var encoded = pair.getPublic().getEncoded();
        return Base64.getEncoder().encodeToString(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
    }

    /** The raw 64-byte Ed25519 signature over {@code bytes}. */
    public byte[] sign(byte[] bytes) {
        return sign(keys, bytes);
    }

    public static byte[] sign(KeyPair pair, byte[] bytes) {
        try {
            var signer = Signature.getInstance("Ed25519");
            signer.initSign(pair.getPrivate());
            signer.update(bytes);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
