package com.idleo.controlplane.billing;

import java.util.List;
import java.util.UUID;

/**
 * A ledger transaction changed what these hosts have earned (a settlement, a giveback for lost time or a refund to a
 * renter), so their agents' trays can show today's figure again. Published by the one posting primitive, in the
 * posting's own database transaction.
 */
public record HostEarningsChanged(List<UUID> hostIds) {

    public HostEarningsChanged {
        hostIds = List.copyOf(hostIds);
    }
}
