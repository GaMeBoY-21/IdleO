package com.idleo.controlplane.identity;

import com.idleo.controlplane.AdminPermission;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** What another module may ask about what an admin can do (ADR 0051). */
public interface AdminAccess {

    /** Everything this admin may do now. */
    Set<AdminPermission> permissions(UUID admin);

    /** An admin other admins can mention. */
    record Mentionable(UUID id, String name) {}

    /** Every admin who can be reached: enrolled, not locked out, on an active account. */
    List<Mentionable> mentionable();

    /** The Owners, for what they're told by email (ADR 0056: a change of a flag that says so). */
    List<UUID> owners();
}
