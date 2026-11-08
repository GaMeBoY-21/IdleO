package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;

/**
 * Every flag is enforced somewhere a test proves (ADR 0056): each {@link Flag} has at least one test marked {@link
 * TestFlags.Enforces} with it. A new flag without one fails here, before anyone relies on a switch that stops nothing.
 */
class FlagEnforcementCoverageTests {

    @Test
    void everyFlagHasATestOfWhereItsEnforced() throws Exception {
        var covered = EnumSet.noneOf(Flag.class);
        var resolver = new PathMatchingResourcePatternResolver();
        var readers = new CachingMetadataReaderFactory(resolver);
        for (var resource : resolver.getResources("classpath*:com/idleo/controlplane/**/*Tests.class")) {
            var name = readers.getMetadataReader(resource).getClassMetadata().getClassName();
            Class<?> type;
            try {
                type = Class.forName(name, false, getClass().getClassLoader());
            } catch (ClassNotFoundException | LinkageError notATest) {
                continue;
            }
            for (var method : type.getDeclaredMethods()) {
                var enforces = method.getAnnotation(TestFlags.Enforces.class);
                if (enforces != null) {
                    covered.add(enforces.value());
                }
            }
        }
        assertThat(EnumSet.complementOf(covered))
                .as("flags with no test of where they're enforced")
                .isEmpty();
    }
}
