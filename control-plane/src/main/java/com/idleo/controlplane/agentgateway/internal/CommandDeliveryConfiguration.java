package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.ValkeyHints;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * This instance listens on its own command channel (ADR 0015). Only with the agents' gRPC server. The
 * subscription starts in the background and retries until Valkey answers ({@link ValkeyHints}): delivery hints are
 * an optimisation, the sweep delivers without them, and Valkey being down must never stop an instance from
 * starting (ADR 0011).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "spring.grpc.server.enabled", matchIfMissing = true)
class CommandDeliveryConfiguration {

    @Bean
    RedisMessageListenerContainer commandHints(
            RedisConnectionFactory connections, CommandDelivery delivery, InstanceId instanceId) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        container.addMessageListener(delivery, new ChannelTopic(CommandDelivery.channel(instanceId.value())));
        container.setAutoStartup(false);
        return container;
    }

    @Bean
    SmartLifecycle commandHintSubscription(RedisMessageListenerContainer commandHints) {
        return ValkeyHints.subscribeInBackground(commandHints, "command-hint");
    }
}
