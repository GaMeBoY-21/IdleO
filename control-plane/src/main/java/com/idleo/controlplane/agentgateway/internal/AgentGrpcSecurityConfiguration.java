package com.idleo.controlplane.agentgateway.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.security.AuthenticationProcessInterceptor;
import org.springframework.grpc.server.security.GrpcSecurity;

/**
 * Deny by default on the gRPC port too. Enrol is open, since the agent has no certificate yet. The
 * agent gateway's methods pass this layer and are authenticated by {@link
 * AgentCertificateInterceptor}, which requires a valid client certificate (ADR 0011). Every other
 * method is refused until it is added here with its own rule.
 */
@Configuration(proxyBeanMethods = false)
// Only with the agents' gRPC server, which operator commands don't start (ADR 0014).
@ConditionalOnBooleanProperty(name = "spring.grpc.server.enabled", matchIfMissing = true)
class AgentGrpcSecurityConfiguration {

    static final String ENROLL = "gpu.agent.v1.EnrollmentService/Enroll";
    static final String OPEN_STREAM = "gpu.agent.v1.AgentGatewayService/OpenStream";
    static final String RENEW_CERTIFICATE = "gpu.agent.v1.AgentGatewayService/RenewCertificate";

    @Bean
    @GlobalServerInterceptor
    AuthenticationProcessInterceptor agentGrpcSecurity(GrpcSecurity grpc) throws Exception {
        return grpc.authorizeRequests(requests -> requests.methods(ENROLL, OPEN_STREAM, RENEW_CERTIFICATE)
                        .permitAll()
                        .allRequests()
                        .denyAll())
                .build();
    }
}
