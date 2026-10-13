package com.idleo.controlplane;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One operator command, run in the {@code ops} profile (ADR 0014): the application runs it, prints the
 * result and exits. Modules contribute commands as beans; {@link OperatorCommandRunner} picks one by name.
 */
public interface OperatorCommand {

    /** The name on the command line, for example {@code grant-credits}. */
    String name();

    /** Its options, for the usage text, for example {@code --host <id> --reason "<why>"}. */
    String usage();

    /**
     * How many values it takes after its name, before its options: {@code grant-admin <email>} takes one. Most take
     * none.
     */
    default int operands() {
        return 0;
    }

    /**
     * Runs the command. {@code operator} is who's running it, from the {@code OPERATOR} environment
     * variable.
     *
     * @throws Usage for a bad command line (exit code 2)
     * @throws Refused when the request is refused and nothing changed (exit code 3)
     * @throws CheckFailed when a check ran and found a problem (exit code 4)
     */
    void run(Arguments args, String operator, PrintStream out);

    /** A bad command line. */
    final class Usage extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Usage(String message) {
            super(message);
        }
    }

    /** The request was refused; nothing changed. */
    final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Refused(String message) {
            super(message);
        }
    }

    /** A check ran and found a problem; nothing changed. */
    final class CheckFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public CheckFailed(String message) {
            super(message);
        }
    }

    /**
     * The command, the values after it, and its options, as {@code --name value} or {@code --name=value}. Spring's
     * own arguments, whose names contain a dot ({@code --spring.profiles.active=…}), are left to Spring.
     */
    record Arguments(List<String> commands, Map<String, List<String>> options) {

        public static Arguments parse(String... args) {
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
                if (name.contains(".")) {
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
            return new Arguments(List.copyOf(commands), Map.copyOf(options));
        }

        /** The {@code index}-th value after the command's name, from 0. */
        public String operand(int index) {
            if (index + 1 >= commands.size() || commands.get(index + 1).isBlank()) {
                throw new Usage("give the command's value after its name");
            }
            return commands.get(index + 1).strip();
        }

        public String text(String name) {
            var values = options.get(name);
            if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
                throw new Usage("give --" + name + " exactly once");
            }
            return values.getFirst();
        }

        public UUID id(String name) {
            try {
                return UUID.fromString(text(name));
            } catch (IllegalArgumentException e) {
                throw new Usage("--" + name + " must be an id like 01a10e8e-9c65-7789-a526-473491c82218");
            }
        }

        public long number(String name) {
            try {
                return Long.parseLong(text(name));
            } catch (NumberFormatException e) {
                throw new Usage("--" + name + " must be a whole number");
            }
        }
    }
}
