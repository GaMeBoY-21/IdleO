package com.idleo.controlplane.flags.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The flags' settings (ADR 0056).
 *
 * @param refreshEvery how often each instance reads the flags again, whatever Valkey says
 * @param trustFor how long the last good read stands when the flags can't be read; after that, each flag is its safe
 *     value
 * @param expireEvery how often a pause whose time is up is lifted in the database (the cache lifts it at its time)
 */
@ConfigurationProperties("flags")
record FlagsProperties(
        @DefaultValue("30s") Duration refreshEvery,
        @DefaultValue("2m") Duration trustFor,
        @DefaultValue("15s") Duration expireEvery) {}
