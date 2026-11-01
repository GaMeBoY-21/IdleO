package com.idleo.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.idleo.controlplane.TestHosts.ConnectedHost;
import com.idleo.controlplane.TestUsers.TestUser;
import gpu.agent.v1.Session.Offer;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one way a test waits on what matching decides after a booking: an offer to a host, a session's state, or
 * anything else that follows from the matcher. A wait lasts {@link #WAIT}, and {@link #WAIT} again after each time the
 * matcher finds presence unreadable. When Valkey doesn't answer in time, which a busy runner sometimes doesn't, the
 * matcher neither offers a booking nor ends it: it stays REQUESTED and is looked at again a little later
 * (SessionMatcher, ADR 0016). That moves when the matcher decides, never what it decides, so the wait follows it, until
 * {@link #PRESENCE_UNKNOWN_LIMIT}. A failure says how many times presence was unreadable meanwhile.
 */
public final class BookingWaits {

    public static final Duration WAIT = Duration.ofSeconds(10);

    /**
     * The longest a wait on the matcher goes on while it can't read presence: a Valkey that hasn't answered for this long
     * is down, not slow, and the test fails.
     */
    public static final Duration PRESENCE_UNKNOWN_LIMIT = Duration.ofMinutes(1);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvc mvc;
    private final List<ApplicationContext> instances;

    /** Waits on the matcher of each of {@code instances}: whichever one matches the booking. */
    public BookingWaits(MockMvc mvc, ApplicationContext... instances) {
        this.mvc = mvc;
        this.instances = List.of(instances);
    }

    /** One look at what a test waits for: null until it's there. */
    @FunctionalInterface
    public interface Look<T> {
        @Nullable
        T next() throws Exception;
    }

    /** The next offer to this host. */
    public Offer offer(ConnectedHost host) throws Exception {
        return until(
                () -> host.nextOffer(Duration.ofMillis(50)).orElse(null),
                () -> "host " + host.id() + " was offered nothing");
    }

    /** The session, as its renter reads it, once it's in {@code state}. */
    public JsonNode state(TestUser renter, UUID sessionId, String state) throws Exception {
        var last = new JsonNode[1];
        return until(
                () -> {
                    last[0] = session(renter, sessionId);
                    return last[0].get("state").asString().equals(state) ? last[0] : null;
                },
                () -> "session " + sessionId + " never reached " + state + "; it's " + last[0]);
    }

    /** The session as its renter reads it now. */
    public JsonNode session(TestUser renter, UUID sessionId) throws Exception {
        return JSON.readTree(mvc.perform(get("/v1/sessions/" + sessionId).header("Authorization", renter.bearer()))
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    /** Looks until {@code look} finds what the test waits for, as long as the matcher may still be deciding. */
    public <T> T until(Look<T> look, Supplier<String> failure) throws Exception {
        long started = System.nanoTime();
        long deadline = started + WAIT.toNanos();
        double unknownAtStart = presenceUnknownLooks();
        double unknown = unknownAtStart;
        while (true) {
            var found = look.next();
            if (found != null) {
                return found;
            }
            double now = presenceUnknownLooks();
            if (now > unknown) {
                unknown = now;
                deadline = Math.min(System.nanoTime() + WAIT.toNanos(), started + PRESENCE_UNKNOWN_LIMIT.toNanos());
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(failure.get() + " (the matcher found presence unreadable "
                        + (long) (unknown - unknownAtStart) + " times meanwhile)");
            }
            Thread.sleep(50);
        }
    }

    /** How many times the matchers have looked for a host and couldn't read presence (SchedulingMetrics). */
    private double presenceUnknownLooks() {
        double looks = 0;
        for (var instance : instances) {
            var counter = instance.getBean(MeterRegistry.class)
                    .find("scheduling.match.outcome")
                    .tag("outcome", "presence_unknown")
                    .counter();
            looks += counter == null ? 0 : counter.count();
        }
        return looks;
    }
}
