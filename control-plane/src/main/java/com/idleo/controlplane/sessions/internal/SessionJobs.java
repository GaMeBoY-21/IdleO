package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.images.CheckedImages;
import com.idleo.controlplane.sessions.Template;
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
    private final UploadFileStore files;
    private final CheckedImages images;

    SessionJobs(TemplateCatalog catalog, UploadStore uploads, UploadFileStore files, CheckedImages images) {
        this.catalog = catalog;
        this.uploads = uploads;
        this.files = files;
        this.images = images;
    }

    Optional<Template> template(SessionStore.Session session) {
        return catalog.find(session.templateId());
    }

    /**
     * The session's spec; none when its template is gone, or its own image (ADR 0042) can't run any more (blocked since it
     * was booked).
     */
    Optional<SessionSpec> spec(SessionStore.Session session) {
        return catalog.find(session.templateId()).flatMap(template -> {
            var maxRuntime = Duration.ofSeconds(session.maxRuntimeSeconds());
            var env = SessionParams.env(session.params(), session.inputs());
            if (!template.ownImage()) {
                return Optional.of(SessionSpecs.batch(template, session.vramNeededMb(), maxRuntime, env));
            }
            return image(session)
                    .map(image -> SessionSpecs.ownImage(
                            template,
                            image,
                            session.vramNeededMb(),
                            maxRuntime,
                            env,
                            session.params(),
                            images.pullTimeLimit()));
        });
    }

    /** The renter's own image the session runs, while it may run. */
    Optional<CheckedImages.Image> image(SessionStore.Session session) {
        return session.imageId() == null ? Optional.empty() : images.runnable(session.imageId());
    }

    /** What a session's own image takes on its host's disk, downloaded and unpacked; 0 for any other session. */
    long imageBytes(SessionStore.Session session) {
        return image(session)
                .map(image -> image.compressedBytes() + image.unpackedBytes())
                .orElse(0L);
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

    /** Every uploaded file as the agent's StartSession names it, each in its input's slot, its path kept. */
    List<InputRef> inputRefs(SessionStore.Session session) {
        return uploads(session).stream()
                .flatMap(named -> files.files(named.upload().id()).stream()
                        .map(file -> InputRef.newBuilder()
                                .setPrefix(file.prefix())
                                .setName(file.storedPath())
                                .setSizeBytes(file.sizeBytes())
                                .setSlot(named.name())
                                .build()))
                .toList();
    }
}
