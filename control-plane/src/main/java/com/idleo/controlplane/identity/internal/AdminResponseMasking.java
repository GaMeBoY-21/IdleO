package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.PersonalDataMask;
import com.idleo.controlplane.RequiresPermission;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every admin response passes through {@link PersonalDataMask} on its way out (ADR 0051), so masking is the rule and
 * not something each endpoint remembers. An endpoint marked {@link RequiresPermission#unmasked} is the exception: the
 * admin's own second factor, and the one reveal. A file (CSV, PDF) isn't JSON and goes as it is: each is its own
 * permission, recorded when it's made.
 */
@ControllerAdvice
@ConditionalOnWebApplication
class AdminResponseMasking implements ResponseBodyAdvice<Object> {

    private final JsonMapper json;

    AdminResponseMasking(JsonMapper json) {
        this.json = json;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converter) {
        var declared = returnType.getMethodAnnotation(RequiresPermission.class);
        return declared != null && !declared.unmasked();
    }

    @Override
    public @Nullable Object beforeBodyWrite(
            @Nullable Object body,
            MethodParameter returnType,
            MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converter,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        return switch (body) {
            case null -> null;
            case byte[] file -> file;
            case String text -> json.writeValueAsString(PersonalDataMask.json(json.readTree(text)));
            default -> PersonalDataMask.json(json.valueToTree(body));
        };
    }
}
