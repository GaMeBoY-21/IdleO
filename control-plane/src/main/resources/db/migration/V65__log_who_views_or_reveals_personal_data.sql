-- The access log (ADR 0051): who at IdleO viewed or revealed whose data, which field, why, when and from which session.
-- Kept a year, like the security log. A person's export shows them theirs. LEGAL(confirm)
CREATE TABLE identity.personal_data_access (
    id               uuid        PRIMARY KEY,
    admin_id         uuid        NOT NULL,
    subject_user_id  uuid,
    field            text        NOT NULL CHECK (field <> ''),
    record_id        text        NOT NULL CHECK (record_id <> ''),
    reason           text        NOT NULL CHECK (reason <> ''),
    admin_session_id uuid,
    at               timestamptz NOT NULL
);
CREATE INDEX personal_data_access_by_subject ON identity.personal_data_access (subject_user_id, at DESC);
CREATE INDEX personal_data_access_by_admin ON identity.personal_data_access (admin_id, at DESC);
CREATE INDEX personal_data_access_oldest ON identity.personal_data_access (at);
GRANT SELECT, INSERT, DELETE ON identity.personal_data_access TO idleo_application;

