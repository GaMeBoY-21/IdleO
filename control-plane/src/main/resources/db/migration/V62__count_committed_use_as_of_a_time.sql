-- When metering last counted a hold's committed use (ADR 0017): every minute the session had started by then.
-- Lease renewal adds the minutes started since, so a renewal between two counts never lets a lease outrun the hold.
-- Null until metering first counts the session; the release before this one never writes it.
ALTER TABLE billing.reservations ADD COLUMN committed_as_of timestamptz;
