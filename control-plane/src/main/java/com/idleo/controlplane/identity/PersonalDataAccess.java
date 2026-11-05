package com.idleo.controlplane.identity;

import java.util.UUID;

/** How another module says in the access log what an admin looked at (ADR 0051). */
public interface PersonalDataAccess {

    /** This admin read this person's data in this field of this record, with no masking between them. */
    void read(CurrentUser admin, UUID subject, String field, String recordId, String reason);

    /** This admin opened a page about this person: a host, a session or a payment of theirs. */
    void viewed(CurrentUser admin, UUID subject, String recordId);

    /** This admin exported a list, which names no one person. */
    void exported(CurrentUser admin, String list, String reason);

    /** This admin searched the console, for this text, already masked. */
    void searched(CurrentUser admin, String maskedQuery);
}
