package com.idleo.controlplane;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What carries a tag (ADR 0052), for any list's {@code tag} filter: asked of the module that keeps tags. */
public interface AdminTagged {

    /** The most ids one filter asks for. */
    int MOST = 5000;

    /** What of this kind carries this tag; null when no tag is asked for. It's never empty: an id nothing has stands in. */
    @Nullable
    List<UUID> subjects(String kind, @Nullable String tag);

    /** Whether this carries this tag. */
    boolean carries(String kind, UUID subject, String tag);

    /** Puts a tag from the Owner's vocabulary on it, audited as this admin. */
    void add(String kind, UUID subject, String tag, UUID admin);

    /** Takes it off, audited as this admin. */
    void remove(String kind, UUID subject, String tag, UUID admin);
}
