package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.OperatorCommand;
import com.idleo.controlplane.identity.AuditTrail;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The invite operator command (ADR 0024, docs/runbooks/invites.md):
 *
 * <pre>
 * create-invites --count &lt;n&gt; --note "&lt;text&gt;" [--expires 30d]
 * </pre>
 *
 * It prints the codes once; only their hashes are stored.
 */
@Configuration(proxyBeanMethods = false)
@Profile("ops")
class InviteCommands {

    private static final Pattern EXPIRY = Pattern.compile("(\\d{1,4})([dh])");

    @Bean
    OperatorCommand createInvites(InviteService invites) {
        return new OperatorCommand() {
            @Override
            public String name() {
                return "create-invites";
            }

            @Override
            public String usage() {
                return "--count <n> --note \"<text>\" [--expires 30d]";
            }

            @Override
            public void run(Arguments args, String operator, PrintStream out) {
                long count = args.number("count");
                if (count < 1 || count > InviteService.MAX_COUNT) {
                    throw new Usage("--count must be from 1 to " + InviteService.MAX_COUNT);
                }
                var note = args.text("note").strip();
                var expiry = expiry(args);
                var codes = invites.create((int) count, note, expiry, AuditTrail.Actor.operator(operator))
                        .codes();
                out.println("made " + codes.size() + " invite code" + (codes.size() == 1 ? "" : "s") + " for \"" + note
                        + "\""
                        + expiry.map(d -> ", each valid for " + describe(d)).orElse(", with no expiry"));
                out.println("They are shown only now; only their hashes are kept. Each works once.");
                codes.forEach(code -> out.println("  " + code));
            }
        };
    }

    private static Optional<Duration> expiry(OperatorCommand.Arguments args) {
        if (!args.options().containsKey("expires")) {
            return Optional.empty();
        }
        var matcher = EXPIRY.matcher(args.text("expires"));
        if (!matcher.matches()) {
            throw new OperatorCommand.Usage("--expires must be a number of days or hours, like 30d or 12h");
        }
        long amount = Long.parseLong(matcher.group(1));
        if (amount < 1) {
            throw new OperatorCommand.Usage("--expires must be at least 1d or 1h");
        }
        return Optional.of(matcher.group(2).equals("d") ? Duration.ofDays(amount) : Duration.ofHours(amount));
    }

    private static String describe(Duration duration) {
        return duration.toHours() % 24 == 0 ? duration.toDays() + " days" : duration.toHours() + " hours";
    }
}
