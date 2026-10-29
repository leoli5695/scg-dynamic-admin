import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { requestDiagnosis, routeConfigurationUrl } from '../src/features/request-journey/links.ts';
import { isUsageUnresolved, usageTokenValue } from '../src/features/token-usage/usagePresentation.ts';
import * as usageNavigation from '../src/features/request-journey/usageNavigation.ts';

// Exercise the real TSX callbacks and lifecycle without a browser or a development server.
// UI components are boundaries; DOM/focus behavior remains part of browser verification.
const journeyFile = new URL('../src/features/request-journey/index.tsx', import.meta.url);
const editorFile = new URL('../src/features/request-replay/components/EditRequestModal.tsx', import.meta.url);
const compiled = new Map();
function loadTsx(file, mocks) {
  if (!compiled.has(file.href)) {
    compiled.set(file.href, ts.transpileModule(readFileSync(file, 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true },
    }).outputText);
  }
  const module = { exports: {} };
  new Function('require', 'module', 'exports', compiled.get(file.href))((name) => {
    assert.ok(name in mocks, `Unexpected dependency: ${name}`);
    return mocks[name];
  }, module, module.exports);
  return module.exports.default;
}

function hooks() {
  const slots = [];
  let cursor = 0;
  let active = true;
  let pendingEffects = [];
  let lateUpdates = 0;
  return {
    useState(initial) {
      const index = cursor++;
      slots[index] ??= { value: typeof initial === 'function' ? initial() : initial };
      return [slots[index].value, (value) => {
        if (!active) { lateUpdates++; return; }
        slots[index].value = typeof value === 'function' ? value(slots[index].value) : value;
      }];
    },
    useRef(initial) {
      const index = cursor++;
      slots[index] ??= { current: initial };
      return slots[index];
    },
    useEffect(effect, deps) {
      const index = cursor++;
      const old = slots[index];
      if (old && deps && old.deps?.length === deps.length && deps.every((value, i) => Object.is(value, old.deps[i]))) return;
      pendingEffects.push(() => {
        old?.cleanup?.();
        slots[index] = { deps, cleanup: effect() };
      });
    },
    render(Component, props) {
      cursor = 0;
      const tree = Component(props);
      const effects = pendingEffects;
      pendingEffects = [];
      effects.forEach(effect => effect());
      return tree;
    },
    unmount() {
      active = false;
      slots.forEach(slot => slot.cleanup?.());
    },
    get lateUpdates() { return lateUpdates; },
  };
}
const jsx = (type, props, key) => ({ type, props: props ?? {}, key });
const ui = Object.fromEntries(['Alert', 'Button', 'Card', 'Col', 'Descriptions', 'Divider', 'Modal', 'Row', 'Select', 'Statistic', 'Steps', 'Table', 'Tag', 'Tooltip'].map(name => [name, name]));
ui.Form = { useForm: () => [{}], Item: 'Form.Item' };
ui.Input = { TextArea: 'TextArea' };
ui.Space = { Compact: 'Space.Compact' };
ui.Typography = { Text: 'Text', Title: 'Title' };
const icons = Object.fromEntries(['ArrowLeftOutlined', 'EditOutlined', 'ReloadOutlined', 'ThunderboltOutlined'].map(name => [name, name]));
const defaultComponent = name => ({ __esModule: true, default: name });
function commonMocks(getHooks) {
  return {
    react: {
      __esModule: true, default: {},
      useState: value => getHooks().useState(value),
      useRef: value => getHooks().useRef(value),
      useEffect: (effect, deps) => getHooks().useEffect(effect, deps),
    },
    'react/jsx-runtime': { jsx, jsxs: jsx },
    antd: ui,
    '@ant-design/icons': icons,
    'react-i18next': { useTranslation: () => ({ t: key => key }) },
  };
}
function nodes(tree, predicate) {
  if (Array.isArray(tree)) return tree.flatMap(child => nodes(child, predicate));
  if (!tree || typeof tree !== 'object') return [];
  return [...(predicate(tree) ? [tree] : []), ...nodes(tree.props?.children, predicate)];
}
const byType = (tree, type) => nodes(tree, node => node.type === type);
function button(tree, label) {
  const found = byType(tree, 'Button').find(node => node.props.children === label);
  assert.ok(found, `Missing button ${label}`);
  return found;
}
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const trace = { id: 73, traceId: 'trace-a', routeId: 'route&a', method: 'POST', path: '/ai/chat', statusCode: 200, latencyMs: 15 };
const editable = { traceId: 73, traceUuid: 'trace-a', method: 'POST', path: '/ai/chat', queryString: 'a=1', headers: { 'X-Api-Key': 'secret', Accept: 'application/json' }, requestBody: '{"a":1}', replayable: true };
const result = { traceId: 73, success: true, statusCode: 200, latencyMs: 20, responseBody: 'ok' };

