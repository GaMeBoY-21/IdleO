package com.idleo.controlplane.agentgateway.internal;

import io.grpc.ServerCredentials;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.NettySslContextServerCredentials;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.NettyGrpcServerFactory;
import org.springframework.grpc.server.ServerBuilderCustomizer;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.grpc.server.service.GrpcServiceConfigurer;
import org.springframework.grpc.server.service.GrpcServiceDiscoverer;

/**
 * The agents' gRPC server: one TLS port, TLS 1.3 only, client certificates requested but optional
 * (ADR 0010).
 *
 * <p>Spring gRPC 1.1 builds server TLS from the SSL bundle's key and trust managers only, and drops
 * the bundle's protocol options, so a TLS 1.2 client could connect. This factory builds the Netty
 * TLS context from the same bundle and applies its {@code enabled-protocols}. Everything else is
 * Spring gRPC's own: service discovery, global interceptors (security) through the service
 * configurer, and the lifecycle with graceful shutdown. Defining this server makes Spring Boot's
 * own Netty gRPC server configuration back off.
 *
 * <p>Keepalive (ADR 0011). Agents sit behind home routers and carrier-grade NAT, whose idle timeouts
 * for TCP can be as short as a few minutes, far below RFC 5382's recommended 2 hours 4 minutes. While
 * a stream is open the agent's 5-second heartbeats keep the mapping alive; HTTP/2 PINGs cover the
 * rest and find dead peers:
 *
 * <ul>
 *   <li>the server pings after 60 idle seconds, and gives up on a peer that doesn't answer within 20;
 *   <li>agents may ping as often as every 20 seconds (they use 30), even with no call open, without
 *       being told to calm down.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentGatewayProperties.class)
class AgentGrpcServerConfiguration {

    static final long KEEPALIVE_TIME_SECONDS = 60;
    static final long KEEPALIVE_TIMEOUT_SECONDS = 20;
    static final long PERMIT_KEEPALIVE_TIME_SECONDS = 20;

    @Bean
    NettyGrpcServerFactory agentGrpcServerFactory(
            GrpcServerProperties properties,
            SslBundles sslBundles,
            GrpcServiceDiscoverer services,
            GrpcServiceConfigurer serviceConfigurer,
            ObjectProvider<ServerBuilderCustomizer<NettyServerBuilder>> customizers)
            throws SSLException {
        var tls = tlsContext(properties, sslBundles);
        var address = "*:" + Objects.requireNonNullElse(properties.getPort(), 9443);
        var builderCustomizers = new ArrayList<ServerBuilderCustomizer<NettyServerBuilder>>();
        builderCustomizers.add(AgentGrpcServerConfiguration::keepAlive);
        builderCustomizers.addAll(customizers.orderedStream().toList());
        var factory = new NettyGrpcServerFactory(address, builderCustomizers, null, null, null) {
            @Override
            protected ServerCredentials credentials() {
                return NettySslContextServerCredentials.create(tls);
            }
        };
        services.findServices().stream()
                .map(service -> serviceConfigurer.configure(service, factory))
                .forEach(factory::addService);
        return factory;
    }

    @Bean
    GrpcServerLifecycle agentGrpcServerLifecycle(
            NettyGrpcServerFactory agentGrpcServerFactory,
            GrpcServerProperties properties,
            ApplicationEventPublisher events) {
        return new GrpcServerLifecycle(
                agentGrpcServerFactory, properties.getShutdown().getGracePeriod(), events);
    }

    private static void keepAlive(NettyServerBuilder builder) {
        builder.keepAliveTime(KEEPALIVE_TIME_SECONDS, TimeUnit.SECONDS)
                .keepAliveTimeout(KEEPALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .permitKeepAliveTime(PERMIT_KEEPALIVE_TIME_SECONDS, TimeUnit.SECONDS)
                .permitKeepAliveWithoutCalls(true);
    }

    private static SslContext tlsContext(GrpcServerProperties properties, SslBundles sslBundles) throws SSLException {
        var bundleName = properties.getSsl().getBundle();
        if (bundleName == null) {
            throw new IllegalStateException("spring.grpc.server.ssl.bundle must name the agent TLS bundle");
        }
        var bundle = sslBundles.getBundle(bundleName);
        var protocols = bundle.getOptions().getEnabledProtocols();
        if (protocols == null || protocols.length == 0) {
            throw new IllegalStateException("the agent TLS bundle must set options.enabled-protocols");
        }
        return GrpcSslContexts.configure(
                        SslContextBuilder.forServer(bundle.getManagers().getKeyManagerFactory()))
                .trustManager(bundle.getManagers().getTrustManagerFactory())
                .clientAuth(ClientAuth.OPTIONAL)
                .protocols(protocols)
                .build();
    }
}
