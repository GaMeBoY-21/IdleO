package com.idleo.controlplane.hosts;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * What the trust module knows that lowers a host's score. The hosts module declares this port and trust implements
 * it, as with {@link HostRatings}. A signal never says which renter reported, or what they wrote.
 */
public interface HostTrustSignals {

    /** What kind of mark it is. */
    enum Kind {
        /** A renter's report about one of the host's sessions that an admin answered with a refund. */
        UPHELD_REPORT,
        /** An admin marked the host while resolving a report: the "trust flag". */
        FLAG
    }

    /** One mark against a host, as of {@code at}. */
    record Signal(UUID hostId, Instant at, Kind kind) {}

    /** The marks since {@code since}, for these hosts. */
    List<Signal> since(Collection<UUID> hostIds, Instant since);
}
