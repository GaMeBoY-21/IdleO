package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.agentgateway.ImagesToPrefetch.Image;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;

class PrefetchListTests {

    @Test
    void theRealRunnerCatalogListsEachImageOnceWithTheLeastGpuMemoryItsTemplatesAsk() throws IOException {
        var images = new PrefetchList(new TemplateCatalog(new FileSystemResource("../templates/development-real.yaml")))
                .images();

        assertThat(images)
                .extracting(Image::image)
                .doesNotHaveDuplicates()
                .allMatch(image -> image.contains("@sha256:"));
        // The real templates share one image; the least memory a template using it asks for is the run-your-code 4 GB.
        var job = images.stream()
                .filter(image -> image.image().contains("idleo/pytorch@"))
                .findFirst()
                .orElseThrow();
        assertThat(job.minVramMb()).isEqualTo(4096);
    }

    @Test
    void theSimulatedCatalogListsItsOneStandInImageAndLeavesTheTestJobsOut() throws IOException {
        var images =
                new PrefetchList(new TemplateCatalog(new FileSystemResource("../templates/development.yaml"))).images();

        // fine-tune-simulated and run-code-simulated share one image; the hidden developer-tests service has more
        // templates
        // (the long run, the echo) on the same image, which don't count.
        assertThat(images).hasSize(1);
        assertThat(images.getFirst().minVramMb()).isEqualTo(4096);
    }

    @Test
    void hiddenServicesAreLeftOut() throws IOException {
        // The production catalog's only template is the diagnostics smoke test, in a hidden service: nothing worth a
        // download.
        var images =
                new PrefetchList(new TemplateCatalog(new FileSystemResource("../templates/catalog.yaml"))).images();

        assertThat(images).isEmpty();
    }
}
