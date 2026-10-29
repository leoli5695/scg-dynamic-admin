import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { defaultUsageRange, normalizeUsageQuery, readUsageQuery, updateUsageQuery } from '../src/features/token-usage/usageQuery.ts';
import { isUsageReturnUrl, journeyUsageUrl, requestCenterReturnUrl, usageRequestJourneyUrl, usageSourceReturnUrl } from '../src/features/request-journey/usageNavigation.ts';

const now = new Date('2026-10-29T12:00:00');
const range = { from: '2026-10-28T08:00:00', to: '2026-10-29T12:00:00' };
const full = { ...range, instanceId: '网关/a', routeId: '路由?x=1&b=二', tenantId: '租户 +/&', model: '模型 +/?=&', granularity: 'day', unresolvedOnly: true, page: 12, size: 50 };
const serialize = value => new URLSearchParams(Object.entries(value).map(([key, item]) => [key, String(item)]));
const parse = value => new URL(value, 'https://console.test');
const context = { instanceId: '网关/a', traceId: '请求/一', routeId: '路由?x&二', params: new URLSearchParams() };
const requestPath = '/instances/%E7%BD%91%E5%85%B3%2Fa/requests';
const usagePath = '/instances/%E7%BD%91%E5%85%B3%2Fa/token_usage';

test('repeated usage-list -> request -> route-usage navigation stays bounded and retains filters', () => {
  let usage = `${usagePath}?${normalizeUsageQuery(serialize(full))}`;
  const lengths = [];
  for (let i = 0; i < 50; i++) {
    const detail = parse(usageRequestJourneyUrl(context.instanceId, 'trace', context.routeId, usage));
    const origin = detail.searchParams.get('returnTo');
    assert.deepEqual(readUsageQuery(parse(origin).searchParams), i === 0 ? full : {
      ...readUsageQuery(parse(usage).searchParams),
    });
    assert.ok(origin.length < 2500);
    usage = journeyUsageUrl({ ...context, traceId: 'trace', params: detail.searchParams }, now);
    lengths.push(usage.length);
    assert.ok(usageSourceReturnUrl(context.instanceId, parse(usage).searchParams.get('returnTo')));
  }
  assert.equal(lengths[49], lengths[1]);
});

test('every nested return destination is validated and excessive depth is rejected', () => {
  const external = 'https://evil.test';
  const badDetail = `${requestPath.replace('/requests', '/trace/id')}?${serialize({ returnTo: external })}`;
  const badUsage = `${usagePath}?${serialize({ returnTo: badDetail })}`;
  assert.equal(isUsageReturnUrl(context.instanceId, badUsage), false);
  assert.equal(usageSourceReturnUrl(context.instanceId, badDetail), undefined);
  let nested = requestPath;
  for (let i = 0; i < 4; i++) {
    nested = i % 2 === 0
      ? `${requestPath.replace('/requests', '/trace/id')}?${serialize({ returnTo: nested })}`
      : `${usagePath}?${serialize({ returnTo: nested })}`;
  }
  assert.equal(isUsageReturnUrl(context.instanceId, nested), false);
  assert.equal(requestCenterReturnUrl(context.instanceId, nested), requestPath);
});

test('switching routes starts a bounded new navigation pair instead of retaining older pairs', () => {
  let usage = '/token-usage?' + normalizeUsageQuery(serialize({ ...range, instanceId: 'gw' }));
  const lengths = [];
  for (let i = 0; i < 30; i++) {
    const routeId = i % 2 === 0 ? 'r1' : 'r2';
    const detail = parse(usageRequestJourneyUrl('gw', 'trace', routeId, usage));
    usage = journeyUsageUrl({ instanceId: 'gw', traceId: 'trace', routeId, params: detail.searchParams }, now);
    assert.ok(isUsageReturnUrl('gw', usage));
    assert.ok(usageSourceReturnUrl('gw', parse(usage).searchParams.get('returnTo')));
    lengths.push(usage.length);
  }
  assert.ok(Math.max(...lengths) < 1500);
  assert.equal(lengths[29], lengths[3]);
});

test('all usage fields round-trip including Unicode, reserved characters and complete return context', () => {
  const source = serialize({ ...full, returnTo: `${requestPath}?${serialize({ ...range, page: 7, routeId: full.routeId })}` });
  const before = source.toString();
  const canonical = normalizeUsageQuery(source, undefined, now);
  assert.deepEqual(readUsageQuery(new URLSearchParams(canonical.toString())), full);
  assert.equal(canonical.get('returnTo'), source.get('returnTo'));
  assert.equal(source.toString(), before);
  assert.equal(normalizeUsageQuery(canonical).toString(), canonical.toString());
});

test('entry defaults become fixed absolute 24-hour bounds, even after refresh much later', () => {
  const canonical = normalizeUsageQuery(new URLSearchParams('routeId=route'), undefined, now);
  assert.deepEqual(readUsageQuery(canonical, undefined, new Date('2027-12-01')), {
    ...defaultUsageRange(now), instanceId: '', routeId: 'route', tenantId: '', model: '',
    granularity: 'hour', unresolvedOnly: false, page: 0, size: 20,
  });
  assert.equal(normalizeUsageQuery(canonical, undefined, new Date('2027-12-01')).toString(), canonical.toString());
});

