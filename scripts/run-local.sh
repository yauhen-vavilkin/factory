#!/usr/bin/env bash
# Local launcher for the Factory app.
# Reads the LLM API key from a file (never argv / logs):
#   key file: $FACTORY_KEY_FILE or ~/.factory/api-key  (chmod 600, single line = key)
# Optional env overrides: FACTORY_LLM_MODEL (default glm-5.3)
# Extra args are passed to the app, e.g.:
#   ./scripts/run-local.sh --factory.inbox.dir=/tmp/inbox
set -euo pipefail
cd "$(dirname "$0")/.."

KEY_FILE="${FACTORY_KEY_FILE:-$HOME/.factory/api-key}"
if [ ! -f "$KEY_FILE" ]; then
  echo "ERROR: key file not found: $KEY_FILE" >&2
  echo "Create it:" >&2
  echo "  install -m 600 /dev/null '$KEY_FILE'" >&2
  echo "  echo '<your-api-key>' > '$KEY_FILE'" >&2
  exit 1
fi
export ANTHROPIC_API_KEY="$(tr -d '[:space:]' < "$KEY_FILE")"
export FACTORY_LLM_MODEL="${FACTORY_LLM_MODEL:-glm-5.3}"

JAR="$(ls -t factory-app/target/factory-app-*.jar 2>/dev/null | head -1 || true)"
if [ -z "$JAR" ]; then
  echo "ERROR: jar not found - build first:" >&2
  echo "  mvn -pl factory-app -am package -DskipTests" >&2
  exit 1
fi
echo "Starting $JAR (model=$FACTORY_LLM_MODEL, key from $KEY_FILE)"
exec java -jar "$JAR" "$@"
