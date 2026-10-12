package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The operator command as Nikki runs it (ADR 0014): its own JVM in the ops profile, which runs the
 * command, prints the result and exits with its status, opening no web or gRPC port. Its own database,
 * so nothing here touches the shared test context.
 */
class CreditCommandProcessTests {

    private static final PostgreSQLContainer POSTGRES = TestcontainersConfiguration.newPostgres();

    record Run(int exitCode, String stdout, String stderr, long millis) {}

    @BeforeAll
    static void start() {
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private static Run run(String... command) throws IOException, InterruptedException {
        var pki = TestPki.get();
        var args = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                ControlPlaneApplication.class.getName(),
                "--spring.profiles.active=ops",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--identity.issuers[0].issuer-uri=https://issuer.example.invalid",
                "--identity.issuers[0].audience=example",
                // Storage isn't used by these commands; it only has to be configured.
                "--sessions.storage.provider=minio",
                "--sessions.storage.endpoint=http://127.0.0.1:9",
                "--sessions.storage.bucket=unused",
                "--sessions.storage.region=us-east-1",
                "--sessions.storage.access-key-id=unused",
                "--sessions.storage.secret-access-key=unused",
                // No command sends email; the mail sender only has to be configured.
                "--spring.mail.host=127.0.0.1",
                // Startup at info, so a server that started would say so.
                "--logging.level.root=info",
                "--logging.structured.format.console="));
        args.addAll(List.of(command));
        var builder = new ProcessBuilder(args);
        var env = builder.environment();
        env.put("OPERATOR", "process-test");
        env.put("HOSTS_CA_ROOT_CERTIFICATE", pki.rootPem);
        env.put("HOSTS_CA_INTERMEDIATE_CERTIFICATE", pki.intermediatePem);
        env.put("HOSTS_CA_INTERMEDIATE_PRIVATE_KEY", pki.encryptedIntermediateKeyPem);
        env.put("HOSTS_CA_INTERMEDIATE_PRIVATE_KEY_PASSWORD", TestPki.INTERMEDIATE_KEY_PASSWORD);
        env.put("AGENT_GRPC_TLS_CERTIFICATE", pki.serverChainPem);
        env.put("AGENT_GRPC_TLS_PRIVATE_KEY", pki.serverKeyPem);
        long started = System.nanoTime();
        var process = builder.start();
        var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(process.exitValue(), stdout, stderr, (System.nanoTime() - started) / 1_000_000);
    }

    private static void assertNoServerStarted(Run run) {
        assertThat(run.stdout()).contains("Started ControlPlaneApplication");
        assertThat(run.stdout()).doesNotContain("Tomcat started on port").doesNotContain("gRPC Server started");
    }

    @Test
    void grantsOnceExitsZeroAndOpensNoPort() throws Exception {
        // The first run applies the migrations, and refuses: the user doesn't exist yet.
        var userId = UUID.randomUUID();
        var refused = run(
                "grant-credits",
                "--user",
                userId.toString(),
                "--paise",
                "50000",
                "--reason",
                "Welcome credit",
                "--key",
                "process-test-1");
        assertThat(refused.exitCode()).as(refused.stderr()).isEqualTo(3);
        assertThat(refused.stderr()).contains("refused: there is no user " + userId);
        assertNoServerStarted(refused);

        var jdbc = new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.update(
                "insert into identity.users values (?, 'active', 'process-test@example.com', true, 'Test', now(), now(), 0)",
                userId);

        var granted = run(
                "grant-credits",
                "--user",
                userId.toString(),
                "--paise",
                "50000",
                "--reason",
                "Welcome credit",
                "--key",
                "process-test-1");
        assertThat(granted.exitCode()).as(granted.stderr()).isZero();
        assertThat(granted.stdout()).contains("granted 50000 paise (₹500.00) to user " + userId);
        assertNoServerStarted(granted);
        System.out.println("grant-credits, command start to exit: " + granted.millis() + " ms");

        var again = run(
                "grant-credits",
                "--user",
                userId.toString(),
                "--paise",
                "50000",
                "--reason",
                "Welcome credit",
                "--key",
                "process-test-1");
        assertThat(again.exitCode()).isZero();
        assertThat(again.stdout()).contains("already granted 50000 paise");
        assertThat(jdbc.queryForObject(
                        "select balance_paise from billing.wallets where user_id = ?", Long.class, userId))
                .isEqualTo(50_000L);
    }

    @Test
    void invitesArePrintedOnceAndStoredOnlyAsHashes() throws Exception {
        var made = run("create-invites", "--count", "3", "--note", "process test", "--expires", "30d");
        assertThat(made.exitCode()).as(made.stderr()).isZero();
        assertThat(made.stdout()).contains("made 3 invite codes for \"process test\", each valid for 30 days");
        assertNoServerStarted(made);
        var codes = made.stdout()
                .lines()
                .map(String::strip)
                .filter(line -> line.matches("IDLE-[A-Z0-9]{4}-[A-Z0-9]{4}"))
                .toList();
        assertThat(codes).hasSize(3).doesNotHaveDuplicates();

        var jdbc = new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        var rows = jdbc.queryForList(
                "select created_by, note, expires_at > created_at + interval '29 days' as long_enough, "
                        + "length(code_hash) as hash_bytes from identity.invite_codes where note = 'process test'");
        assertThat(rows).hasSize(3).allSatisfy(row -> {
            assertThat(row.get("created_by")).isEqualTo("process-test");
            assertThat(row.get("long_enough")).isEqualTo(true);
            assertThat(row.get("hash_bytes")).isEqualTo(32);
        });
        var stored = String.join(" ", jdbc.queryForList("select t::text from identity.invite_codes t", String.class));
        assertThat(codes).noneMatch(code -> stored.contains(code.substring(5)));
    }

    @Test
    void invitesRefuseABadCommandLine() throws Exception {
        assertThat(run("create-invites", "--count", "0", "--note", "x").exitCode())
                .isEqualTo(2);
        assertThat(run("create-invites", "--count", "201", "--note", "x").exitCode())
                .isEqualTo(2);
        assertThat(run("create-invites", "--count", "1").exitCode()).isEqualTo(2);
        assertThat(run("create-invites", "--count", "1", "--note", "x", "--expires", "soon")
                        .exitCode())
                .isEqualTo(2);
    }

    @Test
    void theWalletCheckExitsZeroWhenEverythingMatchesAndFourWhenSomethingDoesnt() throws Exception {
        var clean = run("reconcile-wallets");
        assertThat(clean.exitCode()).as(clean.stderr()).isZero();
        assertThat(clean.stdout()).contains("0 mismatches");
        assertNoServerStarted(clean);

        var jdbc = new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        var userId = UUID.randomUUID();
        jdbc.update(
                "insert into identity.users values (?, 'active', 'check-test@example.com', true, 'Test', now(), now(), 0)",
                userId);
        assertThat(run(
                                "grant-credits",
                                "--user",
                                userId.toString(),
                                "--paise",
                                "700",
                                "--reason",
                                "Check test",
                                "--key",
                                "process-test-check")
                        .exitCode())
                .isZero();
        jdbc.update("update billing.wallets set balance_paise = balance_paise + 1 where user_id = ?", userId);
        try {
            var corrupted = run("reconcile-wallets");
            assertThat(corrupted.exitCode()).as(corrupted.stderr()).isEqualTo(4);
            assertThat(corrupted.stdout())
                    .contains("balance mismatch for user " + userId + ": 701 paise cached, 700 expected")
                    .contains("1 mismatches");
        } finally {
            jdbc.update("update billing.wallets set balance_paise = balance_paise - 1 where user_id = ?", userId);
        }
    }

    @Test
    void aBadCommandLineExitsTwo() throws Exception {
        var run = run("grant-credits", "--paise", "lots");
        assertThat(run.exitCode()).isEqualTo(2);
        assertThat(run.stderr()).contains("usage");
    }
}
