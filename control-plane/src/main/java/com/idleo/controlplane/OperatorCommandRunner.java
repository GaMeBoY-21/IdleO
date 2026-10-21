package com.idleo.controlplane;

import java.io.PrintStream;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Runs the one operator command named on the command line, in the {@code ops} profile (ADR 0014).
 * Exit codes: 0 done (including "already done"), 2 a bad command line, 3 refused with nothing changed.
 * See docs/runbooks/granting-credits.md and docs/runbooks/hosts.md.
 */
@Component
@Profile("ops")
class OperatorCommandRunner implements ApplicationRunner, ExitCodeGenerator {

    static final int OK = 0;
    static final int USAGE = 2;
    static final int REFUSED = 3;
    static final int CHECK_FAILED = 4;

    /** Who's running a command, as the audit log names them: a handle such as {@code nikki} (ADR 0037). */
    static final Pattern OPERATOR_HANDLE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{1,39}");

    private final List<OperatorCommand> commands;
    private final Environment environment;
    private final PrintStream out = System.out;
    private final PrintStream err = System.err;
    private int exitCode = OK;

    OperatorCommandRunner(List<OperatorCommand> commands, Environment environment) {
        this.commands = commands;
        this.environment = environment;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        var args = OperatorCommand.Arguments.parse(arguments.getSourceArgs());
        try {
            if (args.commands().isEmpty()) {
                throw new OperatorCommand.Usage("give exactly one command");
            }
            var command = commands.stream()
                    .filter(candidate -> candidate.name().equals(args.commands().getFirst()))
                    .findFirst()
                    .orElseThrow(() -> new OperatorCommand.Usage(
                            "unknown command " + args.commands().getFirst()));
            if (args.commands().size() != 1 + command.operands()) {
                throw new OperatorCommand.Usage(
                        command.operands() == 0
                                ? "give exactly one command"
                                : command.name() + " takes " + command.operands() + " value after its name");
            }
            var operator = environment.getProperty("OPERATOR", "").strip();
            if (operator.isEmpty()) {
                throw new OperatorCommand.Usage("set OPERATOR to who you are; it's recorded with every change");
            }
            if (!OPERATOR_HANDLE.matcher(operator).matches()) {
                throw new OperatorCommand.Usage(
                        "OPERATOR is a short handle (letters, digits, '.', '_' or '-'), never an email or a full name:"
                                + " the audit log keeps people by id only");
            }
            command.run(args, operator, out);
            exitCode = OK;
        } catch (OperatorCommand.Usage e) {
            err.println("error: " + e.getMessage());
            err.println(usage());
            exitCode = USAGE;
        } catch (OperatorCommand.Refused e) {
            err.println("refused: " + e.getMessage());
            exitCode = REFUSED;
        } catch (OperatorCommand.CheckFailed e) {
            err.println("check failed: " + e.getMessage());
            exitCode = CHECK_FAILED;
        }
    }

    private String usage() {
        return "usage, with OPERATOR set to who you are:\n"
                + commands.stream()
                        .map(command -> "  " + command.name() + " " + command.usage())
                        .sorted()
                        .collect(Collectors.joining("\n"));
    }
}
