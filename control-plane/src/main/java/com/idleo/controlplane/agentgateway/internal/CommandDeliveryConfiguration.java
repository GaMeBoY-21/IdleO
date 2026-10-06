package com.idleo.controlplane.agentgateway.internal;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * This instance listens on its own command channel (ADR 0015). Only with the agents' gRPC server. The
 * subscription starts in the background and retries until Valkey answers: delivery hints are an
 * optimisation, the sweep delivers without them, and Valkey being down must never stop an instance from
 * starting (ADR 0011).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "spring.grpc.server.enabled", matchIfMissing = true)
class CommandDeliveryConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CommandDeliveryConfiguration.class);
    private static final Duration RETRY = Duration.ofSeconds(5);

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
        return new SmartLifecycle() {
            private volatile boolean running;
            private volatile Thread subscriber;

            @Override
            public void start() {
                running = true;
                subscriber = Thread.ofVirtual()
                        .name("command-hint-subscription")
                        .start(() -> {
                            while (running) {
                                try {
                                    commandHints.start();
                                    return;
                                } catch (RuntimeException valkeyDown) {
                                    log.warn(
                                            "Can't subscribe to command hints yet ({}); the sweep delivers meanwhile",
                                            valkeyDown.toString());
                                    try {
                                        Thread.sleep(RETRY);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        return;
                                    }
                                }
                            }
                        });
            }

            @Override
            public void stop() {
                running = false;
                var thread = subscriber;
                if (thread != null) {
                    thread.interrupt();
                }
                commandHints.stop();
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }
}
