package com.idleo.controlplane;

import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A semantic version (https://semver.org), compared by major, minor, patch, then pre-release: the agent gateway's
 * minimum agent version and the release feed's newer-than check use this one comparison.
 */
public record SemanticVersion(
        int major, int minor, int patch, @Nullable String preRelease) implements Comparable<SemanticVersion> {

    /** The pattern published at semver.org, without its named groups. */
    public static final String PATTERN = "(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
            + "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?"
            + "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?";

    private static final Pattern REGEX = Pattern.compile(PATTERN);

    public static Optional<SemanticVersion> parse(String text) {
        var match = REGEX.matcher(text);
        if (!match.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SemanticVersion(
                    Integer.parseInt(match.group(1)),
                    Integer.parseInt(match.group(2)),
                    Integer.parseInt(match.group(3)),
                    match.group(4)));
        } catch (NumberFormatException tooLarge) {
            return Optional.empty();
        }
    }

    /** Pre-release identifiers are compared as text, a simplification of semver's rule. */
    @Override
    public int compareTo(SemanticVersion other) {
        int byNumbers = Integer.compare(major, other.major);
        if (byNumbers == 0) {
            byNumbers = Integer.compare(minor, other.minor);
        }
        if (byNumbers == 0) {
            byNumbers = Integer.compare(patch, other.patch);
        }
        if (byNumbers != 0) {
            return byNumbers;
        }
        if (preRelease == null || other.preRelease == null) {
            return preRelease == null ? (other.preRelease == null ? 0 : 1) : -1;
        }
        return preRelease.compareTo(other.preRelease);
    }
}