function journey(overrides = {}) {
  let scope = hooks();
  let currentKey;
  const retired = [];
  const context = {
    params: { instanceId: 'gw/a', traceId: 'trace-a' }, search: new URLSearchParams(),
    request: { data: { trace, usage: [] }, loading: false, error: undefined, run: () => { calls.refresh++; } },
    route: { data: { category: 'HTTP' }, loading: false, error: undefined },
    ...overrides,
  };
  const calls = { prepare: [], execute: [], navigate: [], refresh: 0 };
  const api = {
    prepare: async () => editable,
    execute: async () => result,
    ...overrides.api,
  };
  const Page = loadTsx(journeyFile, {
    ...commonMocks(() => scope),
    'react-router-dom': {
      useParams: () => context.params,
      useSearchParams: () => [context.search],
      useNavigate: () => url => calls.navigate.push(url),
    },
    '../../api/requestJourney': { getRequestJourney: () => context.request.data },
    '../../api/routes': { getRouteById: () => context.route.data },
    '../../api/replay': {
      prepareReplay: (...args) => { calls.prepare.push(args); return api.prepare(...args); },
      executeReplay: (...args) => { calls.execute.push(args); return api.execute(...args); },
    },
    '../../hooks/useRequest': { useRequest: (_, options) => options.deps.length === 2 ? context.request : context.route },
    '../request-replay/components/EditRequestModal': defaultComponent('EditRequestModal'),
    '../request-replay/components/ReplayResultModal': defaultComponent('ReplayResultModal'),
    '../trace/components/TraceDetailDrawer': defaultComponent('TraceDetailDrawer'),
    '../token-usage/TokenUsageReconciliationDrawer': defaultComponent('UsageDrawer'),
    '../token-usage/usagePresentation': { isUsageUnresolved, usageTokenValue },
    './links': { requestDiagnosis, routeConfigurationUrl },
    './usageNavigation': usageNavigation,
  });
  return {
    context, calls, api, retired,
    render() {
      const child = Page();
      if (currentKey !== undefined && currentKey !== child.key) {
        scope.unmount(); retired.push(scope); scope = hooks();
      }
      currentKey = child.key;
      return scope.render(child.type, child.props);
    },
    unmount() { scope.unmount(); retired.push(scope); },
  };
}
const modal = h => byType(h.render(), 'Modal')[0];
const editor = h => byType(h.render(), 'EditRequestModal')[0];
async function prepare(h) { await button(h.render(), 'journey.editAndResend').props.onClick(); }
async function confirm(h, options = { modifiedBody: 'edited' }, quick = false) {
  await prepare(h);
  editor(h).props.onExecute(quick, options);
  return modal(h);
}

test('unsampled requests display access-log evidence without enabling trace replay', () => {
  for (const statusCode of [200, 503]) {
    const accessLog = { id: 91, traceId: 'trace-a', routeId: 'logged-route', method: 'GET', path: '/recorded',
      statusCode, durationMs: 37, errorMessage: statusCode === 503 ? 'connection failed' : null };
    const h = journey({ request: { data: { trace: null, accessLog, usage: [] }, loading: false, run() {} } });
    const tree = h.render();
    assert.ok(byType(tree, 'Card').some(card => card.props.title === 'journey.accessLog'));
    assert.ok(byType(tree, 'Alert').some(alert => alert.props.message === 'journey.result.logOnly'));
    assert.ok(!byType(tree, 'Alert').some(alert => alert.props.message === 'journey.result.pending'));
    const items = byType(tree, 'Descriptions')[0].props.items;
    assert.equal(items.find(item => item.key === 'path').children, 'GET /recorded');
    assert.equal(items.find(item => item.key === 'latency').children, '37 ms');
    assert.equal(items.find(item => item.key === 'error').children, accessLog.errorMessage || '—');
    assert.equal(byType(tree, 'Steps')[0].props.items[1].status, 'finish');
    assert.equal(byType(tree, 'Steps')[0].props.items[2].status, 'wait');
    assert.equal(button(tree, 'journey.editAndResend').props.disabled, true);
    assert.equal(button(tree, 'journey.openTrace').props.disabled, true);
    button(tree, 'journey.openConfiguration').props.onClick();
    assert.match(h.calls.navigate[0], /logged-route/);
    assert.deepEqual(h.calls.execute, []);
    h.unmount();
  }
});

