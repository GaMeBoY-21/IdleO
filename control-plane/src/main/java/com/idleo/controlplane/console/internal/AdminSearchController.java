package com.idleo.controlplane.console.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminSearch;
import com.idleo.controlplane.PersonalDataMask;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.AdminAccess;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.identity.PersonalDataAccess;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one search box (ADR 0052). Every module's {@link AdminSearch} the admin holds the permission for is asked at the
 * same time, each within a time limit; one that doesn't answer in time is named, and the others still count. An admin
 * without a kind's permission never learns that anything of that kind exists: its provider isn't even asked.
 */
@RestController
@RequestMapping("/admin/v1/search")
@ConditionalOnWebApplication
@EnableConfigurationProperties(ConsoleSearchProperties.class)
@Validated
class AdminSearchController {

    private static final Logger log = LoggerFactory.getLogger(AdminSearchController.class);
    private static final int SHORTEST = 2;

    private final List<AdminSearch> providers;
    private final AdminAccess access;
    private final PersonalDataAccess accessLog;
    private final ConsoleSearchProperties properties;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    AdminSearchController(
            List<AdminSearch> providers,
            AdminAccess access,
            PersonalDataAccess accessLog,
            ConsoleSearchProperties properties) {
        this.providers = providers.stream()
                .sorted(Comparator.comparing(AdminSearch::type))
                .toList();
        this.access = access;
        this.accessLog = accessLog;
        this.properties = properties;
    }

    /** One kind of thing found. */
    record SearchGroup(String type, List<AdminSearch.Hit> hits) {}

    /**
     * @param groups what was found, by kind; a kind with nothing found isn't here
     * @param unanswered the kinds that didn't answer in time, so the admin knows the list may be short
     */
    record SearchResults(List<SearchGroup> groups, List<String> unanswered) {}

    /**
     * Finds what the admin may read by {@code q}: an id, an exact email, UPI ID or PAN, a Razorpay id, a document or
     * ticket number, an image digest, or part of a name. Each search goes in the access log, its text masked.
     */
    @RequiresPermission(AdminPermission.SELF)
    @GetMapping
    SearchResults search(
            @AuthenticationPrincipal CurrentUser admin, @RequestParam @NotBlank @Size(max = 200) String q) {
        var query = new AdminSearch.Query(q.strip());
        if (query.text().length() < SHORTEST) {
            return new SearchResults(List.of(), List.of());
        }
        var held = access.permissions(admin.id());
        var asked = new LinkedHashMap<String, Future<List<AdminSearch.Hit>>>();
        for (var provider : providers) {
            if (held.contains(provider.permission())) {
                asked.put(provider.type(), threads.submit(() -> provider.find(query, properties.hitsPerType())));
            }
        }
        long deadline = System.nanoTime() + properties.providerTimeout().toNanos();
        var groups = new ArrayList<SearchGroup>();
        var unanswered = new ArrayList<String>();
        asked.forEach((type, answer) -> {
            try {
                var hits = answer.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (!hits.isEmpty()) {
                    groups.add(new SearchGroup(type, hits));
                }
            } catch (TimeoutException | ExecutionException failed) {
                answer.cancel(true);
                unanswered.add(type);
                log.warn(
                        "The search for {} didn't answer: {}",
                        type,
                        failed.getClass().getSimpleName());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                unanswered.add(type);
            }
        });
        accessLog.searched(admin, PersonalDataMask.query(query.text()));
        return new SearchResults(List.copyOf(groups), List.copyOf(unanswered));
    }
}
