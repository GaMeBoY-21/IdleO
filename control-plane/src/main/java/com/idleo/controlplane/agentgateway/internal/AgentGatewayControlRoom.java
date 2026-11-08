package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.ControlRoom;
import com.idleo.controlplane.hosts.HostGpus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The agent gateway's parts of the live dashboard (ADR 0056): hosts online now by GPU model, online GPU-hours, and the
 * supply heatmap, all from presence; and its check of the agents' streams. A host counts as its largest GPU, the one a
 * session runs on.
 */
@Component
class AgentGatewayControlRoom implements ControlRoom.SupplySource, ControlRoom.HealthSource {

    private final PresenceHistory history;
    private final Presence presence;
    private final HostGpus gpus;
    private final JdbcClient jdbc;

    AgentGatewayControlRoom(PresenceHistory history, Presence presence, HostGpus gpus, JdbcClient jdbc) {
        this.history = history;
        this.presence = presence;
        this.gpus = gpus;
        this.jdbc = jdbc;
    }

    @Override
    public ControlRoom.Supply supply(Instant now) {
        var models = new HashMap<UUID, String>();
        java.util.function.Function<UUID, String> model = host -> models.computeIfAbsent(
                host, id -> gpus.largest(id).map(HostGpus.Gpu::model).orElse(ControlRoom.UNKNOWN_MODEL));

        var recordedOnline = jdbc.sql("select host_id from agentgateway.presence_states where state = 'online'")
                .query(UUID.class)
                .list();
        var lookup = presence.lookup(recordedOnline);
        List<ControlRoom.ByModel> online = null;
        if (lookup.known()) {
            var counts = new TreeMap<String, Double>();
            lookup.agents().keySet().forEach(host -> counts.merge(model.apply(host), 1.0, Double::sum));
            online = byModel(counts);
        }

        var hours = new TreeMap<String, Double>();
        for (var interval : history.online(now.minus(Duration.ofHours(24)), now)) {
            hours.merge(model.apply(interval.hostId()), ControlRoom.hours(interval.from(), interval.to()), Double::sum);
        }

        // Hosts online, averaged over the 7 days' each hour of the day: host-hours in each hour, over 7.
        var heat = new TreeMap<String, double[]>();
        for (var interval : history.online(now.minus(Duration.ofDays(7)), now)) {
            var cells = heat.computeIfAbsent(model.apply(interval.hostId()), key -> new double[24]);
            ControlRoom.spreadByHour(interval.from(), interval.to(), cells);
        }
        var supply = new ArrayList<ControlRoom.Cell>();
        heat.forEach((name, cells) -> {
            for (int hour = 0; hour < 24; hour++) {
                if (cells[hour] > 0) {
                    supply.add(new ControlRoom.Cell(name, hour, ControlRoom.round(cells[hour] / 7)));
                }
            }
        });
        return new ControlRoom.Supply(online, byModel(hours), supply);
    }

    private static List<ControlRoom.ByModel> byModel(Map<String, Double> values) {
        return values.entrySet().stream()
                .map(entry -> new ControlRoom.ByModel(entry.getKey(), ControlRoom.round(entry.getValue())))
                .toList();
    }

    /** Whether the agents' presence can be read, and how many are connected, across every instance. */
    @Override
    public List<ControlRoom.Check> checks(Instant now) {
        var recordedOnline = jdbc.sql("select host_id from agentgateway.presence_states where state <> 'offline'")
                .query(UUID.class)
                .list();
        var lookup = presence.lookup(recordedOnline);
        if (!lookup.known()) {
            return List.of(new ControlRoom.Check(
                    "agent streams",
                    false,
                    "presence can't be read (Valkey), so whether agents are connected isn't known"));
        }
        int agents = lookup.agents().size();
        return List.of(new ControlRoom.Check(
                "agent streams", true, agents + " agent" + (agents == 1 ? "" : "s") + " connected"));
    }
}
