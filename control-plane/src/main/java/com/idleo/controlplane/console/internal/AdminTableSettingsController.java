package com.idleo.controlplane.console.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * How each admin likes each table (ADR 0057): the columns' order, widths and pins, and how dense its rows are. Only ever
 * their own, and it follows them from browser to browser.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/admin/v1/table-settings")
class AdminTableSettingsController {

    /** The most tables one admin keeps settings for: every table of the console, several times over. */
    private static final int MOST_TABLES = 300;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    AdminTableSettingsController(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /**
     * One table's settings.
     *
     * @param order the columns by name, first to last; one not named keeps its place after these
     * @param widths each resized column's width, in CSS pixels
     * @param pinned the columns kept in view while the table scrolls sideways
     * @param density how tall its rows are: {@code comfortable} or {@code compact}
     */
    record TableSettings(
            @NotNull @Size(max = 60) List<@Size(max = 100) String> order,
            @NotNull @Size(max = 60) Map<String, Integer> widths,
            @NotNull @Size(max = 60) List<@Size(max = 100) String> pinned,

            @NotNull String density) {}

    record AdminTableSettings(Map<String, TableSettings> tables) {}

    @RequiresPermission(AdminPermission.SELF)
    @GetMapping
    AdminTableSettings mine(@AuthenticationPrincipal CurrentUser admin) {
        var tables = new LinkedHashMap<String, TableSettings>();
        jdbc.sql("select table_key, settings::text as settings from console.table_settings where admin_id = :admin")
                .param("admin", admin.id())
                .query(rs -> {
                    tables.put(
                            rs.getString("table_key"), json.readValue(rs.getString("settings"), TableSettings.class));
                });
        return new AdminTableSettings(tables);
    }

    @RequiresPermission(AdminPermission.SELF)
    @PutMapping("/{table}")
    TableSettings keep(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable @Size(max = 120) String table,
            @Valid @RequestBody TableSettings settings) {
        if (!settings.density().equals("comfortable") && !settings.density().equals("compact")) {
            throw ApiProblem.invalid("density", "must be comfortable or compact.");
        }
        var kept = jdbc.sql("""
                        select count(*) from console.table_settings
                        where admin_id = :admin and table_key <> :table
                        """)
                .param("admin", admin.id())
                .param("table", table)
                .query(Long.class)
                .single();
        if (kept >= MOST_TABLES) {
            throw ApiProblem.invalid("table", "You have settings for too many tables already.");
        }
        jdbc.sql("""
                        insert into console.table_settings (admin_id, table_key, settings, updated_at)
                        values (:admin, :table, cast(:settings as jsonb), :now)
                        on conflict (admin_id, table_key)
                        do update set settings = excluded.settings, updated_at = excluded.updated_at
                        """)
                .param("admin", admin.id())
                .param("table", table)
                .param("settings", json.writeValueAsString(settings))
                .param("now", Timestamp.from(clock.instant()))
                .update();
        return settings;
    }
}
