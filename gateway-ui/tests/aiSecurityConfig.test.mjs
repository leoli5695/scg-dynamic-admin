import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildStrategyConfig, strategyToFormValues } from '../src/features/strategies/utils.ts';

test('AI security form preserves words, adapter settings, and disabled outer state', () => {
  const strategy = {
    strategyType: 'AI_SECURITY', enabled: false,
    config: { enabled: true, bannedWords: ['敏感词'], prohibitedWords: ['违禁词'], bannedPatterns: ['test.*'],
      externalAdapter: 'MY_PROVIDER', externalHeaders: { Authorization: '${MODERATION_AUTH}' },
      externalOptions: { appId: 'app-a' }, streamingPolicy: 'BUFFER', piiAction: 'REPLACE' },
  };
  const values = strategyToFormValues(strategy);
  assert.equal(values.enabled, false);
  assert.deepEqual(buildStrategyConfig(values, 'AI_SECURITY'), strategy.config);
});

test('AI security creation sets the inner enable flag and converts parameter rows', () => {
  const config = buildStrategyConfig({ enabled: true, externalHeaderRows: [{ key: 'X-Key', value: '${KEY}' }],
    externalOptionRows: [{ key: 'appId', value: 'app-b' }], bannedWords: ['x'] }, 'AI_SECURITY');
  assert.equal(config.enabled, true);
  assert.deepEqual(config.externalHeaders, { 'X-Key': '${KEY}' });
  assert.deepEqual(config.externalOptions, { appId: 'app-b' });
  assert.equal(config.externalHeaderRows, undefined);
});
