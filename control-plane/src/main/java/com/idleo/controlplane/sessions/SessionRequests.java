package com.idleo.controlplane.sessions;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The one check of a booking against its template (ADR 0019): the sessions module owns it, booking uses it, and the
 * scheduler's quote calls it, so the two always agree. Every problem is an {@code ApiProblem} from the catalog.
 */
public interface SessionRequests {

    /**
     * A request that fits its template.
     *
     * @param params resolved, with defaults filled in
     * @param inputUploadId the renter's ready upload the job reads, if it takes one
     * @param inputUrl the https URL the job fetches, if it takes one
     * @param env what the job's environment will be: its parameters and its input
     */
    record Checked(
            Template template,
            int vramNeededMb,
            Duration maxRuntime,
            Map<String, Object> params,
            @Nullable UUID inputUploadId,
            @Nullable String inputUrl,
            Map<String, String> env) {}

    Checked check(UUID renterId, CreateSessionRequest request);
}
