package com.idleo.controlplane.hosts;

import java.util.Optional;
import java.util.UUID;

/**
 * The signed release feed for the Windows app (docs/prompts/windows-app.md, ADR 0034): the latest manifest the hosts
 * module has read from the {@code releases} bucket and verified against the release key. The agent gateway asks it,
 * once per connection and whenever a new manifest is cached ({@link AgentReleaseCached}), what to offer a host.
 */
public interface AgentReleases {

    /**
     * An update to send in {@code UpdateAgent}: the manifest exactly as signed, its signature, and download links
     * valid for an hour.
     */
    record Update(String version, byte[] manifest, byte[] signature, String appUrl, String distroUrl) {

        public Update {
            manifest = manifest.clone();
            signature = signature.clone();
        }

        @Override
        public byte[] manifest() {
            return manifest.clone();
        }

        @Override
        public byte[] signature() {
            return signature.clone();
        }
    }

    /**
     * The update for a host whose agent runs {@code agentVersion}: only when the release is newer, the host is in its
     * rollout, and the host hasn't rolled that version back; empty otherwise, or when there's no release.
     */
    Optional<Update> updateFor(UUID hostId, String agentVersion);
}
