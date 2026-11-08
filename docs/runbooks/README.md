# runbooks

Every alert links to its section of alerts.md: what the alert means, what to check first, and what to do. monitoring.md says where the metrics, logs and traces go and how Grafana Cloud and the uptime check are set up; backups.md how the database is backed up, restored and rehearsed; audit-chain.md the admin audit log's hash chain and off-site copy (ADR 0051); database.md its failover, standby and users (ADR 0047); breach.md what to do when personal data may have reached the wrong hands (ADR 0037).

Beside them, what running the service needs: deployment.md (production's secrets, and what must be done before launch), kubernetes.md (the chart and the Mac's k3d cluster, ADR 0045), deploying.md (how a change reaches staging and production, promoting, rolling back, a failed canary and the secrets, ADR 0046), the cluster's security (ADR 0048: image-refused.md, network-rules.md, valkey-password.md, kyverno.md, audit-log.md), local-stack.md, and one runbook for each operator task (credits, hosts, invites, admins, email, object storage, the certificate authority, the identity provider). The money's own: month-close.md (the checklist, closing, a correction), reopening-a-period.md, settlement-mismatch.md and sending-the-ca-pack.md (ADR 0055).

Operator commands name who runs them with `OPERATOR=<handle>`: a short handle (letters, digits, `.`, `_` or `-`, such as `nikki`), never an email or a full name, since the audit log keeps people by id only (ADR 0037). Restores re-apply every erasure (backups.md); a personal data breach is breach.md.

The inbox (ADR 0053): breaching-chargeback.md, handling-an-appeal.md, writing-a-macro.md, and payer-upi-pepper.md for the same-payer signal.

The control room (ADR 0056): stop-the-bleeding.md (which kill switch for which incident), maintenance-mode.md and rolling-out-an-agent-release.md.
