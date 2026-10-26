package com.idleo.controlplane.hosts;

import java.util.Optional;
import java.util.Set;

/**
 * The abuse blocklist as a signed file of its own in the release feed (ADR 0038, ADR 0041): the newest the hosts module
 * has read from the {@code releases} bucket and verified against the release key. The agent gateway asks it, once per
 * connection and whenever a new one is cached ({@link BlocklistCached}), what to send an agent whose runner holds an older
 * one; the trust module asks which builds the published list already carries; the images module matches renters' own
 * images against every program it lists (ADR 0042).
 */
public interface Blocklists {

    /**
     * A list to send in {@code UpdateBlocklist}: its version (a date, or the time it was issued, which sort as text), the
     * file exactly as signed and its signature.
     */
    record Published(String version, byte[] blocklist, byte[] signature) {

        public Published {
            blocklist = blocklist.clone();
            signature = signature.clone();
        }

        @Override
        public byte[] blocklist() {
            return blocklist.clone();
        }

        @Override
        public byte[] signature() {
            return signature.clone();
        }
    }

    /** The list for an agent whose runner uses version {@code held} (empty if it has none): only if it is newer. */
    Optional<Published> newerThan(String held);

    /** The SHA-256 of every build the published list names as learned from a review item, lower-case hex. */
    Set<String> learnedBuilds();

    /**
     * Every program the cached list names by its executable's SHA-256 (its {@code hashes} and its {@code learned}
     * builds), lower-case hex, with the list's version.
     */
    record Programs(String version, Set<String> sha256) {

        public Programs {
            sha256 = Set.copyOf(sha256);
        }
    }

    /** The programs of the newest list cached; empty while none has been verified. */
    Optional<Programs> programs();
}