test('access-log fallback does not replace trace evidence or imply AI metering exists', () => {
  const accessLog = { routeId: 'log-route', method: 'GET', path: '/log', statusCode: 200, durationMs: 37 };
  const h = journey({ request: { data: { trace, accessLog, usage: [] }, loading: false, run() {} } });
  assert.equal(byType(h.render(), 'Descriptions')[0].props.items.find(item => item.key === 'path').children, 'POST /ai/chat');
  h.unmount();
  const ai = journey({ request: { data: { trace: null, accessLog, usage: [] }, loading: false, run() {} }, route: { data: { category: 'AI' } } });
  assert.ok(byType(ai.render(), 'Alert').some(alert => alert.props.message === 'journey.result.usageMissing'));
  assert.equal(button(ai.render(), 'journey.editAndResend').props.disabled, true);
  ai.unmount();
});

test('return navigation accepts only the current instance requests path, preserving its complete query', () => {
  const h = journey();
  const center = '/instances/gw%2Fa/requests';
  const valid = `${center}?source=trace&from=2026-01-01&to=2026-01-02&routeId=route%26a&statusCode=500&page=3`;
  for (const value of [center, valid]) {
    h.context.search.set('returnTo', value);
    button(h.render(), 'journey.backToRequests').props.onClick();
    assert.equal(h.calls.navigate.at(-1), value);
  }
  for (const value of ['', 'https://evil.test', '//evil.test', 'javascript:alert(1)', '/instances/other/requests', `${center}-extra`, `${center}/../routes`, `${center}%3fnext=1`, `${center}#other`, `${center}?q=\\evil`, `${center}?q=\n/evil`]) {
    h.context.search.set('returnTo', value);
    button(h.render(), 'journey.backToRequests').props.onClick();
    assert.equal(h.calls.navigate.at(-1), center, value);
  }
  button(h.render(), 'journey.routeUsage').props.onClick();
  const usageUrl = new URL(h.calls.navigate.at(-1), 'https://console.test');
  assert.equal(usageUrl.pathname, '/instances/gw%2Fa/token_usage');
  assert.equal(usageUrl.searchParams.get('routeId'), 'route&a');
  button(h.render(), 'journey.debugAgain').props.onClick();
  assert.equal(h.calls.navigate.at(-1), '/instances/gw%2Fa/routes/route%26a/debug');
  h.unmount();
});

test('usage navigation carries search time and returns to the complete current detail URL', () => {
  const h = journey();
  const origin = '/instances/gw%2Fa/requests?from=2026-10-28T12%3A00%3A00&to=2026-10-29T12%3A00%3A00&source=logs&routeId=route%26a&page=5';
  h.context.search = new URLSearchParams({ returnTo: origin, routeId: 'route&a' });
  button(h.render(), 'journey.routeUsage').props.onClick();
  const url = new URL(h.calls.navigate.at(-1), 'https://console.test');
  assert.equal(url.searchParams.get('from'), '2026-10-28T12:00:00');
  assert.equal(url.searchParams.get('to'), '2026-10-29T12:00:00');
  const detail = new URL(url.searchParams.get('returnTo'), url);
  assert.equal(detail.pathname, '/instances/gw%2Fa/trace/trace-a');
  assert.equal(detail.searchParams.toString(), h.context.search.toString());
  for (const usage of ['/instances/gw%2Fa/token_usage?page=4&size=50', '/token-usage?page=3&size=100']) {
    h.context.search = new URLSearchParams({ returnTo: usage });
    button(h.render(), 'journey.backToUsage').props.onClick();
    assert.equal(h.calls.navigate.at(-1), usage);
  }
  h.unmount();
});

