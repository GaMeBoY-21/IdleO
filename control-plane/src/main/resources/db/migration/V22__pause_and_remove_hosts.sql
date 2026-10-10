-- Hosts can pause, keep disk free for their owner, and be removed (ADR 0023).

-- Paused while paused_at is set and paused_until is empty or still ahead; a pause with an end ends by itself,
-- so nothing sweeps. Resuming clears both.
ALTER TABLE hosts.hosts
    ADD COLUMN paused_at    timestamptz,
    ADD COLUMN paused_until timestamptz,
    ADD COLUMN keep_free_gb integer     NOT NULL DEFAULT 20 CHECK (keep_free_gb BETWEEN 10 AND 500),
    ADD COLUMN removed_at   timestamptz,
    ADD CONSTRAINT hosts_pause_ends_after_start CHECK (paused_until IS NULL OR paused_at IS NOT NULL);

-- A removed host keeps its row, because the ledger points at it, and never comes back.
ALTER TABLE hosts.hosts DROP CONSTRAINT hosts_status_check;
ALTER TABLE hosts.hosts
    ADD CONSTRAINT hosts_status_check
        CHECK (status IN ('pending', 'verified', 'active', 'suspended', 'removed')),
    ADD CONSTRAINT hosts_removed_has_time CHECK ((status = 'removed') = (removed_at IS NOT NULL));
