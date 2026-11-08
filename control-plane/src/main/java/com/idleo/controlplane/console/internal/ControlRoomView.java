package com.idleo.controlplane.console.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ControlRoom;
import com.idleo.controlplane.identity.AdminAccess;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dashboard as one admin may see it (ADR 0056), from the rollups alone: money only for an admin who can read money,
 * decided here on the server; and utilisation, busy GPU-hours over online GPU-hours by model, from the two parts.
 */
@Component
class ControlRoomView {

    private static final TypeReference<List<ControlRoom.Step>> STEPS = new TypeReference<>() {};
    private static final TypeReference<List<ControlRoom.Check>> CHECKS = new TypeReference<>() {};

    private final ControlRoomRollups rollups;
    private final AdminAccess access;
    private final JsonMapper json;

    ControlRoomView(ControlRoomRollups rollups, AdminAccess access, JsonMapper json) {
        this.rollups = rollups;
        this.access = access;
        this.json = json;
    }

    ControlRoom.Dashboard dashboard(UUID admin) {
        var kept = rollups.kept();
        var money = access.permissions(admin).contains(AdminPermission.MONEY_READ)
                ? read(kept.bodies().get("money"), ControlRoom.Money.class)
                : null;
        var marketplace = read(kept.bodies().get("marketplace"), ControlRoom.Marketplace.class);
        var supply = read(kept.bodies().get("supply"), ControlRoom.Supply.class);
        var funnels = kept.bodies().containsKey("funnels")
                ? json.readValue(kept.bodies().get("funnels"), STEPS)
                : List.<ControlRoom.Step>of();
        var health = kept.bodies().containsKey("health")
                ? json.readValue(kept.bodies().get("health"), CHECKS)
                : List.<ControlRoom.Check>of();
        var computedAt = new HashMap<>(kept.computedAt());
        if (money == null) {
            computedAt.remove("money");
        }
        return new ControlRoom.Dashboard(
                money, marketplace, supply, utilisation(marketplace, supply), funnels, health, computedAt);
    }

    /** Busy over online GPU-hours for each model online in the last 24 hours. */
    static List<ControlRoom.ByModel> utilisation(
            ControlRoom.@Nullable Marketplace marketplace, ControlRoom.@Nullable Supply supply) {
        if (marketplace == null || supply == null) {
            return List.of();
        }
        var busy = new HashMap<String, Double>();
        marketplace.busyGpuHours().forEach(model -> busy.put(model.model(), model.value()));
        var ratios = new ArrayList<ControlRoom.ByModel>();
        for (var online : supply.onlineGpuHours()) {
            if (online.value() > 0) {
                ratios.add(new ControlRoom.ByModel(
                        online.model(), ControlRoom.round(busy.getOrDefault(online.model(), 0.0) / online.value())));
            }
        }
        return ratios;
    }

    private <T> @Nullable T read(@Nullable String body, Class<T> type) {
        return body == null || body.equals("null") ? null : json.readValue(body, type);
    }
}
