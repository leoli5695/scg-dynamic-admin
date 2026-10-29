import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import ts from 'typescript';
import * as React from 'react';
import * as presentation from '../src/features/token-usage/usagePresentation.ts';
import * as query from '../src/features/token-usage/usageQuery.ts';
import * as usageNavigation from '../src/features/request-journey/usageNavigation.ts';

const require = createRequire(import.meta.url);
const normalEvent = {
  id: 1, tenantId: 'tenant', instanceId: 'gw', routeId: 'route', model: 'model',
  usageStatus: 'CONFIRMED', settlementStatus: 'APPLIED', promptTokens: 0, completionTokens: 3, totalTokens: 3,
};

// Exercise component output and callbacks without adding a DOM/test-renderer dependency.
// Hooks, API responses and Ant components are controlled; production JSX is compiled in memory.
function harness(file, { search = '', command = null, role = 'ADMIN', overview = {}, pathname = '/token-usage', quota = null } = {}) {
  let params = new URLSearchParams(search);
  let cursor = 0;
  let locationKey = 0;
  let effects = [];
  const states = [];
  const calls = { confirmations: [], corrections: [], retries: [], requests: [], history: [], runs: 0, mutations: [] };
  const form = { validateFields: async () => ({ promptTokens: 1, completionTokens: 2, totalTokens: 3, chargedTokens: 3, monthlyDelta: 0, dailyDelta: 0, reason: 'verified', verified: true }) };
  const components = Object.fromEntries(['Alert', 'Button', 'Card', 'Checkbox', 'Col', 'Descriptions', 'Drawer', 'InputNumber', 'Row', 'Select', 'Space', 'Spin', 'Table', 'Tabs', 'Tag'].map((name) => [name, name]));
  components.DatePicker = { RangePicker: 'RangePicker' };
  components.Input = Object.assign(() => null, { TextArea: 'TextArea', Search: 'Input.Search' });
  components.Form = Object.assign(() => null, { useForm: () => [form], Item: 'Form.Item' });
  components.Modal = { confirm: (options) => { calls.confirmations.push(options); return { destroy() {} }; } };
  const api = {
    listInstances: () => [], getTokenQuotaTenants: () => [],
    getTokenUsageOverview: (filters) => { calls.filters = filters; return { requests: 2, totalTokens: 3, ...overview }; },
    getTokenUsageTimeseries: (_, granularity) => { calls.granularity = granularity; return { series: [] }; }, getTokenUsageBreakdown: () => ({ rows: [] }),
    getTokenUsageEvents: (filters, page, size) => { calls.events = { filters, page, size }; return { content: [normalEvent], total: 800 }; },
    getTokenUsageReconciliation: (id) => { calls.requests.push(id); return command; },
    reconcileTokenUsage: async (...args) => { calls.corrections.push(args); return { ...command, status: 'APPLIED' }; },
    retryTokenUsageReconciliation: async (...args) => { calls.retries.push(args); return { ...command, status: 'APPLIED' }; },
    getTokenQuotaStatus: () => quota,
  };
  const mocks = {
    react: {
      ...React,
      useState: (initial) => {
        const index = cursor++;
        if (!(index in states)) states[index] = typeof initial === 'function' ? initial() : initial;
        return [states[index], (value) => { states[index] = typeof value === 'function' ? value(states[index]) : value; }];
      },
      useMemo: (fn) => fn(), useEffect: (effect) => { effects.push(effect); },
      useRef: (initial) => {
        const index = cursor++;
        states[index] ??= { current: initial };
        return states[index];
      },
    },
    antd: components,
    'react-router-dom': {
      useLocation: () => ({ pathname, search: `?${params}`, key: String(locationKey) }),
      useNavigate: () => (url) => { calls.navigation = url; },
      useSearchParams: () => [params, (next, options) => {
        params = typeof next === 'function' ? next(params) : next;
        locationKey++;
        calls.history.push({ search: params.toString(), replace: options?.replace === true });
      }],
    },
    'react-i18next': { useTranslation: () => ({ t: (key) => key }) },
    '../../hooks/useRequest': { useRequest: (fetcher, options = {}) => {
      const index = cursor++;
      const run = () => {
        calls.runs++;
        try {
          states[index].data = fetcher(new AbortController().signal);
          states[index].error = undefined;
          options.onSuccess?.(states[index].data);
        } catch (error) { states[index].error = error; }
      };
      // Page tests intentionally fetch on each render; drawer tests retain query state.
      if (file !== 'TokenUsageReconciliationDrawer.tsx' || !(index in states)) {
        const data = options.immediate === false ? undefined : fetcher(new AbortController().signal);
        states[index] = { data, loading: false, error: undefined };
        if (options.immediate !== false) effects.push(() => options.onSuccess?.(data));
      }
      return {
        ...states[index], run,
        mutate: (value) => { calls.mutations.push(value); states[index].data = value; },
      };
    } },
    '../../api/instances': api, '../../api/tokenUsage': api,
    '../auth/AuthContext': { useAuth: () => ({ user: { role } }) },
    '../request-journey/usageNavigation': usageNavigation,
    './usagePresentation': presentation, './usageQuery': query,
    './TokenUsageReconciliationDrawer': { default: 'ReconciliationDrawer', __esModule: true },
    '../layout/components/ManagementPage': { ManagementMetric: 'ManagementMetric', ManagementPageHeader: 'ManagementPageHeader' },
    '@ant-design/icons': new Proxy({}, { get: (_, name) => String(name) }),
    'echarts-for-react': { default: 'Chart', __esModule: true }, './token-usage.css': {},
  };
  const source = readFileSync(new URL(`../src/features/token-usage/${file}`, import.meta.url), 'utf8');
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true } }).outputText;
  const exports = {};
  new Function('require', 'exports', compiled)((id) => mocks[id] ?? require(id), exports);
  return {
    calls, components, api,
    params: () => params,
    navigate: (search) => { params = new URLSearchParams(search); locationKey++; },
    render: (props = {}) => {
      cursor = 0;
      effects = [];
      const result = exports.default(props);
      const tree = typeof result.type === 'function' ? result.type(result.props) : result;
      effects.forEach(effect => effect());
      return tree;
    },
    wrapper: (props) => exports.default(props),
  };
}

