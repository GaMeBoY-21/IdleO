package com.idleo.controlplane.agentgateway;

import gpu.agent.v1.Session.AbuseSignal;
import gpu.agent.v1.Session.UsageReport;
import java.util.UUID;

/**
 * A port the agent gateway declares and the trust module implements (ADR 0038): what a host's runner saw in a session that
 * an admin should look at, and what the job did each minute. The gateway acks a signal only after this returns; if it
 * throws, there's no ack and the agent sends it again. A minute's behaviour comes with the usage report that metering
 * bills on, but is handled apart from it, so a failure here never delays a charge.
 */
public interface SessionSignals {

    /** What a job did this minute: its GPU, its memory, its bus, its network, whether it printed. */
    void onBehaviour(UUID hostId, UsageReport report);

    /** The runner flagged the session, or stopped it for software no renter may run on a host's GPU. */
    void onAbuse(UUID hostId, AbuseSignal signal);
}
