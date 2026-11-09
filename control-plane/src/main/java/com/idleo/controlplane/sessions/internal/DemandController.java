package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.ApiProblem;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * When renters want a GPU of this much memory (ADR 0058, the host site's "Demand for your GPU"), from the last 28 days'
 * bookings that one could have run: asked, served, and the gap, the ones that ended without a host (ADR 0056's unmet
 * demand), by hour of the day in India. Counts only, of everyone's bookings; never who, or what they ran. With too few
 * bookings to say anything ({@link #ENOUGH}), it says so rather than draw a pattern from noise.
 */
@RestController
@ConditionalOnWebApplication
class DemandController {

    static final Duration WINDOW = Duration.ofDays(28);
    /** Fewer bookings than this in the window and {@code enough} is false: the hours would be noise. */
    static final int ENOUGH = 20;

    private final JdbcClient jdbc;
    private final Clock clock;

    DemandController(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * @param hour 0 to 23, in India
     * @param asked bookings that this GPU had the memory for
     * @param served those that ran
     * @param unmet those that ended without a host
     */
    record DemandHour(int hour, long asked, long served, long unmet) {}

    /**
     * @param vramMb the GPU's memory, as asked
     * @param days how far back, in days
     * @param enough whether there were enough bookings to read anything from the hours
     */
    record Demand(int vramMb, int days, long asked, long served, long unmet, boolean enough, List<DemandHour> hours) {}

    @GetMapping("/v1/demand")
    Demand demand(@RequestParam int vramMb) {
        if (vramMb < 1 || vramMb > 1_000_000) {
            throw ApiProblem.invalid("vramMb", "must be from 1 to 1000000.");
        }
        var from = Timestamp.from(Instant.now(clock).minus(WINDOW));
        var unmet = new HashMap<Integer, Long>();
        jdbc.sql("""
                        select asked_hour, count(*) as n from sessions.unmet_bookings
                        where asked_at >= :from and gpu_class_mb <= :vram group by asked_hour
                        """).param("from", from).param("vram", vramMb).query(rs -> {
            unmet.put(rs.getInt("asked_hour"), rs.getLong("n"));
        });
        var asked = new long[24];
        var served = new long[24];
        jdbc.sql("select " + SessionsControlRoom.HOUR_ASKED + """
                         as hour, count(*) as asked, count(started_at) as served
                        from sessions.sessions
                        where created_at >= :from and vram_needed_mb <= :vram
                        group by 1
                        """)
                .param("from", from)
                .param("vram", vramMb)
                .query(rs -> {
                    asked[rs.getInt("hour")] = rs.getLong("asked");
                    served[rs.getInt("hour")] = rs.getLong("served");
                });
        var hours = new ArrayList<DemandHour>();
        long totalAsked = 0;
        long totalServed = 0;
        long totalUnmet = 0;
        for (int hour = 0; hour < 24; hour++) {
            long gap = unmet.getOrDefault(hour, 0L);
            hours.add(new DemandHour(hour, asked[hour], served[hour], gap));
            totalAsked += asked[hour];
            totalServed += served[hour];
            totalUnmet += gap;
        }
        return new Demand(
                vramMb, (int) WINDOW.toDays(), totalAsked, totalServed, totalUnmet, totalAsked >= ENOUGH, hours);
    }
}