function nodes(root, type) {
  if (!root) return [];
  if (Array.isArray(root)) return root.flatMap((child) => nodes(child, type));
  if (typeof root !== 'object') return [];
  return [...(root.type === type ? [root] : []), ...nodes(root.props?.children, type)];
}
const button = (tree, key) => nodes(tree, 'Button').find((node) => node.props.children === key);
const table = (tree) => nodes(tree, 'Table')[0];
const renderDrawer = (h, event = normalEvent) => h.render({ event, onClose() {}, onApplied() {} });

test('entry replaces defaults once; user searches push once and back/forward restores every field and page', () => {
  const h = harness('TokenUsagePage.tsx', { search: 'instanceId=gw&routeId=original' });
  h.render();
  const initial = h.params().toString();
  assert.deepEqual(h.calls.history.map(item => item.replace), [true]);
  h.render();
  assert.equal(h.calls.history.length, 1);
  const full = query.normalizeUsageQuery(new URLSearchParams({
    from: '2026-10-28T12:00:00', to: '2026-10-29T12:00:00', instanceId: 'gw',
    routeId: '路由/a', tenantId: '租户', model: '模型 +/', granularity: 'day', page: '4', size: '50', unresolvedOnly: 'true',
  })).toString();
  h.navigate(full);
  let tree = h.render();
  assert.equal(h.calls.events.page, 4);
  assert.equal(h.calls.events.size, 50);
  assert.equal(h.calls.granularity, 'day');
  const search = label => nodes(tree, 'Input.Search').find(node => node.props['aria-label'] === label);
  const originalInputKey = search('tokenUsage.filter.model').key;
  assert.equal(search('tokenUsage.filter.model').props.defaultValue, '模型 +/');
  assert.equal(search('tokenUsage.filter.model').props.onChange, undefined, 'typing does not navigate');
  search('tokenUsage.filter.model').props.onSearch(' 新模型 ');
  assert.equal(h.calls.history.length, 2);
  assert.equal(h.calls.history.at(-1).replace, false);
  tree = h.render();
  assert.equal(h.calls.events.page, 0);
  assert.equal(h.calls.filters.model, '新模型');
  assert.notEqual(search('tokenUsage.filter.model').key, originalInputKey);
  const filtered = h.params().toString();
  search('tokenUsage.filter.model').props.onSearch('新模型');
  assert.equal(h.calls.history.length, 2, 'duplicate submit does not add a history entry');
  table(tree).props.pagination.onChange(3, 50);
  tree = h.render();
  assert.equal(h.calls.events.page, 2);
  const paginated = h.params().toString();
  for (const snapshot of [filtered, full, initial, full, filtered, paginated]) {
    h.navigate(snapshot);
    tree = h.render();
    const expected = query.readUsageQuery(new URLSearchParams(snapshot));
    assert.equal(h.calls.events.page, expected.page);
    assert.equal(h.calls.events.size, expected.size);
    assert.equal(h.calls.granularity, expected.granularity);
    assert.deepEqual(h.calls.filters, Object.fromEntries(['from', 'to', 'instanceId', 'routeId', 'tenantId', 'model'].map(key => [key, expected[key]])));
    assert.equal(search('tokenUsage.filter.model').props.defaultValue, expected.model);
    assert.equal(search('tokenUsage.filter.route').props.defaultValue, expected.routeId);
    assert.equal(nodes(tree, 'RangePicker')[0].props.value[0].format('YYYY-MM-DDTHH:mm:ss'), expected.from);
    assert.equal(nodes(tree, 'Select').find(node => node.props['aria-label'] === 'tokenUsage.filter.tenant').props.value, expected.tenantId || undefined);
  }
  assert.equal(h.calls.history.length, 3, 'history restoration never writes over the restored snapshot');
  table(tree).props.pagination.onChange(3, 100);
  h.render();
  assert.equal(h.calls.events.size, 100);
  assert.equal(h.calls.events.page, 0);
  assert.equal(h.calls.history.length, 4);
});

