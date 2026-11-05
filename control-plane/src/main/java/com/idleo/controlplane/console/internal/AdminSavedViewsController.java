package com.idleo.controlplane.console.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Saved views of a list (ADR 0052): a name for its filters, order and columns, which are what's in its address. Private to
 * who saved it, or shared with every admin. Opening one still asks the server, so nothing shows that its reader may not.
 */
@RestController
@ConditionalOnWebApplication
class AdminSavedViewsController {

    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;

    AdminSavedViewsController(JdbcClient jdbc, IdGenerator ids, Clock clock) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
    }

    record AdminSavedView(UUID id, String name, boolean shared, boolean mine, String query) {}

    record AdminSavedViews(List<AdminSavedView> views) {}

    /** @param query the address's query string: the list's filters, order and columns */
    record NewSavedView(
            @NotBlank @Size(max = 40) String list,
            @NotBlank @Size(max = 80) String name,
            boolean shared,
            @Size(max = 2000) String query) {}

    private List<AdminSavedView> views(CurrentUser admin, String list) {
        return jdbc.sql("""
                        select id, name, shared, owner_id, query from console.saved_views
                        where list_name = :list and (shared or owner_id = :admin)
                        order by name
                        limit 200
                        """)
                .param("list", list)
                .param("admin", admin.id())
                .query((rs, n) -> new AdminSavedView(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getBoolean("shared"),
                        admin.id().equals(rs.getObject("owner_id", UUID.class)),
                        rs.getString("query")))
                .list();
    }

    @RequiresPermission(AdminPermission.SELF)
    @GetMapping("/admin/v1/views")
    AdminSavedViews list(@AuthenticationPrincipal CurrentUser admin, @RequestParam String list) {
        return new AdminSavedViews(views(admin, list));
    }

    @RequiresPermission(AdminPermission.SELF)
    @PostMapping("/admin/v1/views")
    AdminSavedViews save(@AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody NewSavedView request) {
        jdbc.sql("""
                        insert into console.saved_views (id, owner_id, list_name, name, shared, query, created_at)
                        values (:id, :owner, :list, :name, :shared, :query, :now)
                        """)
                .param("id", ids.newId())
                .param("owner", admin.id())
                .param("list", request.list())
                .param("name", request.name().strip())
                .param("shared", request.shared())
                .param("query", request.query())
                .param("now", Timestamp.from(Instant.now(clock)))
                .update();
        return new AdminSavedViews(views(admin, request.list()));
    }

    /** Deletes one this admin saved; another's is a 404, whether it's shared or not. */
    @RequiresPermission(AdminPermission.SELF)
    @DeleteMapping("/admin/v1/views/{id}")
    void delete(@AuthenticationPrincipal CurrentUser admin, @PathVariable UUID id) {
        var deleted = jdbc.sql("delete from console.saved_views where id = :id and owner_id = :admin")
                .param("id", id)
                .param("admin", admin.id())
                .update();
        if (deleted == 0) {
            throw ApiProblem.of(Problem.NOT_FOUND, "There's no such view.");
        }
    }
}
