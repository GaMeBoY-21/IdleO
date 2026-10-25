package com.idleo.controlplane.hosts.internal;

import java.security.PublicKey;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The abuse blocklist as the release feed carries it (ADR 0038): {@code stable/blocklist.json} and its {@code .sig}, signed
 * with the release key like the app's manifest. Only its version and the builds it has learned from review items matter
 * here; what the runner makes of the rest is the runner's to judge.
 *
 * @param version a date, or the time the list was issued ({@code 2026-10-25T09:30:00Z}); both sort as text, so a larger one
 *     is newer
 * @param bytes the list exactly as signed, which is what agents verify again
 * @param learned the SHA-256 of each build an admin confirmed from a review item
 */
record BlocklistFile(String version, byte[] bytes, byte[] signature, Set<String> learned) {

    static final String KEY = ReleaseManifest.CHANNEL + "/blocklist.json";
    static final String SIGNATURE_KEY = KEY + ".sig";
    /** The most a list may be; it is names, hashes and domains, a few kilobytes. */
    static final int MAX_BYTES = 1024 * 1024;

    private static final Pattern VERSION =
            Pattern.compile("\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])(T([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\dZ)?");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    BlocklistFile {
        bytes = bytes.clone();
        signature = signature.clone();
        learned = Set.copyOf(learned);
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public byte[] signature() {
        return signature.clone();
    }

    /**
     * Verifies {@code signature} over {@code bytes} with {@code publicKey}, then reads the version and the learned builds.
     *
     * @throws ReleaseManifest.Rejected if the signature doesn't verify, or it isn't a list
     */
    static BlocklistFile verify(byte[] bytes, byte[] signature, PublicKey publicKey) throws ReleaseManifest.Rejected {
        ReleaseManifest.verifySignature(bytes, signature, publicKey);
        if (bytes.length > MAX_BYTES) {
            throw new ReleaseManifest.Rejected("the blocklist is more than " + MAX_BYTES + " bytes");
        }
        JsonNode root;
        try {
            root = JSON.readTree(bytes);
        } catch (RuntimeException e) {
            throw new ReleaseManifest.Rejected("the blocklist isn't JSON");
        }
        var version = root.path("version").asString("");
        if (!isVersion(version)) {
            throw new ReleaseManifest.Rejected("the blocklist has no version like 2026-10-25 or 2026-10-25T09:30:00Z");
        }
        var learned = new HashSet<String>();
        for (var entry : root.path("learned")) {
            var sha256 = entry.path("sha256").asString("");
            if (!SHA256.matcher(sha256).matches()) {
                throw new ReleaseManifest.Rejected("a learned entry's sha256 isn't 64 lower-case hex digits");
            }
            learned.add(sha256);
        }
        return new BlocklistFile(version, bytes, signature, learned);
    }

    static boolean isVersion(String text) {
        return VERSION.matcher(text).matches();
    }

    /** Whether this list is newer than the one an agent holds ({@code held} empty, or not a version, means none). */
    boolean newerThan(String held) {
        return !isVersion(held) || version.compareTo(held) > 0;
    }
}
