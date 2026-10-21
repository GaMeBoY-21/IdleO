package com.idleo.controlplane.privacy.internal;

import com.idleo.controlplane.OperatorCommand;
import java.io.PrintStream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * {@code reapply-erasures} (ADR 0037, docs/runbooks/backups.md): run by ops/backup/restore.sh after every restore, it
 * erases again every account on the erased list, in the database and in storage, so a backup can't bring one back.
 * Moves no money but the forfeit of credit a restore gave back, and sends no email.
 */
@Configuration(proxyBeanMethods = false)
@Profile("ops")
class ErasureCommand {

    @Bean
    OperatorCommand reapplyErasures(Erasure erasure) {
        return new OperatorCommand() {
            @Override
            public String name() {
                return "reapply-erasures";
            }

            @Override
            public String usage() {
                return "(no options)";
            }

            @Override
            public void run(Arguments args, String operator, PrintStream out) {
                if (!args.options().isEmpty()) {
                    throw new Usage("reapply-erasures takes no options");
                }
                out.println("erased again: " + erasure.reapply() + " accounts");
            }
        };
    }
}
