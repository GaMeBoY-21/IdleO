-- A session is reported once, ever (ADR 0028): a resolved report can't be followed by another, each of which would
-- hold the host's earnings from payout again. The database says so too.
DROP INDEX trust.disputes_one_open_a_session;
CREATE UNIQUE INDEX disputes_one_a_session ON trust.disputes (session_id);
