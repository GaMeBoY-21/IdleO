-- The host score (docs/plan/execution-plan.md "Host score", technical design lines 359-369): how reliable a host has
-- been and how fast its line is, worked out by the hosts module nightly and after each session ends, and read by the
-- scheduler through SchedulableHosts. Nothing in it names a renter.

-- The speed check also times the download; the score uses both directions.
ALTER TABLE hosts.self_tests ADD COLUMN download_kbps integer CHECK (download_kbps > 0);

-- Every self-test's outcome, for the last days' pass rate. hosts.self_tests keeps only the latest.
CREATE TABLE hosts.self_test_results (
    id          uuid        PRIMARY KEY,
    host_id     uuid        NOT NULL REFERENCES hosts.hosts (id),
    passed      boolean     NOT NULL,
    reported_at timestamptz NOT NULL
);
CREATE INDEX self_test_results_by_host ON hosts.self_test_results (host_id, reported_at DESC);
CREATE INDEX self_test_results_by_time ON hosts.self_test_results (reported_at);

-- One row a host: the latest score and what it was made of, for the owner's plain word and the admin's numbers.
CREATE TABLE hosts.host_scores (
    host_id          uuid          PRIMARY KEY REFERENCES hosts.hosts (id),
    reliability      numeric(5, 4) NOT NULL CHECK (reliability BETWEEN 0 AND 1),
    bandwidth        numeric(5, 4) NOT NULL CHECK (bandwidth BETWEEN 0 AND 1),
    session_score    numeric(5, 4) NOT NULL CHECK (session_score BETWEEN 0 AND 1),
    self_test_score  numeric(5, 4) NOT NULL CHECK (self_test_score BETWEEN 0 AND 1),
    penalty          numeric(5, 4) NOT NULL CHECK (penalty BETWEEN 0 AND 1),
    sessions_finished integer      NOT NULL CHECK (sessions_finished >= 0),
    sessions_lost    integer       NOT NULL CHECK (sessions_lost >= 0),
    self_tests_passed integer      NOT NULL CHECK (self_tests_passed >= 0),
    self_tests_failed integer      NOT NULL CHECK (self_tests_failed >= 0),
    reports_upheld   integer       NOT NULL CHECK (reports_upheld >= 0),
    flags            integer       NOT NULL CHECK (flags >= 0),
    speed_kbps       integer       CHECK (speed_kbps > 0),
    computed_at      timestamptz   NOT NULL
);

-- Why an attempt was lost to its host (sessions.leases lapsing, the host going missing, its agent stopping it), so the
-- host score counts it; an attempt its owner took back by returning to the machine is lost without being the host's fault.
ALTER TABLE sessions.attempts ADD COLUMN lost_reason text;
CREATE INDEX attempts_lost_by_host ON sessions.attempts (host_id, ended_at) WHERE lost_reason IS NOT NULL;
