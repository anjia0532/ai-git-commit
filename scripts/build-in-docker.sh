#!/usr/bin/env bash
# Build the plugin inside a disposable Gradle container.
#
# - The host only needs Docker (WSL2 is fine).
# - Gradle caches live in the named volume "ai-git-commit-gradle", so they
#   survive container restarts and are never written into the host home dir.
# - Set PROXY=http://host:port to route Gradle traffic through a proxy.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_IMAGE="${GRADLE_IMAGE:-gradle:8.10-jdk21}"
CACHE_VOLUME="${CACHE_VOLUME:-ai-git-commit-gradle}"
PROXY="${PROXY:-}"

PROXY_ARGS=()
if [ -n "$PROXY" ]; then
  PROXY_ARGS+=(
    -e "HTTP_PROXY=$PROXY"
    -e "HTTPS_PROXY=$PROXY"
    -e "http_proxy=$PROXY"
    -e "https_proxy=$PROXY"
  )
  GRADLE_PROXY=(
    "-Dhttp.proxyHost=$(echo "$PROXY" | sed -E 's#^https?://##; s#:.*$##')"
    "-Dhttp.proxyPort=$(echo "$PROXY" | sed -E 's#^https?://[^:]+:##; s#/.*$##')"
    "-Dhttps.proxyHost=$(echo "$PROXY" | sed -E 's#^https?://##; s#:.*$##')"
    "-Dhttps.proxyPort=$(echo "$PROXY" | sed -E 's#^https?://[^:]+:##; s#/.*$##')"
    "-Dhttps.nonProxyHosts=localhost|127.0.0.1"
  )
else
  GRADLE_PROXY=()
fi

docker run --rm \
  -v "$ROOT":/workspace \
  -v "$CACHE_VOLUME":/home/gradle/.gradle \
  -w /workspace \
  "${PROXY_ARGS[@]}" \
  "$GRADLE_IMAGE" \
  ./gradlew --no-daemon "${GRADLE_PROXY[@]}" "$@"
