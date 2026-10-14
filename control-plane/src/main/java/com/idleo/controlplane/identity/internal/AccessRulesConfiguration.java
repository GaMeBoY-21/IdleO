package com.idleo.controlplane.identity.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts {@link AccessRules} in front of every handler, first, so nothing else runs for a request it refuses. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
class AccessRulesConfiguration implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AccessRules()).order(Ordered.HIGHEST_PRECEDENCE);
    }
}
