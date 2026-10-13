package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.OperatorCommand;
import com.idleo.controlplane.identity.AuditTrail;
import java.io.PrintStream;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Host operator commands (ADR 0015, docs/runbooks/hosts.md):
 *
 * <pre>
 * activate-host --host &lt;host id&gt; --reason "&lt;text&gt;"
 * suspend-host --host &lt;host id&gt; --reason "&lt;text&gt;"
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
@Profile("ops")
class HostCommands {

    @Bean
    OperatorCommand activateHost(HostActivation activation) {
        return command(
                "activate-host",
                (args, operator) ->
                        activation.activate(args.id("host"), args.text("reason"), AuditTrail.Actor.operator(operator)));
    }

    @Bean
    OperatorCommand suspendHost(HostActivation activation) {
        return command(
                "suspend-host",
                (args, operator) ->
                        activation.suspend(args.id("host"), args.text("reason"), AuditTrail.Actor.operator(operator)));
    }

    private interface Action {
        HostActivation.Change apply(OperatorCommand.Arguments args, String operator);
    }

    private static OperatorCommand command(String name, Action action) {
        return new OperatorCommand() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String usage() {
                return "--host <host id> --reason \"<text>\"";
            }

            @Override
            public void run(Arguments args, String operator, PrintStream out) {
                var change = refusing(() -> action.apply(args, operator));
                if (change.changed()) {
                    out.println("host " + change.hostId() + ": " + change.from() + " -> " + change.to() + ", by "
                            + operator);
                } else {
                    out.println("host " + change.hostId() + " is already " + change.to() + "; nothing changed");
                }
            }
        };
    }

    private static <T> T refusing(Supplier<T> action) {
        try {
            return action.get();
        } catch (HostActivation.Refused e) {
            throw new OperatorCommand.Refused(e.getMessage());
        }
    }
}
