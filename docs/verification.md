# Verification

Date: 2026-10-21

## Plugin Verifier

- Tool: IntelliJ Plugin Verifier 1.411
- IDE: `IU-262.10968.63` (IntelliJ IDEA Ultimate 2026.2.3)
- Plugin: `dev.aicommit.jetbrains:1.0.0`
- Result: **Compatible**, no deprecated API usages
- Dynamic plugin eligibility: can probably be enabled/disabled without an IDE restart

The plugin is compiled against IntelliJ Platform 2024.2.5 (`since-build 242`). The model-availability additions use APIs present in that baseline (`ProjectActivity`, `postStartupActivity`, `ReadAction.nonBlocking`, `JBTable`) and are covered by the platform tests below.

## Tests

`scripts/build-in-docker.sh test` runs 56 tests, 0 failures, including:

- SSE streaming parser, plain JSON responses, HTTP errors and cancellation paths
- request payload construction (`reasoning_effort`, `max_tokens`/`max_completion_tokens`, temperature rules)
- model catalog parsing (free detection, context/input/output limits, reasoning levels, default level)
- model availability classification (available / region blocked / retired / rate limited / auth failure)
- model availability cache freshness
- public endpoint model filtering (only free and available models)
- model rotation and failover ordering
- diff rendering and prompt budget/truncation
- commit message cleanup
- English/Chinese resource bundles (UTF-8)
- plugin descriptor loading, toolbar action registration, settings page creation, startup activity class loading, the VCS unversioned-file bridge, and embedded engine lifecycle (extract binary, write config, start on loopback, health check, stop)

## Opt-in live tests

With `AI_COMMIT_E2E=1` and network access:

- `EngineEndToEndTest` starts the bundled Linux engine, loads the free model catalog and generates a real commit message (for example `refactor: make Demo class final` with `mimo-v2.6-flash-free`).
- `BridgeEndToEndTest` exercises the same engine through `OpenCodeZenBridge` inside an IntelliJ test application, loads the catalog through `ModelCatalogService`, and probes one free model through `ModelHealthService`.
- `PublicApiEndpointE2ETest` starts the loopback endpoint, verifies that `/v1/models` only contains free models that passed the probe, and proxies a real chat completion through it with an arbitrary client API key.

The bundled Windows engine was also started manually on loopback in a restricted profile where cache writes were denied; with the persistence patch it reached `ready:true` and answered a chat completion with HTTP 200.
