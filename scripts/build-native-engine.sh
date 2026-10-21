#!/usr/bin/env bash
# Cross-compiles the bundled OpenCode Zen engine from opencode2api sources.
#
# The engine is what lets the plugin use OpenCode's free models without asking
# the user to run a separate gateway: the upstream free-tier gate fingerprints
# the official OpenCode client (Bun's TLS ClientHello and HTTP/1.1 layout), so a
# plain Java HTTP client cannot talk to it. The engine is started and stopped by
# the plugin, talks to the IDE over loopback only, and is never user-managed.
#
# Go modules and build caches live in the named volume "ai-git-commit-go", so
# they survive container restarts and stay off the host.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${OPENCODE2API_SRC:-$ROOT/../_research/opencode2api}"
PIN="${OPENCODE2API_PIN:-0bb23c7a7f0b7cbe08c475e7148c56bc477abcf9}"
GOPROXY_VALUE="${GOPROXY_VALUE:-https://goproxy.cn,direct}"
TARGETS="${TARGETS:-windows/amd64 linux/amd64 linux/arm64 darwin/amd64 darwin/arm64}"

if [ ! -d "$SRC/.git" ]; then
  echo "opencode2api sources not found at $SRC" >&2
  echo "Clone them first: git clone https://github.com/jasonxu114514/opencode2api \"$SRC\"" >&2
  exit 1
fi

pushd "$SRC" >/dev/null
git fetch --depth 1 origin "$PIN" 2>/dev/null || true
git reset --hard -q "$PIN"
# Local hardening patches (idempotent): non-essential state writes must not
# prevent the engine from starting in a read-only profile directory.
for patch in "$ROOT"/scripts/patches/*.patch; do
  [ -e "$patch" ] || continue
  if git apply --check "$patch" 2>/dev/null; then
    git apply "$patch"
  fi
done
popd >/dev/null

mkdir -p "$ROOT/src/main/resources/native" "$ROOT/src/main/resources/META-INF/third-party/opencode2api"
cp "$SRC/LICENSE" "$ROOT/src/main/resources/META-INF/third-party/opencode2api/LICENSE"

docker run --rm \
  -v "$SRC":/src \
  -v "$ROOT/src/main/resources/native":/out \
  -v ai-git-commit-go:/go \
  -w /src \
  -e GOPROXY="$GOPROXY_VALUE" \
  -e GOMODCACHE=/go/pkg/mod \
  -e GOCACHE=/go/cache \
  -e CGO_ENABLED=0 \
  golang:1.24-bookworm \
  sh -c '
    set -e
    go mod download
    for target in '"$TARGETS"'; do
      os=${target%/*}
      arch=${target#*/}
      out=/out/${os}-${arch}
      mkdir -p "$out"
      ext=""
      [ "$os" = "windows" ] && ext=".exe"
      echo "building ${os}/${arch}"
      GOOS=$os GOARCH=$arch go build -trimpath -ldflags="-s -w" \
        -o "$out/opencode2api$ext" ./cmd/opencode2api
    done
  '

echo "--- bundled engine binaries ---"
find "$ROOT/src/main/resources/native" -type f -printf '%p %k KB\n'
