package com.idleo.controlplane.identity;

import java.util.UUID;

/** How another module's endpoint that hands an admin personal data whole says so in the access log (ADR 0051). */
public interface PersonalDataAccess {

    /** This admin read this person's data in this field of this record, with no masking between them. */
    void read(CurrentUser admin, UUID subject, String field, String recordId, String reason);
}
