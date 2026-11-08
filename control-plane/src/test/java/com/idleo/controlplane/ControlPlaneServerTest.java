package com.idleo.controlplane;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

/**
 * The full application on a real port, for what MockMvc can't show: a response read as it arrives. Its own context, so
 * it's closed afterwards. MockMvc is still there, to sign in with.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@DirtiesContext
@ContextConfiguration(initializers = ControlPlaneServerTest.BeforeTheServerStarts.class)
@Import({
    TestcontainersConfiguration.class,
    TestStorageConfiguration.class,
    TestIssuersConfiguration.class,
    TestPkiConfiguration.class,
    TestClockConfiguration.class,
    TestRazorpayConfiguration.class
})
public @interface ControlPlaneServerTest {

    /**
     * A real server starts before the test configurations' dynamic properties are registered, and its gRPC port wants its
     * certificates then, so they go in first.
     */
    class BeforeTheServerStarts implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            var pki = TestPki.get();
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(new MapPropertySource(
                            "control-plane-server-test",
                            Map.of(
                                    "spring.grpc.server.port", "0",
                                    "spring.ssl.bundle.pem.agent-grpc.keystore.certificate", pki.serverChainPem,
                                    "spring.ssl.bundle.pem.agent-grpc.keystore.private-key", pki.serverKeyPem,
                                    "spring.ssl.bundle.pem.agent-grpc.truststore.certificate", pki.rootPem)));
        }
    }
}