test('usage buttons distinguish details and reconciliation, unknown token placeholders stay unknown', () => {
  const h = journey();
  const columns = byType(h.render(), 'Table')[0].props.columns;
  for (const field of ['prompt', 'completion', 'total']) {
    const column = columns.find(column => column.title === `tokenUsage.chart.${field}`);
    assert.equal(column.render(null, { usageStatus: 'UNKNOWN', promptTokens: 0, completionTokens: 0, totalTokens: 0 }), '—');
    assert.equal(column.render(null, { usageStatus: 'CONFIRMED', promptTokens: 0, completionTokens: 0, totalTokens: 0 }), 0);
    assert.equal(column.render(null, { usageStatus: 'CONFIRMED' }), '—');
  }
  const action = columns.at(-1);
  assert.equal(action.render(null, { usageStatus: 'CONFIRMED', settlementStatus: 'NOT_REQUIRED' }).props.children, 'journey.usageDetails');
  for (const row of [{ usageStatus: 'UNKNOWN' }, { usageStatus: 'ESTIMATED' }, { settlementStatus: 'FAILED' }, { settlementStatus: 'PENDING' }]) {
    assert.equal(action.render(null, row).props.children, 'journey.usageReconcile');
  }
  action.render(null, { id: 5, usageStatus: 'CONFIRMED' }).props.onClick();
  assert.equal(byType(h.render(), 'UsageDrawer')[0].props.event.id, 5);
  button(h.render(), 'journey.openTrace').props.onClick();
  assert.equal(byType(h.render(), 'TraceDetailDrawer')[0].props.open, true);
  h.unmount();
});

test('mount, refresh and preparation never send; editing requires a separate explicit confirmation', async () => {
  const pending = deferred();
  const h = journey({ api: { prepare: () => pending.promise } });
  button(h.render(), 'common.refresh').props.onClick();
  assert.equal(h.calls.refresh, 1);
  assert.equal(h.calls.prepare.length, 0);
  const click = button(h.render(), 'journey.editAndResend').props.onClick;
  const preparation = click();
  await click();
  assert.equal(h.calls.prepare.length, 1);
  assert.equal(h.calls.prepare[0][0], 73);
  assert.ok(h.calls.prepare[0][1].signal instanceof AbortSignal);
  assert.equal(button(h.render(), 'journey.editAndResend').props.loading, true);
  assert.equal(h.calls.execute.length, 0);
  pending.resolve(editable);
  await preparation;
  assert.equal(editor(h).props.request, editable);
  editor(h).props.onExecute(false, { modifiedPath: '/edited' });
  assert.equal(modal(h).props.open, true);
  assert.equal(modal(h).props.title, 'journey.confirmResendTitle');
  assert.equal(byType(modal(h), 'Alert')[0].props.message, 'journey.resendWarning');
  assert.equal(h.calls.execute.length, 0);
  modal(h).props.onCancel();
  assert.equal(modal(h).props.open, false);
  assert.ok(editor(h));
  h.unmount();
});

test('confirmed execution uses numeric trace id and instance, locks duplicate sends and opens the result', async () => {
  const pending = deferred();
  const h = journey({ api: { execute: () => pending.promise } });
  const options = { modifiedPath: '/edited', removedHeaders: ['X-Api-Key'], compareWithOriginal: true };
  const confirmation = await confirm(h, options);
  const execution = confirmation.props.onOk();
  await confirmation.props.onOk();
  assert.equal(h.calls.execute.length, 1);
  assert.deepEqual(h.calls.execute[0].slice(0, 3), [73, 'gw/a', options]);
  assert.equal(modal(h).props.confirmLoading, true);
  assert.equal(modal(h).props.cancelButtonProps.disabled, true);
  pending.resolve(result);
  await execution;
  assert.equal(editor(h), undefined);
  assert.equal(modal(h).props.open, false);
  const resultModal = byType(h.render(), 'ReplayResultModal')[0];
  assert.equal(resultModal.props.open, true);
  assert.equal(resultModal.props.result, result);
  resultModal.props.onClose();
  assert.equal(byType(h.render(), 'ReplayResultModal')[0].props.open, false);
  h.unmount();
});

test('quick replay also requires confirmation and executes without edited options', async () => {
  const h = journey();
  const confirmation = await confirm(h, { modifiedBody: 'ignored' }, true);
  assert.equal(confirmation.props.title, 'journey.confirmOriginalTitle');
  assert.equal(h.calls.execute.length, 0);
  await confirmation.props.onOk();
  assert.deepEqual(h.calls.execute[0].slice(0, 3), [73, 'gw/a', {}]);
  h.unmount();
});

