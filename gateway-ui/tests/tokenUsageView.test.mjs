import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import ts from 'typescript';
import * as React from 'react';
import * as presentation from '../src/features/token-usage/usagePresentation.ts';
import * as query from '../src/features/token-usage/usageQuery.ts';

const require = createRequire(import.meta.url);
const normalEvent = {
  id: 1, tenantId: 'tenant', instanceId: 'gw', routeId: 'route', model: 'model',
  usageStatus: 'CONFIRMED', settlementStatus: 'APPLIED', promptTokens: 0, completionTokens: 3, totalTokens: 3,
};

// Exercise component output and callbacks without adding a DOM/test-renderer dependency.
// Hooks, API responses and Ant components are controlled; production JSX is compiled in memory.
function harness(file, { search = '', command = null, role = 'ADMIN', overview = {} } = {}) {
  let params = new URLSearchParams(search);
  let cursor = 0;
  const states = [];
  const calls = { confirmations: [], corrections: [], retries: [], requests: [] };
  const form = { validateFields: async () => ({ promptTokens: 1, completionTokens: 2, totalTokens: 3, chargedTokens: 3, monthlyDelta: 0, dailyDelta: 0, reason: 'verified', verified: true }) };
  const components = Object.fromEntries(['Alert', 'Button', 'Card', 'Checkbox', 'Col', 'Descriptions', 'Drawer', 'InputNumber', 'Row', 'Select', 'Space', 'Spin', 'Table', 'Tabs', 'Tag'].map((name) => [name, name]));
  components.DatePicker = { RangePicker: 'RangePicker' };
  components.Input = Object.assign(() => null, { TextArea: 'TextArea' });
  components.Form = Object.assign(() => null, { useForm: () => [form], Item: 'Form.Item' });
  components.Modal = { confirm: (options) => calls.confirmations.push(options) };
  const api = {
    listInstances: () => [], getTokenQuotaTenants: () => [],
    getTokenUsageOverview: (filters) => { calls.filters = filters; return { requests: 2, totalTokens: 3, ...overview }; },
    getTokenUsageTimeseries: () => ({ series: [] }), getTokenUsageBreakdown: () => ({ rows: [] }),
    getTokenUsageEvents: (filters, page) => { calls.events = { filters, page }; return { content: [normalEvent], total: 80 }; },
    getTokenUsageReconciliation: (id) => { calls.requests.push(id); return command; },
    reconcileTokenUsage: async (...args) => { calls.corrections.push(args); return { ...command, status: 'APPLIED' }; },
    retryTokenUsageReconciliation: async (...args) => { calls.retries.push(args); return { ...command, status: 'APPLIED' }; },
  };
  const mocks = {
    react: {
      ...React,
      useState: (initial) => {
        const index = cursor++;
        if (!(index in states)) states[index] = typeof initial === 'function' ? initial() : initial;
        return [states[index], (value) => { states[index] = typeof value === 'function' ? value(states[index]) : value; }];
      },
      useMemo: (fn) => fn(), useEffect: () => {},
    },
    antd: components,
    'react-router-dom': {
      useNavigate: () => (url) => { calls.navigation = url; },
      useSearchParams: () => [params, (next) => { params = typeof next === 'function' ? next(params) : next; }],
    },
    'react-i18next': { useTranslation: () => ({ t: (key) => key }) },
    '../../hooks/useRequest': { useRequest: (fetcher, options = {}) => ({
      data: options.immediate === false ? undefined : fetcher(new AbortController().signal),
      loading: false, run: () => {}, mutate: () => {},
    }) },
    '../../api/instances': api, '../../api/tokenUsage': api,
    '../auth/AuthContext': { useAuth: () => ({ user: { role } }) },
    '../request-journey/links': { requestJourneyUrl: (...args) => args.join('/') },
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
    calls, components,
    params: () => params,
    navigate: (search) => { params = new URLSearchParams(search); },
    render: (props = {}) => {
      cursor = 0;
      const result = exports.default(props);
      return typeof result.type === 'function' ? result.type(result.props) : result;
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
