package com.idleo.controlplane;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One module's signals that two accounts may be the same person or ring (ADR 0052), worked out from its own tables when
 * they're asked for. It hands over who matched and how sure that is, never the value they share. Nothing acts on them.
 */
public interface AccountLinks {

    /** How much a signal says by itself. */
    enum Strength {
        STRONG,
        MEDIUM,
        WEAK
    }

    /**
     * Another account that shares something with this one.
     *
     * @param signal what they share: same_device, same_network, same_pan, same_upi, same_email, same_payer
     * @param evidence the shared thing, as a mask would show it, in words
     * @param at when it was last seen, when that's known
     */
    record AccountLink(
            UUID otherUser,
            String signal,
            Strength strength,
            String evidence,
            @Nullable Instant at) {}

    List<AccountLink> linkedTo(UUID user);
}
