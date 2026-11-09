-- What an invite code unlocks (ADR 0058): renting, hosting, or both, as every code before this did.
ALTER TABLE identity.invite_codes
    ADD COLUMN unlocks text NOT NULL DEFAULT 'both' CHECK (unlocks IN ('renting', 'hosting', 'both'));

-- The sides a person has unlocked with a code: one row a side. A person with none is unlocked for both, as everyone was
-- before this, and as the release before still activates people; only a code that unlocks one side writes rows.
CREATE TABLE identity.user_sides (
    user_id     uuid        NOT NULL REFERENCES identity.users (id),
    side        text        NOT NULL CHECK (side IN ('renting', 'hosting')),
    unlocked_at timestamptz NOT NULL,
    code_id     uuid        REFERENCES identity.invite_codes (id),
    PRIMARY KEY (user_id, side)
);
GRANT SELECT, INSERT, DELETE ON identity.user_sides TO idleo_application;
