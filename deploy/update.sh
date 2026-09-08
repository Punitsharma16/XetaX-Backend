#!/bin/bash
# Server-side update: pull latest code, rebuild the app image, restart app only.
#   cd /opt/XetaX-Backend/deploy && ./update.sh
# Databases, Caddy and the .env are untouched; data lives in ./data (never deleted).
set -euo pipefail
cd "$(dirname "$0")/.."
echo "▶ git pull"
git pull --ff-only
cd deploy
echo "▶ rebuilding app image"
docker compose build app
echo "▶ restarting app (DBs/Caddy keep running)"
docker compose up -d app
echo "▶ waiting for health…"
for i in $(seq 1 40); do
  sleep 5
  if docker compose exec -T app curl -fs http://localhost:8085/actuator/health 2>/dev/null | grep -q UP; then
    echo "✔ app healthy — $(git log --oneline -1)"
    exit 0
  fi
done
echo "✘ app not healthy after 200s — last log lines:"
docker compose logs --tail=40 app | grep -E "ERROR|Exception|Caused by" | tail -15
exit 1
