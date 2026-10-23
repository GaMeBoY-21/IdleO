package com.idleo.controlplane.scheduling.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.hosts.HostGpus;
import com.idleo.controlplane.hosts.HostOwners;
import com.idleo.controlplane.hosts.SchedulableHosts;
import com.idleo.controlplane.identity.CurrentUser;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final CreditReservations reservations;

    PriceBandController(
            HostOwners owners,
            HostGpus gpus,
            SchedulableHosts hosts,
            SchedulingProperties properties,
            CreditReservations reservations) {
        this.reservations = reservations;
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
     * @param gstPercent the GST added to what renters pay (ADR 0039): a listing price is always before it, and a host's
     *     earnings never change with it; zero while no GST is charged
     */
    record PriceBand(int lowPaisePerMin, int highPaisePerMin, int sampleSize, int gstPercent) {}

    /**
     * The band for a GPU that isn't a host yet: the laptop a link is asking to add (ADR 0032). The approval page asks
     * with the model and memory the machine reported, to prefill its price; the range is the same as a host's own
     * would be, from every listed host with that model (the machine isn't one, so none is left out).
     */
    @GetMapping("/v1/price-band")
    PriceBand forGpu(@RequestParam String gpuModel, @RequestParam int vramMb) {
        if (gpuModel.isBlank() || gpuModel.length() > 100 || vramMb < 1 || vramMb > 1_000_000) {
            throw ApiProblem.of(
                    Problem.VALIDATION_FAILED, "gpuModel is 1 to 100 characters and vramMb 1 to 1,000,000.");
        }
        var peers = hosts.listedForBatch().stream()
                .filter(candidate -> candidate.gpuModel().equals(gpuModel))
                .map(SchedulableHosts.Candidate::pricePaisePerMin)
                .toList();
        var band = PriceBands.of(peers, vramMb, properties);
        return new PriceBand(
                band.lowPaisePerMin(), band.highPaisePerMin(), band.sampleSize(), reservations.gstPercent());
    }

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
        return new PriceBand(
                band.lowPaisePerMin(), band.highPaisePerMin(), band.sampleSize(), reservations.gstPercent());
    }
}