test('usage source action and drawer link retain complete safe nested context', () => {
  const origin = '/instances/gw/trace/request?returnTo=%2Finstances%2Fgw%2Frequests%3Fpage%3D4';
  const h = harness('TokenUsagePage.tsx', { pathname: '/instances/gw/token_usage', search: new URLSearchParams({ returnTo: origin, page: '3', size: '50' }).toString() });
  let tree = h.render({ scopeInstanceId: 'gw' });
  const actions = nodes(tree, 'ManagementPageHeader')[0].props.actions;
  button(actions, 'tokenUsage.backToSource').props.onClick();
  assert.equal(h.calls.navigation, origin);
  button(table(tree).props.columns.at(-1).render(null, normalEvent), 'tokenUsage.details.action').props.onClick();
  tree = h.render({ scopeInstanceId: 'gw' });
  const drawerProps = nodes(tree, 'ReconciliationDrawer')[0].props;
  assert.equal(drawerProps.usageReturnTo, `/instances/gw/token_usage?${h.params()}`);
  const drawer = harness('TokenUsageReconciliationDrawer.tsx');
  const details = drawer.render({ ...drawerProps, event: { ...normalEvent, traceId: 'request' } });
  button(details, 'journey.inspect').props.onClick();
  const link = new URL(drawer.calls.navigation, 'https://console.test');
  assert.equal(link.searchParams.get('returnTo'), drawerProps.usageReturnTo);
  assert.equal(usageNavigation.requestCenterReturnUrl('gw', link.searchParams.get('returnTo')), drawerProps.usageReturnTo);
});

test('scoped page fixes instance filters on first fetch and reset and has nine daily columns', () => {
  const h = harness('TokenUsagePage.tsx', { search: 'instanceId=other&routeId=route-a&unresolvedOnly=true' });
  const tree = h.render({ scopeInstanceId: 'fixed' });
  assert.equal(h.calls.filters.instanceId, 'fixed');
  assert.equal(h.calls.events.filters.unresolvedOnly, true);
  assert.equal(nodes(tree, h.components.Input).find((n) => n.props.readOnly).props.value, 'fixed');
  assert.ok(!nodes(tree, 'Select').some((n) => n.props['aria-label'] === 'tokenUsage.filter.instance'));
  assert.deepEqual(table(tree).props.columns.map((c) => c.dataIndex ?? c.key), [
    'requestTime', 'tenantId', 'routeId', 'model', 'promptTokens', 'completionTokens', 'totalTokens', 'status', 'details',
  ]);
  nodes(tree, 'Card').find((n) => n.props.className === 'token-filter-card').props.extra.props.onClick();
  h.render({ scopeInstanceId: 'fixed' });
  assert.equal(h.calls.filters.instanceId, 'fixed');
  assert.equal(h.calls.filters.routeId, '');
  assert.equal(h.params().get('instanceId'), 'fixed');
  assert.equal(h.calls.events.filters.unresolvedOnly, false);
  assert.notEqual(h.wrapper({ scopeInstanceId: 'fixed' }).key, h.wrapper({ scopeInstanceId: 'other' }).key);
});

