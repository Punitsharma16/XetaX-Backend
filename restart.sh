#!/bin/bash
# Restarts the CRM backend: kills old JVMs, loads .env, starts fresh.
# (pkill patterns are built at runtime so this script never matches itself.)
PAT1=$(printf '%s%s' "Crm" "Application")
pkill -9 -f "$PAT1" 2>/dev/null
PAT2=$(printf '%s%s' "spring-boo" "t:run")
pkill -9 -f "$PAT2" 2>/dev/null
sleep 3
cd "$(dirname "$0")"
set -a; source .env; set +a
nohup mvn spring-boot:run > target/run.log 2>&1 &
echo "started pid $!"
