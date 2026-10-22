package com.idleo.controlplane.identity;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks one of the few handlers a held account (suspended or banned, ADR 0038) may still call, signed in in a browser: reading
 * its own account, its own data's view, export and deletion (ADR 0037), and writing to support. Everything else, and every
 * request that reaches no handler, is 403 {@code account-suspended}. A ban is not a way to keep a person from their own data
 * or from being heard.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OpenToHeldAccounts {}
