package com.idleo.controlplane.images.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Every verdict of a check in the renter's words (ADR 0042), in one place: why an image is blocked or wasn't checked, and
 * the warnings that never block. The web app, the CLI and the SDK show these words as the server gives them.
 */
final class ImageVerdicts {

    private ImageVerdicts() {}

    /** Why a submission is blocked, as a stable code. */
    static final List<String> BLOCKED_REASONS = List.of(
            "wrong_platform",
            "too_large",
            "too_many_layers",
            "too_large_unpacked",
            "unsafe_path",
            "unpack_failed",
            "pull_failed",
            "blocklisted",
            "admin");

    /** The one reason a check fails: it didn't finish, so nothing was decided. */
    static final String NOT_CHECKED = "not_checked";

    /** A size as registries and the web app show it: 8.4 GB, 312 MB (10^9 and 10^6 bytes). */
    static String size(long bytes) {
        if (bytes >= 1_000_000_000L) {
            return BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(1_000_000_000L), 1, RoundingMode.HALF_UP)
                    + " GB";
        }
        return Math.max(1, (bytes + 999_999) / 1_000_000) + " MB";
    }

    static String wrongPlatform(List<String> platforms, String wanted) {
        if (platforms.isEmpty()) {
            return "Your image names no platform we can run. Build it for " + wanted
                    + " (docker buildx build --platform " + wanted + ") and push it again.";
        }
        return "Your image is for " + String.join(", ", platforms) + " only, and hosts here run " + wanted
                + " images. Build it for " + wanted + " (docker buildx build --platform " + wanted
                + ") and push it again.";
    }

    static String tooLarge(long compressed, long limit) {
        return "Your image is " + size(compressed) + " compressed; the most we take is " + size(limit)
                + ". Leave models and data out of the image and bring them as inputs instead.";
    }

    static String tooManyLayers(int layers, int limit) {
        return "Your image has " + layers + " layers; the most we take is " + limit
                + ". Combine steps in your Dockerfile and push it again.";
    }

    static final String BLOCKLISTED =
            "Your image holds software that isn't allowed on IdleO (the acceptable use policy), "
                    + "so it can't run here.";

    static final String ADMIN = "We've blocked this image. If you think it's a mistake, write to us from Help.";

    static String notChecked(@Nullable String why) {
        return "The check didn't finish"
                + (why == null || why.isBlank() ? "." : ": " + why.strip().replaceAll("\\.$", "") + ".")
                + " Nothing was decided, and the image can't be booked until a check completes: check it again.";
    }

    /** A warning: a stable code and the renter's words. */
    record Warning(String code, String message) {}

    /** The warnings a scan carries, most important first. */
    static List<Warning> warnings(ScanStore.Scan scan) {
        var warnings = new ArrayList<Warning>();
        var bySpot = new LinkedHashMap<String, String>();
        for (var secret : scan.secrets()) {
            bySpot.putIfAbsent(
                    secret.path() + "\u0000" + secret.category(), secret.path() + "\u0000" + secretWords(secret));
        }
        for (var spot : bySpot.values()) {
            var parts = spot.split("\u0000", 2);
            warnings.add(new Warning(
                    "secret",
                    "Your image seems to contain " + parts[1] + " in " + parts[0]
                            + ". Anyone with this image can read it."));
        }
        if (scan.critical() + scan.high() > 0) {
            warnings.add(new Warning(
                    "vulnerabilities",
                    "Trivy found " + count(scan.critical(), "critical") + " and " + count(scan.high(), "high")
                            + " known vulnerabilities in your image's packages."));
        }
        if (runsAsRoot(scan.user())) {
            warnings.add(new Warning(
                    "root_user",
                    "Your image runs as root. We always run your job as user 10001, so code that expects root (writing "
                            + "outside /work and /tmp, installing packages) may fail."));
        }
        if (!scan.hasShell()) {
            warnings.add(new Warning(
                    "no_shell",
                    "Your image has no /bin/sh, so a command you type can't run; leave the command empty to run the "
                            + "image's own."));
        }
        return warnings;
    }

    private static String count(int n, String severity) {
        return n + " " + severity;
    }

    static boolean runsAsRoot(@Nullable String user) {
        if (user == null || user.isBlank()) {
            return true;
        }
        var name = user.split(":", 2)[0].strip();
        return name.equals("root") || name.equals("0");
    }

    private static final Map<String, String> SECRET_KINDS = Map.ofEntries(
            Map.entry("aws", "an AWS key"),
            Map.entry("github", "a GitHub token"),
            Map.entry("gitlab", "a GitLab token"),
            Map.entry("slack", "a Slack token"),
            Map.entry("stripe", "a Stripe key"),
            Map.entry("google", "a Google Cloud key"),
            Map.entry("gcp", "a Google Cloud key"),
            Map.entry("huggingface", "a Hugging Face token"),
            Map.entry("openai", "an OpenAI key"),
            Map.entry("anthropic", "an Anthropic key"),
            Map.entry("asymmetricprivatekey", "a private key"),
            Map.entry("privatekey", "a private key"),
            Map.entry("docker", "a Docker token"),
            Map.entry("npm", "an npm token"),
            Map.entry("pypi", "a PyPI token"),
            Map.entry("azure", "an Azure key"));

    private static String secretWords(ScanStore.Secret secret) {
        var kind = secret.category().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        var known = SECRET_KINDS.get(kind);
        if (known != null) {
            return known;
        }
        var title = secret.title().isBlank() ? "secret" : secret.title().strip();
        return (title.matches("(?i)[aeiou].*") ? "an " : "a ") + title;
    }
}
