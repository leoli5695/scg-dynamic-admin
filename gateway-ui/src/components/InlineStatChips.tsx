/**
 * Compact inline stat chips rendered inside a page header's right side.
 * Replaces the old full-width .stats-bar row on every list page (space saving).
 * Chips are optionally clickable (active ring) when they double as filters.
 */
import React from 'react';

export interface InlineStatChipItem {
  key: string;
  value: number;
  label: string;
  tone?: 'green' | 'red' | 'blue' | 'amber';
  active?: boolean;
  onClick?: () => void;
}

const TONE_CLASS: Record<NonNullable<InlineStatChipItem['tone']>, string> = {
  green: 'text-green-600',
  red: 'text-red-600',
  blue: 'text-blue-600',
  amber: 'text-amber-600',
};

const InlineStatChips: React.FC<{ items: InlineStatChipItem[] }> = ({ items }) => (
  <div className="stat-chips-inline">
    {items.map((item) => (
      <div
        key={item.key}
        className={[
          'stat-chip',
          item.active ? 'stat-chip-active' : '',
          item.onClick ? '' : 'stat-chip-static',
        ].filter(Boolean).join(' ')}
        onClick={item.onClick}
      >
        <span className={`stat-chip-value ${item.tone ? TONE_CLASS[item.tone] : ''}`}>
          {item.value}
        </span>
        <span className="stat-chip-label">{item.label}</span>
      </div>
    ))}
  </div>
);

export default InlineStatChips;
