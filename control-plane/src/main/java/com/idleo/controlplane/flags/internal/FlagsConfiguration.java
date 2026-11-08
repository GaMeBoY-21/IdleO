package com.idleo.controlplane.flags.internal;

import com.idleo.controlplane.Flags;
import com.idleo.controlplane.ValkeyHints;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Every instance listens for a changed flag (ADR 0056), subscribing in the background so Valkey being down never stops
 * it starting; and maintenance mode stands in front of every handler, after the access rules, so a signed-out or
 * refused request is told that first.
 */
@Configuration(proxyBeanMethods = false)
class FlagsConfiguration {

    @Bean
    RedisMessageListenerContainer flagHints(RedisConnectionFactory connections, FlagStore flags) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        container.addMessageListener(flags, new ChannelTopic(FlagStore.CHANNEL));
        container.setAutoStartup(false);
        return container;
    }

    @Bean
    SmartLifecycle flagHintSubscription(RedisMessageListenerContainer flagHints) {
        return ValkeyHints.subscribeInBackground(flagHints, "flag-hint");
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication
    static class Maintenance implements WebMvcConfigurer {

        private final Flags flags;
        private final java.time.Clock clock;

        Maintenance(Flags flags, java.time.Clock clock) {
            this.flags = flags;
            this.clock = clock;
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new MaintenanceMode(flags, clock)).order(Ordered.HIGHEST_PRECEDENCE + 1);
        }
    }
}
