package com.idleo.controlplane.sessions.internal;

import com.google.protobuf.Duration;
import com.idleo.controlplane.images.CheckedImages;
import com.idleo.controlplane.sessions.Template;
import gpu.agent.v1.Session.SessionSpec;
import gpu.agent.v1.Session.SessionType;

/** What a session runs, as the agent protocol describes it: the same spec in its Offer and its StartSession. */
final class SessionSpecs {

    private SessionSpecs() {}

    static SessionSpec batch(
            Template template, int vramNeededMb, java.time.Duration maxRuntime, java.util.Map<String, String> env) {
        return SessionSpec.newBuilder()
                .setType(SessionType.SESSION_TYPE_BATCH)
                .setImage(java.util.Objects.requireNonNull(template.image(), "a template with its own image"))
                .addAllArgs(template.args())
                .putAllEnv(env)
                .setVramNeededMb(vramNeededMb)
                .setMemoryMb(template.memoryMb())
                .setCpuMillicores(template.cpuMillicores())
                .setPidsLimit(template.pidsLimit())
                .setScratchMb(template.scratchMb())
                .setCheckpointInterval(duration(template.checkpointInterval()))
                .setMaxRuntime(duration(maxRuntime))
                .build();
    }

    /**
     * A session of the renter's own image (ADR 0042): the image as checked, pinned by digest, pulled at the start within
     * {@code pullTimeLimit} and deleted after; the template's limits as for every session.
     */
    static SessionSpec ownImage(
            Template template,
            CheckedImages.Image image,
            int vramNeededMb,
            java.time.Duration maxRuntime,
            java.util.Map<String, String> env,
            java.util.Map<String, Object> params,
            java.time.Duration pullTimeLimit) {
        return SessionSpec.newBuilder()
                .setType(SessionType.SESSION_TYPE_BATCH)
                .setImage(image.reference())
                .addAllEntrypoint(OwnImageJobs.entrypoint(params))
                .addAllArgs(OwnImageJobs.args(params))
                .setWorkingDir(OwnImageJobs.workingDir(image))
                .setOwnImage(true)
                .setImagePullTimeout(duration(pullTimeLimit))
                .setImageSizeBytes(image.compressedBytes())
                .putAllEnv(env)
                .setVramNeededMb(vramNeededMb)
                .setMemoryMb(template.memoryMb())
                .setCpuMillicores(template.cpuMillicores())
                .setPidsLimit(template.pidsLimit())
                .setScratchMb(template.scratchMb())
                .setCheckpointInterval(duration(template.checkpointInterval()))
                .setMaxRuntime(duration(maxRuntime))
                .build();
    }

    static Duration duration(java.time.Duration value) {
        return Duration.newBuilder()
                .setSeconds(value.getSeconds())
                .setNanos(value.getNano())
                .build();
    }
}
