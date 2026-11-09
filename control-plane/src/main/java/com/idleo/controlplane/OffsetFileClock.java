package com.idleo.controlplane;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The end-to-end suite's clock (ADR 0057): the real time, moved on by however many seconds {@code e2e.clock-file} says.
 * A whole stack runs on it, so a month can close and a target or a strike can run out while a browser watches. It's a
 * file and not an endpoint so that nothing on the network can move time; {@code ProductionSettingsGuard} refuses the
 * setting, so production can never start with it.
 */
final class OffsetFileClock extends Clock {

    private static final Logger log = LoggerFactory.getLogger(OffsetFileClock.class);
    private static final Duration READ_EVERY = Duration.ofMillis(100);

    private final Path file;
    private final ZoneId zone;
    private volatile long offsetSeconds;

    OffsetFileClock(Path file) {
        this(file, ZoneOffset.UTC);
        read();
        var reader = new Thread(this::readForever, "e2e-clock-file");
        reader.setDaemon(true);
        reader.start();
    }

    private OffsetFileClock(Path file, ZoneId zone) {
        this.file = file;
        this.zone = zone;
    }

    private void readForever() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(READ_EVERY);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            }
            read();
        }
    }

    /** No file yet is no offset. A half-written one keeps the last. */
    private void read() {
        try {
            if (Files.exists(file)) {
                var text = Files.readString(file).strip();
                if (!text.isEmpty()) {
                    offsetSeconds = Long.parseLong(text);
                }
            }
        } catch (IOException | NumberFormatException e) {
            log.warn("Couldn't read the end-to-end clock's offset; it keeps the last: {}", e.getMessage());
        }
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId other) {
        var clock = new OffsetFileClock(file, other);
        clock.offsetSeconds = offsetSeconds;
        return clock;
    }

    @Override
    public Instant instant() {
        return Instant.now().plusSeconds(offsetSeconds);
    }
}
