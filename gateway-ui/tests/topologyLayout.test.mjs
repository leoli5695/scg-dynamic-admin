import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as echarts from 'echarts';
import {
  AGGREGATE_CLIENT_ID, AGGREGATE_ROUTE_ID, buildChartOption, filterTopology,
  getTopologyChartHeight, getTopologyDisplayGraph, isSameTopologyContent,
} from '../src/features/topology/chartOption.ts';

const filters = { showDisabledRoutes: true, showOnlyActiveRoutes: false, searchKeyword: '', selectedNodeId: null };
function fixture(clients = 18, routeCount = 8) {
  const nodes = [
    ...Array.from({ length: clients }, (_, i) => ({ id: `c${i}`, name: `47.108.150.${i}`, type: 'client' })),
    { id: 'g', name: 'gateway', type: 'gateway' },
    ...Array.from({ length: routeCount }, (_, i) => ({ id: `r${i}`, name: `route-${i}`, type: 'route', enabled: i !== 7, metrics: { requestCount: i } })),
    ...Array.from({ length: routeCount }, (_, i) => ({ id: `s${i}`, name: `service-${i}`, type: 'service' })),
  ];
  return { instanceId: 'g', generatedAt: 0, timeRangeMinutes: 60, nodes, edges: [
    ...nodes.filter(n => n.type === 'client').map(n => ({ source: n.id, target: 'g', type: 'client-gateway' })),
    ...nodes.filter(n => n.type === 'route').flatMap(n => [
      { source: 'g', target: n.id, type: 'gateway-route' },
      { source: n.id, target: n.id.replace('r', 's'), type: 'route-service' },
    ]),
  ] };
}

for (const clients of [1, 18, 100]) {
  test(`${clients} clients retain readable spacing in actual ECharts coordinates`, () => {
    const data = fixture(clients);
    const width = 1200;
    const height = getTopologyChartHeight(data);
    const chart = echarts.init(null, null, { renderer: 'svg', ssr: true, width, height });
    try {
      chart.setOption(buildChartOption(data, filters, width));
      const series = chart.getModel().getSeriesByIndex(0);
      const points = new Map(series.option.data.map(n => [n.id, series.coordinateSystem.dataToPoint([n.x, n.y])]));
      for (const type of ['client', 'route', 'service']) {
        const column = data.nodes.filter(n => n.type === type).map(n => points.get(n.id)).sort((a, b) => a[1] - b[1]);
        for (let i = 1; i < column.length; i++) assert.ok(column[i][1] - column[i - 1][1] >= 110);
      }
      assert.ok(points.get('r0')[0] - points.get('g')[0] >= 250);
      assert.ok(points.get('g')[1] < 500, 'gateway stays in the initial viewport');
      for (const [x, y] of points.values()) {
        assert.ok(x >= 100 && x <= width - 100);
        assert.ok(y >= 50 && y <= height - 80);
      }
      assert.equal(series.option.data.length, data.nodes.length);
    } finally { chart.dispose(); }
  });
}

test('route filters remove nodes and dangling edges instead of placing them at the origin', () => {
  const data = fixture();
  for (const options of [{ showDisabledRoutes: false }, { showOnlyActiveRoutes: true }]) {
    const visible = filterTopology(data, { ...filters, ...options });
    const ids = new Set(visible.nodes.map(n => n.id));
    assert.ok(!ids.has(options.showDisabledRoutes === false ? 'r7' : 'r0'));
    assert.ok(visible.edges.every(e => ids.has(e.source) && ids.has(e.target)));
  }
});

test('focus shrinks the canvas and node ordering remains stable across refreshes', () => {
  const data = fixture(100);
  const focused = filterTopology(data, { ...filters, selectedNodeId: 'r1' });
  assert.equal(getTopologyChartHeight(focused), 500);
  assert.deepEqual(new Set(focused.nodes.map(n => n.id)), new Set(['g', 'r1', 's1']));
  const positions = d => buildChartOption(d, filters).series[0].data.map(n => [n.id, n.x, n.y]).sort();
  assert.deepEqual(positions(data), positions({ ...data, nodes: [...data.nodes].reverse() }));
});

test('search with no matches produces an empty graph with no stale links', () => {
  const option = buildChartOption(fixture(), { ...filters, searchKeyword: 'missing-node' });
  assert.equal(option.series[0].data.length, 0);
  assert.equal(option.series[0].links.length, 0);
});

test('topology polling reuses content that only has a new generation timestamp', () => {
  const data = fixture();
  assert.equal(isSameTopologyContent(data, { ...data, generatedAt: 1234 }), true);
  assert.equal(isSameTopologyContent(data, { ...data, generatedAt: 1234, timeRangeMinutes: 30 }), false);
  assert.equal(isSameTopologyContent(data, { ...data, generatedAt: 1234, instanceId: 'other' }), false);
});

test('topology polling detects every rendered metrics, node and edge change', () => {
  const data = fixture(2);
  assert.equal(isSameTopologyContent(data, { ...data, metrics: { totalRequests: 1 } }), false);
  assert.equal(isSameTopologyContent(data, {
    ...data,
    nodes: data.nodes.map((node, index) => index === 0 ? { ...node, metrics: { requestCount: 1 } } : node),
  }), false);
  assert.equal(isSameTopologyContent(data, {
    ...data,
    edges: data.edges.map((edge, index) => index === 0 ? { ...edge, metrics: { requestCount: 1 } } : edge),
  }), false);
});

test('overview aggregation bounds dense columns without losing traffic or focus', () => {
  const data = fixture(100, 24);
  const display = getTopologyDisplayGraph(data, { ...filters, aggregate: true });
  assert.equal(display.nodes.filter(n => n.type === 'client').length, 8);
  assert.equal(display.nodes.filter(n => n.type === 'route').length, 12);
  assert.ok(display.nodes.some(n => n.id === AGGREGATE_CLIENT_ID));
  assert.ok(display.nodes.some(n => n.id === AGGREGATE_ROUTE_ID));
  assert.ok(getTopologyChartHeight(display) < getTopologyChartHeight(data));

  const visibleIds = new Set(display.nodes.map(n => n.id));
  assert.ok(display.edges.every(e => visibleIds.has(e.source) && visibleIds.has(e.target)));
  assert.equal(buildChartOption(data, { ...filters, aggregate: true }).series[0].data.length, display.nodes.length);
  const focused = getTopologyDisplayGraph(data, { ...filters, aggregate: true, selectedNodeId: AGGREGATE_CLIENT_ID });
  assert.ok(focused.nodes.some(n => n.id === AGGREGATE_CLIENT_ID));

  const requestsByType = (graph, type) => graph.nodes
    .filter(n => n.type === type)
    .reduce((sum, n) => sum + (n.metrics?.requestCount || 0), 0);
  assert.equal(requestsByType(display, 'client'), requestsByType(data, 'client'));
  assert.equal(requestsByType(display, 'route'), requestsByType(data, 'route'));
});
