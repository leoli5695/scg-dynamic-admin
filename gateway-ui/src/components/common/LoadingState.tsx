/**
 * Loading State Components
 * Skeleton screens for better loading UX
 */
import React from 'react';
import { Skeleton, Spin, Space } from 'antd';

/**
 * Table skeleton - for table loading states
 */
export const TableSkeleton: React.FC<{ rows?: number }> = ({ rows = 5 }) => (
  <div style={{ padding: '16px' }}>
    <Skeleton
      active
      paragraph={{ rows }}
    />
  </div>
);

/**
 * Card skeleton - for card loading states
 */
export const CardSkeleton: React.FC = () => (
  <div style={{ padding: '16px' }}>
    <Skeleton
      active
      avatar
      paragraph={{ rows: 2 }}
    />
  </div>
);

/**
 * Grid skeleton - for multiple cards
 */
export const GridSkeleton: React.FC<{ count?: number }> = ({ count = 4 }) => (
  <div style={{
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))',
    gap: '16px',
    padding: '16px',
  }}>
    {Array.from({ length: count }).map((_, i) => (
      <Skeleton
        key={i}
        active
        avatar
        paragraph={{ rows: 2 }}
      />
    ))}
  </div>
);

/**
 * Tab loading - for lazy loaded tab content
 */
export const TabLoading: React.FC = () => (
  <div style={{
    display: 'flex',
    justifyContent: 'center',
    alignItems: 'center',
    minHeight: '300px',
  }}>
    <Spin size="large" />
  </div>
);

/**
 * Page loading - full page loading state
 */
export const PageLoading: React.FC = () => (
  <div style={{
    display: 'flex',
    justifyContent: 'center',
    alignItems: 'center',
    minHeight: '100vh',
    background: 'var(--bg-primary)',
  }}>
    <Space direction="vertical" align="center">
      <Spin size="large" />
    </Space>
  </div>
);

/**
 * Stat skeleton - for stat cards
 */
export const StatSkeleton: React.FC = () => (
  <div style={{
    background: 'var(--bg-card)',
    borderRadius: 'var(--radius-lg)',
    padding: '24px',
  }}>
    <Skeleton.Input active size="small" style={{ width: 80, marginBottom: 8 }} />
    <Skeleton.Input active size="large" style={{ width: 120 }} />
  </div>
);

/**
 * Detail skeleton - for detail page loading
 */
export const DetailSkeleton: React.FC = () => (
  <div style={{ padding: '24px' }}>
    <Skeleton active paragraph={{ rows: 4 }} />
  </div>
);

/**
 * List skeleton - for list items
 */
export const ListSkeleton: React.FC<{ count?: number }> = ({ count = 3 }) => (
  <div style={{ padding: '16px' }}>
    {Array.from({ length: count }).map((_, i) => (
      <Skeleton
        key={i}
        active
        avatar
        paragraph={{ rows: 1 }}
        style={{ marginBottom: 16 }}
      />
    ))}
  </div>
);

export default {
  TableSkeleton,
  CardSkeleton,
  GridSkeleton,
  TabLoading,
  PageLoading,
  StatSkeleton,
  DetailSkeleton,
  ListSkeleton,
};