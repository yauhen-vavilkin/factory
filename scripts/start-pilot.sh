#!/bin/sh
# One command from a fresh clone: build and start the Compose stack, wait for
# health, print the current readiness including the smoke disclosure, then run
# the explicit pilot startup action and report the result. Never prints .env.
set -eu
cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
    echo "No .env found. Copy the template and fill in the required values first:" >&2
    echo "  cp .env.pilot.example .env" >&2
    exit 1
fi

set -a
. ./.env
set +a

docker compose up -d --build

BASE_URL="${FACTORY_APP_URL:-http://127.0.0.1:${FACTORY_APP_PORT:-8080}}"
tries=0
until curl --fail --silent --output /dev/null --max-time 5 "$BASE_URL/actuator/health"; do
    tries=$((tries + 1))
    if [ "$tries" -gt 60 ]; then
        echo "Factory did not become healthy; diagnose with:" >&2
        echo "  docker compose logs --tail=100 factory coding-runtime postgres" >&2
        exit 1
    fi
    sleep 2
done

echo "Current readiness and smoke disclosure (no model call):"
curl --fail --silent "$BASE_URL/api/pilot/status"
printf '\n\n'
echo "Running the explicit startup action now; it runs the disclosed bounded smoke only when needed..."
RESULT=$(curl --silent -X POST "$BASE_URL/api/pilot/start")
echo "$RESULT"
printf '\n'
if echo "$RESULT" | grep -q '"executionReady"[[:space:]]*:[[:space:]]*true'; then
    echo "Pilot execution is ready. Open $BASE_URL/tasks to review discovered tasks, then Run selected."
else
    echo "Pilot execution is NOT ready: fix the blockers above and repeat this same command." >&2
    exit 1
fi
