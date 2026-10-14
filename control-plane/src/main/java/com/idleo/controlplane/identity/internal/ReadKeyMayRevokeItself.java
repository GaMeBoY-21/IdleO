package com.idleo.controlplane.identity.internal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the one write a read key may make (ADR 0025): revoking itself, when the route's path variable {@link #value()}
 * is the key's own id. Revoking only ever takes access away.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@interface ReadKeyMayRevokeItself {
    /** The path variable that holds the key's id. */
    String value();
}
