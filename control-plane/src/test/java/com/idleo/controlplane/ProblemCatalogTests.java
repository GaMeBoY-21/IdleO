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
 * and the clients' copy in {@code api/problems.json} are written from the enum and must match the committed files,
 * and no error is raised except through {@link ApiProblem}. After an intended change, rewrite both with {@code
 * ./mvnw test -Dtest=ProblemCatalogTests -Dproblems.write=true}.
 */
class ProblemCatalogTests {

    static final Path PUBLISHED = Path.of("..", "docs", "api", "problems.md");

    /** Slug, status and title of every type, for clients to check they have words for each (web/src/lib/problems.ts). */
    static final Path FOR_CLIENTS = Path.of("..", "api", "problems.json");

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
    void theClientsCopyMatchesTheCatalog() throws IOException {
        var generated = json();
        if (Boolean.getBoolean("problems.write")) {
            Files.writeString(FOR_CLIENTS, generated);
        }
        assertThat(FOR_CLIENTS).exists();
        assertThat(generated)
                .as("api/problems.json is out of date; run ./mvnw test -Dtest=ProblemCatalogTests "
                        + "-Dproblems.write=true and review the diff")
                .isEqualTo(Files.readString(FOR_CLIENTS));
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

    /** The clients' copy: one object per problem, in catalog order. */
    static String json() {
        var out = new StringBuilder("[\n");
        var problems = Problem.values();
        for (int i = 0; i < problems.length; i++) {
            var problem = problems[i];
            out.append("  { \"slug\": \"")
                    .append(escape(problem.slug()))
                    .append("\", \"status\": ")
                    .append(problem.status().value())
                    .append(", \"title\": \"")
                    .append(escape(problem.title()))
                    .append("\" }")
                    .append(i < problems.length - 1 ? ",\n" : "\n");
        }
        return out.append("]\n").toString();
    }

    private static String escape(String text) {
        var out = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
