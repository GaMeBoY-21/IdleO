-- Metering (ADR 0017): every minute of a session, and its two witnesses.

-- The control plane's own lease record, as coverage: one row per unbroken
-- stretch of an attempt's lease, from the send of its StartSession or a renewal
-- to that send plus the lease. A renewal sent before the stretch ends extends
-- it; one sent late starts a new stretch, so the gap is never covered.
CREATE TABLE sessions.lease_coverage (
    id            uuid        PRIMARY KEY,
    session_id    uuid        NOT NULL REFERENCES sessions.sessions (id),
    attempt       integer     NOT NULL CHECK (attempt >= 1),
    covered_from  timestamptz NOT NULL,
    covered_until timestamptz NOT NULL,
    CHECK (covered_until > covered_from)
);

CREATE INDEX lease_coverage_by_attempt ON sessions.lease_coverage (session_id, attempt, covered_until DESC);

-- When an attempt stopped running, on the control plane's clock, and the price
-- its minutes are charged at (its host's, copied at match time).
ALTER TABLE sessions.attempts ADD COLUMN ended_at timestamptz;
ALTER TABLE sessions.attempts ADD COLUMN price_paise_per_min integer;
UPDATE sessions.attempts a
SET price_paise_per_min = coalesce(s.price_paise_per_min, s.max_price_paise_per_min)
FROM sessions.sessions s
WHERE s.id = a.session_id;
ALTER TABLE sessions.attempts ALTER COLUMN price_paise_per_min SET NOT NULL;
ALTER TABLE sessions.attempts ADD CONSTRAINT attempts_price_positive CHECK (price_paise_per_min > 0);

-- One row per session minute (technical design "Metering and billing"). The
-- agent's UsageReport sets agent_reported; the control plane sets lease_alive
-- from its own lease record (lease_checked says it has decided). A minute is
-- billable only with both. minute_at is the session's minute 0 plus
-- minute_index, on the control plane's clock, and the partition key.
CREATE TABLE metering.usage_minutes (
    session_id     uuid        NOT NULL,
    minute_index   integer     NOT NULL CHECK (minute_index >= 0),
    minute_at      timestamptz NOT NULL,
    agent_reported boolean     NOT NULL DEFAULT false,
    agent_attempt  integer     CHECK (agent_attempt >= 1),
    gpu_seconds    integer     CHECK (gpu_seconds BETWEEN 1 AND 60),
    avg_util_pct   integer     CHECK (avg_util_pct BETWEEN 0 AND 100),
    lease_alive    boolean     NOT NULL DEFAULT false,
    lease_checked  boolean     NOT NULL DEFAULT false,
    lease_attempt  integer     CHECK (lease_attempt >= 1),
    billed         boolean     NOT NULL DEFAULT false,
    waived         boolean     NOT NULL DEFAULT false,
    PRIMARY KEY (session_id, minute_index, minute_at),
    CHECK (agent_reported = (agent_attempt IS NOT NULL AND gpu_seconds IS NOT NULL)),
    CHECK (NOT lease_alive OR lease_checked),
    CHECK (NOT billed OR (agent_reported AND lease_alive))
) PARTITION BY RANGE (minute_at);

-- A month per partition, in UTC: this month and the next three. A daily job
-- keeps three months ahead. There's deliberately no default partition: a minute
-- outside every partition fails loudly instead of landing somewhere unwatched.
DO $$
DECLARE
    first_month timestamp := date_trunc('month', now() AT TIME ZONE 'UTC');
    month_start timestamp;
BEGIN
    FOR i IN 0..3 LOOP
        month_start := first_month + make_interval(months => i);
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS metering.%I PARTITION OF metering.usage_minutes FOR VALUES FROM (%L) TO (%L)',
            'usage_minutes_' || to_char(month_start, 'YYYY_MM'),
            month_start AT TIME ZONE 'UTC',
            (month_start + interval '1 month') AT TIME ZONE 'UTC');
    END LOOP;
END
$$;

-- Each session the control plane meters, from its minute 0 until it's closed:
-- its renter, its spending cap, and how many settlements it has had.
CREATE TABLE metering.metered_sessions (
    session_id      uuid        PRIMARY KEY,
    renter_id       uuid        NOT NULL,
    started_at      timestamptz NOT NULL,
    max_spend_paise bigint      NOT NULL CHECK (max_spend_paise > 0),
    ended_at        timestamptz,
    closed_at       timestamptz,
    created_at      timestamptz NOT NULL,
    CHECK (closed_at IS NULL OR ended_at IS NOT NULL)
);

CREATE INDEX metered_sessions_open ON metering.metered_sessions (started_at) WHERE closed_at IS NULL;

-- How far the control plane has decided each attempt's lease witness: every
-- whole minute up to checked_through (relative to the attempt's first minute).
CREATE TABLE metering.metered_attempts (
    session_id      uuid    NOT NULL REFERENCES metering.metered_sessions (session_id),
    attempt         integer NOT NULL CHECK (attempt >= 1),
    checked_through integer NOT NULL DEFAULT -1,
    PRIMARY KEY (session_id, attempt)
);

-- Minutes only one witness saw, past the wait after the session ended: never
-- charged automatically (ADR 0005). Nobody works this queue yet.
CREATE TABLE metering.review_minutes (
    session_id   uuid        NOT NULL,
    minute_index integer     NOT NULL,
    minute_at    timestamptz NOT NULL,
    attempt      integer,
    reason       text        NOT NULL CHECK (reason IN ('agent_only', 'lease_only', 'late_report')),
    created_at   timestamptz NOT NULL,
    PRIMARY KEY (session_id, minute_index)
);
