package com.idleo.controlplane;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Why an admin is doing something (ADR 0027): every admin action takes one, at least 5 characters once the spaces
 * around it are removed, and it goes into the audit log beside the change.
 */
public record AdminReason(
        @NotBlank @Size(min = 5, max = 500) String reason) {

    public AdminReason {
        reason = reason == null ? null : reason.strip();
    }
}
