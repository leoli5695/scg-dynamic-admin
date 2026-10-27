import { test } from 'node:test';
import assert from 'node:assert/strict';
import { filterStrategyTypes } from '../src/features/strategies/strategyPicker.ts';

const types = [
  {
    typeCode: 'RATE_LIMITER',
    typeName: '限流器',
    typeNameEn: 'Rate Limiter',
    category: 'traffic',
    description: '基于QPS的限流策略',
  },
  {
    typeCode: 'AUTH',
    typeName: '认证策略',
    typeNameEn: 'Authentication',
    category: 'security',
    description: '多种认证方式',
  },
  {
    typeCode: 'TOKEN_RATE_LIMITER',
    typeName: 'Token 限流',
    typeNameEn: 'Token Rate Limiter',
    category: 'ai',
  },
  { typeCode: 'ACCESS_LOG', category: 'observability' },
  { typeCode: 'AI_PROXY', category: 'ai' },
];
const codes = (options, label) =>
  filterStrategyTypes(types, options, label).map((type) => type.typeCode);

test('Chinese, mixed-case English, type codes and common auth keywords find the intended strategy', () => {
  assert.deepEqual(codes({ query: '认证' }), ['AUTH']);
  assert.deepEqual(codes({ query: '  rAtE   limiter  ', tab: 'general' }), ['RATE_LIMITER']);
  assert.deepEqual(codes({ query: 'TOKEN_RATE_LIMITER' }), ['TOKEN_RATE_LIMITER']);
  assert.deepEqual(codes({ query: 'ＪＷＴ' }), ['AUTH']);
  assert.deepEqual(codes({ query: 'api key' }), ['AUTH']);
});

test('tab, category and keyword filters combine without including system-managed strategies', () => {
  assert.deepEqual(codes({ tab: 'general' }), ['RATE_LIMITER', 'AUTH']);
  assert.deepEqual(codes({ tab: 'ai' }), ['TOKEN_RATE_LIMITER']);
  assert.deepEqual(codes({ category: 'security', query: 'JWT' }), ['AUTH']);
  assert.deepEqual(codes({ category: 'traffic', query: 'JWT' }), []);
  assert.deepEqual(
    codes({ query: '安全防护' }, (value) => (value === 'security' ? '安全防护' : value)),
    ['AUTH'],
  );
  assert.deepEqual(codes({}), ['RATE_LIMITER', 'AUTH', 'TOKEN_RATE_LIMITER']);
});
