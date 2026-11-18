import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import ts from 'typescript';
import * as React from 'react';
import { effectiveStrategy, strategyRouteIds, strategySummary } from '../src/features/routes/configuration.ts';
const strategy = (id, scope = 'ROUTE', extra = {}) => ({ strategyId: id, strategyName: id, strategyType: 'TIMEOUT', scope, routeId: scope === 'ROUTE' ? 'r1' : undefined, enabled: true, priority: 100, config: {}, ...extra });

test('route strategies beat global priority and select shared membership deterministically', () => {
  const global = strategy('global', 'GLOBAL', { priority: 999 });
  const shared = strategy('a', 'ROUTE', { routeId: 'r2', routeIds: ['r1', 'r2'] });
  const direct = strategy('b');
  assert.equal(effectiveStrategy([global, direct, shared], 'r1', 'TIMEOUT'), shared);
  assert.equal(effectiveStrategy([shared, direct, global], 'r1', 'TIMEOUT'), shared);
  assert.equal(effectiveStrategy([global, direct, shared], 'r3', 'TIMEOUT'), global);
  assert.deepEqual(strategyRouteIds(shared), ['r2', 'r1']);
});

test('disabled, detached, other-type and other-route strategies are not effective', () => {
  const rows = [strategy('off', 'ROUTE', { enabled: false }), strategy('detached', 'ROUTE', { routeId: '', routeIds: [] }), strategy('other', 'ROUTE', { routeId: 'r2' }), strategy('auth', 'ROUTE', { strategyType: 'AUTH' })];
  assert.equal(effectiveStrategy(rows, 'r1', 'TIMEOUT'), undefined);
});

test('summary uses an allowlist and never exposes credential or nested secret fields', () => {
  assert.deepEqual(strategySummary(strategy('auth', 'ROUTE', { strategyType: 'AUTH', config: { authType: 'JWT', secretKey: 'private', custom: 'private' } })), { authType: 'JWT' });
  assert.deepEqual(strategySummary(strategy('quota', 'GLOBAL', { strategyType: 'TOKEN_RATE_LIMITER', config: { tenantQuotas: { tenant: { dailyQuota: 12, apiKey: 'private', monthlyQuota: { secret: 'private' } } } } })), { tenantQuotas: { tenant: { dailyQuota: 12 } } });
});

function editor(props, impact = { data: [], loading: false }) {
  const components = Object.fromEntries(['Alert', 'Button', 'Col', 'Input', 'InputNumber', 'Row', 'Select', 'Switch', 'Tag'].map(name => [name, name]));
  components.Form = { useForm: () => [{}], useWatch: () => undefined, Item: 'Form.Item' };
  components.Radio = { Group: 'Radio.Group', Button: 'Radio.Button' };
  components.Typography = { Text: 'Text' };
  const mocks = {
    react: {
      ...React,
      // Direct function calls (no renderer) need inert hook shims; state updates are irrelevant here.
      useState: value => [typeof value === 'function' ? value() : value, () => {}],
      useMemo: fn => fn(),
      useCallback: fn => fn,
      useEffect: () => {},
      useRef: value => ({ current: value }),
    },
    antd: components,
    'react-i18next': { useTranslation: () => ({ t: key => key }) },
    '@ant-design/icons': new Proxy({}, { get: (_, key) => String(key) }),
    '../../instance-detail/ResourceEditor': { default: 'ResourceEditor', __esModule: true },
    '../../../hooks/useRequest': { useRequest: () => impact },
    '../../../api/strategies': {}, '../../../api/routes': {},
    '../utils': { strategyToFormValues: value => value, requiresGlobalUniqueness: () => false, buildStrategyConfig: () => ({}) },
    './DynamicConfigFields': {}, './TokenRateLimiterFields': {}, './AiProxyFields': {}, './AiSecurityFields': {},
  };
  const source = readFileSync(new URL('../src/features/strategies/components/StrategyFormModal.tsx', import.meta.url), 'utf8');
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true } }).outputText;
  const exports = {};
  const require = createRequire(import.meta.url);
  new Function('require', 'exports', compiled)(id => mocks[id] ?? require(id), exports);
  return exports.default({ typeInfo: { typeCode: 'TIMEOUT' }, routes: [], onCancel() {}, onSubmit: async () => {}, ...props });
}

function nodes(root, type) {
  if (Array.isArray(root)) return root.flatMap(child => nodes(child, type));
  if (!root || typeof root !== 'object') return [];
  return [...(root.type === type ? [root] : []), ...nodes(root.props?.children, type)];
}

function findNode(root, predicate) {
  if (Array.isArray(root)) {
    for (const child of root) {
      const hit = findNode(child, predicate);
      if (hit) return hit;
    }
    return undefined;
  }
  if (!root || typeof root !== 'object') return undefined;
  if (predicate(root)) return root;
  return findNode(root.props?.children, predicate);
}

const formOf = tree => findNode(tree, node => typeof node.props?.onFinish === 'function');

test('workbench creation locks both scope group and explicit GLOBAL radio override', () => {
  const tree = editor({ mode: 'create', initialRouteId: 'r1', lockBinding: true });
  const form = formOf(tree);
  assert.equal(form.props.initialValues.scope, 'ROUTE');
  assert.equal(form.props.initialValues.routeId, 'r1');
  assert.equal(nodes(tree, 'Radio.Group')[0].props.disabled, true);
  assert.equal(nodes(tree, 'Radio.Button').find(node => node.props.value === 'GLOBAL').props.disabled, true);
});

test('unavailable edit impact blocks saving without marking cancellation busy', async () => {
  let saved = false;
  const tree = editor({ mode: 'edit', strategy: strategy('s'), onSubmit: async () => { saved = true; } }, { error: new Error('unavailable'), loading: false });
  const form = formOf(tree);
  const submit = findNode(tree, node => node.props?.htmlType === 'submit');
  const cancel = findNode(tree, node => node.type === 'Button' && typeof node.props?.onClick === 'function');
  assert.equal(form.props.disabled, true);
  assert.equal(submit.props.disabled, true);
  assert.equal(submit.props.loading, false); // submitting/saving stay false
  assert.equal(cancel.props.disabled, false); // cancellation is not marked busy
  await form.props.onFinish({});
  assert.equal(saved, false);
});
