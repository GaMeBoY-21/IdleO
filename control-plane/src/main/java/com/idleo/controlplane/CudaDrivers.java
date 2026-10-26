package com.idleo.controlplane;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The NVIDIA driver a CUDA version needs on Windows, by minor version compatibility (ADR 0042): {@code
 * api/cuda-drivers.json}, the one copy of the rule, which the agent's {@code drivers.rs} reads too (its {@code
 * MIN_NVIDIA_DRIVER} for our job image). A renter's own image is offered only to a host whose GPU's driver runs the CUDA
 * version the image needs.
 */
public final class CudaDrivers {

    private static final Pattern VERSION = Pattern.compile("(\\d+)(?:\\.(\\d+))?.*");
    private static final Pattern REQUIRE = Pattern.compile("(?:^|\\s)cuda>=(\\d+(?:\\.\\d+)?)");
    private static final Map<Integer, String> FLOORS = load();

    private CudaDrivers() {}

    private static Map<Integer, String> load() {
        try (var in = new ClassPathResource("api/cuda-drivers.json").getInputStream()) {
            var floors = new LinkedHashMap<Integer, String>();
            for (var major : JsonMapper.builder().build().readTree(in).path("majors")) {
                floors.put(
                        major.path("cuda").asInt(),
                        major.path("windows_min_driver").asString());
            }
            if (floors.isEmpty()) {
                throw new IllegalStateException("api/cuda-drivers.json names no CUDA version");
            }
            return Map.copyOf(floors);
        } catch (IOException e) {
            throw new UncheckedIOException("api/cuda-drivers.json isn't in the jar", e);
        }
    }

    /** The oldest Windows driver CUDA {@code major} runs on, if the table knows that major. */
    public static Optional<String> minimumDriver(int major) {
        return Optional.ofNullable(FLOORS.get(major));
    }

    /**
     * Whether a GPU with {@code driver} (for example {@code 572.16}) runs an application built with CUDA {@code cuda}
     * ({@code 12.4}). A major the table doesn't know, or an unreadable version, doesn't run.
     */
    public static boolean supports(@Nullable String driver, String cuda) {
        var needed = parse(cuda);
        if (needed == null || driver == null) {
            return false;
        }
        var floor = FLOORS.get(needed[0]);
        var found = parse(driver);
        var minimum = floor == null ? null : parse(floor);
        return found != null
                && minimum != null
                && (found[0] != minimum[0] ? found[0] > minimum[0] : found[1] >= minimum[1]);
    }

    /**
     * The CUDA version an image's config says it needs: {@code NVIDIA_REQUIRE_CUDA}'s {@code cuda>=X.Y} first (what the
     * NVIDIA container toolkit itself reads), else {@code CUDA_VERSION}'s major and minor; null when it says neither.
     */
    public static @Nullable String needed(@Nullable String requireCuda, @Nullable String cudaVersion) {
        if (requireCuda != null) {
            var match = REQUIRE.matcher(requireCuda);
            if (match.find()) {
                return majorMinor(match.group(1));
            }
        }
        return cudaVersion == null ? null : majorMinor(cudaVersion.strip());
    }

    private static @Nullable String majorMinor(String version) {
        var parsed = parse(version);
        return parsed == null ? null : parsed[0] + "." + parsed[1];
    }

    private static int @Nullable [] parse(String version) {
        var match = VERSION.matcher(version.strip());
        if (!match.matches()) {
            return null;
        }
        try {
            return new int[] {
                Integer.parseInt(match.group(1)), match.group(2) == null ? 0 : Integer.parseInt(match.group(2))
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
