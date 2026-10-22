-- Software no renter may run on a host's GPU, and what to do about it (ADR 0038).

-- A ban is an account held until an admin clears the review item that caused it: refused like a suspension, but only that
-- review lifts it (Reinstate on the Users page does not).
ALTER TABLE identity.users DROP CONSTRAINT users_status_check;
ALTER TABLE identity.users ADD CONSTRAINT users_status_check
    CHECK (status IN ('waiting', 'active', 'suspended', 'banned', 'erased'));

-- One review item for each session the runner or the behaviour reports flagged or stopped: what was seen, the renter, the host
-- and what was done automatically. An admin confirms it (the suspension stays, perhaps a ban) or clears it (the account is
-- reinstated and the session's charge refunded). Never over a name or an address: ids only.
CREATE TABLE trust.abuse_items (
    id             uuid        PRIMARY KEY,
    session_id     uuid        NOT NULL UNIQUE,
    attempt        integer     NOT NULL CHECK (attempt > 0),
    renter_id      uuid        NOT NULL,
    host_id        uuid,
    state          text        NOT NULL CHECK (state IN ('open', 'confirmed', 'cleared')),
    -- What happened without an admin: nothing, the session stopped, or it stopped and the account was suspended.
    action         text        NOT NULL CHECK (action IN ('none', 'stopped', 'suspended')),
    banned         boolean     NOT NULL DEFAULT false,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    resolved_at    timestamptz,
    resolved_by    uuid,
    resolution     text,
    refund_txn     uuid,
    refund_paise   bigint      CHECK (refund_paise > 0),
    CHECK ((state = 'open') = (resolved_at IS NULL))
);

CREATE INDEX abuse_items_by_state ON trust.abuse_items (state, created_at);
CREATE INDEX abuse_items_by_renter ON trust.abuse_items (renter_id);

-- Each signal on an item, in the order they came: a process name, a listed build, a pool's domain, dropped connections, a
-- mining-like pattern of behaviour. `detail` is a program's name or a listed domain, a few numbers for a pattern; `sha256`
-- is a listed build's.
CREATE TABLE trust.abuse_signals (
    id         uuid        PRIMARY KEY,
    item_id    uuid        NOT NULL REFERENCES trust.abuse_items (id),
    kind       text        NOT NULL CHECK (kind IN ('process_name', 'process_hash', 'pool_lookup', 'connection_drops', 'mining_pattern')),
    detail     text        NOT NULL,
    sha256     text        NOT NULL DEFAULT '',
    count      bigint      NOT NULL DEFAULT 0,
    stopped    boolean     NOT NULL,
    at         timestamptz NOT NULL
);

CREATE INDEX abuse_signals_by_item ON trust.abuse_signals (item_id, at);

-- A signal the agent sends again until it is acked is one row, not two.
CREATE UNIQUE INDEX abuse_signals_once ON trust.abuse_signals (item_id, kind, detail, sha256, stopped);

-- An admin's notes on an item: the words are their reason.
CREATE TABLE trust.abuse_notes (
    id         uuid        PRIMARY KEY,
    item_id    uuid        NOT NULL REFERENCES trust.abuse_items (id),
    author_id  uuid        NOT NULL,
    -- 5 to 500 characters when written; empty once the person is erased (ADR 0037).
    note       text        NOT NULL CHECK (length(note) <= 500),
    at         timestamptz NOT NULL
);

CREATE INDEX abuse_notes_by_item ON trust.abuse_notes (item_id, at);

-- What a job did, each minute it ran: how hard it worked the GPU and how much of its memory, the traffic over the PCIe bus,
-- what the network carried and whether it printed. For the review of abuse only: never billed on, never shown to a host,
-- kept 90 days (legal.retention.behaviour).
CREATE TABLE trust.behavior_samples (
    session_id   uuid        NOT NULL,
    attempt      integer     NOT NULL,
    minute_index integer     NOT NULL,
    host_id      uuid        NOT NULL,
    util_pct     integer     NOT NULL,
    memory_pct   integer     NOT NULL,
    pcie_kb_s    integer     NOT NULL,
    net_out      bigint      NOT NULL,
    net_in       bigint      NOT NULL,
    printed      bigint      NOT NULL,
    conn_drops   bigint      NOT NULL,
    at           timestamptz NOT NULL,
    PRIMARY KEY (session_id, attempt, minute_index)
);

CREATE INDEX behavior_samples_by_time ON trust.behavior_samples (at);
