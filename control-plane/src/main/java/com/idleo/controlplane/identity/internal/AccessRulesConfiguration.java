package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.identity.SecurityLog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts {@link AccessRules} in front of every handler, first, so nothing else runs for a request it refuses. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
class AccessRulesConfiguration implements WebMvcConfigurer {

    private final NoticeAcceptances notices;
    private final IdentityProperties properties;
    private final SecurityLog security;

    AccessRulesConfiguration(NoticeAcceptances notices, IdentityProperties properties, SecurityLog security) {
        this.notices = notices;
        this.properties = properties;
        this.security = security;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AccessRules(notices, properties.webAppUrl(), security))
                .order(Ordered.HIGHEST_PRECEDENCE);
    }
}
