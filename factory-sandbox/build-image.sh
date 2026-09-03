#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
docker build -t factory-sandbox:jdk21 .
echo "Image factory-sandbox:jdk21 built OK"
