-- Giving back minutes lost with a host (ADR 0017).

-- A checkpoint counts from when storage finished writing its _COMPLETE marker,
-- on storage's clock, never the agent's. Rows from before this kept only when
-- the control plane recorded them, which is no earlier.
ALTER TABLE sessions.checkpoints ADD COLUMN completed_at timestamptz;
UPDATE sessions.checkpoints SET completed_at = created_at;
ALTER TABLE sessions.checkpoints ALTER COLUMN completed_at SET NOT NULL;

-- Each host's part of a settlement, so a refund reverses it in the proportions
-- it was charged in.
CREATE TABLE metering.settlement_parts (
    txn_id           uuid   NOT NULL REFERENCES metering.settlements (txn_id),
    host_id          uuid   NOT NULL,
    amount_paise     bigint NOT NULL CHECK (amount_paise > 0),
    host_share_paise bigint NOT NULL CHECK (host_share_paise >= 0 AND host_share_paise <= amount_paise),
    PRIMARY KEY (txn_id, host_id)
);

-- Settlements from before this: each host's minutes at its attempt's price,
-- and the settlement's host share in proportion (exact for one host, which is
-- every settlement so far).
INSERT INTO metering.settlement_parts (txn_id, host_id, amount_paise, host_share_paise)
SELECT p.txn_id, p.host_id, p.amount,
       s.host_share_paise * p.amount / s.amount_paise
FROM (
    SELECT u.settlement_txn AS txn_id, a.host_id, sum(a.price_paise_per_min)::bigint AS amount
    FROM metering.usage_minutes u
    JOIN sessions.attempts a ON a.session_id = u.session_id AND a.attempt = u.agent_attempt
    WHERE u.billed
    GROUP BY u.settlement_txn, a.host_id
) p
JOIN metering.settlements s ON s.txn_id = p.txn_id;

-- One waiver per lost attempt: its minutes that end after waived_from (its
-- newest complete checkpoint, or its first RUNNING if it had none) are never
-- charged, and those already settled were refunded once, in refund_txn.
CREATE TABLE metering.waivers (
    session_id          uuid        NOT NULL REFERENCES metering.metered_sessions (session_id),
    attempt             integer     NOT NULL CHECK (attempt >= 1),
    host_id             uuid        NOT NULL,
    checkpoint_at       timestamptz,
    waived_from         timestamptz NOT NULL,
    refund_txn          uuid        NOT NULL UNIQUE,
    refunded_minutes    integer     NOT NULL CHECK (refunded_minutes >= 0),
    refunded_paise      bigint      NOT NULL CHECK (refunded_paise >= 0),
    host_refund_paise   bigint      NOT NULL CHECK (host_refund_paise >= 0),
    platform_refund_paise bigint    NOT NULL CHECK (platform_refund_paise >= 0),
    created_at          timestamptz NOT NULL,
    PRIMARY KEY (session_id, attempt),
    CHECK (host_refund_paise + platform_refund_paise = refunded_paise),
    CHECK ((refunded_minutes = 0) = (refunded_paise = 0))
);

-- A settled minute that was waived later names the refund that gave it back.
ALTER TABLE metering.usage_minutes ADD COLUMN refund_txn uuid;
ALTER TABLE metering.usage_minutes ADD CONSTRAINT usage_minutes_refund_only_billed_and_waived
    CHECK (refund_txn IS NULL OR (billed AND waived));
