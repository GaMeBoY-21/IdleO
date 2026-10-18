-- Why a setup step or self-test check failed, in words: the error text or the check's finding, scrubbed by the setup
-- app of the Windows user name and profile path before it is sent. At most 2,000 characters, truncated by the server.
ALTER TABLE hosts.install_events ADD COLUMN detail text;
