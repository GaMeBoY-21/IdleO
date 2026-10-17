package com.idleo.controlplane.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.TestHosts;
import com.idleo.controlplane.TestHosts.ConnectedHost;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.scheduling.internal.SchedulingProperties.BandDefault;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The suggested price band (ADR 0023): other active hosts with the same GPU model, or the class default. */
@ControlPlaneTest
class PriceBandTests {

    private static final SchedulingProperties PROPERTIES = new SchedulingProperties(
            null,
            1,
            null,
            null,
            null,
            Map.of(8, new BandDefault(8, 14), 12, new BandDefault(12, 20), 24, new BandDefault(24, 42)));

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @LocalGrpcServerPort
    int grpcPort;

    private final List<ConnectedHost> hosts = new ArrayList<>();

    @BeforeEach
    void startWithoutOtherTestsHosts() {
        jdbc.update("update hosts.hosts set status = 'suspended' where status = 'active'");
    }

    @AfterEach
    void disconnect() {
        hosts.forEach(ConnectedHost::disconnect);
        hosts.clear();
    }

    private ConnectedHost host(int price) throws Exception {
        var host = TestHosts.ready(mvc, grpcPort, jdbc, price);
        hosts.add(host);
        return host;
    }

    private ResultActions band(ConnectedHost host) throws Exception {
        return mvc.perform(get("/v1/hosts/" + host.id() + "/price-band").header("Authorization", host.agent().bearer));
    }

    @Test
    void withFewerThanThreeOtherHostsItsTheDefaultForTheGpusMemoryClass() throws Exception {
        var mine = host(15);
        host(30);
        host(31);

        // 12,282 MB is the 12 GB class. Two other hosts are too few to say anything.
        band(mine)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lowPaisePerMin").value(12))
                .andExpect(jsonPath("$.highPaisePerMin").value(20))
                .andExpect(jsonPath("$.sampleSize").value(0));
    }

    @Test
    void withEnoughOtherHostsItsTheirRangeAndNotTheHostsOwnPrice() throws Exception {
        var mine = host(99);
        for (var price : new int[] {10, 12, 14, 20}) {
            host(price);
        }

        band(mine)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lowPaisePerMin").value(10))
                .andExpect(jsonPath("$.highPaisePerMin").value(20))
                .andExpect(jsonPath("$.sampleSize").value(4));
    }

    @Test
    void aGpuThatIsntAHostYetGetsTheBandOfTheHostsWithItsModel() throws Exception {
        var user = TestUsers.signUp(mvc);
        // Nobody lists this model: the default for its memory class (12 GB: 12 to 20).
        mvc.perform(get("/v1/price-band")
                        .param("gpuModel", "NVIDIA GeForce RTX 9999")
                        .param("vramMb", "12282")
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lowPaisePerMin").value(12))
                .andExpect(jsonPath("$.highPaisePerMin").value(20))
                .andExpect(jsonPath("$.sampleSize").value(0));

        var first = host(10);
        for (var price : new int[] {12, 14, 20}) {
            host(price);
        }
        var model =
                jdbc.queryForObject("select model from hosts.host_gpus where host_id = ?", String.class, first.id());
        mvc.perform(get("/v1/price-band")
                        .param("gpuModel", model)
                        .param("vramMb", "12282")
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lowPaisePerMin").value(10))
                .andExpect(jsonPath("$.highPaisePerMin").value(20))
                .andExpect(jsonPath("$.sampleSize").value(4));
        mvc.perform(get("/v1/price-band")
                        .param("gpuModel", "")
                        .param("vramMb", "0")
                        .header("Authorization", user.bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-failed"));
        mvc.perform(get("/v1/price-band").param("gpuModel", model).param("vramMb", "12282"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anotherUsersHostIsNotFoundAndAHostWithoutAGpuHasNoBand() throws Exception {
        var mine = host(15);
        var stranger = TestUsers.signUp(mvc);
        mvc.perform(get("/v1/hosts/" + mine.id() + "/price-band").header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());

        var neverConnected = TestAgent.enrol(mvc, grpcPort);
        mvc.perform(get("/v1/hosts/" + neverConnected.hostId + "/price-band")
                        .header("Authorization", neverConnected.bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/host-no-gpu"));
    }

    @Test
    void fromFiveHostsTheOneHighestAndLowestPriceAreLeftOutSoOneOutlierSetsNoEdge() {
        // Three or four hosts: every price counts.
        assertThat(PriceBands.of(List.of(10, 12, 500), 12_282, PROPERTIES)).isEqualTo(new PriceBands.Band(10, 500, 3));
        assertThat(PriceBands.of(List.of(1, 12, 14, 16), 12_282, PROPERTIES)).isEqualTo(new PriceBands.Band(1, 16, 4));
        // Five to nine: one outlier at either end moves nothing.
        assertThat(PriceBands.of(List.of(1, 12, 14, 16, 500), 12_282, PROPERTIES))
                .isEqualTo(new PriceBands.Band(12, 16, 3));
        assertThat(PriceBands.of(List.of(500, 10, 11, 12, 13, 14, 15, 16, 2), 12_282, PROPERTIES))
                .isEqualTo(new PriceBands.Band(10, 16, 7));
    }

    @Test
    void theTopAndBottomTenthOfThePricesAreIgnored() {
        // Twenty prices from 10 to 29, one wild one each end: two are dropped from each side.
        var prices = new ArrayList<>(IntStream.rangeClosed(10, 29).boxed().toList());
        prices.set(0, 1);
        prices.set(19, 500);

        var band = PriceBands.of(prices, 12_282, PROPERTIES);

        assertThat(band).isEqualTo(new PriceBands.Band(12, 27, 16));
    }

    @Test
    void theMemoryClassIsTheLargestOneTheGpuReachesAndTheSmallestBelowThat() {
        assertThat(PriceBands.gpuClassGb(8_188)).isEqualTo(8);
        assertThat(PriceBands.gpuClassGb(12_282)).isEqualTo(12);
        assertThat(PriceBands.gpuClassGb(24_564)).isEqualTo(24);
        assertThat(PriceBands.gpuClassGb(4_096)).isEqualTo(8);
        assertThat(PriceBands.gpuClassGb(49_000)).isEqualTo(24);
        assertThat(PriceBands.of(List.of(), 8_188, PROPERTIES)).isEqualTo(new PriceBands.Band(8, 14, 0));
    }
}
