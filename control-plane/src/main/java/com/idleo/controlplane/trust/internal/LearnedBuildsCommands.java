package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.OperatorCommand;
import java.io.PrintStream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.json.JsonMapper;

/**
 * Operator command (ADR 0014, docs/runbooks/abuse.md):
 *
 * <pre>
 * export-blocklist-additions
 * </pre>
 *
 * Prints the builds admins confirmed as abuse as the JSON array {@code scripts/release/blocklist.sh --additions} reads. The
 * blocklist is signed with a key the control plane does not hold, so the operator carries this file to where the key is.
 */
@Configuration(proxyBeanMethods = false)
@Profile("ops")
class LearnedBuildsCommands {

    @Bean
    OperatorCommand exportBlocklistAdditions(LearnedBuilds learned) {
        return new OperatorCommand() {
            @Override
            public String name() {
                return "export-blocklist-additions";
            }

            @Override
            public String usage() {
                return "";
            }

            @Override
            public void run(Arguments args, String operator, PrintStream out) {
                out.println(JsonMapper.builder()
                        .build()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(learned.export()));
            }
        };
    }
}
