-- Each booking that ended without a host (ADR 0056): the GPU class it asked for and the hour in India it asked, which
-- the control room's heatmap compares with what was asked and served. No person is named here; the session is.
CREATE TABLE sessions.unmet_bookings (
    session_id uuid PRIMARY KEY REFERENCES sessions.sessions (id),
    gpu_class_mb integer NOT NULL CHECK (gpu_class_mb > 0),
    asked_hour smallint NOT NULL CHECK (asked_hour BETWEEN 0 AND 23),
    why text NOT NULL,
    asked_at timestamptz NOT NULL,
    ended_at timestamptz NOT NULL
);
CREATE INDEX unmet_bookings_by_asked_at ON sessions.unmet_bookings (asked_at);
GRANT SELECT, INSERT ON sessions.unmet_bookings TO idleo_application;

INSERT INTO sessions.unmet_bookings (session_id, gpu_class_mb, asked_hour, why, asked_at, ended_at)
SELECT s.id,
       CASE WHEN s.vram_needed_mb <= 8000 THEN 8000
            WHEN s.vram_needed_mb <= 12000 THEN 12000
            WHEN s.vram_needed_mb <= 16000 THEN 16000
            WHEN s.vram_needed_mb <= 24000 THEN 24000
            ELSE s.vram_needed_mb END,
       extract(hour FROM s.created_at AT TIME ZONE 'Asia/Kolkata')::smallint,
       coalesce((SELECT e.reason FROM sessions.session_events e
                 WHERE e.session_id = s.id AND e.to_state = 'NO_HOST_FREE'
                 ORDER BY e.at DESC LIMIT 1), 'no host'),
       s.created_at,
       s.state_changed_at
FROM sessions.sessions s
WHERE s.state = 'NO_HOST_FREE';

-- What was asked by hour: the heatmap's rollup and the list behind a cell read the last week's bookings.
CREATE INDEX sessions_by_created_at ON sessions.sessions (created_at);