test('missing traces, invalid numeric ids and explicit false disable resending; absent flag allows preparation', async () => {
  for (const unavailable of [null, { ...trace, id: undefined }, { ...trace, id: 0 }, { ...trace, replayable: false }]) {
    const h = journey();
    h.context.request.data.trace = unavailable;
    const action = button(h.render(), 'journey.editAndResend');
    assert.equal(action.props.disabled, true);
    await action.props.onClick();
    assert.equal(h.calls.prepare.length, 0);
    h.unmount();
  }
  const h = journey();
  assert.equal(button(h.render(), 'journey.editAndResend').props.disabled, false);
  await prepare(h);
  assert.ok(editor(h));
  h.unmount();
});

test('preparation rejects unavailable or mismatched data and shows failures without sending', async () => {
  for (const [response, expected] of [[{ ...editable, replayable: false }, 'journey.replayUnavailable'], [{ ...editable, traceId: 999 }, 'journey.replayIdentityMismatch']]) {
    const h = journey({ api: { prepare: async () => response } });
    await prepare(h);
    assert.equal(editor(h), undefined);
    assert.ok(byType(h.render(), 'Alert').some(node => node.props.message === expected));
    assert.equal(h.calls.execute.length, 0);
    if (!response.replayable) assert.equal(button(h.render(), 'journey.editAndResend').props.disabled, true);
    h.unmount();
  }
  const h = journey({ api: { prepare: async () => { throw new Error('offline'); } } });
  await prepare(h);
  assert.ok(byType(h.render(), 'Alert').some(node => node.props.message === 'journey.replayPrepareError offline'));
  assert.equal(button(h.render(), 'journey.editAndResend').props.loading, false);
  h.api.prepare = async () => editable;
  await prepare(h);
  assert.ok(editor(h));
  h.unmount();
});

test('execution failures stay visible in confirmation and never automatically retry', async () => {
  const h = journey({ api: { execute: async () => { throw new Error('connection lost'); } } });
  const confirmation = await confirm(h);
  await confirmation.props.onOk();
  assert.equal(modal(h).props.open, true);
  assert.equal(modal(h).props.confirmLoading, false);
  assert.ok(byType(modal(h), 'Alert').some(node => node.props.message === 'journey.replayExecuteError connection lost'));
  h.render();
  assert.equal(h.calls.execute.length, 1);
  assert.ok(editor(h));
  h.unmount();
});

test('unsuccessful replay responses show result and error feedback', async () => {
  const failed = { ...result, success: false, statusCode: 502, error: 'upstream response' };
  const h = journey({ api: { execute: async () => failed } });
  await (await confirm(h)).props.onOk();
  assert.equal(byType(h.render(), 'ReplayResultModal')[0].props.result, failed);
  assert.ok(byType(h.render(), 'Alert').some(node => node.props.message === 'journey.replayFailed upstream response'));
  h.unmount();
});

test('changing trace or instance resets drawers and confirmation; late preparation cannot reopen the old editor', async () => {
  for (const change of [{ traceId: 'trace-b' }, { instanceId: 'gw-b' }]) {
    for (const reject of [false, true]) {
      const pending = deferred();
      const h = journey({ api: { prepare: () => pending.promise } });
      button(h.render(), 'journey.openTrace').props.onClick();
      const preparation = button(h.render(), 'journey.editAndResend').props.onClick();
      const signal = h.calls.prepare[0][1].signal;
      h.context.params = { ...h.context.params, ...change };
      h.render();
      assert.equal(signal.aborted, true);
      reject ? pending.reject(new Error('old error')) : pending.resolve(editable);
      await preparation;
      assert.equal(editor(h), undefined);
      assert.equal(byType(h.render(), 'TraceDetailDrawer')[0].props.open, false);
      assert.ok(!byType(h.render(), 'Alert').some(node => node.props.type === 'error'));
      assert.equal(h.retired[0].lateUpdates, 0);
      h.unmount();
    }
  }
  const h = journey();
  await confirm(h);
  h.context.params = { ...h.context.params, traceId: 'trace-c' };
  assert.equal(modal(h).props.open, false);
  assert.equal(editor(h), undefined);
  assert.equal(h.calls.execute.length, 0);
  h.unmount();
});

