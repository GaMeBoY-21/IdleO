package com.idleo.controlplane.privacy.internal;

import com.idleo.controlplane.PersonalData;
import com.idleo.controlplane.identity.BrowserSignInOnly;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.identity.OpenToWaitingUsers;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A person's own data (ADR 0037): what we hold, by group, and its export. See the module's documentation. */
@RestController
@RequestMapping("/v1/me")
@ConditionalOnWebApplication
class YourDataController {

    private final List<PersonalData> modules;
    private final Exports exports;

    YourDataController(List<PersonalData> modules, Exports exports) {
        this.modules = List.copyOf(modules);
        this.exports = exports;
    }

    /** One thing counted: what, how many, and the dates they span (null when there are none). */
    record DataItem(
            String label,
            long count,
            @Nullable Instant earliest,
            @Nullable Instant latest) {}

    /** One group, in the order Account shows them: {@code account}, {@code sessions}, … {@code reports}. */
    record DataGroup(String group, List<DataItem> items) {}

    record YourData(List<DataGroup> groups) {}

    /** What we hold about the person, by group, with counts and dates; nothing of the records themselves. */
    @OpenToWaitingUsers
    @GetMapping("/data")
    YourData data(@AuthenticationPrincipal CurrentUser user) {
        var all = new ArrayList<PersonalData.Group>();
        modules.forEach(module -> all.addAll(module.summary(user.id())));
        return new YourData(PersonalData.GROUPS.stream()
                .map(group -> new DataGroup(
                        group,
                        all.stream()
                                .filter(item -> item.group().equals(group))
                                .sorted(Comparator.comparing(PersonalData.Group::label))
                                .map(item -> new DataItem(item.label(), item.count(), item.earliest(), item.latest()))
                                .toList()))
                .toList());
    }

    record ExportList(List<Exports.Export> exports) {}

    /** The person's exports, newest first. */
    @OpenToWaitingUsers
    @GetMapping("/exports")
    ExportList list(@AuthenticationPrincipal CurrentUser user) {
        return new ExportList(exports.list(user.id()));
    }

    /**
     * Asks for an export of everything: 202 with it, made by a job and emailed when ready. 409 {@code
     * export-in-progress} while one is being made; 429 {@code export-too-soon} within a day of the last, with {@code
     * Retry-After}. Only in a browser.
     */
    @OpenToWaitingUsers
    @BrowserSignInOnly
    @PostMapping("/exports")
    ResponseEntity<Exports.Export> request(@AuthenticationPrincipal CurrentUser user) {
        var export = exports.request(user.id());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/v1/me/exports/" + export.id()))
                .body(export);
    }

    record ExportDownload(String url, Instant expiresAt) {}

    /** A link to the person's own ready export, which works for 5 minutes; 404 for anyone else's. */
    @OpenToWaitingUsers
    @GetMapping("/exports/{id}/download")
    ExportDownload download(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        var download = exports.download(user.id(), id);
        return new ExportDownload(download.url().toString(), download.expiresAt());
    }
}
