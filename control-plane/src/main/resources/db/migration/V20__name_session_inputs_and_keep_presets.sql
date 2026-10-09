-- Named inputs and presets (ADR 0022).

-- What a session reads, by its template's name for each input ("model",
-- "data"): exactly one of an upload of the renter's or an https link. An
-- upload's row keeps it from being deleted or expiring while a session that
-- hasn't ended uses it.
CREATE TABLE sessions.session_inputs (
    session_id uuid NOT NULL REFERENCES sessions.sessions (id),
    name       text NOT NULL CHECK (name ~ '^[a-z][a-z0-9_]{0,31}$'),
    upload_id  uuid REFERENCES sessions.uploads (id),
    url        text,
    PRIMARY KEY (session_id, name),
    CHECK ((upload_id IS NULL) <> (url IS NULL))
);

CREATE INDEX session_inputs_by_upload ON sessions.session_inputs (upload_id) WHERE upload_id IS NOT NULL;

-- A session's one input, from before inputs had names, under the name "file".
INSERT INTO sessions.session_inputs (session_id, name, upload_id, url)
SELECT id, 'file', input_upload_id, input_url
FROM sessions.sessions
WHERE input_upload_id IS NOT NULL OR input_url IS NOT NULL;

DROP INDEX sessions.sessions_by_input_upload;
ALTER TABLE sessions.sessions DROP CONSTRAINT sessions_one_input;
ALTER TABLE sessions.sessions DROP COLUMN input_upload_id;
ALTER TABLE sessions.sessions DROP COLUMN input_url;

-- The option chosen for each of the template's preset questions, by question
-- name, defaults filled in, so Run again asks the same way.
ALTER TABLE sessions.sessions ADD COLUMN presets jsonb NOT NULL DEFAULT '{}'::jsonb;
