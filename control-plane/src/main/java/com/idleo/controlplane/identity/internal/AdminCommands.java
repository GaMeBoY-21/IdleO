package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.OperatorCommand;
import com.idleo.controlplane.identity.AuditTrail;
import java.io.PrintStream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The admin role's operator commands (ADR 0027, docs/runbooks/admin.md), the only way to grant or take it:
 *
 * <pre>
 * grant-admin &lt;email&gt; --reason "&lt;text&gt;"
 * revoke-admin &lt;email&gt; --reason "&lt;text&gt;"
 * </pre>
 *
 * Revoking and granting again is also how an admin locked out by wrong codes gets back in, with a fresh enrolment.
 */
@Configuration(proxyBeanMethods = false)
@Profile("ops")
class AdminCommands {

    @Bean
    OperatorCommand grantAdmin(AdminRoles roles) {
        return command("grant-admin", (email, reason, operator) -> {
            var change = roles.grant(email, reason, AuditTrail.Actor.operator(operator));
            return change.changed()
                    ? "user " + change.userId() + " (" + email + ") is now an admin; they enrol a second factor at"
                            + " their first visit to /admin, by " + operator
                    : "user " + change.userId() + " (" + email + ") is already an admin; nothing changed";
        });
    }

    @Bean
    OperatorCommand revokeAdmin(AdminRoles roles) {
        return command("revoke-admin", (email, reason, operator) -> {
            var change = roles.revoke(email, reason, AuditTrail.Actor.operator(operator));
            return change.changed()
                    ? "user " + change.userId() + " (" + email + ") is no longer an admin; their second factor,"
                            + " recovery codes and admin sessions are gone, by " + operator
                    : "user " + change.userId() + " (" + email + ") isn't an admin; nothing changed";
        });
    }

    private interface Action {
        String apply(String email, String reason, String operator);
    }

    private static OperatorCommand command(String name, Action action) {
        return new OperatorCommand() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String usage() {
                return "<email> --reason \"<text>\"";
            }

            @Override
            public int operands() {
                return 1;
            }

            @Override
            public void run(Arguments args, String operator, PrintStream out) {
                var email = args.operand(0);
                var reason = args.text("reason");
                try {
                    out.println(action.apply(email, reason, operator));
                } catch (AdminRoles.Refused e) {
                    throw new Refused(e.getMessage());
                }
            }
        };
    }
}
