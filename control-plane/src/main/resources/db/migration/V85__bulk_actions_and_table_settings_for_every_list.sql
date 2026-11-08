-- contract: bulk_items no longer points only at inbox items, since a row of any list can be acted on; the release before writes only rows the constraint allowed and reads nothing from it, safe since v0
ALTER TABLE queues.bulk_items DROP CONSTRAINT bulk_items_item_id_fkey;

-- Which list a batch's items are rows of (ADR 0057). Everything before this was the inbox.
ALTER TABLE queues.bulk_batches ADD COLUMN list text NOT NULL DEFAULT 'inbox';

-- How an admin likes each table (ADR 0057): columns' order, widths and pins, and how dense its rows are.
CREATE TABLE console.table_settings (
    admin_id uuid NOT NULL,
    table_key text NOT NULL,
    settings jsonb NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (admin_id, table_key)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON console.table_settings TO idleo_application;
