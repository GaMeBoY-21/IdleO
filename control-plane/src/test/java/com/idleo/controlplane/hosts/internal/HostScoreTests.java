package com.idleo.controlplane.hosts.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.hosts.HostSessionResults;
import com.idleo.controlplane.hosts.HostTrustSignals;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The host score as the hosts module keeps it: worked out from sessions, self-tests, reports and the admin's flag
 * (fakes of the other modules' ports, so each input is controlled), stored, and shown to the owner as a plain word.
 */
@ControlPlaneTest
class HostScoreTests {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MutableClock clock;

    @Autowired
    MockMvc mvc;

    @Autowired
    HostsPersonalData personalData;

    @LocalGrpcServerPort
    int grpcPort;

    private final List<HostSessionResults.Result> results = new ArrayList<>();
    private final List<HostTrustSignals.Signal> signals = new ArrayList<>();

    @AfterEach
    void reset() {
        clock.reset();
    }

    private HostScoreService service() {
        return new HostScoreService(
                jdbc,
                clock,
                (hostIds, since) -> results.stream()
                        .filter(r -> hostIds.contains(r.hostId()))
                        .toList(),
                (Collection<UUID> hostIds, Instant since) -> signals.stream()
                        .filter(s -> hostIds.contains(s.hostId()))
                        .toList());
    }

    private UUID newHost() throws Exception {
        return TestAgent.enrol(mvc, grpcPort).hostId;
    }

    private double reliability(UUID host) {
        return jdbcTemplate.queryForObject(
                "select reliability from hosts.host_scores where host_id = ?", Double.class, host);
    }

    private double bandwidth(UUID host) {
        return jdbcTemplate.queryForObject(
                "select bandwidth from hosts.host_scores where host_id = ?", Double.class, host);
    }

    private void result(UUID host, boolean finished, int times) {
        for (int i = 0; i < times; i++) {
            results.add(new HostSessionResults.Result(host, clock.instant().minus(Duration.ofHours(3)), finished));
        }
    }

    private void selfTest(UUID host, boolean passed) {
        jdbcTemplate.update(
                "insert into hosts.self_test_results (id, host_id, passed, reported_at) values (?, ?, ?, ?)",
                UUID.randomUUID(),
                host,
                passed,
                Timestamp.from(clock.instant().minus(Duration.ofHours(5))));
    }

    private void speed(UUID host, Integer upload, Integer download) {
        jdbcTemplate.update("""
                insert into hosts.self_tests (host_id, passed, distro_version, agent_version, checks, reported_at,
                    upload_kbps, download_kbps)
                values (?, true, '0.1.0', '0.1.0', '[]', ?, ?, ?)
                on conflict (host_id) do update set upload_kbps = excluded.upload_kbps,
                    download_kbps = excluded.download_kbps
                """, host, Timestamp.from(clock.instant()), upload, download);
    }

    @Test
    void aHostWithNoDataStartsAtAverageOnBothAndIsNew() throws Exception {
        var host = newHost();

        service().refresh(host);

        assertThat(reliability(host)).isEqualTo(0.5);
        assertThat(bandwidth(host)).isEqualTo(0.5);
        var stored = service().stored(List.of(host)).get(host);
        assertThat(stored.breakdown().label()).isEqualTo(HostReliability.Label.NEW);
        assertThat(HostViews.reliability(stored).lowers()).isEmpty();
    }

    @Test
    void finishedSessionsRaiseTheStoredScoreAndLostOnesLowerIt() throws Exception {
        var good = newHost();
        var bad = newHost();
        result(good, true, 12);
        result(bad, false, 12);

        service().refresh(good);
        service().refresh(bad);

        assertThat(reliability(good)).isGreaterThan(0.5);
        assertThat(reliability(bad)).isLessThan(0.5);
        var shown = HostViews.reliability(service().stored(List.of(bad)).get(bad));
        assertThat(shown.label()).isEqualTo("fair");
        assertThat(shown.lowers()).extracting(HostViews.Lowers::kind).containsExactly("sessions_lost");
        assertThat(shown.lowers().getFirst().count()).isEqualTo(12);
    }

    @Test
    void selfTestsReportsAndTheFlagAllMoveItAndTheOwnerIsToldOnlyWhatLowersIt() throws Exception {
        var host = newHost();
        result(host, true, 10);
        service().refresh(host);
        double clean = reliability(host);

        selfTest(host, false);
        selfTest(host, false);
        service().refresh(host);
        double afterFailedSelfTests = reliability(host);
        assertThat(afterFailedSelfTests).isLessThan(clean);

        signals.add(new HostTrustSignals.Signal(
                host, clock.instant().minus(Duration.ofDays(1)), HostTrustSignals.Kind.UPHELD_REPORT));
        service().refresh(host);
        double afterReport = reliability(host);
        assertThat(afterReport).isLessThan(afterFailedSelfTests);

        signals.add(new HostTrustSignals.Signal(
                host, clock.instant().minus(Duration.ofDays(1)), HostTrustSignals.Kind.FLAG));
        service().refresh(host);
        assertThat(reliability(host)).isLessThan(afterReport);

        // The owner learns that reports lowered it, but not how many, nor that an admin marked the host.
        var shown = HostViews.reliability(service().stored(List.of(host)).get(host));
        assertThat(shown.lowers()).extracting(HostViews.Lowers::kind).containsExactly("self_tests_failed", "reports");
        assertThat(shown.lowers().get(1).count()).isNull();
        // The admin has the numbers.
        var stored = service().stored(List.of(host)).get(host).breakdown();
        assertThat(stored.reportsUpheld()).isOne();
        assertThat(stored.flags()).isOne();
    }

    @Test
    void aRenterStoppingASessionNeverLowersTheScore() throws Exception {
        // A renter's stop is a finished session as far as the host goes: the results port never calls it lost.
        var host = newHost();
        service().refresh(host);
        double before = reliability(host);

        result(host, true, 1);
        service().refresh(host);

        assertThat(reliability(host)).isGreaterThanOrEqualTo(before);
    }

    @Test
    void bandwidthIsTheHostsSpeedAgainstTheFleetsNinetiethPercentile() {
        assertThat(HostScoreService.bandwidth(50_000, 100_000)).isEqualTo(0.5);
        assertThat(HostScoreService.bandwidth(100_000, 100_000)).isEqualTo(1.0);
        assertThat(HostScoreService.bandwidth(400_000, 100_000)).isEqualTo(1.0);
        assertThat(HostScoreService.bandwidth(null, 100_000)).isEqualTo(0.5);
        assertThat(HostScoreService.bandwidth(50_000, 0)).isEqualTo(0.5);
    }

    @Test
    void aFasterLineScoresHigherUsingBothUploadAndDownload() throws Exception {
        var slow = newHost();
        var fast = newHost();
        var unmeasured = newHost();
        // Far above anything else the suite's hosts report, so these decide the fleet's 90th percentile.
        speed(slow, 300_000_000, 100_000_000);
        speed(fast, 900_000_000, 700_000_000);

        service().refresh(slow);
        service().refresh(fast);
        service().refresh(unmeasured);

        assertThat(bandwidth(fast)).isGreaterThan(bandwidth(slow));
        assertThat(bandwidth(slow)).isGreaterThan(0.0).isLessThan(1.0);
        assertThat(bandwidth(unmeasured)).isEqualTo(0.5);
        // The speed is the mean of the two directions.
        assertThat(service().stored(List.of(slow)).get(slow).speedKbps()).isEqualTo(200_000_000);
        // A host that only ever measured one direction is judged by it.
        var uploadOnly = newHost();
        speed(uploadOnly, 600_000_000, null);
        service().refresh(uploadOnly);
        assertThat(service().stored(List.of(uploadOnly)).get(uploadOnly).speedKbps())
                .isEqualTo(600_000_000);
        service().refreshAll();
        assertThat(bandwidth(uploadOnly)).isGreaterThan(bandwidth(slow)).isLessThanOrEqualTo(bandwidth(fast));
    }

    @Test
    void theNightlyJobWorksOutEveryHostThatIsntRemoved() throws Exception {
        var host = newHost();
        result(host, true, 3);
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from hosts.host_scores where host_id = ?", Integer.class, host))
                .isZero();

        service().refreshAll();

        assertThat(reliability(host)).isGreaterThan(0.5);
    }

    @Test
    void erasingTheOwnerRemovesTheScoreAndTheSelfTestRecords() throws Exception {
        var host = newHost();
        selfTest(host, true);
        service().refresh(host);
        jdbcTemplate.update(
                "insert into hosts.self_test_runs (id, host_id, requested_by_kind, requested_by, requested_at, state)"
                        + " values (?, ?, 'owner', ?, ?, 'running')",
                UUID.randomUUID(),
                host,
                UUID.randomUUID(),
                Timestamp.from(clock.instant()));
        var tables = List.of("self_test_results", "host_scores", "self_test_runs");
        for (var table : tables) {
            assertThat(count(table, host)).as(table + " before").isPositive();
        }

        var owner = jdbcTemplate.queryForObject("select owner_user_id from hosts.hosts where id = ?", UUID.class, host);
        personalData.erase(owner, com.idleo.controlplane.PersonalData.ErasureStep.DELETE_RECORDS);

        for (var table : tables) {
            assertThat(count(table, host)).as(table + " after").isZero();
        }
    }

    private int count(String table, UUID host) {
        return jdbcTemplate.queryForObject(
                "select count(*) from hosts." + table + " where host_id = ?", Integer.class, host);
    }
}
