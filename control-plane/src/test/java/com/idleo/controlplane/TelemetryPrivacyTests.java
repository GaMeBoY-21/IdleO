package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Metrics and traces carry nothing personal (ADR 0036, ADR 0037, docs/reference/personal-data-map.md): no meter's tag is an
 * id, an email, an address or a path with an id in it, and no trace attribute holds an email, an address or a query
 * string. A trace's URL path may hold an opaque id, which leads to nobody once an account is erased.
 */
@ControlPlaneTest
class TelemetryPrivacyTests {

    private static final Pattern UUID_SHAPED =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);

    @Autowired
    MockMvc mvc;

    @Autowired
    MeterRegistry meters;

    @Autowired
    ObservationRegistry observations;

    /** Requests that put an id in a path, an email in a query, and a session that's someone else's. */
    private void traffic(TestUsers.TestUser user) throws Exception {
        mvc.perform(get("/v1/me").header("Authorization", user.bearer()));
        mvc.perform(get("/v1/me").queryParam("email", "asha@example.com").header("Authorization", user.bearer()));
        mvc.perform(get("/v1/sessions/" + UUID.randomUUID()).header("Authorization", user.bearer()));
        mvc.perform(get("/v1/uploads/" + UUID.randomUUID()).header("Authorization", user.bearer()));
        mvc.perform(get("/v1/me").header("Authorization", "Bearer not-a-token"));
    }

    @Test
    void noMetersTagIsPersonal() throws Exception {
        traffic(TestUsers.signUp(mvc));
        var tags = meters.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream()
                        .map(tag -> meter.getId().getName() + " " + tag.getKey() + "=" + tag.getValue()))
                .toList();
        assertThat(tags).isNotEmpty();
        for (var tag : tags) {
            var value = tag.substring(tag.indexOf('=') + 1);
            assertThat(UUID_SHAPED.matcher(value).find()).as(tag).isFalse();
            // A version (the JVM's 25.0.4.1) is shaped like an address, and is neither.
            if (!tag.contains("version=")) {
                assertThat(ContactDetails.found(value)).as(tag).isFalse();
            }
            assertThat(value).as(tag).doesNotContain("?");
        }
        assertThat(tags.stream().filter(tag -> tag.startsWith("http.server.requests uri=")))
                .contains("http.server.requests uri=/v1/sessions/{id}");
    }

    @Test
    void noTraceAttributeIsPersonal() throws Exception {
        // The shared context keeps a handler once it's added (Micrometer can't remove one), so this one records only
        // while this test runs. Left recording, it copied every later observation in the suite, on whichever thread
        // made it, Valkey's event loop included, until other tests' Valkey commands missed their 1 s timeout.
        var recording = new AtomicBoolean(true);
        var seen = new ConcurrentLinkedQueue<KeyValue>();
        ObservationHandler<Observation.Context> recorder = new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return recording.get();
            }

            @Override
            public void onStop(Observation.Context context) {
                if (recording.get()) {
                    context.getAllKeyValues().forEach(seen::add);
                }
            }
        };
        observations.observationConfig().observationHandler(recorder);
        try {
            traffic(TestUsers.signUp(mvc));
        } finally {
            recording.set(false);
        }

        assertThat(seen).isNotEmpty();
        for (var attribute : List.copyOf(seen)) {
            assertThat(ContactDetails.found(attribute.getValue()))
                    .as(attribute.toString())
                    .isFalse();
            assertThat(attribute.getValue())
                    .as(attribute.toString())
                    .doesNotContain("?")
                    .doesNotContain("Bearer ")
                    .doesNotContain("eyJ");
        }
    }
}
