package com.idleo.controlplane.console.internal;

import com.idleo.controlplane.ConsoleSeed;
import com.idleo.controlplane.OperatorCommand;
import java.io.PrintStream;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * {@code seed-console}: made-up data to measure the console on (ADR 0052). It refuses unless {@code console.seed.allowed}
 * is on, which only the local profile does, and {@code ProductionSettingsGuard} refuses in production.
 */
@Configuration(proxyBeanMethods = false)
@Profile("ops")
class ConsoleSeedCommand {

    static final ConsoleSeed.Sizes FULL = new ConsoleSeed.Sizes(100_000, 2_000, 1_000_000, 300_000);

    @Bean
    OperatorCommand seedConsole(List<ConsoleSeed> seeds, @Value("${console.seed.allowed:false}") boolean allowed) {
        return new OperatorCommand() {
            @Override
            public String name() {
                return "seed-console";
            }

            @Override
            public String usage() {
                return "(local only: 100k users, 2k hosts, 1M sessions, 300k payments)";
            }

            @Override
            public void run(Arguments args, String operator, PrintStream out) {
                if (!allowed) {
                    throw new Refused("console.seed.allowed is off: made-up data is for a local stack only");
                }
                seeds.forEach(seed -> {
                    seed.seed(FULL);
                    out.println("seeded " + seed.getClass().getSimpleName());
                });
            }
        };
    }
}
