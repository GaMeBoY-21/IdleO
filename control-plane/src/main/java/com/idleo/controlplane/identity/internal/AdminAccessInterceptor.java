package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The one check of an admin's permission (ADR 0051), on the handler itself rather than its path, so however a path is
 * spelt it meets the same rule. By now the security chain has seen an admin with a live admin session; this answers
 * whether that admin may do this: 403 {@code admin-permission-required}, naming what's missing, and for a sensitive
 * permission whether a factor was given recently: 403 {@code step-up-required}. Then {@link AdminSafeWrites}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
class AdminAccessInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private final AdminPermissions permissions;
    private final AdminProperties properties;
    private final Clock clock;
    private final AdminSafeWrites safeWrites;

    AdminAccessInterceptor(
            AdminPermissions permissions, AdminProperties properties, Clock clock, AdminSafeWrites safeWrites) {
        this.safeWrites = safeWrites;
        this.permissions = permissions;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        var declared = method.getMethodAnnotation(RequiresPermission.class);
        if (declared == null) {
            return true;
        }
        response.setHeader("X-Request-Id", AdminRequestContext.requestId());
        var permission = declared.value();
        if (permission != AdminPermission.SELF
                && !permissions.has(currentAdmin().id(), permission)) {
            throw ApiProblem.of(
                    Problem.ADMIN_PERMISSION_REQUIRED,
                    "You need the " + permission.key() + " permission for this. An Owner can grant a role that has it.",
                    "permission",
                    permission.key());
        }
        if (permission.sensitive() || declared.stepUp()) {
            var factorAt = AdminRequestContext.factorAt();
            if (factorAt == null || factorAt.plus(properties.stepUpWindow()).isBefore(Instant.now(clock))) {
                throw ApiProblem.of(
                        Problem.STEP_UP_REQUIRED,
                        "Confirm it's you: this needs a passkey or a code given in the last "
                                + properties.stepUpWindow().toMinutes() + " minutes.");
            }
        }
        return safeWrites.before(request, response, declared);
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception failure) {
        safeWrites.after(request, response);
    }

    private static CurrentUser currentAdmin() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser user) {
            return user;
        }
        throw new IllegalStateException("an admin endpoint was reached without its security chain");
    }
}
