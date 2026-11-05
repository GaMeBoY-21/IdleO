-- The console's pages and timeline (ADR 0052): a host's score a day, and finding what's about a host or a session.
CREATE TABLE hosts.host_score_history (
    host_id     uuid         NOT NULL REFERENCES hosts.hosts (id),
    day         timestamptz  NOT NULL,
    reliability numeric(5, 4) NOT NULL,
    bandwidth   numeric(5, 4) NOT NULL,
    penalty     numeric(5, 4) NOT NULL,
    PRIMARY KEY (host_id, day)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON hosts.host_score_history TO idleo_application;

CREATE INDEX sessions_by_host ON sessions.sessions (host_id, created_at DESC);
CREATE INDEX tickets_by_session ON support.tickets (session_id, created_at DESC) WHERE session_id IS NOT NULL;
CREATE INDEX tickets_by_host ON support.tickets (host_id, created_at DESC) WHERE host_id IS NOT NULL;
CREATE INDEX abuse_items_by_host ON trust.abuse_items (host_id, created_at DESC);
CREATE INDEX abuse_items_by_session ON trust.abuse_items (session_id, created_at DESC);
CREATE INDEX agent_commands_by_session ON agentgateway.agent_commands (session_id, created_at);
CREATE INDEX settlement_parts_by_host ON metering.settlement_parts (host_id);
CREATE INDEX payment_events_by_payment ON billing.payment_events (payment_id, received_at DESC);
CREATE INDEX payment_events_by_order ON billing.payment_events (order_id, received_at DESC);
CREATE INDEX credit_lots_by_source ON billing.credit_lots (source_id);
CREATE INDEX invoices_by_topup ON billing.invoices (topup_id);
CREATE INDEX refund_parts_by_topup ON billing.refund_parts (topup_id);
CREATE INDEX chargebacks_by_topup ON billing.chargebacks (topup_id);
