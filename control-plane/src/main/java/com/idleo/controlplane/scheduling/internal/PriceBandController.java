package com.idleo.controlplane.scheduling.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.hosts.HostGpus;
import com.idleo.controlplane.hosts.HostOwners;
import com.idleo.controlplane.hosts.SchedulableHosts;
import com.idleo.controlplane.identity.CurrentUser;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The price a host is suggested to ask (ADR 0023). It lives here because it reads every listed host's price, and
 * hosts may not depend on scheduling (ADR 0019). Prices move in steps of 1 paisa a minute, ₹0.60 an hour.
 */
@RestController
class PriceBandController {

    private final HostOwners owners;
    private final HostGpus gpus;
    private final SchedulableHosts hosts;
    private final SchedulingProperties properties;

    PriceBandController(HostOwners owners, HostGpus gpus, SchedulableHosts hosts, SchedulingProperties properties) {
        this.owners = owners;
        this.gpus = gpus;
        this.hosts = hosts;
        this.properties = properties;
    }

    /**
     * @param lowPaisePerMin the low end, in paise a minute
     * @param highPaisePerMin the high end
     * @param sampleSize how many other hosts with the same GPU model it comes from; 0 when too few have listed one and
     *     it's the default for the GPU's memory class
     */
    record PriceBand(int lowPaisePerMin, int highPaisePerMin, int sampleSize) {}

    /** Another user's host is a 404. */
    @GetMapping("/v1/hosts/{hostId}/price-band")
    PriceBand get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID hostId) {
        if (!owners.owns(user.id(), hostId)) {
            throw ApiProblem.of(Problem.NOT_FOUND, "No such host.");
        }
        var gpu = gpus.largest(hostId)
                .orElseThrow(() -> ApiProblem.of(
                        Problem.HOST_NO_GPU, "This host's agent hasn't reported a GPU yet; connect it first."));
        var peers = hosts.listedForBatch().stream()
                .filter(candidate -> !candidate.hostId().equals(hostId)
                        && candidate.gpuModel().equals(gpu.model()))
                .map(SchedulableHosts.Candidate::pricePaisePerMin)
                .toList();
        var band = PriceBands.of(peers, gpu.vramMb(), properties);
        return new PriceBand(band.lowPaisePerMin(), band.highPaisePerMin(), band.sampleSize());
    }
}
