package com.idleo.controlplane.identity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * What the module that keeps a person's data can show whole, to an admin who gives a reason (ADR 0051). Each module
 * names its fields, and reads the value only when asked by identity's one reveal, which checks the permission and
 * writes the access log.
 */
public interface RevealableFields {

    /** A whole value, and whose it is, when it's one person's. */
    record Revealed(@Nullable UUID subject, String value) {}

    /** Each field's name, and how to read it from the id of the record that holds it. */
    Map<String, Function<String, Optional<Revealed>>> fields();
}
