-- Fair use of a host's home internet (ADR 0038): how fast a machine uploaded to our storage in its latest self-test, and the
-- most a job may upload at once on it (half of that, never under the agent's floor), both in kbit/s. Null until a self-test
-- that measured the speed has run; a later self-test that did not leaves what is here.
ALTER TABLE hosts.self_tests
    ADD COLUMN upload_kbps     integer CHECK (upload_kbps > 0),
    ADD COLUMN upload_cap_kbps integer CHECK (upload_cap_kbps > 0);