test('invalid dates, order, integers, sizes and enums normalize at the URL boundary', () => {
  for (const patch of [{ from: 'garbage' }, { from: '2026-02-30T08:00:00' }, { from: '2026-02-29T08:00:00' },
    { from: '2026-10-28T24:00:00' }, { from: '0000-01-01T00:00:00' }, { from: '2026-10-28T08:00:00Z' },
    { from: '2026-10-28T08:00:00+08:00' }, { from: '2026-10-30T08:00:00' }, { to: '' }]) {
    const result = readUsageQuery(normalizeUsageQuery(serialize({ ...range, ...patch }), undefined, now));
    assert.equal(result.from, defaultUsageRange(now).from, JSON.stringify(patch));
    assert.equal(result.to, defaultUsageRange(now).to);
  }
  for (const page of ['-1', '1.5', 'Infinity', 'NaN', '1e2', '1000001', '9007199254740992', '', ' 2']) {
    assert.equal(readUsageQuery(serialize({ ...range, page })).page, 0, page);
  }
  for (const size of ['-1', '0', '10', '21', '200', '20.0', '1e2', 'Infinity']) {
    assert.equal(readUsageQuery(serialize({ ...range, size })).size, 20, size);
  }
  for (const size of [20, 50, 100]) assert.equal(readUsageQuery(serialize({ ...range, size })).size, size);
  assert.equal(readUsageQuery(serialize({ ...range, page: 1000000 })).page, 1000000);
  assert.equal(readUsageQuery(serialize({ ...range, granularity: 'month' })).granularity, 'hour');
  assert.equal(readUsageQuery(serialize({ ...range, unresolvedOnly: 'yes' })).unresolvedOnly, false);
});

test('every filter and size resets page atomically; pagination/no-op submissions preserve all filters', () => {
  const original = serialize(full);
  for (const patch of [{ from: '2026-10-28T09:00:00' }, { to: '2026-10-29T13:00:00' }, { tenantId: 'other' },
    { routeId: 'other' }, { model: 'other' }, { instanceId: 'other' }, { granularity: 'hour' }, { unresolvedOnly: false }, { size: 100 }]) {
    assert.deepEqual(readUsageQuery(updateUsageQuery(original, { ...patch, page: 8 })), { ...full, ...patch, page: 0 });
  }
  assert.deepEqual(readUsageQuery(updateUsageQuery(original, { page: 13 })), { ...full, page: 13 });
  assert.deepEqual(readUsageQuery(updateUsageQuery(original, { model: ` ${full.model} ` })), full);
  assert.deepEqual(readUsageQuery(original), full);
  assert.deepEqual(readUsageQuery(updateUsageQuery(original, { instanceId: 'forged', page: 8 }, full.instanceId)), { ...full, page: 8 });
  assert.equal(normalizeUsageQuery(original, 'fixed').get('instanceId'), 'fixed');
});

test('history snapshots restore the entire prior query rather than resetting its page', () => {
  const history = [normalizeUsageQuery(serialize(full)).toString()];
  history.push(updateUsageQuery(new URLSearchParams(history[0]), { model: 'next' }).toString());
  history.push(updateUsageQuery(new URLSearchParams(history[1]), { page: 3 }).toString());
  for (const index of [2, 1, 0, 1, 2]) {
    const expected = index === 0 ? full : { ...full, model: 'next', page: index === 1 ? 0 : 3 };
    assert.deepEqual(readUsageQuery(new URLSearchParams(history[index])), expected);
  }
});

test('return targets use exact real paths and same-instance scope, including unfiltered global usage', () => {
  const instance = context.instanceId;
  for (const target of [requestPath, `${requestPath}?${serialize({ ...range, page: 5 })}`, usagePath,
    `${usagePath}?${serialize(full)}`, '/token-usage', `/token-usage?${serialize(full)}`]) {
    assert.equal(requestCenterReturnUrl(instance, target), target);
  }
  for (const bad of ['https://evil.test', '//evil.test', 'javascript:alert(1)', '/token_usage', '/token-usage/extra',
    '/instances/other/token_usage', '/instances/other/requests', `${usagePath}/../requests`, `${usagePath}%3Fpage=1`,
    `${usagePath}#x`, `${usagePath}?q=\\evil`, `${usagePath}?q=\n`, `${usagePath}?instanceId=other`,
    `/token-usage?instanceId=other`, `/token-usage?${serialize({ instanceId: instance })}&instanceId=other`,
    `${requestPath}?instanceId=other`]) {
    assert.equal(requestCenterReturnUrl(instance, bad), requestPath, bad);
    assert.equal(isUsageReturnUrl(instance, bad), false, bad);
  }
  const detail = usageRequestJourneyUrl(instance, context.traceId, context.routeId, `/token-usage?${serialize(full)}`);
  assert.equal(parse(detail).searchParams.get('returnTo'), `/token-usage?${serialize(full)}`);
  assert.equal(usageSourceReturnUrl(instance, detail), detail);
  for (const bad of [detail.replace('%E7%BD%91%E5%85%B3%2Fa', 'other'), `${usagePath}?returnTo=${encodeURIComponent(detail)}`,
    `${detail.split('?')[0]}/extra`, `${detail.split('/trace/')[0]}/trace/%2E%2E`, `${detail.split('?')[0]}#x`, 'https://evil.test']) {
    assert.equal(usageSourceReturnUrl(instance, bad), undefined, bad);
  }
  assert.equal(usageSourceReturnUrl('', detail), undefined, 'global scope needs an effective instance for a detail return');
  assert.equal(parse(usageRequestJourneyUrl(instance, 'trace', null, '/token-usage?instanceId=other')).searchParams.has('returnTo'), false);
});

