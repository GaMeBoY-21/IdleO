package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.identity.DashboardFigures;
import com.idleo.controlplane.identity.DashboardFigures.Figure;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The admin console's dashboard (ADR 0027): each figure from the module that owns it. */
@RestController
@ConditionalOnWebApplication
class AdminDashboardController {

    private final List<DashboardFigures> modules;
    private final Clock clock;

    AdminDashboardController(List<DashboardFigures> modules, Clock clock) {
        this.modules = modules;
        this.clock = clock;
    }

    /** The pilot's figures now, counted when asked. */
    record AdminDashboard(
            long hostsOnline,
            long sessionsRunning,
            long sessionsToday,
            long settlementBacklog,
            long reconciliationMismatches,
            long reviewQueue,
            Instant at) {}

    @GetMapping("/admin/v1/dashboard")
    AdminDashboard dashboard() {
        var figures = collect(modules);
        return new AdminDashboard(
                figures.get(Figure.HOSTS_ONLINE),
                figures.get(Figure.SESSIONS_RUNNING),
                figures.get(Figure.SESSIONS_TODAY),
                figures.get(Figure.SETTLEMENT_BACKLOG),
                figures.get(Figure.RECONCILIATION_MISMATCHES),
                figures.get(Figure.REVIEW_QUEUE),
                clock.instant());
    }

    /** Every figure, each from exactly one module. */
    static Map<Figure, Long> collect(List<DashboardFigures> modules) {
        var figures = new EnumMap<Figure, Long>(Figure.class);
        for (var module : modules) {
            module.figures().forEach((figure, value) -> {
                if (figures.put(figure, value) != null) {
                    throw new IllegalStateException(figure + " is counted by two modules");
                }
            });
        }
        for (var figure : Figure.values()) {
            if (!figures.containsKey(figure)) {
                throw new IllegalStateException("no module counts " + figure);
            }
        }
        return figures;
    }
}
