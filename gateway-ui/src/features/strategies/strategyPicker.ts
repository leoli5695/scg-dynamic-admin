import type { StrategyTypeInfo } from '../../types/strategy';

export type StrategyPickerTab = 'all' | 'general' | 'ai';
const MANAGED_TYPES = new Set(['ACCESS_LOG', 'AI_PROXY']);
const SEARCH_ALIASES: Record<string, string> = {
  AUTH: 'JWT OAuth2 API Key API_KEY Basic HMAC 认证 鉴权',
  RATE_LIMITER: 'QPS 限流 流量',
  TOKEN_RATE_LIMITER: 'Token 令牌 配额 限流',
  IP_FILTER: 'IP 黑名单 白名单',
};

export const getCreatableStrategyTypes = (types: StrategyTypeInfo[]) =>
  types.filter((type) => !MANAGED_TYPES.has(type.typeCode));

export function filterStrategyTypes(
  types: StrategyTypeInfo[],
  {
    tab = 'all',
    category = 'all',
    query = '',
  }: { tab?: StrategyPickerTab; category?: string; query?: string },
  categoryLabel: (category: string) => string = (value) => value,
) {
  const terms = query.normalize('NFKC').toLowerCase().split(/\s+/).filter(Boolean);
  return getCreatableStrategyTypes(types).filter((type) => {
    if (tab === 'ai' && type.category !== 'ai') return false;
    if (tab === 'general' && type.category === 'ai') return false;
    if (category !== 'all' && type.category !== category) return false;
    const text = [
      type.typeName,
      type.typeNameEn,
      type.typeCode,
      type.description,
      type.category,
      categoryLabel(type.category),
      SEARCH_ALIASES[type.typeCode],
    ]
      .join(' ')
      .normalize('NFKC')
      .toLowerCase();
    return terms.every((term) => text.includes(term));
  });
}
