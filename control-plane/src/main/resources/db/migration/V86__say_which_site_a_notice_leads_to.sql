-- Which site a notice's link is a path on (ADR 0058): the renter app's, as every notice before this was, or the host
-- site's for a host's own (their laptops, earnings and payouts). The link stays a path; the release before writes no
-- column of this and reads none.
ALTER TABLE notifications.notifications
    ADD COLUMN site text NOT NULL DEFAULT 'app' CHECK (site IN ('app', 'host'));
