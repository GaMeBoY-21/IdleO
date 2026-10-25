package com.idleo.controlplane.hosts.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.TestReleaseKeys;
import com.idleo.controlplane.hosts.BlocklistCached;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The abuse blocklist as the release feed carries it (ADR 0038): cached only if its signature verifies against the release
 * key and it is a list; one that is older than the cached one, or tampered with, is ignored; a newer one is announced. A feed
 * of its own, so what the application's feed holds is untouched.
 */
class BlocklistFeedTests {

    private static final String HASH = "c".repeat(64);

    private final List<Object> announced = new ArrayList<>();
    private AgentReleaseFeed feed;

    @BeforeEach
    void feed() {
        ApplicationEventPublisher events = announced::add;
        var properties = new AgentReleaseProperties(
                URI.create("http://127.0.0.1:1"),
                "releases",
                "auto",
                "key",
                "secret",
                TestReleaseKeys.get().publicKeyBase64(),
                Duration.ofMinutes(5),
                Duration.ofHours(1));
        feed = new AgentReleaseFeed(properties, (JdbcClient) null, events);
    }

    private static byte[] list(String version, String learned) {
        return ("{\"version\":\"%s\",\"names\":[],\"hashes\":[],\"pools\":[],\"learned\":[%s]}"
                        .formatted(version, learned))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String learned(String sha256) {
        return "{\"sha256\":\"%s\",\"name\":\"a miner\",\"kind\":\"miner\",\"item\":\"01a1\"}".formatted(sha256);
    }

    private void accept(byte[] file) {
        feed.acceptBlocklist(file, TestReleaseKeys.get().sign(file));
    }

    @Test
    void aListSignedWithTheReleaseKeyIsCachedAnnouncedAndSentOnlyToARunnerWithAnOlderOne() {
        accept(list("2026-10-25T09:30:00Z", learned(HASH)));

        assertThat(announced).containsExactly(new BlocklistCached("2026-10-25T09:30:00Z"));
        assertThat(feed.learnedBuilds()).containsExactly(HASH);
        assertThat(feed.newerThan("").orElseThrow().version()).isEqualTo("2026-10-25T09:30:00Z");
        assertThat(feed.newerThan("2026-10-22")).isPresent();
        assertThat(feed.newerThan("2026-10-25T09:00:00Z")).isPresent();
        assertThat(feed.newerThan("2026-10-25T09:30:00Z"))
                .as("it already has it")
                .isEmpty();
        assertThat(feed.newerThan("9999-12-31")).as("it has a newer one").isEmpty();
        // A runner that sends something that isn't a version has none we can compare, so it is sent the list.
        assertThat(feed.newerThan("garbage")).isPresent();
    }

    @Test
    void whatIsSentIsTheFileExactlyAsSignedWithItsSignature() {
        var file = list("2026-10-25T09:30:00Z", "");
        accept(file);

        var sent = feed.newerThan("").orElseThrow();
        assertThat(sent.blocklist()).isEqualTo(file);
        assertThat(sent.signature()).isEqualTo(TestReleaseKeys.get().sign(file));
    }

    @Test
    void aListSignedByAnotherKeyOrChangedAfterSigningIsIgnoredAndTheLastGoodOneStays() {
        accept(list("2026-10-25T09:30:00Z", learned(HASH)));
        announced.clear();

        var other = list("2026-10-26T00:00:00Z", "");
        feed.acceptBlocklist(other, TestReleaseKeys.sign(TestReleaseKeys.another(), other));
        var tampered = list("2026-10-27T00:00:00Z", learned("d".repeat(64)));
        feed.acceptBlocklist(tampered, TestReleaseKeys.get().sign(list("2026-10-27T00:00:00Z", "")));

        assertThat(announced).isEmpty();
        assertThat(feed.newerThan("").orElseThrow().version()).isEqualTo("2026-10-25T09:30:00Z");
        assertThat(feed.learnedBuilds()).containsExactly(HASH);
    }

    @Test
    void anOlderListIsNeverTakenInPlaceOfANewerOne() {
        accept(list("2026-10-25T09:30:00Z", learned(HASH)));
        announced.clear();

        accept(list("2026-10-24T09:30:00Z", ""));
        accept(list("2026-10-25", ""));

        assertThat(announced).isEmpty();
        assertThat(feed.newerThan("").orElseThrow().version()).isEqualTo("2026-10-25T09:30:00Z");
    }

    @Test
    void aNewerListIsAnnouncedOnceAndTheSameListAgainIsNot() {
        accept(list("2026-10-25T09:30:00Z", ""));
        accept(list("2026-10-25T09:30:00Z", ""));
        accept(list("2026-10-26T09:30:00Z", ""));

        assertThat(announced)
                .containsExactly(
                        new BlocklistCached("2026-10-25T09:30:00Z"), new BlocklistCached("2026-10-26T09:30:00Z"));
    }

    @Test
    void aSignedFileThatIsNotAListIsIgnored() {
        for (var bad : List.of(
                "not json",
                "[]",
                "{}",
                "{\"version\":\"tomorrow\"}",
                "{\"version\":\"2026-10-25 09:30\"}",
                "{\"version\":\"2026-10-25\",\"learned\":[{\"sha256\":\"abc\"}]}")) {
            accept(bad.getBytes(StandardCharsets.UTF_8));
        }

        assertThat(announced).isEmpty();
        assertThat(feed.newerThan("")).isEmpty();
    }
}
