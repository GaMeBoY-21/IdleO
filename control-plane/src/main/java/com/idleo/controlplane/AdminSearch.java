package com.idleo.controlplane;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/**
 * What one module finds for the console's search box (ADR 0052), for admins who hold its permission. Each kind of thing
 * is one bean, in the module that owns it, reading its own tables. Personal values match exactly, after normalising;
 * names by trigram. Hits go out through the same masking as every admin response.
 */
public interface AdminSearch {

    /** What was typed, stripped, and the shapes a provider asks of it. */
    record Query(String text) {

        /** The text as a UUID, when it is one. */
        public Optional<UUID> id() {
            try {
                return Optional.of(UUID.fromString(text));
            } catch (IllegalArgumentException notOne) {
                return Optional.empty();
            }
        }

        /** The text for an exact match on an email or a UPI ID, which are kept in any case. */
        public String lower() {
            return text.toLowerCase(Locale.ROOT);
        }

        /** The text for a PAN or a document's number. */
        public String upper() {
            return text.toUpperCase(Locale.ROOT).replace(" ", "");
        }
    }

    /**
     * One thing found.
     *
     * @param page the console's page it opens: user, host, session, payment, refund, invoice, payout, ticket or image
     * @param id the id that page takes
     */
    record Hit(
            String page, String id, String title, @Nullable String detail) {}

    /** What this bean finds, the group its hits are shown under. */
    String type();

    /** What an admin must hold to see that any of these exists. */
    AdminPermission permission();

    List<Hit> find(Query query, int limit);

    static AdminSearch of(String type, AdminPermission permission, BiFunction<Query, Integer, List<Hit>> finder) {
        return new AdminSearch() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public AdminPermission permission() {
                return permission;
            }

            @Override
            public List<Hit> find(Query query, int limit) {
                return finder.apply(query, limit);
            }
        };
    }
}
