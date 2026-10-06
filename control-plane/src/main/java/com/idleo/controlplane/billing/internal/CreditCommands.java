package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.Paise;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Operator commands for free credit (ADR 0014), run in the {@code ops} profile, which starts no web or
 * gRPC server: the command runs, prints its result and exits. See docs/runbooks/granting-credits.md.
 *
 * <pre>
 * OPERATOR=&lt;who&gt; java -jar control-plane.jar --spring.profiles.active=prod,ops \
 *     grant-credits --user &lt;user id&gt; --paise &lt;n&gt; --reason "&lt;text&gt;" --key &lt;idempotency key&gt;
 * OPERATOR=&lt;who&gt; java -jar control-plane.jar --spring.profiles.active=prod,ops \
 *     reverse-grant --grant &lt;grant id&gt; --reason "&lt;text&gt;"
 * </pre>
 *
 * <p>Exit codes: 0 done (including "already granted" and "already reversed"), 2 a bad command line, 3
 * refused (a limit, an unknown user or grant, a reused key, or too little available balance).
 */
@Component
@Profile("ops")
class CreditCommands implements ApplicationRunner, ExitCodeGenerator {

    static final int OK = 0;
    static final int USAGE = 2;
    static final int REFUSED = 3;

    private static final String USAGE_TEXT = """
            usage, with OPERATOR set to who you are:
              grant-credits --user <user id> --paise <n> --reason "<text>" --key <idempotency key>
              reverse-grant --grant <grant id> --reason "<text>\"""";

    private final CreditGrants grants;
    private final Environment environment;
    private final PrintStream out = System.out;
    private final PrintStream err = System.err;
    private int exitCode = OK;

    CreditCommands(CreditGrants grants, Environment environment) {
        this.grants = grants;
        this.environment = environment;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            exitCode = execute(args);
        } catch (Usage e) {
            err.println("error: " + e.getMessage());
            err.println(USAGE_TEXT);
            exitCode = USAGE;
        } catch (CreditGrants.Refused e) {
            err.println("refused: " + e.getMessage());
            exitCode = REFUSED;
        }
    }

    private int execute(ApplicationArguments arguments) {
        var args = CommandLine.parse(arguments.getSourceArgs());
        var commands = args.commands();
        if (commands.size() != 1) {
            throw new Usage("give exactly one command");
        }
        var operator = environment.getProperty("OPERATOR", "").strip();
        if (operator.isEmpty()) {
            throw new Usage("set OPERATOR to who you are; it's recorded with the grant");
        }
        switch (commands.getFirst()) {
            case "grant-credits" -> {
                var outcome = grants.grant(
                        uuid(args, "user"),
                        Paise.of(number(args, "paise")),
                        option(args, "reason"),
                        option(args, "key"),
                        operator);
                var grant = outcome.grant();
                out.println((outcome.created() ? "granted " : "already granted ") + describe(grant.amount())
                        + " to user " + grant.userId());
                out.println("  grant " + grant.id() + ", ledger transaction " + grant.txnId());
                out.println("  by " + grant.grantedBy() + " at " + grant.createdAt() + ", reason: " + grant.reason());
                out.println("  wallet balance now " + describe(outcome.walletBalance()));
            }
            case "reverse-grant" -> {
                var outcome = grants.reverse(uuid(args, "grant"), option(args, "reason"), operator);
                var reversal = outcome.reversal();
                out.println((outcome.created() ? "reversed " : "already reversed ") + "grant " + reversal.grantId()
                        + " of " + describe(outcome.grant().amount()) + " to user "
                        + outcome.grant().userId());
                out.println("  reversal " + reversal.id() + ", ledger transaction " + reversal.txnId());
                out.println("  by " + reversal.reversedBy() + " at " + reversal.createdAt() + ", reason: "
                        + reversal.reason());
                out.println("  wallet balance now " + describe(outcome.walletBalance()));
            }
            default -> throw new Usage("unknown command " + commands.getFirst());
        }
        return OK;
    }

    /** Paise, with rupees beside them so an operator can spot a typo. Integer arithmetic only. */
    static String describe(Paise amount) {
        long paise = amount.value();
        return String.format("%d paise (₹%d.%02d)", paise, paise / 100, Math.abs(paise % 100));
    }

    private static String option(CommandLine args, String name) {
        List<String> values = args.options().get(name);
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
            throw new Usage("give --" + name + " exactly once");
        }
        return values.getFirst();
    }

    private static UUID uuid(CommandLine args, String name) {
        try {
            return UUID.fromString(option(args, name));
        } catch (IllegalArgumentException e) {
            throw new Usage("--" + name + " must be an id like 01a10e8e-9c65-7789-a526-473491c82218");
        }
    }

    private static long number(CommandLine args, String name) {
        try {
            return Long.parseLong(option(args, name));
        } catch (NumberFormatException e) {
            throw new Usage("--" + name + " must be a whole number of paise");
        }
    }

    /**
     * The command and its options, as {@code --name value} or {@code --name=value}. Spring's own
     * arguments ({@code --spring.…} and other settings with {@code =}) are left to Spring.
     */
    record CommandLine(List<String> commands, Map<String, List<String>> options) {

        private static final Set<String> NAMES = Set.of("user", "paise", "reason", "key", "grant");

        static CommandLine parse(String... args) {
            var commands = new ArrayList<String>();
            var options = new HashMap<String, List<String>>();
            for (int i = 0; i < args.length; i++) {
                var arg = args[i];
                if (!arg.startsWith("--")) {
                    commands.add(arg);
                    continue;
                }
                var equals = arg.indexOf('=');
                var name = equals < 0 ? arg.substring(2) : arg.substring(2, equals);
                if (!NAMES.contains(name)) {
                    continue;
                }
                String value;
                if (equals >= 0) {
                    value = arg.substring(equals + 1);
                } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    value = args[++i];
                } else {
                    value = "";
                }
                options.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
            }
            return new CommandLine(List.copyOf(commands), Map.copyOf(options));
        }
    }

    private static final class Usage extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Usage(String message) {
            super(message);
        }
    }
}
