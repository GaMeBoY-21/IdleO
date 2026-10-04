package com.idleo.controlplane.agentgateway.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.security.AuthenticationProcessInterceptor;
import org.springframework.grpc.server.security.GrpcSecurity;

/**
 * Deny by default on the gRPC port too. Enrol is open, since the agent has no certificate yet;
 * every other method is refused until it is added here with its own rule (the agent stream will
 * require a client certificate).
 */
@Configuration(proxyBeanMethods = false)
class AgentGrpcSecurityConfiguration {

    static final String ENROLL = "gpu.agent.v1.EnrollmentService/Enroll";

    @Bean
    @GlobalServerInterceptor
    AuthenticationProcessInterceptor agentGrpcSecurity(GrpcSecurity grpc) throws Exception {
        return grpc.authorizeRequests(requests ->
                        requests.methods(ENROLL).permitAll().allRequests().denyAll())
                .build();
    }
}
