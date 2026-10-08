package com.idleo.controlplane.sessions.internal;

import com.google.protobuf.Duration;
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
                .setImage(template.image())
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

    static Duration duration(java.time.Duration value) {
        return Duration.newBuilder()
                .setSeconds(value.getSeconds())
                .setNanos(value.getNano())
                .build();
    }
}
