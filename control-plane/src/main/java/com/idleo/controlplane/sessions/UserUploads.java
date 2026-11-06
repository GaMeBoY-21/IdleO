package com.idleo.controlplane.sessions;

import java.util.UUID;

/** Whose an upload is, for what another module lets a person attach (ADR 0053). */
public interface UserUploads {

    boolean owns(UUID user, UUID upload);

    /** A short-lived link to a ready upload of one file, saved under its name; empty for anything else. */
    java.util.Optional<java.net.URL> link(UUID user, UUID upload, java.time.Duration ttl);
}
