package com.idleo.controlplane.sessions.internal;

import gpu.agent.v1.Session.InputRef;
import gpu.agent.v1.Session.SessionSpec;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * What a session's attempts run (ADR 0019): its spec, with the environment made from its parameters and input, the
 * same in its Offer and every StartSession; and the uploaded input the agent stages, which its read grant covers.
 */
@Component
class SessionJobs {

    private final TemplateCatalog catalog;
    private final UploadStore uploads;

    SessionJobs(TemplateCatalog catalog, UploadStore uploads) {
        this.catalog = catalog;
        this.uploads = uploads;
    }

    Optional<SessionSpec> spec(SessionStore.Session session) {
        return catalog.find(session.templateId())
                .map(template -> SessionSpecs.batch(
                        template,
                        session.vramNeededMb(),
                        Duration.ofSeconds(session.maxRuntimeSeconds()),
                        SessionParams.env(
                                session.params(),
                                input(session)
                                        .map(UploadStore.Upload::storedName)
                                        .orElse(null),
                                session.inputUrl())));
    }

    /** The session's uploaded input, if it has one. */
    Optional<UploadStore.Upload> input(SessionStore.Session session) {
        return session.inputUploadId() == null ? Optional.empty() : uploads.find(session.inputUploadId());
    }

    /** The input as the agent's StartSession names it. */
    Optional<InputRef> inputRef(SessionStore.Session session) {
        return input(session)
                .map(upload -> InputRef.newBuilder()
                        .setPrefix(upload.prefix())
                        .setName(upload.storedName())
                        .setSizeBytes(upload.sizeBytes())
                        .build());
    }
}
