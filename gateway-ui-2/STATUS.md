# gateway-ui-2 — FROZEN, not deployed

**Status: frozen exploration branch. Do not build on this without reading the whole file.**

This is a Vue 3 + ant-design-vue rewrite of the React console in `../gateway-ui`. It was started
and then stopped part-way. As of 2026-10-24 it is deliberately frozen rather than continued.

## It is not deployed anywhere

Nothing in the repository builds, packages or serves this directory:

- `docker-compose.yml` builds only `./gateway-ui`; there is no `gateway-ui-2` service.
- This directory has **no Dockerfile, no nginx.conf and no .dockerignore**.
- `k8s/`, `scripts/`, `prometheus/`, `README.md`, `DEPLOYMENT.md` and `docs/` contain **zero**
  references to `gateway-ui-2`.
- CI exists (`.github/workflows/ci.yml`) but its `Frontend (gateway-ui)` job only ever enters
  `./gateway-ui`; the comment at `ci.yml:157` names this directory as frozen and not deployed.
  So nothing type-checks, lints or builds `gateway-ui-2` on a schedule.

Every change made here is therefore pure maintenance cost with no user-visible effect.

## How far it got

`src/router/index.ts` maps 28 console routes; **23 of them point at `PlaceholderPage`**. The five
implemented modules are auth/login, instances, instance-detail, services and routes. The unfinished
23 are the heaviest ones: trace (waterfall + full-chain), copilot (including SSE streaming),
strategies, filter-chain, topology, stress-test, kubernetes, license, middleware-detail.

## What was wrong with it

- **73% was copy-paste from `gateway-ui`, with no shared package.** There is no root workspace
  (`package.json` / `pnpm-workspace.yaml` / `turbo.json`), so nothing could be shared. `types/`,
  `locales/`, several `*.css` files and `utils/instance.ts` were byte-identical duplicates;
  `src/api/http.ts` says so in its own header comment ("Ported 1:1 from the legacy React
  api/http.ts"). A backend DTO change had to be made twice.
- **Its CSS contradicted its own component library.** This project uses `ant-design-vue@4.2.6`
  (antd v5 class names) while `gateway-ui` uses `antd@6.3.2`. `src/global.css` correctly notes that
  `antd-overrides.css` cannot be reused - but ~4,700 lines of other copied CSS containing 86
  distinct `.ant-*` selectors were carried over anyway, so those styles silently do not apply.
- **Dead code.** 18 files / 2,271 lines had zero importers and were removed on 2026-10-24: 17 of
  the 21 `src/types/*.ts` files (only `instance`, `kubernetes`, `route` and `service` are used) and
  `src/components/ErrorBoundary.vue`, which was written but never wired into `App.vue`. The
  remaining four type files, `env.d.ts` (Vite/TS ambient declarations - required to compile) and
  `features/routes/index.vue` (lazy-imported by the router) are all live.
- **No lint config and no lint/format scripts**, unlike `gateway-ui` which has `eslint.config.js`
  and runs `lint --max-warnings 0`.
- **Born behind on dependencies**: pinia 2.3.1, vue-tsc 2.2.12, vite 6.4.4, echarts 5.6.0,
  TypeScript 5.8.3 - `gateway-ui` is on vite 7.3.1, echarts 6.0.0, TS 5.9.3.

## Why it was frozen instead of finished

Completing the remaining 23 modules was estimated at 6-9 person-months for a single maintainer,
during which `gateway-ui` would still be the deployed console and would still need bug fixes - i.e.
double maintenance, with zero front-end tests to catch regressions in either. Switching from React
to Vue solves no user problem.

## If you restart this

1. Add a Dockerfile, nginx.conf and .dockerignore, and wire a service into `docker-compose.yml`,
   otherwise it still cannot ship.
2. Add `eslint.config.js` and lint/format scripts to match `gateway-ui`.
3. Set up a root workspace and move `types/`, `locales/` and the HTTP client into a shared package
   **before** porting more modules, or the duplication returns.
4. Rewrite the copied `.ant-*` CSS for ant-design-vue 4.x instead of carrying over antd 6 selectors.
5. Wire `src/components/` error handling into `App.vue` (the deleted `ErrorBoundary.vue` is in git
   history if you want it back).
6. Until then, treat `../gateway-ui/src/types/` as the single source of truth for DTO shapes.
