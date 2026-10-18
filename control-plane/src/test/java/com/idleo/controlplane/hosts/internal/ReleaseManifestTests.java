package com.idleo.controlplane.hosts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idleo.controlplane.TestReleaseKeys;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The release feed's checks (ADR 0034): only a manifest whose Ed25519 signature verifies against the release key is
 * read, its fields are checked, an older one never replaces a newer one, and the rollout's buckets are stable and even.
 */
class ReleaseManifestTests {

    static String manifest(String version, int rollout) {
        return """
                {"version":"%s","channel":"stable","released_at":"2026-10-18T12:00:00Z","rollout_percent":%d,
                 "min_windows_build":19045,
                 "app":{"file":"%s/idleo-app-%s.zip","sha256":"%s","size":9800000},
                 "distro":{"version":"0.1.0","file":"distro/idleo-distro-0.1.0.tar.gz","sha256":"%s","size":98000000},
                 "setup":{"file":"%s/IdleO-Setup.exe","sha256":"%s","size":9700000}}
                """.formatted(
                        version, rollout, version, version, "a".repeat(64), "b".repeat(64), version, "c".repeat(64));
    }

    private static ReleaseManifest verify(String text) throws ReleaseManifest.Rejected {
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        return ReleaseManifest.verify(
                bytes,
                TestReleaseKeys.get().sign(bytes),
                ReleaseManifest.publicKey(TestReleaseKeys.get().publicKeyBase64()));
    }

    @Test
    void a_good_manifest_verifies_and_reads() throws Exception {
        var manifest = verify(manifest("0.2.0", 10));
        assertThat(manifest.version()).isEqualTo("0.2.0");
        assertThat(manifest.rolloutPercent()).isEqualTo(10);
        assertThat(manifest.minWindowsBuild()).isEqualTo(19045);
        assertThat(manifest.app().file()).isEqualTo("0.2.0/idleo-app-0.2.0.zip");
        assertThat(manifest.distroVersion()).isEqualTo("0.1.0");
        assertThat(manifest.setup().size()).isEqualTo(9_700_000);
    }

    @Test
    void a_tampered_manifest_is_refused() {
        var bytes = manifest("0.2.0", 10).getBytes(StandardCharsets.UTF_8);
        var signature = TestReleaseKeys.get().sign(bytes);
        var tampered = manifest("0.2.0", 100).getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ReleaseManifest.verify(
                        tampered,
                        signature,
                        ReleaseManifest.publicKey(TestReleaseKeys.get().publicKeyBase64())))
                .isInstanceOf(ReleaseManifest.Rejected.class)
                .hasMessageContaining("signature");
    }

    @Test
    void a_manifest_signed_with_another_key_is_refused() {
        var bytes = manifest("0.2.0", 10).getBytes(StandardCharsets.UTF_8);
        var signature = TestReleaseKeys.sign(TestReleaseKeys.another(), bytes);
        assertThatThrownBy(() -> ReleaseManifest.verify(
                        bytes,
                        signature,
                        ReleaseManifest.publicKey(TestReleaseKeys.get().publicKeyBase64())))
                .isInstanceOf(ReleaseManifest.Rejected.class);
    }

    @Test
    void a_signed_manifest_with_bad_fields_is_refused() {
        assertThatThrownBy(() -> verify(manifest("latest", 10))).hasMessageContaining("semver");
        assertThatThrownBy(() -> verify(manifest("0.2.0", 101))).hasMessageContaining("rollout_percent");
        assertThatThrownBy(() -> verify(manifest("0.2.0", 10).replace("\"stable\"", "\"beta\"")))
                .hasMessageContaining("channel");
        assertThatThrownBy(() -> verify(manifest("0.2.0", 10).replace("0.2.0/IdleO", "../IdleO")))
                .hasMessageContaining("setup.file");
        assertThatThrownBy(() -> verify(manifest("0.2.0", 10).replace("a".repeat(64), "A".repeat(64))))
                .hasMessageContaining("app.sha256");
    }

    @Test
    void the_feed_keeps_the_newer_manifest_when_an_older_one_is_replayed() {
        var announced = new ArrayList<Object>();
        ApplicationEventPublisher events = new ApplicationEventPublisher() {
            @Override
            public void publishEvent(ApplicationEvent event) {
                announced.add(event);
            }

            @Override
            public void publishEvent(Object event) {
                announced.add(event);
            }
        };
        var feed = new AgentReleaseFeed(
                new AgentReleaseProperties(
                        URI.create("http://localhost:1"),
                        "releases",
                        "us-east-1",
                        "key",
                        "secret",
                        TestReleaseKeys.get().publicKeyBase64(),
                        Duration.ofHours(1),
                        Duration.ofHours(1)),
                null,
                events);
        try {
            accept(feed, manifest("0.3.0", 10));
            accept(feed, manifest("0.2.0", 100));
            assertThat(feed.latest()).get().extracting(ReleaseManifest::version).isEqualTo("0.3.0");
            assertThat(announced).hasSize(1);
            // The same version again with a raised rollout replaces it and is announced, so more agents are offered it;
            // the same manifest read again is not.
            accept(feed, manifest("0.3.0", 50));
            accept(feed, manifest("0.3.0", 50));
            assertThat(feed.latest())
                    .get()
                    .extracting(ReleaseManifest::rolloutPercent)
                    .isEqualTo(50);
            assertThat(announced).hasSize(2);
            // One that fails verification changes nothing.
            var bytes = manifest("0.4.0", 100).getBytes(StandardCharsets.UTF_8);
            feed.accept(bytes, TestReleaseKeys.sign(TestReleaseKeys.another(), bytes));
            assertThat(feed.latest()).get().extracting(ReleaseManifest::version).isEqualTo("0.3.0");
            assertThat(announced).hasSize(2);
        } finally {
            feed.destroy();
        }
    }

    private static void accept(AgentReleaseFeed feed, String text) {
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        feed.accept(bytes, TestReleaseKeys.get().sign(bytes));
    }

    @Test
    void a_hosts_bucket_is_stable_and_buckets_spread_evenly() {
        var host = UUID.fromString("0192f0a0-0000-7000-8000-000000000001");
        assertThat(ReleaseManifest.bucket(host, "0.2.0")).isEqualTo(ReleaseManifest.bucket(host, "0.2.0"));
        var counts = new int[100];
        int hosts = 100_000;
        for (int i = 0; i < hosts; i++) {
            counts[ReleaseManifest.bucket(UUID.randomUUID(), "0.2.0")]++;
        }
        // Each bucket should hold about 1,000; a fair hash stays well within 20% of that at this size.
        for (int count : counts) {
            assertThat(count).isBetween(800, 1200);
        }
        int inTen = 0;
        for (int i = 0; i < 10; i++) {
            inTen += counts[i];
        }
        assertThat(inTen / (double) hosts).isBetween(0.09, 0.11);
    }

    @Test
    void raising_the_rollout_only_ever_adds_hosts() throws Exception {
        var ten = verify(manifest("0.2.0", 10));
        var fifty = verify(manifest("0.2.0", 50));
        for (int i = 0; i < 2_000; i++) {
            var host = UUID.randomUUID();
            if (ten.includes(host)) {
                assertThat(fifty.includes(host)).isTrue();
            }
        }
        assertThat(verify(manifest("0.2.0", 0)).includes(UUID.randomUUID())).isFalse();
        assertThat(verify(manifest("0.2.0", 100)).includes(UUID.randomUUID())).isTrue();
    }
}
