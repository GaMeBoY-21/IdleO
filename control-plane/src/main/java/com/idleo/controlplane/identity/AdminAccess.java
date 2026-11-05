package com.idleo.controlplane.identity;

import com.idleo.controlplane.AdminPermission;
import java.util.Set;
import java.util.UUID;

/** What another module may ask about what an admin can do (ADR 0051). */
public interface AdminAccess {

    /** Everything this admin may do now. */
    Set<AdminPermission> permissions(UUID admin);
}
