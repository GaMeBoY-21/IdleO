package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.ValkeyHints;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** Each web instance hears session hints for its open event streams (ADR 0019), subscribed in the background. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
class SessionHintConfiguration {

    @Bean
    RedisMessageListenerContainer sessionHintListener(RedisConnectionFactory connections, SessionHints hints) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        container.addMessageListener(hints, new ChannelTopic(SessionHints.CHANNEL));
        container.setAutoStartup(false);
        return container;
    }

    @Bean
    SmartLifecycle sessionHintSubscription(RedisMessageListenerContainer sessionHintListener) {
        return ValkeyHints.subscribeInBackground(sessionHintListener, "session-hint");
    }
}
