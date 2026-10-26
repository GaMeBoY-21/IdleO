package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.hosts.SchedulableHosts;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class SchedulableHostService implements SchedulableHosts {

    private final JdbcClient jdbc;
    private final Clock clock;

    SchedulableHostService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public List<Candidate> batchCandidates(int maxPricePaisePerMin, int vramNeededMb) {
        return jdbc.sql("""
                        select * from (
                            select distinct on (h.id) h.id, l.price_paise_per_min, h.gpu_temp_cap_c, g.gpu_uuid,
                                   g.vram_mb, g.model, g.driver_version, h.keep_free_gb,
                                   h.paused_at is not null and (h.paused_until is null or h.paused_until > :now) as paused,
                                   coalesce(sc.reliability, 0.5) as reliability, coalesce(sc.bandwidth, 0.5) as bandwidth
                            from hosts.hosts h
                            left join hosts.host_scores sc on sc.host_id = h.id
                            join hosts.price_listings l on l.host_id = h.id and l.workload_type = 'batch'
                            join hosts.host_gpus g on g.host_id = h.id and g.gone_at is null
                            where h.status = 'active' and l.price_paise_per_min <= :maxPrice
                            order by h.id, g.vram_mb desc, g.gpu_uuid
                        ) largest
                        where vram_mb >= :vram
                        """)
                .param("maxPrice", maxPricePaisePerMin)
                .param("vram", vramNeededMb)
                .param("now", Timestamp.from(Instant.now(clock)))
                .query((rs, n) -> new Candidate(
                        rs.getObject("id", UUID.class),
                        rs.getInt("price_paise_per_min"),
                        rs.getInt("gpu_temp_cap_c"),
                        rs.getString("gpu_uuid"),
                        rs.getInt("vram_mb"),
                        rs.getString("model"),
                        rs.getInt("keep_free_gb") * 1024,
                        rs.getBoolean("paused"),
                        rs.getDouble("reliability"),
                        rs.getDouble("bandwidth"),
                        rs.getString("driver_version")))
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockForBooking(UUID hostId) {
        return jdbc.sql("select id from hosts.hosts where id = :hostId and status = 'active' for update skip locked")
                .param("hostId", hostId)
                .query(UUID.class)
                .optional()
                .isPresent();
    }
}
