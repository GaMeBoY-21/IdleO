package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.IdempotentRequests;
import com.idleo.controlplane.identity.CurrentUser;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** "Run self-test" on the owner's machine page. The rules are {@link SelfTestRuns}'. */
@RestController
@RequestMapping("/v1/hosts/{id}")
class HostSelfTestController {

    private final HostViews views;
    private final SelfTestRuns runs;
    private final IdempotentRequests idempotency;

    HostSelfTestController(HostViews views, SelfTestRuns runs, IdempotentRequests idempotency) {
        this.views = views;
        this.runs = runs;
        this.idempotency = idempotency;
    }

    /** The newest run, with the checks that have arrived, and whether another can start now (and if not, why). */
    @GetMapping("/self-test")
    SelfTestRuns.SelfTestStatus get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        views.owned(user.id(), id);
        return runs.status(id);
    }

    /** Asks the machine's agent to run its full self-test now. Like pausing, it is open to an API key. */
    @PostMapping("/self-tests")
    ResponseEntity<String> start(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @RequestHeader(name = IdempotentRequests.HEADER, required = false) @Nullable String idempotencyKey) {
        views.owned(user.id(), id);
        return idempotency.execute(HostController.IDEMPOTENCY_TABLE, user.id(), idempotencyKey, id, () -> {
            runs.start(id, SelfTestRuns.Requester.owner(user.id()));
            return ResponseEntity.accepted().body(runs.status(id));
        });
    }
}
