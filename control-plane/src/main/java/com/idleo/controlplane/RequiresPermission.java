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

    /** Whether it needs a recent factor though its permission isn't sensitive: changing one's own passkeys. */
    boolean stepUp() default false;

    /**
     * The record this write changes, by its name in {@code AdminRecords}: its {@code If-Match} must carry the record's
     * version. Empty for what changes no existing record: a create, a run, a read with a reason.
     */
    String record() default "";

    /**
     * Whether its response can't be stored for a repeat (ADR 0051): codes shown once, a file. A repeat with the same key
     * is told it was done, rather than shown it again.
     */
    boolean shownOnce() default false;
}
