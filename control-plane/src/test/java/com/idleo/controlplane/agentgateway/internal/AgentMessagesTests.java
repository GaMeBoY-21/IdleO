package com.idleo.controlplane.agentgateway.internal;

import static org.assertj.core.api.Assertions.assertThat;

import gpu.agent.v1.AgentGateway.Hello;
import gpu.agent.v1.Telemetry.GpuInfo;
import gpu.agent.v1.Telemetry.OperatingSystem;
import org.junit.jupiter.api.Test;

/** Development hosts (a Mac, fake GPUs) are refused unless the switch is on (ADR 0013). */
class AgentMessagesTests {

    private static final String REAL_GPU = "GPU-8a6b1f3e-0000-0000-0000-000000000001";
    private static final String FAKE_GPU = "GPU-00000000-fake-0000-000000000000";

    private static Hello hello(OperatingSystem os, String gpuUuid) {
        return Hello.newBuilder()
                .setAgentVersion("0.1.0")
                .setOs(os)
                .addGpus(GpuInfo.newBuilder().setUuid(gpuUuid).setName("GPU").setVramMb(8192))
                .build();
    }

    @Test
    void byDefaultAMacIsRefused() {
        assertThat(AgentMessages.validate(hello(OperatingSystem.OPERATING_SYSTEM_MACOS, REAL_GPU), false))
                .isEqualTo(new AgentMessages.HelloResult.Invalid("macOS hosts are for development only"));
    }

    @Test
    void byDefaultAFakeGpuIsRefused() {
        assertThat(AgentMessages.validate(hello(OperatingSystem.OPERATING_SYSTEM_LINUX, FAKE_GPU), false))
                .isEqualTo(new AgentMessages.HelloResult.Invalid("fake GPUs are for development only"));
    }

    @Test
    void withDevelopmentHostsAllowedBothAreAccepted() {
        var result = AgentMessages.validate(hello(OperatingSystem.OPERATING_SYSTEM_MACOS, FAKE_GPU), true);

        assertThat(result)
                .isInstanceOfSatisfying(
                        AgentMessages.HelloResult.Valid.class,
                        valid -> assertThat(valid.hello().hello().os()).isEqualTo("macos"));
    }

    @Test
    void realHostsAreAcceptedEitherWay() {
        assertThat(AgentMessages.validate(hello(OperatingSystem.OPERATING_SYSTEM_WINDOWS, REAL_GPU), false))
                .isInstanceOf(AgentMessages.HelloResult.Valid.class);
    }
}
