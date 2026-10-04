-- One schema per control-plane module (ADR 0008). A module's tables live
-- only in its own schema, and no module reads another module's tables.
CREATE SCHEMA identity;
CREATE SCHEMA hosts;
CREATE SCHEMA agentgateway;
CREATE SCHEMA overlay;
CREATE SCHEMA scheduling;
CREATE SCHEMA sessions;
CREATE SCHEMA metering;
CREATE SCHEMA billing;
CREATE SCHEMA payouts;
CREATE SCHEMA trust;
CREATE SCHEMA notifications;
