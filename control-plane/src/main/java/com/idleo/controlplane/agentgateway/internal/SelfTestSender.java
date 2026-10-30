package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.agentgateway.AgentCommands;
import com.idleo.controlplane.hosts.SelfTestRequested;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.Host.RunSelfTest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Tells a host's agent to run its self-test when its owner or an admin asked for it in the web app ({@link
 * SelfTestRequested}), through the durable command queue. The command is dropped if the agent hasn't taken it within
 * {@link #LIVES}: by then the page has stopped waiting for the run.
 */
@Component
class SelfTestSender {

    static final String RUN_SELF_TEST = "run_self_test";
    static final Duration LIVES = Duration.ofMinutes(2);

    private final AgentCommands commands;
    private final Clock clock;

    SelfTestSender(AgentCommands commands, Clock clock) {
        this.commands = commands;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void on(SelfTestRequested event) {
        // Once a run (ADR 0047): its message id comes from the run's, so the event delivered again queues nothing.
        commands.enqueueOnce(
                UUID.nameUUIDFromBytes(("self-test:" + event.runId()).getBytes(StandardCharsets.UTF_8)),
                event.hostId(),
                RUN_SELF_TEST,
                OpenStreamResponse.newBuilder()
                        .setRunSelfTest(
                                RunSelfTest.newBuilder().setRunId(event.runId().toString())),
                Instant.now(clock).plus(LIVES),
                true);
    }
}
