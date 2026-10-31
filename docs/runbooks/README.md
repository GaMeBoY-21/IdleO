# runbooks

Every alert links to its section of alerts.md: what the alert means, what to check first, and what to do. monitoring.md says where the metrics, logs and traces go and how Grafana Cloud and the uptime check are set up; backups.md how the database is backed up, restored and rehearsed; database.md its failover, standby and users (ADR 0047); breach.md what to do when personal data may have reached the wrong hands (ADR 0037).

Beside them, what running the service needs: deployment.md (production's secrets, and what must be done before launch), kubernetes.md (the chart and the Mac's k3d cluster, ADR 0045), deploying.md (how a change reaches staging and production, promoting, rolling back, a failed canary and the secrets, ADR 0046), the cluster's security (ADR 0048: image-refused.md, network-rules.md, valkey-password.md, kyverno.md, audit-log.md), local-stack.md, and one runbook for each operator task (credits, hosts, invites, admins, email, object storage, the certificate authority, the identity provider).

Operator commands name who runs them with `OPERATOR=<handle>`: a short handle (letters, digits, `.`, `_` or `-`, such as `nikki`), never an email or a full name, since the audit log keeps people by id only (ADR 0037). Restores re-apply every erasure (backups.md); a personal data breach is breach.md.
