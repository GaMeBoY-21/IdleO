package com.idleo.controlplane;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Names a JavaScript error's place in our own sources (ADR 0036): the web builds make source maps but never serve them
 * (web/scripts/postbuild.ts, build-site.ts move them to {@code *-sourcemaps/}); the folder they're deployed to is
 * {@code vitals.source-maps} ({@code VITALS_SOURCE_MAPS}). A script's file name carries its content's hash, so its map
 * is found by name whichever release it came from. With no folder, or no map for a file, an error keeps the place the
 * browser gave.
 *
 * <p>A map's {@code mappings} are base64 VLQ (the Source Map v3 format, tc39.es/ecma426): lines by {@code ;}, segments
 * by {@code ,}, each the generated column, then the source, its line and its column, each a difference from the one
 * before. Only the line asked about is looked up.
 */
@Component
class SourceMaps {

    private static final String BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    record Map3(List<String> sources, String mappings) {}

    private final @Nullable Path folder;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Optional<Map3>> loaded = new ConcurrentHashMap<>();
    private volatile @Nullable Map<String, Path> byName;

    SourceMaps(@Value("${vitals.source-maps:}") String folder) {
        this.folder = folder.isBlank() ? null : Path.of(folder);
    }

    /**
     * The original {@code source:line} for a place in a built script ({@code /assets/Book-3f2a.js}, line and column as
     * the browser counts them, from 1); empty when it can't be told.
     */
    Optional<String> original(String script, int line, int column) {
        if (folder == null || script.isEmpty() || line < 1) {
            return Optional.empty();
        }
        var name = script.substring(script.lastIndexOf('/') + 1) + ".map";
        var map = loaded.computeIfAbsent(name, this::load);
        return map.flatMap(found -> find(found, line - 1, Math.max(0, column - 1)));
    }

    private Optional<Map3> load(String name) {
        var path = index().get(name);
        if (path == null) {
            return Optional.empty();
        }
        try {
            var node = json.readTree(Files.readString(path));
            var sources = new java.util.ArrayList<String>();
            node.path("sources").forEach(source -> sources.add(source.asString()));
            return Optional.of(
                    new Map3(List.copyOf(sources), node.path("mappings").asString("")));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Every map under the folder by its file name, read once. */
    private Map<String, Path> index() {
        var known = byName;
        if (known != null) {
            return known;
        }
        var found = new HashMap<String, Path>();
        if (folder != null && Files.isDirectory(folder)) {
            try (var paths = Files.walk(folder)) {
                paths.filter(path -> path.getFileName().toString().endsWith(".map"))
                        .forEach(path -> found.putIfAbsent(path.getFileName().toString(), path));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        byName = Map.copyOf(found);
        return byName;
    }

    /** The source and line of the last segment at or before {@code column} on generated {@code line} (both from 0). */
    static Optional<String> find(Map3 map, int line, int column) {
        var mappings = map.mappings();
        int generatedLine = 0;
        int generatedColumn = 0;
        int source = 0;
        int sourceLine = 0;
        String found = null;
        var fields = new int[5];
        int i = 0;
        while (i < mappings.length() && generatedLine <= line) {
            char c = mappings.charAt(i);
            if (c == ';') {
                // A new generated line: its columns count from 0 again; the rest carries on.
                generatedLine++;
                generatedColumn = 0;
                i++;
                continue;
            }
            if (c == ',') {
                i++;
                continue;
            }
            int count = 0;
            while (i < mappings.length() && mappings.charAt(i) != ',' && mappings.charAt(i) != ';') {
                int value = 0;
                int shift = 0;
                int digit;
                do {
                    if (i >= mappings.length()) {
                        return Optional.empty();
                    }
                    digit = BASE64.indexOf(mappings.charAt(i++));
                    if (digit < 0) {
                        return Optional.empty();
                    }
                    value += (digit & 31) << shift;
                    shift += 5;
                } while ((digit & 32) != 0);
                if (count < fields.length) {
                    fields[count] = (value & 1) == 1 ? -(value >> 1) : value >> 1;
                }
                count++;
            }
            generatedColumn += fields[0];
            if (count >= 4) {
                source += fields[1];
                sourceLine += fields[2];
                if (generatedLine == line
                        && generatedColumn <= column
                        && source >= 0
                        && source < map.sources().size()) {
                    found = map.sources().get(source) + ":" + (sourceLine + 1);
                }
            }
        }
        return Optional.ofNullable(found).map(SourceMaps::tidy);
    }

    /** A source as the build names it, without the climb out of the build folder: {@code src/features/book/Book.tsx}. */
    private static String tidy(String source) {
        var stripped = source.replace("\\", "/");
        while (stripped.startsWith("../")) {
            stripped = stripped.substring(3);
        }
        return stripped.startsWith("web/") ? stripped.substring(4) : stripped;
    }
}
