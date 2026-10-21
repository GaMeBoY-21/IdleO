#!/usr/bin/env bash
# Erases again, in a restored database, every account on the erased list (ADR 0037, docs/runbooks/backups.md): the
# control plane's reapply-erasures operator command, pointed at the restored database. The list is read from the
# database and from storage (erasures/), so an account erased after the moment restored to is erased again too.
# ops/backup/restore.sh runs it after every restore unless told not to.
#
#   ops/backup/reapply-erasures.sh --port 55432                     development: the local profile, its storage
#   ops/backup/reapply-erasures.sh --port 55432 --jar /srv/idleo/control-plane.jar   production, with the server's env
#
# In production the server's environment (its secrets) must be loaded first, as for any operator command; the
# database's address is the only thing changed. OPERATOR names who's running it.
set -euo pipefail
cd "$(dirname "$0")/../.."
root="$(pwd)"
port=""
jar=""
while (($#)); do
  case "$1" in
    --port) port="$2"; shift 2 ;;
    --jar) jar="$2"; shift 2 ;;
    -h | --help) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "reapply-erasures: unknown option $1 (--help)" >&2; exit 2 ;;
  esac
done
[[ -n "$port" ]] || { echo "reapply-erasures: say which database: --port PORT (the restored one's)" >&2; exit 2; }
export OPERATOR="${OPERATOR:-restore}"
export SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$port/${PGDATABASE:-controlplane}"
if [[ -n "$jar" ]]; then
  exec java -jar "$jar" --spring.profiles.active=prod,ops reapply-erasures
fi
# Development: the local profile without compose, so nothing starts another database beside the restored one.
export SPRING_DOCKER_COMPOSE_ENABLED=false
export SPRING_DATASOURCE_USERNAME="${PGUSER:-controlplane}" SPRING_DATASOURCE_PASSWORD="${PGPASSWORD:-local-only-not-a-secret}"
cd "$root/control-plane"
exec ./mvnw -B -q spring-boot:run -Dspring-boot.run.profiles=local,ops -Dspring-boot.run.arguments=reapply-erasures
