package com.idleo.controlplane;

import java.util.Map;
import java.util.UUID;

/**
 * What of a list this admin may have, for a list that's narrower than its one permission: the inbox holds only the kinds
 * of work an admin may read. It's applied to its file as to its screen.
 */
public interface AdminListScope {

    /** The list's name. */
    String list();

    /** The filters that keep this admin to what's theirs. */
    Map<String, Object> forAdmin(UUID admin);
}
