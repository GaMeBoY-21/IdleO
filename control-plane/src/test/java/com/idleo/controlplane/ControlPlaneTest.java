package com.idleo.controlplane;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * The full application against a real PostgreSQL. Every class with this annotation shares one
 * Spring context, and so one database container; {@link NoSessionsLeftWaiting} keeps a test's sessions from reaching the next
 * test's hosts.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(NoSessionsLeftWaiting.class)
@Import({
    TestcontainersConfiguration.class,
    TestStorageConfiguration.class,
    TestIssuersConfiguration.class,
    TestPkiConfiguration.class,
    TestClockConfiguration.class,
    TestRazorpayConfiguration.class,
    ThrowingController.class,
    TypedProblemsGuard.class
})
public @interface ControlPlaneTest {}
