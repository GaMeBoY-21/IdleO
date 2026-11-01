package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.util.ClassUtils;

/**
 * Every test class shares the one context {@link ControlPlaneTest} makes, or closes its own once it has run. Spring's
 * cache keeps a context until the suite ends, and a kept context runs its scheduled jobs against its own PostgreSQL,
 * Valkey and MinIO all that while, though no test uses it again: a dozen of them once loaded a 4-CPU runner until
 * Valkey missed its timeouts. Two classes share a context exactly when Spring's cache key, their merged configuration,
 * is the same.
 */
class TestContextsTests {

    @ControlPlaneTest
    static class Shared {}

    @Test
    void everyTestClassWithAContextOfItsOwnClosesItAfterwards() {
        var shared = configuration(Shared.class);
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(SpringBootTest.class));
        var kept = new TreeSet<String>();
        int sharing = 0;
        for (var candidate : scanner.findCandidateComponents("com.idleo.controlplane")) {
            var testClass = ClassUtils.resolveClassName(
                    candidate.getBeanClassName(), getClass().getClassLoader());
            if (testClass == Shared.class) {
                continue;
            }
            if (configuration(testClass).equals(shared)) {
                sharing++;
            } else if (AnnotatedElementUtils.findMergedAnnotation(testClass, DirtiesContext.class) == null) {
                kept.add(testClass.getName());
            }
        }
        assertThat(sharing).as("test classes sharing the one context").isGreaterThan(100);
        assertThat(kept)
                .as("test classes with a context of their own that the cache would keep: add @DirtiesContext")
                .isEmpty();
    }

    private static MergedContextConfiguration configuration(Class<?> testClass) {
        return BootstrapUtils.resolveTestContextBootstrapper(testClass).buildMergedContextConfiguration();
    }
}
