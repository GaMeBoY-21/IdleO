-- The console's search (ADR 0052): names by trigram, personal values by an exact match on their normal form, and
-- Razorpay's and RazorpayX's ids.
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

CREATE INDEX users_by_lower_email ON identity.users (lower(email));
CREATE INDEX users_by_name_trigram ON identity.users USING gin (display_name public.gin_trgm_ops);
CREATE INDEX hosts_by_name_trigram ON hosts.hosts USING gin (name public.gin_trgm_ops);
CREATE INDEX methods_by_lower_vpa ON payouts.methods (lower(vpa));
CREATE INDEX payouts_by_razorpayx_id ON payouts.payouts (razorpayx_id);

-- A PAN is sealed, so an exact match needs a value made from it under a key: the same PAN gives the same value, and the
-- value gives nothing back without the key. Rows from before this are filled by PanLookups.
ALTER TABLE payouts.pans ADD COLUMN pan_lookup bytea;
CREATE INDEX pans_by_lookup ON payouts.pans (pan_lookup);
