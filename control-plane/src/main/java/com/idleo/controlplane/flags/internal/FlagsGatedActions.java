package com.idleo.controlplane.flags.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Flag;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.AdminRecords;
import com.idleo.controlplane.identity.Approvals;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What another admin approves in flags (ADR 0056): running one again, with no cooling-off when no one else can (an outage
 * mustn't last longer for want of a second admin). Pausing is never gated. And the flag's version, for {@code If-Match}.
 */
@Configuration(proxyBeanMethods = false)
class FlagsGatedActions {

    @Bean
    @ConditionalOnWebApplication
    Approvals.GatedAction resumeFlag(FlagStore flags) {
        return Approvals.action(
                AdminFlagsController.RESUME,
                AdminPermission.FLAGS_CHANGE,
                Approvals.Cooling.NONE,
                run -> flags.resume(
                        Flag.of(run.params().get("id").asString())
                                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such flag.")),
                        run.reason(),
                        run.actor()));
    }

    @Bean
    AdminRecords flagRecords(JdbcClient jdbc) {
        Function<Map<String, String>, OptionalLong> version =
                variables -> jdbc.sql("select version from flags.flags where key = :key")
                        .param("key", variables.getOrDefault("id", ""))
                        .query(Long.class)
                        .optional()
                        .map(OptionalLong::of)
                        .orElse(OptionalLong.empty());
        return () -> Map.of("flag", version);
    }
}
