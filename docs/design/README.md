# Design documents (not shipping features)

`docs/features/` is meant to be trustworthy: if a document is in there, the capability it describes
exists in the code. This directory holds the documents that cannot make that claim — designs that were
written ahead of implementation, or that describe a surface which was only ever partially built.

**How to read a document here:** every one carries an implementation-status banner at the top and
labels each section **Implemented** or **Not implemented (design only)**. Read the banner before
acting on anything. Where a section describes something that does exist, it names the real class,
endpoint or config key so you can verify it.

**How to decide where a new document goes:**

| The document describes… | Put it in |
|---|---|
| A capability a user can turn on and use today | `docs/features/` |
| A capability that is designed but not built, or built only in part | `docs/design/` |
| A one-off plan or cleanup whose work is finished | `docs/archive/` |
| A measurement or fix log tied to a specific date | `docs/performance/`, `tools/gateway-perf/results/` |

When an item here becomes real, move it to `docs/features/`, delete the status banner, and add it to
the index in `docs/FEATURES.md` and section 21 of `docs/ARCHITECTURE.md`.

## Current contents

| Document | Status | What actually exists today |
|---|---|---|
| [historical-comparison.md](historical-comparison.md) | Mostly design. There is no `HistoricalController`, no `/api/historical/*` endpoint, no `historical.*` config key and no metric-snapshot or downsampling table. | Period-over-period **filter** comparison on the gateway side (`/internal/filter-chain/historical*`, `/internal/filter-chain/comparison*`, backed by `HistoricalDataTracker` and `PeriodComparisonAnalyzer`), multi-instance comparison in the admin (`MultiInstanceComparisonController`, `/api/multi-instance/*`), point-in-time metric queries (`MonitorController`, capped at 168 hours), and linear-regression bottleneck projection (`PerformancePredictor`). |
