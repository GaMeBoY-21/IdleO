package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.hosts.HostListings;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Listings: the batch price and the host's limits (ADR 0015). */
@Service
class HostListingService implements HostListings {

    private final JdbcClient jdbc;
    private final Clock clock;

    HostListingService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Optional<Listing> listing(UUID hostId) {
        return jdbc.sql("""
                        select l.price_paise_per_min, h.gpu_temp_cap_c, h.max_concurrent_sessions, h.keep_free_gb
                        from hosts.hosts h
                        join hosts.price_listings l on l.host_id = h.id and l.workload_type = 'batch'
                        where h.id = :hostId
                        """)
                .param("hostId", hostId)
                .query((rs, n) -> new Listing(
                        rs.getInt("price_paise_per_min"),
                        rs.getInt("gpu_temp_cap_c"),
                        rs.getInt("max_concurrent_sessions"),
                        rs.getInt("keep_free_gb")))
                .optional();
    }

    /** Sets the owner's listing, replacing any earlier one; empty if the host isn't theirs or was removed. */
    @Transactional
    Optional<Listing> put(UUID ownerUserId, UUID hostId, Listing listing) {
        var now = Timestamp.from(Instant.now(clock));
        int updated = jdbc.sql("""
                        update hosts.hosts
                        set gpu_temp_cap_c = :cap, max_concurrent_sessions = :max, keep_free_gb = :keepFree, updated_at = :now
                        where id = :hostId and owner_user_id = :owner and status <> 'removed'
                        """)
                .param("cap", listing.gpuTempCapC())
                .param("max", listing.maxConcurrentSessions())
                .param("keepFree", listing.keepFreeGb())
                .param("now", now)
                .param("hostId", hostId)
                .param("owner", ownerUserId)
                .update();
        if (updated == 0) {
            return Optional.empty();
        }
        jdbc.sql("""
                        insert into hosts.price_listings (host_id, workload_type, price_paise_per_min, created_at, updated_at)
                        values (:hostId, 'batch', :price, :now, :now)
                        on conflict (host_id, workload_type)
                        do update set price_paise_per_min = excluded.price_paise_per_min, updated_at = excluded.updated_at
                        """)
                .param("hostId", hostId)
                .param("price", listing.batchPricePaisePerMin())
                .param("now", now)
                .update();
        return Optional.of(listing);
    }
}
