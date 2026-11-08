package com.idleo.controlplane;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The live dashboard's parts (ADR 0056), each counted by the module that owns the facts, from its own tables, and asked
 * only by the console's rollup job: never on a page view. A module implements the part it owns as a bean; the console
 * stores what each gives in its rollups and the dashboard reads those.
 */
public final class ControlRoom {

    private ControlRoom() {}

    /** India's hours, which the heatmaps are drawn in. */
    public static final java.time.ZoneId INDIA = java.time.ZoneId.of("Asia/Kolkata");

    /** What a host's GPU is called before its agent has reported it. */
    public static final String UNKNOWN_MODEL = "not reported yet";

    /** The hours from {@code from} to {@code to}. */
    public static double hours(Instant from, Instant to) {
        return java.time.Duration.between(from, to).toMillis() / 3_600_000.0;
    }

    /**
     * Adds to each hour of the day (India's, 0 to 23) its share of {@code from} to {@code to}, in hours: the one way a
     * stretch online or running becomes a heatmap's row.
     */
    public static void spreadByHour(Instant from, Instant to, double[] cells) {
        var at = from;
        while (at.isBefore(to)) {
            var local = at.atZone(INDIA);
            var nextHour =
                    local.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant();
            var end = nextHour.isBefore(to) ? nextHour : to;
            cells[local.getHour()] += hours(at, end);
            at = end;
        }
    }

    /** {@code value} to two decimal places, as the dashboard shows hours and ratios. */
    public static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    /**
     * Money over a window, the same figures as the money reports' (ADR 0055) over the same days in India.
     *
     * @param gmvPaise what sessions charged renters, hosts' and our shares together
     * @param takePaise our revenue, net
     * @param refundsPaise bought credit refunded
     * @param chargebacksPaise what chargebacks took back
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "ControlRoomMoneyWindow")
    public record MoneyWindow(long gmvPaise, long takePaise, long refundsPaise, long chargebacksPaise) {}

    /** Today, the last 7 days and the last 30, each ending today, in India. */
    @io.swagger.v3.oas.annotations.media.Schema(name = "ControlRoomMoney")
    public record Money(MoneyWindow today, MoneyWindow week, MoneyWindow month) {}

    /** Billing's part. */
    public interface MoneySource {
        Money money(Instant now);
    }

    /** One reason sessions failed, and how many, over the last 24 hours. */
    @io.swagger.v3.oas.annotations.media.Schema(name = "ControlRoomReason")
    public record Reason(String reason, long count) {}

    /** How many there are of one GPU model. */
    @io.swagger.v3.oas.annotations.media.Schema(name = "ControlRoomByModel")
    public record ByModel(String model, double value) {}

    /** One cell of a heatmap: a GPU model at an hour of the day in India (0 to 23), over the last 7 days. */
    @io.swagger.v3.oas.annotations.media.Schema(name = "ControlRoomCell")
    public record Cell(String model, int hour, double value) {}

    /**
     * The marketplace now, and over the last 24 hours.
     *
     * @param sessionsRunning sessions running now, stopping included
     * @param bookingsWaiting bookings waiting to be matched with a host
     * @param bookingsToday sessions booked since midnight in India
     * @param matchP50Seconds how long a booking waited to be matched, the median; null with none matched
     * @param matchP95Seconds the same, the 95th percentile
     * @param startsAttempted sessions that were matched, and so tried to start
     * @param startsSucceeded of those, the ones that reached running
     * @param failures the five commonest reasons sessions ended without finishing, most first
     * @param busyGpuHours GPU-hours sessions ran, by the model they ran on
     * @param demand session-hours started, by model and hour of day
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "ControlRoomMarketplace")
    public record Marketplace(
            long sessionsRunning,
            long bookingsWaiting,
            long bookingsToday,
            @Nullable Double matchP50Seconds,
            @Nullable Double matchP95Seconds,
            long startsAttempted,
            long startsSucceeded,
            List<Reason> failures,
            List<ByModel> busyGpuHours,
            List<Cell> demand) {}

    /** The sessions module's part. */
    public interface MarketplaceSource {
        Marketplace marketplace(Instant now);
    }

    /**
     * Supply: hosts online, from the agent gateway's presence history.
     *
     * @param online hosts online now, by GPU model; null while presence can't be read
     * @param onlineGpuHours over the last 24 hours, by model
     * @param supply hosts online, averaged by model and hour of day over the last 7 days
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "ControlRoomSupply")
    public record Supply(@Nullable List<ByModel> online, List<ByModel> onlineGpuHours, List<Cell> supply) {}

    /** The agent gateway's part. */
    public interface SupplySource {
        Supply supply(Instant now);
    }

    /**
     * One step of a funnel: everyone who ever reached it.
     *
     * @param funnel {@code renters} or {@code hosts}
     * @param order its place in the funnel, from 1
     * @param step what reaching it means, such as "first top-up"
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "ControlRoomStep")
    public record Step(String funnel, int order, String step, long people) {}

    /** A module's steps of the funnels, each counted from its own tables. */
    public interface FunnelSource {
        List<Step> steps();
    }

    /**
     * One of the control plane's own checks.
     *
     * @param name what it checks, such as "database"
     * @param ok whether it's as it should be
     * @param detail what it found, in plain words
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "ControlRoomCheck")
    public record Check(String name, boolean ok, String detail) {}

    /** A module's checks, each cheap: they run every 10 s. */
    public interface HealthSource {
        List<Check> checks(Instant now);
    }

    /**
     * The whole dashboard as the rollups hold it.
     *
     * @param money null for an admin who can't read money
     * @param utilisation busy GPU-hours over online GPU-hours, the last 24 hours, by model; null with none online
     * @param computedAt when each part was last worked out
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "ControlRoomDashboard")
    public record Dashboard(
            @Nullable Money money,
            @Nullable Marketplace marketplace,
            @Nullable Supply supply,
            List<ByModel> utilisation,
            List<Step> funnels,
            List<Check> health,
            Map<String, Instant> computedAt) {}
}
