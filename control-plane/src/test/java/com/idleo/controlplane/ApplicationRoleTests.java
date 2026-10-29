package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idleo.migration.DatabaseMigration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The two database users as a cluster has them (ADR 0045, docs/runbooks/deployment.md): ops/postgres's first-start
 * script makes them, the migration Job's entry point runs Flyway as the migration user, and the control plane's login
 * then has only what serving requests needs. It reads its own privileges, as the runbook says, and fails on any
 * change, removal or truncation of an append-only table, on anything it owns, on any schema it could create in, and on
 * a table a migration left without its grants.
 */
class ApplicationRoleTests {

    private static final String MIGRATION_PASSWORD = "tc-only-migration-7b2e04";
    private static final String APPLICATION_PASSWORD = "tc-only-application-5d81c9";

    private static final PostgreSQLContainer postgres = TestcontainersConfiguration.newPostgres()
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../ops/postgres/initdb/10-idleo-users.sh", 0755),
                    "/docker-entrypoint-initdb.d/10-idleo-users.sh")
            .withEnv("IDLEO_MIGRATION_PASSWORD", MIGRATION_PASSWORD)
            .withEnv("IDLEO_APPLICATION_PASSWORD", APPLICATION_PASSWORD);

    @BeforeAll
    static void migrateAsTheMigrationUser() {
        postgres.start();
        DatabaseMigration.main(new String[] {
            "--spring.datasource.url=" + postgres.getJdbcUrl(),
            "--spring.datasource.username=idleo_migration",
            "--spring.datasource.password=" + MIGRATION_PASSWORD
        });
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    private static Connection as(String user, String password) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), user, password);
    }

    private static Connection application() throws SQLException {
        return as("idleo_app", APPLICATION_PASSWORD);
    }

    private static List<String> strings(Connection connection, String sql) throws SQLException {
        var rows = new ArrayList<String>();
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return rows;
    }

    @Test
    void theMigrationUserOwnsEverySchemaAndTable() throws SQLException {
        try (var superuser = as(postgres.getUsername(), postgres.getPassword())) {
            assertThat(strings(superuser, """
                            select n.nspname || ' ' || pg_get_userbyid(n.nspowner) from pg_namespace n
                            where n.nspname not like 'pg\\_%' and n.nspname not in ('information_schema', 'public')
                              and pg_get_userbyid(n.nspowner) <> 'idleo_migration'
                            union all
                            select c.oid::regclass || ' ' || pg_get_userbyid(c.relowner) from pg_class c
                            join pg_namespace n on n.oid = c.relnamespace
                            where n.nspname not like 'pg\\_%' and n.nspname not in ('information_schema', 'public')
                              and pg_get_userbyid(c.relowner) <> 'idleo_migration'
                            """)).isEmpty();
        }
    }

    @Test
    void theApplicationNeverChangesRemovesOrTruncatesAnAppendOnlyTable() throws SQLException {
        try (var app = application()) {
            // A table is append-only when one trigger refuses both an update and a delete (tgtype's DELETE and UPDATE
            // bits).
            var appendOnly = strings(app, """
                    select distinct c.oid::regclass::text from pg_trigger t join pg_class c on c.oid = t.tgrelid
                    where not t.tgisinternal and t.tgtype & 24 = 24 order by 1
                    """);
            assertThat(appendOnly)
                    .contains(
                            "identity.audit_log",
                            "billing.ledger_entries",
                            "billing.reservation_changes",
                            "hosts.status_changes",
                            "sessions.session_events",
                            "payouts.events");
            var grants = strings(app, """
                    select table_schema || '.' || table_name || ' ' || privilege_type
                    from information_schema.role_table_grants
                    where grantee in ('idleo_app', 'idleo_application')
                    """);
            for (var table : appendOnly) {
                assertThat(grants).as(table).contains(table + " SELECT", table + " INSERT");
                assertThat(grants).as(table).doesNotContain(table + " UPDATE", table + " DELETE", table + " TRUNCATE");
            }
            assertThat(grants).noneMatch(grant -> grant.endsWith(" TRUNCATE"));
        }
    }

    @Test
    void everyTableAMigrationMadeIsGrantedToTheApplication() throws SQLException {
        try (var app = application()) {
            assertThat(strings(app, """
                            select c.oid::regclass::text from pg_class c join pg_namespace n on n.oid = c.relnamespace
                            where c.relkind in ('r', 'p')
                              and n.nspname not like 'pg\\_%'
                              and n.nspname not in ('information_schema', 'public', 'flyway')
                              and not (has_table_privilege(c.oid, 'SELECT') and has_table_privilege(c.oid, 'INSERT'))
                            """)).isEmpty();
            // The sequences first (offset 0 keeps the planner from asking has_sequence_privilege of a table).
            assertThat(strings(app, """
                            select name from (
                                select c.oid, c.oid::regclass::text as name from pg_class c
                                join pg_namespace n on n.oid = c.relnamespace
                                where c.relkind = 'S' and n.nspname <> 'flyway' offset 0
                            ) sequences where not has_sequence_privilege(oid, 'USAGE')
                            """)).isEmpty();
        }
    }

    @Test
    void theApplicationOwnsNothingAndCreatesNothing() throws SQLException {
        try (var app = application()) {
            assertThat(strings(app, """
                            select 'schema ' || nspname from pg_namespace where nspowner = 'idleo_app'::regrole
                              or nspowner = 'idleo_application'::regrole
                            union all
                            select 'relation ' || oid::regclass from pg_class where relowner = 'idleo_app'::regrole
                              or relowner = 'idleo_application'::regrole
                            union all
                            select 'function ' || oid::regprocedure from pg_proc where proowner = 'idleo_app'::regrole
                              or proowner = 'idleo_application'::regrole
                            union all
                            select 'database ' || datname from pg_database where datdba = 'idleo_app'::regrole
                            """)).isEmpty();
            assertThat(strings(app, """
                            select nspname from pg_namespace where has_schema_privilege(oid, 'CREATE')
                            union all
                            select 'database' where has_database_privilege(current_database(), 'CREATE')
                            union all
                            select 'flyway history' where has_schema_privilege('flyway', 'USAGE')
                            """)).isEmpty();
            try (var statement = app.createStatement()) {
                assertThatThrownBy(() -> statement.execute("create table metering.anything (id int)"))
                        .hasMessageContaining("permission denied");
                assertThatThrownBy(() -> statement.execute("alter table identity.audit_log disable trigger all"))
                        .hasMessageContaining("must be owner");
            }
        }
    }

    @Test
    void theApplicationMakesNextMonthsPartitionsThroughTheirFunction() throws SQLException {
        var month = LocalDate.now().withDayOfMonth(1).plusMonths(9);
        var partition = "metering.usage_minutes_%04d_%02d".formatted(month.getYear(), month.getMonthValue());
        try (var app = application();
                var statement = app.prepareStatement("select metering.ensure_usage_partition(?)")) {
            statement.setObject(1, month);
            statement.execute();
            assertThat(strings(
                            app,
                            "select pg_get_userbyid(relowner) from pg_class where oid = to_regclass('" + partition
                                    + "')"))
                    .containsExactly("idleo_migration");
            assertThat(strings(app, "select has_table_privilege('" + partition + "', 'INSERT')::text"))
                    .containsExactly("true");
        }
    }
}
