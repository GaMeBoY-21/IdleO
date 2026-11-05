package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.RequiresPermission;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin console's Installs card: how long installs take from opening the setup app to
 * the laptop listed, the last 20 with their clicks and where each stopped, and the failures by reason.
 */
@RestController
@ConditionalOnWebApplication
class AdminInstallsController {

    private final HostInstalls installs;

    AdminInstallsController(HostInstalls installs) {
        this.installs = installs;
    }

    @RequiresPermission(AdminPermission.HOSTS_READ)
    @AdminList("installs")
    @GetMapping("/admin/v1/installs")
    HostInstalls.InstallsSummary installs(
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        return installs.summary(sort, cursor, limit);
    }
}
