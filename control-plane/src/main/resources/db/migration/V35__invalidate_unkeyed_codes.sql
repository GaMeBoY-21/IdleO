-- Invite codes and admin recovery codes are now stored as HMAC-SHA-256 under a server key (identity.invite-code-key,
-- ADR 0024 and ADR 0027), not plain SHA-256. The plain hashes already stored can never match a keyed one, so every
-- unused code is retired here, in so many words: an unused invite code expires now, and an unused recovery code
-- counts as used. No real users exist yet. New invite codes come from create-invites; an admin gets new recovery
-- codes by being revoked and granted again (docs/runbooks/admin.md).
UPDATE identity.invite_codes
SET expires_at = now()
WHERE used_by IS NULL
  AND (expires_at IS NULL OR expires_at > now());

UPDATE identity.admin_recovery_codes
SET used_at = now()
WHERE used_at IS NULL;
