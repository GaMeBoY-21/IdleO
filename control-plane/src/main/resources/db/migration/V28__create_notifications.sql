-- What each person was told (ADR 0026): one row for each message, for the in-app list and for the email sent
-- with it. user_id is the identity module's user id, with no foreign key across modules. id is a UUIDv7, so
-- ordering by id is ordering by time, and a page cursor is an id.
--
-- dedupe_key names the event that caused the message ("session-ended:<session id>"), so an event delivered
-- twice makes one message. in_app is whether the person's choices let it into their list; email_wanted whether
-- their choices let it be emailed. email_claimed_at is set just before the email is handed to the mail server
-- and cleared if that fails, and email_sent_at once it was handed over, so a message is emailed at most once.
CREATE TABLE notifications.notifications (
    id               uuid        PRIMARY KEY,
    user_id          uuid        NOT NULL,
    kind             text        NOT NULL,
    dedupe_key       text        NOT NULL,
    title            text        NOT NULL,
    body             text        NOT NULL,
    link             text        NOT NULL CHECK (link LIKE '/%'),
    created_at       timestamptz NOT NULL,
    in_app           boolean     NOT NULL,
    read_at          timestamptz,
    email_wanted     boolean     NOT NULL,
    email_claimed_at timestamptz,
    email_sent_at    timestamptz,
    UNIQUE (user_id, dedupe_key),
    CHECK (in_app OR email_wanted)
);

CREATE INDEX notifications_in_app ON notifications.notifications (user_id, id DESC) WHERE in_app;
CREATE INDEX notifications_unread ON notifications.notifications (user_id) WHERE in_app AND read_at IS NULL;
