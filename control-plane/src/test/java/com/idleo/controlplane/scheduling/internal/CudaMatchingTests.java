package com.idleo.controlplane.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestHosts;
import com.idleo.controlplane.TestHosts.ConnectedHost;
import com.idleo.controlplane.hosts.SchedulableHosts;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A renter's own image is offered only to a host whose GPU's driver runs the CUDA version it needs (ADR 0042), by the one
 * rule in api/cuda-drivers.json: the scheduler's filters (CandidateFinder) that matching, quotes and the server's price
 * ceiling all use.
 */
@ControlPlaneTest
class CudaMatchingTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CandidateFinder finder;

    @LocalGrpcServerPort
    int grpcPort;

    private final List<ConnectedHost> hosts = new ArrayList<>();

    @AfterEach
    void disconnect() {
        hosts.forEach(ConnectedHost::disconnect);
        hosts.clear();
    }

    @Test
    void anImageNeedingANewerCudaThanAHostsDriverIsNeverOfferedToIt() throws Exception {
        var older = TestHosts.ready(mvc, grpcPort, jdbc, 2);
        var newer = TestHosts.ready(mvc, grpcPort, jdbc, 2);
        hosts.add(older);
        hosts.add(newer);
        // The test agents report 572.16 (CUDA 12.8); the newer host's owner has updated to an R580 driver.
        jdbc.update("update hosts.host_gpus set driver_version = '581.57' where host_id = ?", newer.id());

        var ids = List.of(older.id(), newer.id());
        var forCuda13 = finder.free(10, 1024, 0, "13.0", List.of()).stream()
                .map(SchedulableHosts.Candidate::hostId)
                .filter(ids::contains)
                .toList();
        assertThat(forCuda13).containsExactly(newer.id());
        var forCuda12 = finder.free(10, 1024, 0, "12.4", List.of()).stream()
                .map(SchedulableHosts.Candidate::hostId)
                .filter(ids::contains)
                .toList();
        assertThat(forCuda12).containsExactlyInAnyOrderElementsOf(ids);
        var forNone = finder.free(10, 1024, 0, null, List.of()).stream()
                .map(SchedulableHosts.Candidate::hostId)
                .filter(ids::contains)
                .toList();
        assertThat(forNone).as("an image that names no CUDA runs anywhere").containsExactlyInAnyOrderElementsOf(ids);
        var forCuda14 = finder.free(10, 1024, 0, "14.0", List.of()).stream()
                .map(SchedulableHosts.Candidate::hostId)
                .filter(ids::contains)
                .toList();
        assertThat(forCuda14).as("a CUDA the table doesn't know runs nowhere").isEmpty();
    }
}
