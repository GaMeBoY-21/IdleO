package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.hosts.SchedulableHosts;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class SchedulableHostService implements SchedulableHosts {

    private final JdbcClient jdbc;

    SchedulableHostService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Candidate> batchCandidates(int maxPricePaisePerMin, int vramNeededMb) {
        return jdbc.sql("""
                        select * from (
                            select distinct on (h.id) h.id, l.price_paise_per_min, h.gpu_temp_cap_c, g.gpu_uuid,
                                   g.vram_mb
                            from hosts.hosts h
                            join hosts.price_listings l on l.host_id = h.id and l.workload_type = 'batch'
                            join hosts.host_gpus g on g.host_id = h.id and g.gone_at is null
                            where h.status = 'active' and l.price_paise_per_min <= :maxPrice
                            order by h.id, g.vram_mb desc, g.gpu_uuid
                        ) largest
                        where vram_mb >= :vram
                        """)
                .param("maxPrice", maxPricePaisePerMin)
                .param("vram", vramNeededMb)
                .query((rs, n) -> new Candidate(
                        rs.getObject("id", UUID.class),
                        rs.getInt("price_paise_per_min"),
                        rs.getInt("gpu_temp_cap_c"),
                        rs.getString("gpu_uuid"),
                        rs.getInt("vram_mb")))
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
