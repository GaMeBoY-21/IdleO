package com.idleo.controlplane;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Free text that goes into the audit log (an admin's reason, an invite's note) holds no email address and no IP
 * address: the log keeps people by id only (ADR 0037). What counts is {@link ContactDetails}.
 */
@Documented
@Constraint(validatedBy = ContactDetails.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoContactDetails {

    String message() default "Leave email and IP addresses out: the audit log keeps people by id only.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
