/**
 * Built-in LLM provider catalogue for the services UI.
 *
 * Mirrors the gateway data plane's LlmProvider enum (my-gateway): the provider
 * type is the only thing an operator chooses for an AI service. Each type bakes
 * in its official base URL, so a stored `baseUrl` is only an *override* (relay /
 * egress proxy / self-hosted OpenAI-compatible backend).
 */
import type { AiProviderType } from '../../types/service';

export interface AiProviderMeta {
  id: AiProviderType;
  /** i18n key for the localized display name. */
  labelKey: string;
  defaultBaseUrl: string;
}

export const AI_PROVIDERS: AiProviderMeta[] = [
  { id: 'qwen', labelKey: 'services.provider.qwen', defaultBaseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1' },
  { id: 'openai', labelKey: 'services.provider.openai', defaultBaseUrl: 'https://api.openai.com/v1' },
  { id: 'deepseek', labelKey: 'services.provider.deepseek', defaultBaseUrl: 'https://api.deepseek.com' },
  { id: 'zhipu', labelKey: 'services.provider.zhipu', defaultBaseUrl: 'https://open.bigmodel.cn/api/paas/v4' },
  { id: 'moonshot', labelKey: 'services.provider.moonshot', defaultBaseUrl: 'https://api.moonshot.cn/v1' },
  { id: 'anthropic', labelKey: 'services.provider.anthropic', defaultBaseUrl: 'https://api.anthropic.com/v1' },
];

export const getProviderMeta = (id?: string): AiProviderMeta | undefined =>
  AI_PROVIDERS.find((p) => p.id === id);

/**
 * True only when the stored baseUrl is a real override — i.e. it differs from
 * the provider's built-in official address. Legacy rows may have the default
 * value persisted; those should still read as "official address" in the UI.
 */
export const hasCustomBaseUrl = (providerType?: string, baseUrl?: string): boolean => {
  const value = baseUrl?.trim();
  if (!value) return false;
  const meta = getProviderMeta(providerType);
  return !meta || value.replace(/\/+$/, '') !== meta.defaultBaseUrl.replace(/\/+$/, '');
};