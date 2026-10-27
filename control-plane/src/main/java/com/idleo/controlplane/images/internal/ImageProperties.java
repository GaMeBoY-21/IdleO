package com.idleo.controlplane.images.internal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Renters' own images (ADR 0042), {@code images.*} in application.yaml.
 *
 * @param enabled whether renters may use their own images at all: false until the scanner has been measured on the
 *     server it runs on (production refuses true without the scanner's limits and secrets)
 * @param platform the one platform an image must have: {@code linux/amd64}; a development stack on a Mac runs arm64
 *     (its own distribution can't nest amd64 containers), which production refuses
 * @param maxCompressed the most an image's layers may be together, compressed
 * @param maxLayers the most layers an image may have
 * @param maxLayerUnpacked the most one layer may unpack to; the scanner stops reading past it
 * @param maxImageUnpacked the most all layers together may unpack to
 * @param pullTimeLimit how long a host may take to download an image at a session's start before the attempt fails,
 *     uncharged
 * @param checkDeadline how long a whole check may take
 * @param checkAttempts how many scanners may claim a check (each one that stops reporting loses it) before it's failed
 * @param claimLasts how long a scanner's claim lasts without a word from it
 * @param devRegistries registries that may be at a private address and spoken to over plain HTTP: the development
 *     stack's own; production refuses any
 */
@ConfigurationProperties("images")
@Validated
record ImageProperties(
        boolean enabled,
        @NotBlank @Pattern(regexp = "linux/(amd64|arm64)") String platform,
        @NotNull DataSize maxCompressed,
        @Min(1) int maxLayers,
        @NotNull DataSize maxLayerUnpacked,
        @NotNull DataSize maxImageUnpacked,
        @NotNull Duration pullTimeLimit,
        @NotNull Duration checkDeadline,
        @Min(1) int checkAttempts,
        @NotNull Duration claimLasts,
        List<String> devRegistries,
        Scanner scanner,
        RegistryToken registryToken) {

    ImageProperties {
        devRegistries = devRegistries == null ? List.of() : List.copyOf(devRegistries);
    }

    /**
     * The scanner's internal API: on {@code port} only, never the public one, with {@code token} as its bearer (32
     * characters or more; production refuses the tests' value).
     */
    record Scanner(@Min(1) int port, String token) {}

    /** The key registry tokens are sealed under (32 bytes, base64, or {@code file:…}). */
    record RegistryToken(String key) {}
}
