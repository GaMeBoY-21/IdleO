package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.SemanticVersion;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A release manifest for the Windows app (ADR 0034): {@code stable/latest.json}, signed with Ed25519 over its exact
 * bytes. Only a manifest whose signature verifies against the release key is ever read; its fields are then checked
 * before anything is trusted. The download links are not part of it: the size and SHA-256 of each file are.
 *
 * @param bytes the manifest exactly as signed, which is what agents verify again
 * @param signature the raw 64-byte Ed25519 signature
 */
record ReleaseManifest(
        byte[] bytes,
        byte[] signature,
        String version,
        SemanticVersion semver,
        String channel,
        Instant releasedAt,
        int rolloutPercent,
        int minWindowsBuild,
        Artifact app,
        Artifact distro,
        String distroVersion,
        Artifact setup) {

    /** The channel the control plane serves. */
    static final String CHANNEL = "stable";

    /** One downloadable file: its object key under the channel's folder, its SHA-256 (lowercase hex) and size. */
    record Artifact(String file, String sha256, long size) {}

    /** Why a manifest was refused. Nothing from a refused manifest is used. */
    static final class Rejected extends Exception {
        private static final long serialVersionUID = 1L;

        Rejected(String message) {
            super(message);
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    /** A key under the channel's folder: no leading slash, no "..", no backslash. */
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*");
    /** X.509 SubjectPublicKeyInfo's fixed prefix for a raw Ed25519 key (RFC 8410). */
    private static final byte[] ED25519_SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    /**
     * Checks that {@code signature} (the raw 64 bytes) is the release key's Ed25519 signature over exactly {@code bytes}: the
     * one check behind the manifest and the abuse blocklist, which are signed the same way (ADR 0034).
     *
     * @throws Rejected if it doesn't verify
     */
    static void verifySignature(byte[] bytes, byte[] signature, PublicKey publicKey) throws Rejected {
        try {
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(bytes);
            if (signature.length != 64 || !verifier.verify(signature)) {
                throw new Rejected("the signature doesn't verify against the release key");
            }
        } catch (GeneralSecurityException e) {
            throw new Rejected("the signature couldn't be checked: " + e.getMessage());
        }
    }

    /**
     * Verifies {@code signature} over {@code bytes} with {@code publicKey}, then reads and checks the fields.
     *
     * @throws Rejected if the signature doesn't verify or a field is missing or out of range
     */
    static ReleaseManifest verify(byte[] bytes, byte[] signature, PublicKey publicKey) throws Rejected {
        verifySignature(bytes, signature, publicKey);
        JsonNode root;
        try {
            root = JSON.readTree(bytes);
        } catch (JacksonException e) {
            throw new Rejected("the manifest isn't JSON");
        }
        var version = text(root, "version");
        var semver = SemanticVersion.parse(version).orElseThrow(() -> new Rejected("version isn't semver"));
        var channel = text(root, "channel");
        if (!CHANNEL.equals(channel)) {
            throw new Rejected("the channel is " + channel + ", not " + CHANNEL);
        }
        Instant releasedAt;
        try {
            releasedAt = Instant.parse(text(root, "released_at"));
        } catch (java.time.format.DateTimeParseException e) {
            throw new Rejected("released_at isn't an RFC 3339 time");
        }
        int rollout = number(root, "rollout_percent", 0, 100);
        int minWindowsBuild = number(root, "min_windows_build", 0, Integer.MAX_VALUE);
        var distro = root.get("distro");
        if (distro == null || !distro.isObject()) {
            throw new Rejected("distro is missing");
        }
        return new ReleaseManifest(
                bytes.clone(),
                signature.clone(),
                version,
                semver,
                channel,
                releasedAt,
                rollout,
                minWindowsBuild,
                artifact(root, "app"),
                artifact(root, "distro"),
                text(distro, "version"),
                artifact(root, "setup"));
    }

    /**
     * Whether a host is in the rollout: the first 8 bytes of SHA-256 over the host id's text and the version's, as
     * an unsigned number, modulo 100, below {@code rolloutPercent}. The same host and version always land in the same
     * bucket, so raising the percentage only ever adds hosts.
     */
    boolean includes(UUID hostId) {
        return bucket(hostId, version) < rolloutPercent;
    }

    static int bucket(UUID hostId, String version) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest((hostId.toString() + version).getBytes(StandardCharsets.UTF_8));
            long first = 0;
            for (int i = 0; i < 8; i++) {
                first = (first << 8) | (digest[i] & 0xff);
            }
            return (int) Long.remainderUnsigned(first, 100);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The release key from its text form: the raw 32-byte Ed25519 public key in base64, as {@code
     * agent/release-keys/release.pub} holds it.
     *
     * @throws IllegalArgumentException if it isn't one
     */
    static PublicKey publicKey(String base64) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the release public key isn't base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalArgumentException("the release public key isn't 32 bytes");
        }
        var spki = new byte[ED25519_SPKI_PREFIX.length + raw.length];
        System.arraycopy(ED25519_SPKI_PREFIX, 0, spki, 0, ED25519_SPKI_PREFIX.length);
        System.arraycopy(raw, 0, spki, ED25519_SPKI_PREFIX.length, raw.length);
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("the release public key isn't an Ed25519 key", e);
        }
    }

    private static Artifact artifact(JsonNode root, String name) throws Rejected {
        var node = root.get(name);
        if (node == null || !node.isObject()) {
            throw new Rejected(name + " is missing");
        }
        var file = text(node, "file");
        if (!FILE.matcher(file).matches() || file.contains("..")) {
            throw new Rejected(name + ".file isn't a plain key under the channel's folder");
        }
        var sha = text(node, "sha256");
        if (!SHA256.matcher(sha).matches()) {
            throw new Rejected(name + ".sha256 isn't 64 lowercase hex digits");
        }
        var size = node.get("size");
        if (size == null || !size.canConvertToLong() || size.asLong() <= 0) {
            throw new Rejected(name + ".size isn't a positive number");
        }
        return new Artifact(file, sha, size.asLong());
    }

    private static String text(JsonNode node, String field) throws Rejected {
        var value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new Rejected(field + " is missing");
        }
        return value.asString();
    }

    private static int number(JsonNode node, String field, int min, int max) throws Rejected {
        var value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new Rejected(field + " is missing");
        }
        int number = value.asInt();
        if (number < min || number > max) {
            throw new Rejected(field + " is out of range");
        }
        return number;
    }
}