test('same mounted page follows URL navigation immediately and resets the queried page', () => {
  const h = harness('TokenUsagePage.tsx', { search: 'instanceId=a&routeId=route-a' });
  let tree = h.render();
  assert.ok(table(tree).props.columns.some((c) => c.dataIndex === 'instanceId'));
  table(tree).props.pagination.onChange(3);
  h.render();
  assert.equal(h.calls.events.page, 2);
  h.navigate('instanceId=b&routeId=route-b&unresolvedOnly=true');
  tree = h.render();
  assert.equal(h.calls.events.page, 0);
  assert.equal(h.calls.filters.instanceId, 'b');
  assert.equal(h.calls.filters.routeId, 'route-b');
  assert.equal(nodes(tree, 'Tabs')[0].props.activeKey, 'unresolved');
  h.navigate('instanceId=a&routeId=route-a');
  tree = h.render();
  assert.equal(h.calls.filters.instanceId, 'a');
  assert.equal(h.calls.filters.routeId, 'route-a');
  assert.equal(h.calls.events.filters.unresolvedOnly, false);
  nodes(tree, 'Select').find((n) => n.props['aria-label'] === 'tokenUsage.filter.instance').props.onChange(undefined);
  h.render();
  assert.equal(h.calls.filters.instanceId, '');
});

test('warning and tabs update unresolved URL; counts are independent, not summed', () => {
  const h = harness('TokenUsagePage.tsx', { overview: { unknownUsageRequests: 2, failedSettlementRequests: 2 } });
  let tree = h.render();
  nodes(tree, 'Alert').find((n) => n.props.type === 'warning').props.action.props.onClick();
  tree = h.render();
  assert.equal(h.params().get('unresolvedOnly'), 'true');
  assert.equal(h.calls.events.filters.unresolvedOnly, true);
  assert.equal(nodes(tree, 'Tabs')[0].props.items[1].label, 'tokenUsage.tabs.unresolved');
  nodes(tree, 'Tabs')[0].props.onChange('daily');
  h.render();
  assert.equal(h.params().has('unresolvedOnly'), false);
});

test('table renders unknown as dashes, estimates with a visible status, and normal actions as details', () => {
  const h = harness('TokenUsagePage.tsx');
  const tree = h.render();
  const columns = table(tree).props.columns;
  for (const field of ['promptTokens', 'completionTokens', 'totalTokens']) {
    assert.equal(columns.find((c) => c.dataIndex === field).render(0, { ...normalEvent, usageStatus: 'UNKNOWN' }), '—');
  }
  const status = columns.find((c) => c.key === 'status').render(null, { ...normalEvent, usageStatus: 'ESTIMATED' });
  assert.equal(nodes(status, 'Tag')[0].props.color, 'gold');
  assert.equal(nodes(status, 'Tag')[0].props.children, 'tokenUsage.status.ESTIMATED');
  const action = columns.find((c) => c.key === 'details').render(null, normalEvent);
  assert.equal(action.props.children, 'tokenUsage.details.action');
});

test('normal records show details without correction forms or unavailable messages', () => {
  for (const role of ['ADMIN', 'VIEWER']) {
    const h = harness('TokenUsageReconciliationDrawer.tsx', { role });
    const tree = renderDrawer(h, { ...normalEvent, quotaLedgerId: 'ledger', quotaLedgerState: 'PENDING' });
    assert.equal(tree.props.title, 'tokenUsage.details.title');
    assert.equal(nodes(tree, h.components.Form).length, 0);
    assert.equal(nodes(tree, 'Alert').length, 0);
    assert.deepEqual(h.calls.requests, [1], 'existing commands remain discoverable for normal records');
    assert.equal(nodes(tree, 'details').length, 2, 'advanced and raw data remain collapsed');
    const advanced = nodes(tree, 'Descriptions')[1].props.items.map((item) => item.key);
    for (const key of ['format', 'service', 'latency', 'reserved', 'charged', 'period']) assert.ok(advanced.includes(key));
  }
});

