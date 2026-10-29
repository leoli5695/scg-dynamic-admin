# Archived documents

One-off plans and summaries whose work is finished, or which have been superseded by a document that
lives next to the code. **Nothing here is maintained.** Do not follow the instructions, tick the
checkboxes, or trust the file paths, line numbers or counts in these files — they describe the
repository as it was on the date each one was written, and the repository has moved on.

Each file keeps a status banner at the top saying what was actually completed. They are retained
because the reasoning in them (why a change was made, what was measured, what was rejected) is not
recorded anywhere else.

| Document | Was | Archived because |
|---|---|---|
| [gateway-ui-optimization-plan.md](gateway-ui-optimization-plan.md) | `gateway-ui/OPTIMIZATION_PLAN.md` | The plan landed: i18n was split into `src/i18n.ts` + `src/locales/{en,zh}/`, `src/utils/instance.ts` exists and exports everything the plan asked for, `: any` is at zero, the `.bak` files are gone, `src/styles/animations.css` exists and the instance detail page was rebuilt as `features/instance-detail/InstanceDetailLayout.tsx` with `React.Suspense`. Two items remain genuinely open (shared components under `src/components/ui/`, unified error handling). Every path, line number and file size in it is stale. |
| [gateway-ui-console-cleanup.md](gateway-ui-console-cleanup.md) | `gateway-ui/CONSOLE_CLEANUP_GUIDE.md` | The cleanup is done — `git grep -c console.log` over `gateway-ui/src` hits a single file, and `src/utils/logger.ts` exists. **Its recommended `drop_console: true` was actively wrong**: `vite.config.ts` deliberately uses `pure_funcs` instead so that `console.error`/`console.warn` survive the production build. The banner in the file says so. |
| [stress-test-optimization.md](stress-test-optimization.md) | `gateway-admin/docs/features/stress-test-optimization.md` | One-off optimization plan. The memory-bound statistics work landed, but as `service/StressTestStatistics.java`, not the `StressTestOptimizer.java` / `StreamingStatistics.java` the plan named. Its "Database Migration" item claimed a `V24__create_stress_test_table.sql` and Flyway/Liquibase support — neither exists; there is no migration runner in this repository at all, so that item is still open and is called out as such. |
| [gateway-admin-namespace-test-summary.md](gateway-admin-namespace-test-summary.md) | `gateway-admin/TEST_SUMMARY.md` | Duplicate of [`gateway-admin/src/test/README_NamespaceTests.md`](../../gateway-admin/src/test/README_NamespaceTests.md), which sits next to the tests it describes and is the authoritative copy. This one also claimed the tests need a running gateway-admin on :9090 and my-gateway on :80; they run through MockMvc and need only MySQL, Redis and Nacos. |

## Where a document should live instead

| The document is… | Put it in |
|---|---|
| A capability a user can turn on and use today | `docs/features/` |
| A design that is not built, or only partly built | `docs/design/` |
| A finished one-off plan, or superseded by a doc next to the code | `docs/archive/` (here) |
| A measurement or fix log tied to a specific date | `docs/performance/`, `tools/gateway-perf/results/` |
