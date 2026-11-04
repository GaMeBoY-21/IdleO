package com.idleo.controlplane;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The permission an {@code /admin/v1} endpoint needs (ADR 0051). Every admin endpoint has one, or the control plane
 * refuses to start: nothing is open by default. An admin without it gets 403 {@code admin-permission-required}; anyone
 * who isn't an admin still gets the 404 of a path that doesn't exist.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    AdminPermission value();

    /**
     * Whether its response goes out whole instead of through {@code PersonalDataMask}: only the admin's own second
     * factor, and the one reveal.
     */
    boolean unmasked() default false;
}
