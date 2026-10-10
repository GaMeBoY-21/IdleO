-- How many minutes each host's part of a settlement was for, so an owner's earnings can say "41 min" beside each
-- amount (ADR 0023). The ledger has only amounts.
ALTER TABLE metering.settlement_parts ADD COLUMN minutes integer;

UPDATE metering.settlement_parts p
SET minutes = m.minutes
FROM (
    SELECT u.settlement_txn AS txn_id, a.host_id, count(*)::integer AS minutes
    FROM metering.usage_minutes u
    JOIN sessions.attempts a ON a.session_id = u.session_id AND a.attempt = u.agent_attempt
    WHERE u.billed
    GROUP BY u.settlement_txn, a.host_id
) m
WHERE m.txn_id = p.txn_id AND m.host_id = p.host_id;

ALTER TABLE metering.settlement_parts ALTER COLUMN minutes SET NOT NULL;
ALTER TABLE metering.settlement_parts ADD CONSTRAINT settlement_parts_minutes_positive CHECK (minutes > 0);
