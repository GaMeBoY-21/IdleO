package com.idleo.controlplane.hosts;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * A host's rating from its renters (ADR 0028), for its owner's view of the machine. The hosts module declares this
 * port and the trust module implements it, because ratings are trust's, and a dependency from hosts on trust would
 * point the wrong way. It never says who rated or what they wrote.
 */
public interface HostRatings {

    /**
     * @param averageStars the mean of the stars, to one decimal place
     * @param count how many ratings it's the mean of
     */
    record Rating(BigDecimal averageStars, int count) {}

    /**
     * The ratings of these hosts' sessions that ended in the last 90 days; a host with fewer than 5 such ratings isn't
     * in the map, so no one rating can be picked out.
     */
    Map<UUID, Rating> of(Collection<UUID> hostIds);
}
