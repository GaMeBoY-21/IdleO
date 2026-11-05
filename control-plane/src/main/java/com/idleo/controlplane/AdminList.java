package com.idleo.controlplane;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * That this endpoint serves the {@link AdminListing} with this name (ADR 0052): its paging, order, filters, saved views
 * and CSV. Every list of the console says so; a test fails for one that doesn't.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminList {

    String value();
}
