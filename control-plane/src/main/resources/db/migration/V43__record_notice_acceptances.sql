-- Which Terms and privacy notice each person accepted, and when (ADR 0037): one row each time, never changed. The
-- first also records that they confirmed they are at least legal.notices.minimum-age. Ids only; kept with the money
-- records after an account is erased, as the record of what was agreed (LEGAL(confirm)).
CREATE TABLE identity.notice_acceptances (
    id              uuid        PRIMARY KEY,
    user_id         uuid        NOT NULL REFERENCES identity.users (id),
    terms_version   text        NOT NULL,
    privacy_version text        NOT NULL,
    age_confirmed   boolean     NOT NULL,
    accepted_at     timestamptz NOT NULL
);

CREATE INDEX notice_acceptances_user ON identity.notice_acceptances (user_id, accepted_at DESC);
