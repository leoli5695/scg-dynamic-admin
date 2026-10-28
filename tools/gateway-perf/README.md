# Gateway performance baseline

This is a test harness, not a production feature. It starts the current gateway bytecode, a synthetic upstream, and the load generator as three separate processes on loopback. It never changes server deployments or uses paid model endpoints. `GatewayBaselineApplication` lives in `src/test` and is not packaged in the production JAR.

## Run on Windows

Requires JDK 21+, Maven, Node 20+, and the project's Maven dependencies. Example:

```powershell
./tools/gateway-perf/run.ps1 `
  -JavaHome 'C:/path/to/jdk' `
  -MavenCommand 'C:/path/to/maven/bin/mvn.cmd' `
  -Levels '1,16,64' -Repetitions 2 -WarmupMs 3000 -HoldMs 8000
```

The script compiles the reactor and runs `GatewayConfigurationTest` to obtain the correct test/runtime classpath. If Java/Maven are not on PATH, pass the paths explicitly. The local IntelliJ installation is an optional fallback on the original development machine.

Validate the load generator itself:

```powershell
node --test tools/gateway-perf/load.test.mjs
```

Raw output is written to `tools/gateway-perf/results/<timestamp>/` (Git-ignored):

- `results.json`: every run, HTTP errors, latency/TTFT/TPOT distributions, generator checks, per-second resources, source commit and environment.
- `report.md`: readable comparison table, including low-sample and environment-limit flags.
- `gateway.log`, `upstream.log`, `build.log`: reproducibility and diagnostics.

Only an ephemeral test JWT is used. `ready.json` is deleted on normal teardown. No real credentials are required. The harness binds to `127.0.0.1`; do not expose its unauthenticated fixture metrics endpoint.

## Scripts in this directory

| File | Role |
|---|---|
| `run.ps1` | Windows entry point. Resolves the JDK/Maven paths, compiles the reactor, runs `GatewayConfigurationTest` to capture the test classpath, then invokes `run.mjs`. |
| `run.mjs` | Orchestrator. Starts the mock upstream and the gateway process, prewarms, drives every case through `load.mjs`, writes `results.json` / `report.md` / `*.log`, and removes `ready.json` on teardown. |
| `load.mjs` | Closed-loop load generator plus the `measure` / `distribution` helpers used by the runner. |
| `load.test.mjs` | Self-test for the generator (`node --test tools/gateway-perf/load.test.mjs`). |
| `mock.mjs` | Deterministic upstream fixture (ordinary JSON, 1MiB body, timed SSE) exposing `/__baseline/metrics` for its own CPU/RSS/event-loop lag. |
| `summarize.mjs` | Re-renders a publishable `report.md` from a saved `results.json`. It refuses incomplete, failed or superseded runs and checks the expected run count. |
| `compare-basic.mjs` | Builds the Basic coalescing A/B report from two result files. |

`run.mjs` writes both `results.json` and a first-pass `report.md` during the run. To regenerate the report later from saved results, or to build the coalescing comparison:

```powershell
node tools/gateway-perf/summarize.mjs <results.json> <report.md>
node tools/gateway-perf/compare-basic.mjs <off-results.json> <on-results.json> <report.md>
```

Reports produced by `summarize.mjs` link back to this README, so keep the two consistent when the harness changes.

## Scenario matrix

| Class | Cases | What is exercised |
|---|---|---|
| Ordinary API | direct upstream and gateway | 1KiB-class JSON response, persistent HTTP/1.1 connections, real SCG filter chain |
| Authentication | JWT, API key, Basic | Actual authentication filters and bindings; reused JWT/warm cache; BCrypt cost=10 with successful-result caching disabled |
| Large request | direct upstream and gateway | Exactly 1MiB POST, upstream validates received length |
| AI streaming | direct upstream and gateway | 16 SSE content events plus DONE; gateway enables AI_PROXY selection, body handling, provider URL rewrite and key injection |

The deterministic SSE upstream waits 50ms before the first content frame, then schedules one every 10ms. Actual timer cadence is measured through the direct control; OS timer granularity may be coarser. The gateway provider key is a local test fixture, representing a warm key cache rather than admin-key-fetch latency.

The gateway is configured with 512MiB heap, 256MiB maximum direct memory, and `ActiveProcessorCount=4`. The CPU-count setting is **not an OS quota**. Filter-chain tracking remains active. Hot-path diagnostic warnings are suppressed. Trace export, access logs, response/semantic caches, WAF, Redis, Nacos traffic and background scheduled tasks are disabled to keep this first baseline reproducible. Large-body results measure pass-through, not JSON transformation or WAF scans.

## Measurement semantics

- Closed-loop fixed concurrency. This measures behaviour under concurrent in-flight requests, not open-loop arrival-rate capacity.
- Warmup traffic precedes each measured window. Successful completion rate counts validated responses finishing inside the window. Latency/error samples correspond to requests started in the window and allowed to drain afterwards.
- P50/P95/P99 are nearest-rank percentiles of successful requests. Errors and timeouts are reported separately, never hidden inside success latency. P99 with fewer than 1,000 samples is flagged as low-confidence.
- TTFT is the first **content event**, not receipt of HTTP headers. TPOT is the interval between content events. Missing frames or DONE are errors even if HTTP status is 200.
- Gateway CPU is the process CPU-time delta divided by elapsed time, in CPU cores. Heap/direct/nonheap and GC are sampled through a fixture endpoint in the gateway process. Heap peaks include garbage awaiting collection from previous cases; they are not per-request retained memory. Gateway OS RSS is unavailable in this fixture and is not inferred from heap.
- The upstream reports its CPU, RSS and event-loop lag. The load generator reports its own CPU, RSS and event-loop lag. A run is flagged if the generator or upstream approaches one full core or has high event-loop delay; do not treat such a run as the gateway's maximum capacity.
- Keep repetitions and direct controls. Do not subtract separate-run percentiles to claim added gateway latency. Results are a local baseline, not production SLOs, multi-node capacity, or WAN/TLS results.

Before recording, the runner calibrates the idle event-loop timing floor and prewarms all gateway routes/JIT (ordinary 15s, JWT/API key 5s each, Basic/body/SSE 3s each, plus 1s admission warmup per case). The overload event-loop threshold is the larger of 20ms or the idle P99 plus 10ms. This matters on Windows with coarse timer resolution. Per-run distributions and resource samples are retained; individual request timings are not stored.

To reproduce on another host, build the same commit, preserve JVM/load parameters, and run the fixture on an otherwise quiet machine. Compare ordinary APIs, authentication, large bodies and TTFT separately. Redis/Nacos dependency and observability costs should be measured as explicitly labelled follow-up profiles.

## Basic verification coalescing A/B

Both profiles disable successful-result caching and retain BCrypt cost=10. They differ only in whether concurrent identical checks share one computation. Requests use one fixed account, so results describe repeated-credential workloads, not many distinct accounts/passwords. Selected Basic profiles use 20 seconds of BCrypt prewarming before measurements.

```powershell
./tools/gateway-perf/run.ps1 -Cases 'gateway-basic' -BasicCoalescing false -Levels '1,16,64' -Repetitions 2 -OutputDirectory './tools/gateway-perf/results/basic-off'
./tools/gateway-perf/run.ps1 -Cases 'gateway-basic' -BasicCoalescing true -Levels '1,16,64' -Repetitions 2 -OutputDirectory './tools/gateway-perf/results/basic-on'
```
