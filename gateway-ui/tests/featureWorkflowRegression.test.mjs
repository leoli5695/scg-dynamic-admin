import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import ts from 'typescript';
import * as React from 'react';
import * as sessions from '../src/features/copilot/utils/session.ts';

const require = createRequire(import.meta.url);
const flush = () => new Promise(resolve => setImmediate(resolve));
function nodes(root, type) {
  if (!root || typeof root !== 'object') return [];
  if (Array.isArray(root)) return root.flatMap(child => nodes(child, type));
  return [...(root.type === type ? [root] : []), ...nodes(root.props?.children, type)];
}
function hooks() {
  const slots = [];
  let cursor = 0;
  let effects = [];
  return {
    useState(initial) {
      const index = cursor++;
      slots[index] ??= { value: typeof initial === 'function' ? initial() : initial };
      return [slots[index].value, value => { slots[index].value = typeof value === 'function' ? value(slots[index].value) : value; }];
    },
    useRef(initial) { const index = cursor++; slots[index] ??= { current: initial }; return slots[index]; },
    useMemo: fn => fn(),
    useEffect(effect, deps) {
      const index = cursor++;
      const old = slots[index];
      if (old && deps && deps.length === old.deps.length && deps.every((value, i) => Object.is(value, old.deps[i]))) return;
      effects.push(() => { old?.cleanup?.(); slots[index] = { deps, cleanup: effect() }; });
    },
    render(Component, props) {
      cursor = 0; effects = [];
      const tree = Component(props);
      effects.forEach(effect => effect());
      return tree;
    },
  };
}
function load(file, mocks) {
  const compiled = ts.transpileModule(readFileSync(new URL(`../src/${file}`, import.meta.url), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true },
  }).outputText;
  const exports = {};
  new Function('require', 'exports', compiled)(id => mocks[id] ?? require(id), exports);
  return exports.default;
}
const defaultComponent = name => ({ __esModule: true, default: name });
const ui = Object.fromEntries(['Button', 'Empty', 'Input', 'Menu', 'Result', 'Select', 'Spin', 'Tooltip'].map(key => [key, key]));
ui.Input = Object.assign(() => null, { TextArea: 'TextArea' });
ui.Typography = { Text: 'Text' };
ui.Layout = Object.assign(() => null, { Header: 'Header', Sider: 'Sider', Content: 'Content' });
const common = {
  antd: ui,
  '@ant-design/icons': new Proxy({}, { get: (_, key) => String(key) }),
  'react-i18next': { useTranslation: () => ({ t: (key, args) => args ? `${key}:${JSON.stringify(args)}` : key }) },
  'react-router-dom': { useNavigate: () => () => {}, useLocation: () => ({ pathname: '/kubernetes' }), Outlet: 'Outlet' },
};

test('enabled Kubernetes stays reachable before the first deployment; disabled remains gated', () => {
  const lifecycle = hooks();
  const state = { ready: true, settings: { kubernetesEnabled: true }, hasClusterDeployment: false };
  const mocks = {
    ...common, react: { ...React, ...lifecycle },
    './SystemSettingsContext': { useSystemSettings: () => state },
    '../settings/SystemSettingsContext': { useSystemSettings: () => state },
    './components/GatewayLogo': defaultComponent('Logo'), './components/LanguageSwitcher': defaultComponent('LanguageSwitcher'),
    './UserMenu': defaultComponent('UserMenu'),
  };
  const Gate = load('features/settings/FeatureGate.tsx', mocks);
  const Shell = load('features/layout/AppShell.tsx', mocks);
  assert.equal(lifecycle.render(Gate, { feature: 'kubernetesEnabled', children: 'cluster-manager' }).props.children, 'cluster-manager');
  const menuHasKubernetes = tree => nodes(tree, 'Menu')[0].props.items.find(item => item.key === 'platform').children.some(item => item.key === 'kubernetes');
  assert.equal(menuHasKubernetes(lifecycle.render(Shell)), true);
  state.settings.kubernetesEnabled = false;
  assert.equal(lifecycle.render(Gate, { feature: 'kubernetesEnabled', children: 'cluster-manager' }).type, 'Result');
  assert.equal(menuHasKubernetes(lifecycle.render(Shell)), false);
  const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  assert.match(app, /path="\/kubernetes" element={<FeatureGate feature="kubernetesEnabled"><KubernetesPage/);
});

function chatHarness() {
  const lifecycle = hooks();
  const sent = [];
  const routeRequest = { data: { instanceId: 'A', routes: [{ id: 'route-a' }] }, loading: false, error: undefined };
  const storage = new Map();
  const previousStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value) } });
  const Component = load('features/copilot/components/ChatTab.tsx', {
    ...common, react: { ...React, ...lifecycle },
    '../../../api/copilot': {
      getCopilotHistory: async () => ({ messages: [] }),
      sendCopilotChatStream: async body => { sent.push(body); },
    },
    '../../../api/routes': {},
    '../../../hooks/useRequest': { useRequest: () => routeRequest },
    '../../../api/http': { ApiError: class extends Error {}, isAbortError: () => false },
    '../../../utils/antdStatic': { message: { error() {}, success() {} } },
    '../../../components/ai/AiReportRenderer': defaultComponent('AiReportRenderer'),
    '../../auth/AuthContext': { useAuth: () => ({ user: { username: 'admin' } }) },
    '../utils/session': sessions, '../../../styles/ai-report.css': {},
  });
  return {
    sent, routeRequest,
    render: instanceId => lifecycle.render(Component, { instanceId, provider: 'provider', model: 'model' }),
    restore() {
      if (previousStorage) Object.defineProperty(globalThis, 'localStorage', previousStorage);
      else delete globalThis.localStorage;
    },
  };
}
const action = (tree, name) => nodes(tree, 'Button').find(node => node.props.children === `copilot.${name}_action`);

test('Copilot cannot carry a selected route across instances or reuse cached foreign options', async () => {
  const h = chatHarness();
  try {
    h.render('A'); await flush();
    nodes(h.render('A'), 'Select')[0].props.onChange('route-a');
    assert.equal(action(h.render('A'), 'explain').props.disabled, false);
    const switching = h.render('B');
    assert.equal(action(switching, 'explain').props.disabled, true);
    assert.deepEqual(nodes(switching, 'Select')[0].props.options, []);
    await flush();
    h.routeRequest.data = { instanceId: 'B', routes: [{ id: 'route-b' }] };
    assert.equal(action(h.render('B'), 'draft').props.disabled, true);
    nodes(h.render('B'), 'Select')[0].props.onChange('route-b');
    await action(h.render('B'), 'explain').props.onClick();
    assert.equal(h.sent.length, 1);
    assert.equal(h.sent[0].instanceId, 'B');
    assert.equal(h.sent[0].message, 'copilot.explain_prompt:{"routeId":"route-b"}');
  } finally { h.restore(); }
});

test('Copilot disables selected route actions during refresh, on error, and after route deletion', async () => {
  const h = chatHarness();
  try {
    h.render('A'); await flush();
    nodes(h.render('A'), 'Select')[0].props.onChange('route-a');
    assert.equal(action(h.render('A'), 'draft').props.disabled, false);
    h.routeRequest.loading = true;
    assert.equal(action(h.render('A'), 'draft').props.disabled, true);
    h.routeRequest.loading = false; h.routeRequest.error = new Error('failed');
    assert.equal(action(h.render('A'), 'draft').props.disabled, true);
    h.routeRequest.error = undefined; h.routeRequest.data.routes = [];
    assert.equal(action(h.render('A'), 'draft').props.disabled, true);
  } finally { h.restore(); }
});
