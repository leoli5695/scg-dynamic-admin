import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import ts from 'typescript';
import * as React from 'react';

const require = createRequire(import.meta.url);
const flush = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
};
function nodes(root, type) {
  if (!root || typeof root !== 'object') return [];
  if (Array.isArray(root)) return root.flatMap(child => nodes(child, type));
  return [...(root.type === type ? [root] : []), ...nodes(root.props?.children, type)];
}

// Run the real page effects with controllable API timing and React hook lifetimes.
function harness(feature) {
  let instanceId = 'A';
  let traceId = 'first';
  let cursor = 0;
  let effects = [];
  const slots = [];
  const calls = { errors: [], lookups: [], prepares: [], executions: [] };
  const t = key => key;
  const emptyPage = async () => ({ content: [], totalElements: 0 });
  const api = {
    getTraceByTraceId: async (id, options) => { calls.lookups.push([id, options]); return { id: 1, instanceId: 'A' }; },
    getTracesByTimeRange: emptyPage, getErrorTraces: emptyPage, getSlowTraces: emptyPage,
    getTraceStats: async () => ({}),
    prepareReplay: async (id, options) => { calls.prepares.push([id, options]); return { traceId: id }; },
    executeReplay: async (...args) => { calls.executions.push(args); return {}; },
  };
  const ui = Object.fromEntries(['Badge', 'Button', 'Card', 'Col', 'Dropdown', 'Input', 'Popconfirm', 'Row', 'Segmented', 'Select', 'Space', 'Statistic', 'Table', 'Tabs', 'Tag', 'Tooltip'].map(key => [key, key]));
  ui.Typography = { Title: 'Title', Text: 'Text' };
  ui.DatePicker = { RangePicker: 'RangePicker' };
  const mocks = {
    react: {
      ...React,
      useState(initial) {
        const index = cursor++;
        slots[index] ??= { value: typeof initial === 'function' ? initial() : initial };
        return [slots[index].value, value => { slots[index].value = typeof value === 'function' ? value(slots[index].value) : value; }];
      },
      useCallback: fn => fn, useMemo: fn => fn(),
      useEffect(effect, deps) {
        const index = cursor++;
        const previous = slots[index];
        if (previous && deps.every((value, i) => Object.is(value, previous.deps[i]))) return;
        effects.push(() => {
          previous?.cleanup?.();
          slots[index] = { deps, cleanup: effect() };
        });
      },
    },
    antd: ui,
    '../../utils/antdStatic': { message: { error: value => calls.errors.push(value) } },
    '@ant-design/icons': new Proxy({}, { get: (_, key) => String(key) }),
    'react-i18next': { useTranslation: () => ({ t }) },
    'react-router-dom': { useSearchParams: () => [new URLSearchParams({ traceId })], useNavigate: () => () => {} },
    '../instance-detail/InstanceDetailContext': { useInstanceDetail: () => ({ instance: { instanceId } }) },
    '../../api/traces': api, '../../api/replay': api,
    '../../api/http': { ApiError: class ApiError extends Error {} },
    '../../hooks/useRequest': { useRequest: () => ({ data: undefined }) },
    './utils': { DEFAULT_VISIBLE_COLUMNS: [], ALL_COLUMNS: [], TIME_RANGE_OPTIONS: [] },
    './components/EditRequestModal': { __esModule: true, default: 'EditRequestModal' },
    './components/ReplayResultModal': { __esModule: true, default: 'ReplayResultModal' },
    './components/TraceDetailDrawer': { __esModule: true, default: 'TraceDetailDrawer' },
    './components/FullChainTab': { __esModule: true, default: 'FullChainTab' },
    './trace.css': {}, './replay.css': {},
  };
  const source = readFileSync(new URL(`../src/features/${feature}/index.tsx`, import.meta.url), 'utf8');
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true } }).outputText;
  const exports = {};
  new Function('require', 'exports', compiled)(id => mocks[id] ?? (id.startsWith('../../utils/') || id.startsWith('../request-journey/') ? {} : require(id)), exports);
  return {
    calls, api,
    navigate(instance, trace) { instanceId = instance; traceId = trace; },
    render() {
      cursor = 0; effects = [];
      const tree = exports.default();
      effects.forEach(effect => effect());
      return tree;
    },
    unmount() { slots.forEach(slot => slot.cleanup?.()); },
  };
}

for (const feature of ['trace', 'request-replay']) {
  test(`${feature}: rejects a foreign-instance deep link`, async () => {
    const h = harness(feature);
    h.api.getTraceByTraceId = async () => ({ id: 9, instanceId: 'B' });
    h.render(); await flush();
    const tree = h.render();
    assert.equal(h.calls.errors.at(-1), feature === 'trace' ? 'trace.instanceMismatch' : 'replay.instanceMismatch');
    assert.equal(h.calls.prepares.length, 0);
    const detail = nodes(tree, feature === 'trace' ? 'TraceDetailDrawer' : 'EditRequestModal')[0];
    assert.equal(detail?.props.open ?? false, false);
  });

  test(`${feature}: switching instances clears old details and reruns lookup`, async () => {
    const h = harness(feature);
    h.render(); await flush();
    h.render();
    h.navigate('B', 'first');
    h.render(); await flush();
    const tree = h.render();
    assert.equal(h.calls.lookups.length, 2);
    assert.equal(h.calls.lookups[0][1].signal.aborted, true);
    const detail = nodes(tree, feature === 'trace' ? 'TraceDetailDrawer' : 'EditRequestModal')[0];
    assert.equal(detail?.props.open ?? false, false);
  });
}

test('replay: late preparation cannot replace the next deep link', async () => {
  const h = harness('request-replay');
  const old = deferred();
  h.api.getTraceByTraceId = async id => ({ id: id === 'first' ? 1 : 2, instanceId: 'A' });
  h.api.prepareReplay = (id, options) => {
    h.calls.prepares.push([id, options]);
    return id === 1 ? old.promise : Promise.resolve({ traceId: 2 });
  };
  h.render(); await flush();
  h.navigate('A', 'second'); h.render(); await flush();
  old.resolve({ traceId: 1 }); await flush();
  const editor = nodes(h.render(), 'EditRequestModal')[0];
  assert.equal(editor.props.request.traceId, 2);
  assert.equal(editor.props.open, true);
  assert.equal(h.calls.prepares[0][1].signal.aborted, true);
  await editor.props.onExecute(true, {});
  assert.deepEqual(h.calls.executions[0], [2, 'A', {}]);
  h.unmount();
});
