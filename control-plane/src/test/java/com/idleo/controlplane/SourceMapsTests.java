package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A JavaScript error's place in our own sources, read from a build's source map (ADR 0036). */
class SourceMapsTests {

    /**
     * Generated line 1: one segment, to src/a.ts line 1. Generated line 2: column 0 to src/a.ts line 2, then column 5
     * to src/b.ts line 2. ("C" is +1, "K" is +5, in base64 VLQ.)
     */
    private static final SourceMaps.Map3 MAP =
            new SourceMaps.Map3(List.of("../../src/a.ts", "../../src/b.ts"), "AAAA;AACA,KCAK");

    @Test
    void theLastSegmentAtOrBeforeTheColumnNamesTheSourceAndLine() {
        assertThat(SourceMaps.find(MAP, 0, 0)).contains("src/a.ts:1");
        assertThat(SourceMaps.find(MAP, 1, 3)).contains("src/a.ts:2");
        assertThat(SourceMaps.find(MAP, 1, 5)).contains("src/b.ts:2");
        assertThat(SourceMaps.find(MAP, 1, 90)).contains("src/b.ts:2");
        assertThat(SourceMaps.find(MAP, 7, 0)).as("a line the map doesn't have").isEmpty();
    }

    @Test
    void aMapIsFoundByItsScriptsNameInTheFolder(@TempDir Path folder) throws Exception {
        Files.createDirectories(folder.resolve("dist-sourcemaps/assets"));
        Files.writeString(
                folder.resolve("dist-sourcemaps/assets/Book-3f2a.js.map"),
                "{\"version\":3,\"sources\":[\"../../src/a.ts\",\"../../src/b.ts\"],\"names\":[],\"mappings\":\"AAAA;AACA,KCAK\"}");
        var maps = new SourceMaps(folder.toString());

        assertThat(maps.original("/assets/Book-3f2a.js", 2, 6)).contains("src/b.ts:2");
        assertThat(maps.original("https://app.example/assets/Book-3f2a.js", 1, 1))
                .contains("src/a.ts:1");
        assertThat(maps.original("/assets/Other-9999.js", 1, 1)).isEmpty();
        assertThat(new SourceMaps("").original("/assets/Book-3f2a.js", 1, 1))
                .as("no folder")
                .isEmpty();
    }
}
