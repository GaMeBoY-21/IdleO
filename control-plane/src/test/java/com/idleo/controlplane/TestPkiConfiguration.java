package com.idleo.controlplane;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/** Points the CA and the gRPC TLS port at the in-memory test PKI, as PEM content. */
@TestConfiguration(proxyBeanMethods = false)
class TestPkiConfiguration {

    @Bean
    DynamicPropertyRegistrar testPki() {
        var pki = TestPki.get();
        return registry -> {
            registry.add("hosts.ca.root-certificate", () -> pki.rootPem);
            registry.add("hosts.ca.intermediate-certificate", () -> pki.intermediatePem);
            registry.add("hosts.ca.intermediate-private-key", () -> pki.encryptedIntermediateKeyPem);
            registry.add("hosts.ca.intermediate-private-key-password", () -> TestPki.INTERMEDIATE_KEY_PASSWORD);
            registry.add("spring.ssl.bundle.pem.agent-grpc.keystore.certificate", () -> pki.serverChainPem);
            registry.add("spring.ssl.bundle.pem.agent-grpc.keystore.private-key", () -> pki.serverKeyPem);
            registry.add("spring.ssl.bundle.pem.agent-grpc.truststore.certificate", () -> pki.rootPem);
        };
    }
}
