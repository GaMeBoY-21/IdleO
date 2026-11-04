package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.identity.Approvals;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** What another admin approves in identity (ADR 0051): lifting a suspension, role changes, and bulk invites. */
@Configuration(proxyBeanMethods = false)
class IdentityGatedActions {

    static final String INVITES_BULK = "invites.bulk";
    static final String ROLE_GRANT = "admin.role_grant";
    static final String ROLE_REMOVE = "admin.role_remove";

    @Bean
    Approvals.GatedAction liftSuspension(UserSuspensions suspensions) {
        return Approvals.action(
                "user.unsuspend",
                AdminPermission.USERS_UNSUSPEND,
                Approvals.Cooling.ACCESS,
                run -> suspensions.unsuspend(
                        UUID.fromString(run.params().get("id").asString()), run.reason(), run.actor()));
    }

    @Bean
    Approvals.GatedAction grantRole(AdminRoleChanges roles) {
        return Approvals.action(
                ROLE_GRANT,
                AdminPermission.ADMINS_MANAGE,
                Approvals.Cooling.ACCESS,
                run -> roles.grant(
                        UUID.fromString(run.params().get("id").asString()),
                        run.params().get("role").asString(),
                        run.reason(),
                        run.actor()));
    }

    @Bean
    Approvals.GatedAction removeRole(AdminRoleChanges roles) {
        return Approvals.action(
                ROLE_REMOVE,
                AdminPermission.ADMINS_MANAGE,
                Approvals.Cooling.ACCESS,
                run -> roles.remove(
                        UUID.fromString(run.params().get("id").asString()),
                        run.params().get("role").asString(),
                        run.reason(),
                        run.actor()));
    }

    /** The codes are secrets, so they're made when whoever asked fetches them, once. */
    @Bean
    Approvals.GatedAction bulkInvites(InviteService invites) {
        return Approvals.fileAction(INVITES_BULK, AdminPermission.INVITES_CREATE, Approvals.Cooling.MONEY, run -> {
            var days = run.params().path("expiresInDays");
            var created = invites.create(
                    run.params().get("count").asInt(),
                    run.params().get("note").asString().strip(),
                    days.isNumber() ? Optional.of(Duration.ofDays(days.asLong())) : Optional.empty(),
                    run.actor());
            return new Approvals.File(
                    String.join("\n", created.codes()).getBytes(StandardCharsets.UTF_8),
                    "text/plain",
                    "idleo-invites.txt");
        });
    }
}
