package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.OperatorCommand;
import java.io.PrintStream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The nightly wallet check, run now (ADR 0014, docs/runbooks/granting-credits.md): {@code reconcile-wallets}. It
 * changes nothing. Exits 0 when every wallet, reservation and hold matches the ledger and the ledger balances,
 * 4 when anything doesn't, and 3 when another instance is running the check.
 */
@Configuration(proxyBeanMethods = false)
@Profile("ops")
class ReconciliationCommand {

    @Bean
    OperatorCommand reconcileWallets(WalletReconciliation reconciliation) {
        return new OperatorCommand() {
            @Override
            public String name() {
                return "reconcile-wallets";
            }

            @Override
            public String usage() {
                return "(no options)";
            }

            @Override
            public void run(Arguments args, String operator, PrintStream out) {
                if (!args.options().isEmpty()) {
                    throw new Usage("reconcile-wallets takes no options");
                }
                var result = reconciliation.run();
                if (!result.ran()) {
                    throw new Refused("another instance is running the check; try again in a minute");
                }
                out.println("ledger: " + result.ledgerDebitsPaise() + " paise of debits, " + result.ledgerCreditsPaise()
                        + " paise of credits" + (result.ledgerBalanced() ? "" : ": UNBALANCED"));
                for (var mismatch : result.mismatches()) {
                    out.println("  " + mismatch.kind().name().toLowerCase(java.util.Locale.ROOT) + " mismatch for user "
                            + mismatch.userId() + ": " + mismatch.walletPaise() + " paise cached, "
                            + mismatch.expectedPaise() + " expected");
                }
                int problems = result.mismatches().size() + (result.ledgerBalanced() ? 0 : 1);
                out.println(problems + " mismatches");
                if (problems > 0) {
                    throw new CheckFailed(problems + " mismatches; see docs/runbooks/granting-credits.md");
                }
            }
        };
    }
}