test('unknown correction form has no fake measured zeroes and is blocked for readers or pending ledger', () => {
  const unknown = { ...normalEvent, usageStatus: 'UNKNOWN', promptTokens: 0, completionTokens: 0, totalTokens: 0 };
  const admin = harness('TokenUsageReconciliationDrawer.tsx');
  const tree = renderDrawer(admin, unknown);
  assert.equal(tree.props.title, 'tokenUsage.reconcile.title');
  const form = nodes(tree, admin.components.Form)[0];
  for (const field of ['promptTokens', 'completionTokens', 'totalTokens']) assert.equal(form.props.initialValues[field], undefined);
  const measured = nodes(tree, 'Descriptions')[0].props.items.find((item) => item.key === 'tokens');
  assert.equal(measured.children, '— + — = —');
  for (const options of [{ role: 'VIEWER' }, {}]) {
    const h = harness('TokenUsageReconciliationDrawer.tsx', options);
    const blocked = renderDrawer(h, options.role ? unknown : { ...unknown, quotaLedgerId: 'ledger', quotaLedgerState: 'PENDING' });
    assert.equal(nodes(blocked, h.components.Form).length, 0);
    assert.equal(button(blocked, 'tokenUsage.reconcile.submit'), undefined);
  }
});

test('correction submission requires confirmation before the API is called', async () => {
  const h = harness('TokenUsageReconciliationDrawer.tsx');
  const tree = renderDrawer(h, { ...normalEvent, usageStatus: 'ESTIMATED' });
  await button(tree, 'tokenUsage.reconcile.submit').props.onClick();
  assert.equal(h.calls.confirmations.length, 1);
  assert.equal(h.calls.corrections.length, 0);
  await h.calls.confirmations[0].onOk();
  assert.equal(h.calls.corrections.length, 1);
});

test('existing reconciliations stay readable; only admins can retry after confirmation', async () => {
  for (const status of ['APPLIED', 'PENDING', 'REVIEW_REQUIRED']) {
    const command = { id: 'command', status, monthly_delta: -1, daily_delta: 0, original_usage: '{"totalTokens":0}' };
    for (const role of ['ADMIN', 'VIEWER']) {
      const h = harness('TokenUsageReconciliationDrawer.tsx', { command, role });
      const tree = renderDrawer(h, { ...normalEvent, usageStatus: 'RECONCILED' });
      assert.equal(nodes(tree, h.components.Form).length, 0);
      assert.ok(nodes(tree, 'Descriptions').some((n) => n.props.items.some((item) => item.children === 'command')));
      const retry = button(tree, 'tokenUsage.reconcile.retry');
      if (role === 'ADMIN' && status !== 'APPLIED') {
        retry.props.onClick();
        assert.equal(h.calls.retries.length, 0);
        assert.equal(h.calls.confirmations.length, 1);
        await h.calls.confirmations[0].onOk();
        assert.equal(h.calls.retries.length, 1);
      } else assert.equal(retry, undefined);
    }
  }
});

test('applied correction replaces the stale snapshot and refreshes the related quota', () => {
  const applied = {
    id: 'cmd-1', status: 'APPLIED', prompt_tokens: 11, completion_tokens: 22, total_tokens: 33,
    charged_tokens: 33, monthly_delta: 5, daily_delta: 2, operator: 'admin', reason: 'verified',
  };
  const h = harness('TokenUsageReconciliationDrawer.tsx', {
    command: applied,
    quota: { tenantId: 'tenant', monthlyQuota: 1000, monthlyUsed: 105, dailyQuota: 100, dailyUsed: 12 },
  });
  const event = { ...normalEvent, usageStatus: 'UNKNOWN', settlementStatus: 'PENDING', promptTokens: 0, completionTokens: 0, totalTokens: 0, quotaPeriod: 'BOTH' };
  const tree = renderDrawer(h, event);
  assert.equal(tree.props.title, 'tokenUsage.details.title');
  const items = nodes(tree, 'Descriptions')[0].props.items;
  assert.equal(items.find(item => item.key === 'usage').children.props.children, 'tokenUsage.status.RECONCILED');
  assert.equal(items.find(item => item.key === 'settlement').children, 'tokenUsage.status.APPLIED');
  assert.equal(items.find(item => item.key === 'tokens').children, '11 + 22 = 33');
  const quotaItems = nodes(tree, 'Descriptions')[1].props.items;
  assert.deepEqual(quotaItems.map(item => item.children), ['105 / 1000', '12 / 100']);
  assert.equal(nodes(tree, h.components.Form).length, 0);
  assert.ok(!nodes(tree, 'Button').some(node => node.props.children === 'tokenUsage.reconcile.retry'));
});