test('request search -> detail -> usage -> detail preserves time range and nested search filters/page', () => {
  for (const source of ['traces', 'logs']) {
    const origin = `${requestPath}?${serialize({ ...range, source, routeId: context.routeId, traceId: context.traceId, statusCode: 503, page: 7 })}`;
    const params = new URLSearchParams({ routeId: context.routeId, returnTo: origin });
    const url = parse(journeyUsageUrl({ ...context, params, traceTime: '2026-01-01T12:00:00' }, now));
    assert.equal(url.pathname, usagePath);
    assert.equal(url.searchParams.get('from'), range.from);
    assert.equal(url.searchParams.get('to'), range.to);
    assert.equal(url.searchParams.get('routeId'), context.routeId);
    assert.equal(url.searchParams.get('instanceId'), context.instanceId);
    const detail = usageSourceReturnUrl(context.instanceId, url.searchParams.get('returnTo'));
    assert.ok(detail);
    assert.equal(parse(detail).searchParams.toString(), params.toString());
    assert.equal(requestCenterReturnUrl(context.instanceId, parse(detail).searchParams.get('returnTo')), origin);
  }
});

test('global usage -> instance detail -> scoped usage retains the original global filters and page', () => {
  for (const instanceId of ['', context.instanceId]) {
    const original = `/token-usage?${normalizeUsageQuery(serialize({ ...full, instanceId }))}`;
    const detail = parse(usageRequestJourneyUrl(context.instanceId, context.traceId, context.routeId, original));
    assert.equal(requestCenterReturnUrl(context.instanceId, detail.searchParams.get('returnTo')), original);
    const scoped = parse(journeyUsageUrl({ ...context, params: detail.searchParams }, now));
    assert.equal(scoped.searchParams.get('from'), full.from);
    assert.equal(scoped.searchParams.get('to'), full.to);
    assert.equal(scoped.searchParams.get('instanceId'), context.instanceId);
    const backToDetail = usageSourceReturnUrl(context.instanceId, scoped.searchParams.get('returnTo'));
    assert.equal(parse(backToDetail).searchParams.get('returnTo'), original);
    assert.deepEqual(readUsageQuery(parse(original).searchParams), { ...full, instanceId });
  }
});

test('unsafe/invalid source cannot inject a range or an unsafe nested destination', () => {
  for (const returnTo of [`/instances/other/requests?${serialize(range)}`, `https://evil.test?${serialize(range)}`,
    `${requestPath}?from=bad&to=bad`]) {
    const url = parse(journeyUsageUrl({ ...context, params: new URLSearchParams({ returnTo }) }, now));
    assert.equal(url.searchParams.get('from'), defaultUsageRange(now).from);
    assert.equal(url.searchParams.get('to'), defaultUsageRange(now).to);
    const nested = parse(url.searchParams.get('returnTo')).searchParams.get('returnTo');
    assert.equal(requestCenterReturnUrl(context.instanceId, nested), nested);
  }
});

test('fallback windows honor server naive time, explicit offsets, epoch and UTC log timestamps in multiple zones', () => {
  const moduleUrl = new URL('../src/features/request-journey/usageNavigation.ts', import.meta.url).href;
  for (const zone of ['Asia/Shanghai', 'UTC', 'America/New_York']) {
    const source = `import { journeyUsageUrl } from ${JSON.stringify(moduleUrl)};
      const inputs = [{traceTime:'2026-10-29T12:00:00'}, {usageRequestTime:'2026-10-29T12:00:00+08:00'},
        {traceTime:1793246400000}, {accessLogTime:'2026-10-29T04:00:00'}, {traceTime:'invalid', usageRequestTime:'2026-10-29T04:00:00Z'}];
      console.log(JSON.stringify(inputs.map(input => {
        const url = new URL(journeyUsageUrl({instanceId:'gw',traceId:'trace',routeId:'route',params:new URLSearchParams(),...input}), 'https://console.test');
        return ['from','to'].map(key => new Date(url.searchParams.get(key)).toISOString());
      })));`;
    const result = JSON.parse(execFileSync(process.execPath, ['--experimental-strip-types', '--input-type=module', '-e', source], {
      env: { ...process.env, TZ: zone }, encoding: 'utf8',
    }));
    for (const actual of result) assert.deepEqual(actual, ['2026-10-29T03:30:00.000Z', '2026-10-29T04:30:00.000Z'], zone);
  }
});
