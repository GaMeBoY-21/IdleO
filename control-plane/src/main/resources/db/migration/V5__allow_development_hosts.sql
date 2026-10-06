-- A development agent on a Mac reports os = macos (ADR 0013). Production
-- control planes refuse such a Hello, so only local and test databases ever
-- hold the value.
ALTER TABLE hosts.hosts
    DROP CONSTRAINT hosts_os_check,
    ADD CONSTRAINT hosts_os_check CHECK (os IN ('windows', 'linux', 'macos'));