test('duplicate confirmation cannot submit the same correction twice and refreshes quota once', async () => {
  const h = harness('TokenUsageReconciliationDrawer.tsx', { command: null });
  const event = { ...normalEvent, usageStatus: 'UNKNOWN', settlementStatus: 'PENDING' };
  let applied = 0;
  const tree = h.render({ event, onClose() {}, onApplied() { applied += 1; } });
  const submit = nodes(tree, 'Button').find(node => node.props.children === 'tokenUsage.reconcile.submit');
  await submit.props.onClick();
  assert.equal(h.calls.confirmations.length, 1);
  await Promise.all([h.calls.confirmations[0].onOk(), h.calls.confirmations[0].onOk()]);
  assert.equal(h.calls.corrections.length, 1);
  assert.equal(applied, 1);
  assert.ok(h.calls.runs >= 1, 'related quota is reloaded after apply');
});

test('a committed correction with a lost response refreshes parent and quota once after readback', async () => {
  const h = harness('TokenUsageReconciliationDrawer.tsx', {
    quota: { monthlyQuota: 1000, monthlyUsed: 10 },
  });
  const event = { ...normalEvent, usageStatus: 'UNKNOWN', settlementStatus: 'PENDING' };
  let applied = 0;
  const props = { event, onClose() {}, onApplied() { applied++; } };
  const command = { id: 'committed', status: 'APPLIED', prompt_tokens: 10, completion_tokens: 20, total_tokens: 30, charged_tokens: 30 };
  h.api.reconcileTokenUsage = async () => {
    h.api.getTokenUsageReconciliation = () => command;
    h.api.getTokenQuotaStatus = () => ({ monthlyQuota: 1000, monthlyUsed: 40 });
    throw new Error('Response lost');
  };
  await button(h.render(props), 'tokenUsage.reconcile.submit').props.onClick();
  await h.calls.confirmations[0].onOk();
  let tree = h.render(props);
  assert.equal(applied, 1);
  assert.equal(h.calls.runs, 2, 'readback plus quota refresh');
  assert.equal(nodes(tree, 'Descriptions')[0].props.items.find(item => item.key === 'tokens').children, '10 + 20 = 30');
  assert.equal(nodes(tree, 'Descriptions')[1].props.items[0].children, '40 / 1000');
  assert.equal(nodes(tree, 'Alert').some(node => node.props.message === 'Response lost'), false);
  h.render(props);
  assert.equal(applied, 1, 'rerenders do not repeat synchronization');
});

test('an applied command discovered on open synchronizes parent and quota once', () => {
  const h = harness('TokenUsageReconciliationDrawer.tsx', { command: { id: 'already-applied', status: 'APPLIED' } });
  let applied = 0;
  const props = { event: normalEvent, onClose() {}, onApplied() { applied++; } };
  h.render(props);
  h.render(props);
  assert.equal(applied, 1);
  assert.equal(h.calls.runs, 1);
});

test('failed quota refresh hides the stale snapshot and offers a working retry', async () => {
  const h = harness('TokenUsageReconciliationDrawer.tsx', { quota: { monthlyQuota: 1000, monthlyUsed: 10 } });
  const props = { event: { ...normalEvent, usageStatus: 'UNKNOWN' }, onClose() {}, onApplied() {} };
  const initial = h.render(props);
  h.api.getTokenQuotaStatus = () => { throw new Error('Quota unavailable'); };
  await button(initial, 'tokenUsage.reconcile.submit').props.onClick();
  await h.calls.confirmations[0].onOk();
  const failed = h.render(props);
  const warning = nodes(failed, 'Alert').find(node => node.props.message === 'tokenUsage.reconcile.quotaError');
  assert.ok(warning);
  assert.equal(nodes(failed, 'Descriptions').some(node => node.props.title === 'tokenUsage.reconcile.quotaTitle'), false);
  h.api.getTokenQuotaStatus = () => ({ monthlyQuota: 1000, monthlyUsed: 13 });
  warning.props.action.props.onClick();
  const recovered = h.render(props);
  assert.equal(nodes(recovered, 'Descriptions').find(node => node.props.title === 'tokenUsage.reconcile.quotaTitle').props.items[0].children, '13 / 1000');
});
