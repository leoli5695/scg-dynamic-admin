/** Matches the server's unresolved-events predicate; the two categories can overlap. */
export function isUsageUnresolved(event: {
  usageStatus?: string | null;
  settlementStatus?: string | null;
}): boolean {
  return (
    event.usageStatus === 'UNKNOWN' ||
    event.usageStatus === 'ESTIMATED' ||
    event.settlementStatus === 'FAILED' ||
    event.settlementStatus === 'PENDING'
  );
}

type UsageTokenField = 'promptTokens' | 'completionTokens' | 'totalTokens';
type UsageValues = { usageStatus?: string | null } & Partial<
  Record<UsageTokenField, number | null>
>;

/** Unknown measurements must not expose legacy zero placeholders as measured usage. */
export function usageTokenValue(event: UsageValues, field: UsageTokenField): number | '—' {
  return event.usageStatus === 'UNKNOWN' ? '—' : (event[field] ?? '—');
}
