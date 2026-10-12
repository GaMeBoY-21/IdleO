package com.idleo.controlplane.identity.internal;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts {@link RequestLimits} in front of every {@code /v1} handler, after sign-in has said who's calling. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@EnableConfigurationProperties(RequestLimitsProperties.class)
class RequestLimitsConfiguration implements WebMvcConfigurer {

    private final RequestLimits limits;

    RequestLimitsConfiguration(StringRedisTemplate redis, RequestLimitsProperties properties, Clock clock) {
        this.limits = new RequestLimits(redis, properties, clock);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(limits).addPathPatterns("/v1/**");
    }
}
