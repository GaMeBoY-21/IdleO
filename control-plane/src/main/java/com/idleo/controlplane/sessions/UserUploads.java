package com.idleo.controlplane.sessions;

import java.util.UUID;

/** Whose an upload is, for what another module lets a person attach (ADR 0053). */
public interface UserUploads {

    boolean owns(UUID user, UUID upload);
}
