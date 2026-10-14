package com.idleo.controlplane.identity.internal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks one of the few handlers a waiting user (no invite code yet, ADR 0024) may call: reading and renaming their own
 * account, and activating it. Every other handler, and every request that reaches none, is {@code invite-required}.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@interface OpenToWaitingUsers {}
