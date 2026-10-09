package com.idleo.controlplane.sessions.internal;

import gpu.agent.v1.Session.InputRef;
import gpu.agent.v1.Session.SessionSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * What a session's attempts run (ADR 0019, ADR 0022): its spec, with the environment made from its parameters and
 * inputs, the same in its Offer and every StartSession; and the uploaded files the agent stages, each into its input's
 * folder, which the attempt's read grant covers.
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
                        SessionParams.env(session.params(), session.inputs())));
    }

    /** The session's uploaded inputs, by input name. */
    List<Named> uploads(SessionStore.Session session) {
        var named = new ArrayList<Named>();
        for (var input : session.inputs()) {
            if (input.uploadId() != null) {
                uploads.find(input.uploadId()).ifPresent(upload -> named.add(new Named(input.name(), upload)));
            }
        }
        return named;
    }

    /** An upload, as the input it is. */
    record Named(String name, UploadStore.Upload upload) {}

    /** Every uploaded file as the agent's StartSession names it, each in its input's slot. */
    List<InputRef> inputRefs(SessionStore.Session session) {
        return uploads(session).stream()
                .map(named -> InputRef.newBuilder()
                        .setPrefix(named.upload().prefix())
                        .setName(named.upload().storedName())
                        .setSizeBytes(named.upload().sizeBytes())
                        .setSlot(named.name())
                        .build())
                .toList();
    }
}
