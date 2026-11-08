package com.idleo.controlplane;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks one of the few {@code /v1} writes that keep working in maintenance mode (ADR 0056): Razorpay's and RazorpayX's
 * webhooks and Resend's email events (money and mail must never be lost), signing in from the command line, and a
 * renter stopping their own running session (it only ever costs them less). Every other write is 503 with Retry-After,
 * decided after routing by the handler reached, never by the path's spelling. Reads, the admin console, agents and
 * metering aren't {@code /v1} writes and are never stopped. {@code MaintenanceModeTests} lists exactly these handlers.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OpenDuringMaintenance {}
