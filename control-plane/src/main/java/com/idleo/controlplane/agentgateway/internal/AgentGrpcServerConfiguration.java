package com.idleo.controlplane.agentgateway.internal;

import io.grpc.ServerCredentials;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.NettySslContextServerCredentials;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.util.Objects;
import javax.net.ssl.SSLException;
import org.springframework.beans.factory.ObjectProvider;
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
 */
@Configuration(proxyBeanMethods = false)
class AgentGrpcServerConfiguration {

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
        var factory =
                new NettyGrpcServerFactory(address, customizers.orderedStream().toList(), null, null, null) {
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
