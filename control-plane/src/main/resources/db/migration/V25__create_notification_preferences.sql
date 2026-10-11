-- What each person chose to be told about, and where (ADR 0024). Only the choices that differ from the
-- default, which is everything on, are stored. user_id is the identity module's user id, with no foreign
-- key across modules.
CREATE TABLE notifications.preferences (
    user_id    uuid        NOT NULL,
    kind       text        NOT NULL,
    email      boolean     NOT NULL,
    in_app     boolean     NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, kind),
    CHECK (NOT (email AND in_app))
);
