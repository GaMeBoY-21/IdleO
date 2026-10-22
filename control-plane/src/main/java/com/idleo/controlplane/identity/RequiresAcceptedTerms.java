package com.idleo.controlplane.identity;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler that creates something (ADR 0038): a booking, an upload, a machine added, linked or priced. It refuses
 * a person who hasn't accepted the Terms and privacy notice in force, with 403 {@code terms-not-accepted} saying where to
 * accept them, whether the request came from the app, the command line, the SDK or an API key; acceptance itself needs a
 * browser sign-in, so a key can never accept on a person's behalf. Calls that only read, stop, pause, remove or download are
 * never marked: nobody is trapped with a running job or a machine they can't take off.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresAcceptedTerms {}
