package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.OperatorCommand;
import com.idleo.controlplane.Paise;
import java.io.PrintStream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The free credit operator commands (ADR 0014, docs/runbooks/granting-credits.md):
 *
 * <pre>
 * grant-credits --user &lt;user id&gt; --paise &lt;n&gt; --reason "&lt;text&gt;" --key &lt;idempotency key&gt;
 * reverse-grant --grant &lt;grant id&gt; --reason "&lt;text&gt;"
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
@Profile("ops")
class CreditCommands {

    @Bean
    OperatorCommand grantCredits(CreditGrants grants) {
        return new OperatorCommand() {
            @Override
            public String name() {
                return "grant-credits";
            }

            @Override
            public String usage() {
                return "--user <user id> --paise <n> --reason \"<text>\" --key <idempotency key>";
            }

            @Override
            public void run(Arguments args, String operator, PrintStream out) {
                var outcome = refusing(() -> grants.grant(
                        args.id("user"),
                        Paise.of(args.number("paise")),
                        args.text("reason"),
                        args.text("key"),
                        operator));
                var grant = outcome.grant();
                out.println((outcome.created() ? "granted " : "already granted ") + describe(grant.amount())
                        + " to user " + grant.userId());
                out.println("  grant " + grant.id() + ", ledger transaction " + grant.txnId());
                out.println("  by " + grant.grantedBy() + " at " + grant.createdAt() + ", reason: " + grant.reason());
                out.println("  wallet balance now " + describe(outcome.walletBalance()));
            }
        };
    }

    @Bean
    OperatorCommand reverseGrant(CreditGrants grants) {
        return new OperatorCommand() {
            @Override
            public String name() {
                return "reverse-grant";
            }

            @Override
            public String usage() {
                return "--grant <grant id> --reason \"<text>\"";
            }

            @Override
            public void run(Arguments args, String operator, PrintStream out) {
                var outcome = refusing(() -> grants.reverse(args.id("grant"), args.text("reason"), operator));
                var reversal = outcome.reversal();
                out.println((outcome.created() ? "reversed " : "already reversed ") + "grant " + reversal.grantId()
                        + " of " + describe(outcome.grant().amount()) + " to user "
                        + outcome.grant().userId());
                out.println("  reversal " + reversal.id() + ", ledger transaction " + reversal.txnId());
                out.println("  by " + reversal.reversedBy() + " at " + reversal.createdAt() + ", reason: "
                        + reversal.reason());
                out.println("  wallet balance now " + describe(outcome.walletBalance()));
            }
        };
    }

    private static <T> T refusing(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (CreditGrants.Refused e) {
            throw new OperatorCommand.Refused(e.getMessage());
        }
    }

    /** Paise, with rupees beside them so an operator can spot a typo. Integer arithmetic only. */
    static String describe(Paise amount) {
        long paise = amount.value();
        return String.format("%d paise (₹%d.%02d)", paise, paise / 100, Math.abs(paise % 100));
    }
}
