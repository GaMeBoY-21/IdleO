package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The problem catalog (ADR 0019): slugs are stable and unique, the published list in {@code docs/api/problems.md}
 * is written from the enum and must match the committed file, and no error is raised except through {@link
 * ApiProblem}. After an intended change, rewrite the list with {@code ./mvnw test -Dtest=ProblemCatalogTests
 * -Dproblems.write=true}.
 */
class ProblemCatalogTests {

    static final Path PUBLISHED = Path.of("..", "docs", "api", "problems.md");

    private static final Pattern SLUG = Pattern.compile("[a-z]+(-[a-z]+)*");

    /** Ways to build an error response other than ApiProblem; only the advice that renders them may use them. */
    private static final Pattern BUILT_ELSEWHERE =
            Pattern.compile("new\\s+ResponseStatusException|ProblemDetail\\s*\\.\\s*for|new\\s+ProblemDetail\\b"
                    + "|new\\s+ErrorResponseException|ErrorResponse\\s*\\.\\s*(create|builder)");

    @Test
    void slugsAreUniqueKebabCaseAndTitlesAreSet() {
        var slugs = Arrays.stream(Problem.values()).map(Problem::slug).toList();
        assertThat(slugs)
                .doesNotHaveDuplicates()
                .allMatch(slug -> SLUG.matcher(slug).matches());
        assertThat(Problem.values())
                .allSatisfy(problem -> assertThat(problem.title()).isNotBlank())
                .allSatisfy(problem -> assertThat(problem.when()).endsWith("."))
                .allSatisfy(problem -> assertThat(problem.type().toString()).isEqualTo("/problems/" + problem.slug()));
    }

    @Test
    void theCatalogHasEverySlugTheApiPromises() {
        assertThat(Arrays.stream(Problem.values()).map(Problem::slug))
                .contains(
                        "validation-failed",
                        "malformed-request",
                        "unauthenticated",
                        "account-suspended",
                        "not-found",
                        "idempotency-key-invalid",
                        "request-in-progress",
                        "idempotency-key-reused",
                        "version-required",
                        "version-stale",
                        "insufficient-credit",
                        "session-not-cancellable",
                        "session-not-running",
                        "outputs-not-ready",
                        "input-not-accepted",
                        "input-required",
                        "input-too-large",
                        "upload-not-ready",
                        "upload-in-use",
                        "upload-quota-exceeded",
                        "too-many-streams",
                        "internal-error");
    }

    @Test
    void thePublishedListMatchesTheCatalog() throws IOException {
        var generated = markdown();
        if (Boolean.getBoolean("problems.write")) {
            Files.createDirectories(PUBLISHED.getParent());
            Files.writeString(PUBLISHED, generated);
        }
        assertThat(PUBLISHED).exists();
        assertThat(generated)
                .as("docs/api/problems.md is out of date; run ./mvnw test -Dtest=ProblemCatalogTests "
                        + "-Dproblems.write=true and review the diff")
                .isEqualTo(Files.readString(PUBLISHED));
    }

    @Test
    void errorsAreRaisedOnlyThroughTheCatalog() throws IOException {
        List<Path> offenders;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            offenders = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> !file.endsWith(Path.of("com/idleo/controlplane/ProblemDetailsAdvice.java")))
                    .filter(file -> {
                        try {
                            return BUILT_ELSEWHERE
                                    .matcher(Files.readString(file))
                                    .find();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
        }
        assertThat(offenders)
                .as("errors go through ApiProblem.of(Problem, detail), never a ResponseStatusException or a bare "
                        + "ProblemDetail")
                .isEmpty();
    }

    @Test
    void theScanRecognisesTheWaysAroundTheCatalog() {
        assertThat(BUILT_ELSEWHERE
                        .matcher("throw new ResponseStatusException(HttpStatus.CONFLICT)")
                        .find())
                .isTrue();
        assertThat(BUILT_ELSEWHERE
                        .matcher("ProblemDetail.forStatusAndDetail(status, detail)")
                        .find())
                .isTrue();
        assertThat(BUILT_ELSEWHERE
                        .matcher("throw ApiProblem.of(Problem.NOT_FOUND, \"No such host.\")")
                        .find())
                .isFalse();
    }

    /** The published list: one row per problem, in catalog order. */
    static String markdown() {
        var out = new StringBuilder();
        out.append("# API problem types\n\n");
        out.append("<!-- Generated from control-plane/src/main/java/com/idleo/controlplane/Problem.java by "
                + "ProblemCatalogTests. Don't edit by hand: ./mvnw test -Dtest=ProblemCatalogTests "
                + "-Dproblems.write=true -->\n\n");
        out.append("Every error from the API is an RFC 9457 problem detail (`application/problem+json`). Its `type` "
                + "is one of the relative URIs below, `/problems/<slug>`, and never changes once published: clients "
                + "branch on it, never on `title` or `detail`. The status always matches the table. `detail` says "
                + "what happened this time, in words for a person. A `validation-failed` problem also has `errors`, "
                + "each with a `field` (such as `params.epochs`) and a `detail`. See ADR 0019.\n\n");
        out.append("| Type | Status | Title | When it happens |\n");
        out.append("| --- | --- | --- | --- |\n");
        for (var problem : Problem.values()) {
            out.append("| `")
                    .append(problem.type())
                    .append("` | ")
                    .append(problem.status().value())
                    .append(" | ")
                    .append(problem.title())
                    .append(" | ")
                    .append(problem.when().replace("|", "\\|"))
                    .append(" |\n");
        }
        return out.toString();
    }
}
