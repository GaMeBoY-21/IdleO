package com.idleo.controlplane.scheduling.internal;

import jakarta.validation.constraints.Positive;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * What the public pages may say that only configuration knows.
 *
 * @param startingCreditPaise the credit a new account starts with, for the pricing page; unset until someone has
 *     decided what a new account gets, and then the page says nothing about it
 */
@ConfigurationProperties("public-site")
@Validated
record PublicSiteProperties(@Nullable @Positive Long startingCreditPaise) {}
