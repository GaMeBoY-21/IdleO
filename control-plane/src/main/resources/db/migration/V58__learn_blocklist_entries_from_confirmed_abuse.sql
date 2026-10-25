-- A build an admin confirmed as abuse from a review item, to go on the blocklist (ADR 0038, ADR 0041). The blocklist is a
-- file signed with a key the control plane does not hold, so this is the list of what the next signed file is to carry:
-- `export-blocklist-additions` prints it, scripts/release/blocklist.sh signs and publishes it with the distribution's own
-- list, and a row is "published" once the cached feed names its hash. Removing a row pulls the build from the next file.
CREATE TABLE trust.blocklist_additions (
    id         uuid        PRIMARY KEY,
    -- The review item it was learned from. No foreign key: the item is deleted by its retention rule long before a build
    -- should leave the list, and the list's `item` is only a pointer for whoever reads it.
    item_id    uuid        NOT NULL,
    sha256     text        NOT NULL UNIQUE CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    -- The program's name as the runner saw it.
    name       text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    kind       text        NOT NULL CHECK (kind IN ('miner', 'password_cracker')),
    reason     text        NOT NULL CHECK (char_length(reason) BETWEEN 5 AND 500),
    added_by   uuid        NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX blocklist_additions_by_item ON trust.blocklist_additions (item_id);
