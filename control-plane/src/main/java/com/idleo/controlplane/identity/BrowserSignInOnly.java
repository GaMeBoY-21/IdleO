package com.idleo.controlplane.identity;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler only a browser's sign-in may call, never an API key (ADR 0025): anything that makes a credential or
 * lets one in, or moves an owner's machine or money. The identity module checks it once, after routing, on the
 * handler the request actually reached, so no spelling of the path gets around it.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BrowserSignInOnly {}
