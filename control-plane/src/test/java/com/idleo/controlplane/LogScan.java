package com.idleo.controlplane;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationListener;

/**
 * Reads every line the control plane logs in the whole test suite for personal data and secrets (ADR 0036): email
 * addresses, bearer tokens and API keys, storage credentials and signed links, and renters' file names, in a storage
 * key or alone (the names the tests give them). Registered for every test application context (src/test/resources/META-INF/
 * spring.factories), after Spring Boot has set up logging; a finding is written to {@link #VIOLATIONS}, which the
 * enforcer refuses at {@code verify}, beside {@link #SCANNED}, which proves the scan ran.
 */
public final class LogScan implements ApplicationListener<ApplicationPreparedEvent> {

    static final Path DIRECTORY = Path.of("target", "log-scan");
    static final Path VIOLATIONS = DIRECTORY.resolve("violations.txt");
    static final Path SCANNED = DIRECTORY.resolve("scanned.txt");

    /** What must never be logged, by name. */
    static final Map<String, Pattern> FORBIDDEN = forbidden();

    static {
        // Once for each test run (one JVM, surefire's default), so a finding from an earlier run never lingers.
        try {
            Files.deleteIfExists(VIOLATIONS);
            Files.deleteIfExists(SCANNED);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Pattern> forbidden() {
        var patterns = new LinkedHashMap<String, Pattern>();
        patterns.put(
                "email address", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}"));
        patterns.put("JSON web token", Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]*"));
        patterns.put("bearer token", Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{16,}"));
        patterns.put("API key", Pattern.compile("idleo_[A-Za-z0-9_-]{16,}"));
        patterns.put(
                "signed storage link",
                Pattern.compile("(?i)X-Amz-(Signature|Credential|Security-Token)=[A-Za-z0-9%/._-]{8,}"));
        patterns.put(
                "storage secret",
                Pattern.compile("(?i)(secret[-_]?access[-_]?key|session[-_]?token)\\s*[=:]\\s*\\S{8,}"));
        patterns.put("renter's file key", Pattern.compile("uploads/[0-9a-f-]{36}/files/"));
        // A file name alone can't be told by its shape, so the names the upload tests give renters' files stand in.
        patterns.put(
                "renter's file name",
                Pattern.compile(
                        "train\\.jsonl|model 00001\\.safetensors|tokenizer\\.json|src/__init__\\.py|\\b(big|short|good|mine)\\.bin\\b"));
        return Map.copyOf(patterns);
    }

    /** The name of the first forbidden pattern {@code line} holds. */
    static Optional<String> finding(String line) {
        return FORBIDDEN.entrySet().stream()
                .filter(entry -> entry.getValue().matcher(line).find())
                .map(Map.Entry::getKey)
                .sorted()
                .findFirst();
    }

    @Override
    public void onApplicationEvent(ApplicationPreparedEvent event) {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return;
        }
        var root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(Scanner.NAME) != null) {
            return;
        }
        var scanner = new Scanner();
        scanner.setContext(context);
        scanner.start();
        root.addAppender(scanner);
        append(SCANNED, "attached to a context\n");
    }

    private static synchronized void append(Path file, String text) {
        try {
            Files.createDirectories(DIRECTORY);
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class Scanner extends AppenderBase<ILoggingEvent> {

        static final String NAME = "log-scan";

        Scanner() {
            setName(NAME);
        }

        @Override
        protected void append(ILoggingEvent event) {
            var text = event.getFormattedMessage();
            if (event.getThrowableProxy() != null) {
                text += "\n" + ThrowableProxyUtil.asString(event.getThrowableProxy());
            }
            var found = finding(text);
            if (found.isPresent()) {
                // Where it was and what kind, never the value itself: this file is read in CI logs.
                LogScan.append(
                        VIOLATIONS,
                        found.get() + " logged by " + event.getLoggerName() + ": "
                                + event.getMessage().replaceAll("[\\r\\n]+", " ") + "\n");
            }
        }
    }
}
