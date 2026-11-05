package com.idleo.controlplane.console.internal;

import com.idleo.controlplane.AdminPages;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminTimeline;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.AdminAccess;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one timeline (ADR 0052): every kind of event about a user, host, session or payment the admin may read, asked of
 * each module at the same time and merged, newest first, with a cursor. Which kinds there are comes from the modules; an
 * admin without a kind's permission doesn't learn of its events.
 */
@RestController
@RequestMapping("/admin/v1/timeline")
@ConditionalOnWebApplication
class AdminTimelineController {

    private static final Set<String> SUBJECTS = Set.of("user", "host", "session", "payment");
    private static final Comparator<AdminTimeline.TimelineEvent> NEWEST_FIRST = Comparator.comparing(
                    AdminTimeline.TimelineEvent::at)
            .thenComparing(AdminTimeline.TimelineEvent::key)
            .reversed();

    private final List<AdminTimeline> providers;
    private final AdminAccess access;
    private final ConsoleSearchProperties properties;
    private final ConsoleFanOut fanOut;

    AdminTimelineController(
            List<AdminTimeline> providers,
            AdminAccess access,
            ConsoleSearchProperties properties,
            ConsoleFanOut fanOut) {
        this.providers = providers.stream()
                .sorted(Comparator.comparing(AdminTimeline::kind))
                .toList();
        this.access = access;
        this.properties = properties;
        this.fanOut = fanOut;
    }

    /**
     * @param kinds every kind this admin may filter by
     * @param unanswered the kinds that didn't answer in time, so the admin knows some may be missing
     */
    record TimelinePage(
            List<AdminTimeline.TimelineEvent> events,
            List<String> kinds,
            List<String> unanswered,
            @Nullable String nextCursor) {}

    /** {@code kinds} leaves only those kinds; none given means all. */
    @RequiresPermission(AdminPermission.SELF)
    @GetMapping
    TimelinePage timeline(
            @AuthenticationPrincipal CurrentUser admin,
            @RequestParam @Schema(allowableValues = {"user", "host", "session", "payment"}) String subject,
            @RequestParam UUID id,
            @RequestParam(required = false) @Nullable List<String> kinds,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        if (!SUBJECTS.contains(subject)) {
            throw ApiProblem.invalid("subject", "must be one of " + SUBJECTS + ".");
        }
        int size = AdminPages.limit(limit);
        var after = cursor == null || cursor.isBlank() ? null : Position.decode(cursor);
        var held = access.permissions(admin.id());
        var permitted = providers.stream()
                .filter(provider -> held.contains(provider.permission()))
                .toList();
        var about = new AdminTimeline.Subject(subject, id);
        var window = new AdminTimeline.Window(after == null ? null : after.at(), size + 1 + SAME_INSTANT);
        var questions = new LinkedHashMap<String, Callable<List<AdminTimeline.TimelineEvent>>>();
        permitted.stream()
                .filter(provider -> kinds == null || kinds.isEmpty() || kinds.contains(provider.kind()))
                .forEach(provider -> questions.put(provider.kind(), () -> provider.events(about, window)));
        var answers = fanOut.ask(questions, properties.providerTimeout());
        var merged = new ArrayList<AdminTimeline.TimelineEvent>();
        answers.answered().values().forEach(merged::addAll);
        merged.sort(NEWEST_FIRST);
        var page = merged.stream()
                .filter(event -> after == null || after.isBefore(event))
                .limit(size + 1L)
                .toList();
        var shown = page.size() > size ? page.subList(0, size) : page;
        return new TimelinePage(
                List.copyOf(shown),
                permitted.stream().map(AdminTimeline::kind).toList(),
                answers.unanswered(),
                page.size() > size
                        ? new Position(shown.getLast().at(), shown.getLast().key()).encode()
                        : null);
    }

    /** How many more than a page each module is asked for, since events at the cursor's own instant are dropped again. */
    private static final int SAME_INSTANT = 10;

    /** Where a page ended: an instant, and among events at that instant, the event. */
    private record Position(Instant at, String key) {

        boolean isBefore(AdminTimeline.TimelineEvent event) {
            return event.at().isBefore(at)
                    || (event.at().equals(at) && event.key().compareTo(key) < 0);
        }

        String encode() {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString((at + "|" + key).getBytes(StandardCharsets.UTF_8));
        }

        static Position decode(String cursor) {
            try {
                var raw = new String(Base64.getUrlDecoder().decode(cursor.strip()), StandardCharsets.UTF_8);
                var bar = raw.indexOf('|');
                return new Position(Instant.parse(raw.substring(0, bar)), raw.substring(bar + 1));
            } catch (RuntimeException notOurs) {
                throw ApiProblem.invalid("cursor", "isn't a cursor this API gave out.");
            }
        }
    }
}
