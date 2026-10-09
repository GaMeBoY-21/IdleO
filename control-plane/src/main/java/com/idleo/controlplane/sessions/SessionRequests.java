package com.idleo.controlplane.sessions;

import java.time.Duration;
import java.util.List;
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
     * @param params resolved, with presets and defaults filled in
     * @param presets the option of each preset question, defaults filled in
     * @param inputs what the job reads, in the template's order of inputs; only those given
     * @param env what the job's environment will be: its parameters and its inputs
     * @param diskNeededMb the free disk a host needs for its uploaded inputs and scratch disk, with a margin
     */
    record Checked(
            Template template,
            int vramNeededMb,
            int diskNeededMb,
            Duration maxRuntime,
            Map<String, Object> params,
            Map<String, String> presets,
            List<Input> inputs,
            Map<String, String> env) {

        public Checked {
            params = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(params));
            presets = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(presets));
            inputs = List.copyOf(inputs);
        }
    }

    /**
     * One input as given: a ready upload of the renter's, or an https link.
     *
     * @param name the template's name for it
     * @param uploadBytes the upload's size; 0 for a link
     */
    record Input(
            String name, @Nullable UUID uploadId, @Nullable String url, long uploadBytes) {}

    /** The check for booking: everything, including the input the template needs. */
    default Checked check(UUID renterId, CreateSessionRequest request) {
        return check(renterId, request, false);
    }

    /**
     * The one check, for booking and quoting alike. {@code inputMayWait}, for a quote only: an input the template
     * requires may be left out, so a price can be shown while the upload is still going (ADR 0021); an input that is
     * given is still checked in full.
     */
    Checked check(UUID renterId, CreateSessionRequest request, boolean inputMayWait);
}
