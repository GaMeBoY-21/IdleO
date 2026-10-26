package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The CUDA driver rule (ADR 0042), read from api/cuda-drivers.json, the file the agent's drivers.rs reads too. */
class CudaDriversTests {

    @Test
    void eachMajorNeedsItsFloorByMinorVersionCompatibility() {
        assertThat(CudaDrivers.supports("572.16", "12.8")).isTrue();
        assertThat(CudaDrivers.supports("527.41", "12.4")).isTrue();
        assertThat(CudaDrivers.supports("527.40", "12.0")).isFalse();
        assertThat(CudaDrivers.supports("572.16", "13.0")).isFalse();
        assertThat(CudaDrivers.supports("581.57", "13.0")).isTrue();
        assertThat(CudaDrivers.supports("452.39", "11.8")).isTrue();
        assertThat(CudaDrivers.supports("0.0", "12.0")).as("a fake GPU").isFalse();
        assertThat(CudaDrivers.supports(null, "12.0")).isFalse();
        assertThat(CudaDrivers.supports("999.0", "14.0")).as("an unknown major").isFalse();
    }

    @Test
    void anImagesConfigSaysWhatItNeeds() {
        assertThat(CudaDrivers.needed("cuda>=12.4 brand=tesla,driver>=470,driver<471", "12.4.1"))
                .isEqualTo("12.4");
        assertThat(CudaDrivers.needed(null, "12.1.0")).isEqualTo("12.1");
        assertThat(CudaDrivers.needed("cuda>=13.0", null)).isEqualTo("13.0");
        assertThat(CudaDrivers.needed(null, null)).isNull();
    }

    @Test
    void theJobImagesFloorIsTheAgentsMinimumDriver() throws Exception {
        assertThat(CudaDrivers.minimumDriver(12)).contains("527.41");
        assertThat(Files.readString(Path.of("../agent/release/src/drivers.rs")))
                .as("the agent reads the same file")
                .contains("include_str!(\"../../../api/cuda-drivers.json\")");
    }
}
