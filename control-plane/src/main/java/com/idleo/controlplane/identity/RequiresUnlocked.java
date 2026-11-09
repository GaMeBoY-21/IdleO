package com.idleo.controlplane.identity;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler that starts something on one side of the account (ADR 0058): booking, buying credit or checking an
 * image needs renting unlocked; linking, pricing or adding a laptop needs hosting. It refuses a person who hasn't unlocked
 * that side with 403 {@code side-not-unlocked}, saying which. As with {@link RequiresAcceptedTerms}, reading, stopping,
 * pausing, removing and downloading are never marked, and an upload isn't: an appeal's evidence is one, from either side.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresUnlocked {
    Side value();
}
