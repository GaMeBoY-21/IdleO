package com.idleo.controlplane.console.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ControlRoom;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The live dashboard (ADR 0056): the rollups as this admin may see them, at once and then every 10 s over server-sent
 * events. Nothing here asks a module anything: the rollup job does, once for everyone.
 */
@RestController
@ConditionalOnWebApplication
class AdminControlRoomController {

    private final ControlRoomView view;
    private final ControlRoomStreams streams;

    AdminControlRoomController(ControlRoomView view, ControlRoomStreams streams) {
        this.view = view;
        this.streams = streams;
    }

    @RequiresPermission(AdminPermission.OPS_DASHBOARD_READ)
    @GetMapping("/admin/v1/control-room")
    ControlRoom.Dashboard dashboard(@AuthenticationPrincipal CurrentUser admin) {
        return view.dashboard(admin.id());
    }

    @RequiresPermission(AdminPermission.OPS_DASHBOARD_READ)
    @GetMapping(value = "/admin/v1/control-room/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            description = "The dashboard as a `dashboard` event at once and every 10 seconds, the same body as GET"
                    + " /admin/v1/control-room. It closes after 10 minutes: reconnect.")
    SseEmitter stream(@AuthenticationPrincipal CurrentUser admin, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        return streams.open(admin.id());
    }
}
