package com.idleo.controlplane.hosts.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin console's Installs card (host experience §6 row 11): how long installs take from opening the setup app to
 * the laptop listed, the last 20 with their clicks and where each stopped, and the failures by reason.
 */
@RestController
@ConditionalOnWebApplication
class AdminInstallsController {

    private final HostInstalls installs;

    AdminInstallsController(HostInstalls installs) {
        this.installs = installs;
    }

    @GetMapping("/admin/v1/installs")
    HostInstalls.InstallsSummary installs() {
        return installs.summary();
    }
}
