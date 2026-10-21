# runbooks

Every alert links to its section of alerts.md: what the alert means, what to check first, and what to do. monitoring.md says where the metrics, logs and traces go and how Grafana Cloud and the uptime check are set up; backups.md how the database is backed up, restored and rehearsed; breach.md what to do when personal data may have reached the wrong hands (ADR 0037).

Beside them, what running the service needs: deployment.md (production's secrets, and what must be done before launch), local-stack.md, and one runbook for each operator task (credits, hosts, invites, admins, email, object storage, the certificate authority, the identity provider).
