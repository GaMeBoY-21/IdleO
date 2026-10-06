package com.idleo.controlplane.agentgateway;

import gpu.agent.v1.Session.UsageReport;
import java.util.UUID;

/**
 * A port the agent gateway declares and the metering module implements (ADR 0017): an agent's report of one
 * minute of work, the first of the two witnesses. The gateway acks the report only after this returns; if it
 * throws, there's no ack and the agent sends it again.
 */
public interface UsageReports {

    void onUsage(UUID hostId, UsageReport report);
}
