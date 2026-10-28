import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildStrategyConfig, strategyToFormValues } from '../src/features/strategies/utils.ts';

test('AI strategy edits preserve service bindings and fallback model mappings', () => {
  const upstreams = [
    { serviceId: 'a', provider: 'openai', credentialRef: 'key-a', weight: 20, modelMapping: { chat: 'gpt-a' } },
    { serviceId: 'b', provider: 'openai', credentialRef: 'key-b', weight: 80, modelMapping: { chat: 'gpt-b' } },
  ];
  const fallbacks = [{ serviceId: 'backup', provider: 'qwen', credentialRef: 'key-c', modelMapping: { chat: 'qwen-plus' } }];
  const strategy = {
    strategyType: 'AI_PROXY', enabled: true,
    config: { upstreams, fallbacks, modelMapping: { chat: 'gpt-b' }, providerBaseUrlOverride: { openai: 'https://relay.example/v1' } },
  };
  const config = buildStrategyConfig(strategyToFormValues(strategy), 'AI_PROXY');
  assert.deepEqual(config.upstreams, upstreams);
  assert.deepEqual(config.fallbacks, fallbacks);
  assert.deepEqual(config.modelMapping, strategy.config.modelMapping);
  assert.deepEqual(config.providerBaseUrlOverride, strategy.config.providerBaseUrlOverride);
});
