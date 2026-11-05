-- A keyed hash of the UPI ID a payment came from, for the linked-accounts signal "same payer" (docs/runbooks/payer-upi-pepper.md):
-- never the ID itself. One row per pepper configured at the time, so a rotation can still link across it.
CREATE TABLE billing.payer_hashes (
    topup_id    uuid        NOT NULL REFERENCES billing.topups (id),
    key_version integer     NOT NULL CHECK (key_version > 0),
    user_id     uuid        NOT NULL,
    hash        bytea       NOT NULL CHECK (length(hash) = 32),
    created_at  timestamptz NOT NULL,
    PRIMARY KEY (topup_id, key_version)
);
CREATE INDEX payer_hashes_by_hash ON billing.payer_hashes (key_version, hash);
CREATE INDEX payer_hashes_by_user ON billing.payer_hashes (user_id);
GRANT SELECT, INSERT ON billing.payer_hashes TO idleo_application;
