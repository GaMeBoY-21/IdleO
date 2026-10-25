-- Why an account was suspended, chosen by the admin who suspends it (ADR 0041): misconduct as a host holds the account's
-- payouts until the suspension is lifted; any other reason leaves them being paid. Null for an account that is not
-- suspended, and for one held by the abuse watch (a renter's session was stopped), which is not about hosting.
ALTER TABLE identity.users ADD COLUMN suspension_cause text CHECK (suspension_cause IN ('host_misconduct', 'other'));
