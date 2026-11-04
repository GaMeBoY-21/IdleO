package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.RequiresPermission;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Deny by default (ADR 0051): the control plane doesn't start if an {@code /admin/v1} endpoint declares no permission.
 */
@Component
@ConditionalOnWebApplication
class AdminEndpointCheck implements SmartInitializingSingleton {

    static final String PREFIX = "/admin/";

    private final RequestMappingHandlerMapping mappings;
    private final AdminSafeWrites safeWrites;

    AdminEndpointCheck(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings,
            AdminSafeWrites safeWrites) {
        this.mappings = mappings;
        this.safeWrites = safeWrites;
    }

    @Override
    public void afterSingletonsInstantiated() {
        var undeclared = undeclared(mappings.getHandlerMethods());
        if (!undeclared.isEmpty()) {
            throw new IllegalStateException("admin endpoints that declare no permission: " + undeclared);
        }
        var unknown = mappings.getHandlerMethods().values().stream()
                .map(handler -> handler.getMethodAnnotation(RequiresPermission.class))
                .filter(java.util.Objects::nonNull)
                .map(RequiresPermission::record)
                .filter(record -> !record.isBlank() && !safeWrites.knows(record))
                .distinct()
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("admin endpoints name records no module versions: " + unknown);
        }
    }

    /** Every admin endpoint without {@link RequiresPermission}. */
    static List<String> undeclared(Map<RequestMappingInfo, HandlerMethod> handlers) {
        return handlers.entrySet().stream()
                .filter(handler ->
                        handler.getKey().getPatternValues().stream().anyMatch(path -> path.startsWith(PREFIX)))
                .filter(handler -> !handler.getValue().hasMethodAnnotation(RequiresPermission.class))
                .map(handler -> handler.getKey().toString())
                .sorted()
                .toList();
    }
}