test('switching request or unmounting during execution aborts observation and ignores late success and errors', async () => {
  for (const switchRequest of [false, true]) {
    for (const reject of [false, true]) {
      const pending = deferred();
      const h = journey({ api: { execute: () => pending.promise } });
      const execution = (await confirm(h)).props.onOk();
      const signal = h.calls.execute[0][3].signal;
      if (switchRequest) {
        h.context.params = { instanceId: 'other', traceId: 'other-trace' };
        h.render();
      } else h.unmount();
      assert.equal(signal.aborted, true);
      reject ? pending.reject(new Error('late execution error')) : pending.resolve(result);
      await execution;
      assert.equal(h.retired[0].lateUpdates, 0);
      assert.equal(h.calls.execute.length, 1);
      if (switchRequest) {
        assert.equal(byType(h.render(), 'ReplayResultModal')[0].props.open, false);
        assert.equal(editor(h), undefined);
        assert.ok(!byType(h.render(), 'Alert').some(node => node.props.type === 'error'));
        h.unmount();
      }
    }
  }
});

function editHarness() {
  const scope = hooks();
  const calls = [];
  const Editor = loadTsx(editorFile, {
    ...commonMocks(() => scope),
    '../../instance-detail/ResourceEditor': defaultComponent('ResourceEditor'),
  });
  const props = { open: true, request: editable, replaying: false, onCancel() {}, onExecute: (...args) => calls.push(args) };
  return { calls, render: () => scope.render(Editor, props) };
}
function headerContent(h) { return h.render().props.sections[1].content; }
function deleteHeader(h, name) {
  const row = nodes(headerContent(h), node => node.key === name)[0];
  button(row, '×').props.onClick();
}
function addHeader(h, name, value) {
  const inputs = nodes(headerContent(h), node => node.props.placeholder?.startsWith('replay.header_'));
  inputs.find(node => node.props.placeholder === 'replay.header_name').props.onChange({ target: { value: name } });
  inputs.find(node => node.props.placeholder === 'replay.header_value').props.onChange({ target: { value } });
  button(headerContent(h), '+').props.onClick();
}

test('editor explicitly removes deleted captured headers while preserving remaining request options', async () => {
  const h = editHarness();
  deleteHeader(h, 'X-Api-Key');
  await h.render().props.onFinish();
  const [quick, options] = h.calls[0];
  assert.equal(quick, false);
  assert.deepEqual(options.removedHeaders, ['X-Api-Key']);
  assert.deepEqual(options.modifiedHeaders, { Accept: 'application/json' });
  assert.equal(options.compareWithOriginal, true);
  assert.equal(options.modifiedBody, undefined);
  assert.equal(editable.headers['X-Api-Key'], 'secret');
});

test('editor can re-add a deleted header with different casing without subsequently removing it', async () => {
  const h = editHarness();
  deleteHeader(h, 'X-Api-Key');
  addHeader(h, 'x-api-key', 'replacement');
  await h.render().props.onFinish();
  assert.deepEqual(h.calls[0][1].removedHeaders, []);
  assert.equal(h.calls[0][1].modifiedHeaders['x-api-key'], 'replacement');
  deleteHeader(h, 'x-api-key');
  await h.render().props.onFinish();
  assert.deepEqual(h.calls[1][1].removedHeaders, ['X-Api-Key']);
});

test('editor preserves the existing quick-replay behavior and does not send deletion options in quick mode', () => {
  const h = editHarness();
  deleteHeader(h, 'X-Api-Key');
  h.render().props.footerExtra.props.onClick();
  assert.deepEqual(h.calls, [[true, {}]]);
});

test('journey copy is complete in both languages and distinguishes HTTP clues from proven causes', () => {
  const zh = JSON.parse(readFileSync(new URL('../src/locales/zh/journey.json', import.meta.url), 'utf8'));
  const en = JSON.parse(readFileSync(new URL('../src/locales/en/journey.json', import.meta.url), 'utf8'));
  assert.deepEqual(Object.keys(zh).sort(), Object.keys(en).sort());
  const source = readFileSync(journeyFile, 'utf8');
  for (const [, key] of source.matchAll(/['"](journey\.[\w.]+)['"]/g)) {
    assert.ok(zh[key]?.trim(), `Missing zh: ${key}`);
    assert.ok(en[key]?.trim(), `Missing en: ${key}`);
  }
  for (const key of ['authentication', 'rateLimit', 'upstream', 'request']) {
    assert.match(zh[`journey.result.${key}`], /初步线索/);
    assert.match(en[`journey.result.${key}`], /Preliminary clue/);
  }
  assert.match(zh['journey.resendWarning'], /副作用.*Token 费用/);
  assert.match(en['journey.resendWarning'], /side effects.*Token charges/);
  assert.match(zh['journey.replayExecuteError'], /可能已执行/);
  assert.match(en['journey.replayExecuteError'], /may already have executed/);
});
