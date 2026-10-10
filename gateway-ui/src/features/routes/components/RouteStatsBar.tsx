/**
 * Route count chips (total, enabled, disabled), rendered inline in the page
 * header instead of a full-width stats row.
 */
import React from 'react';
import { useTranslation } from 'react-i18next';
import InlineStatChips from '../../../components/InlineStatChips';

interface RouteStatsBarProps {
  total: number;
  enabled: number;
  disabled: number;
}

const RouteStatsBar: React.FC<RouteStatsBarProps> = ({ total, enabled, disabled }) => {
  const { t } = useTranslation();

  return (
    <InlineStatChips
      items={[
        { key: 'total', value: total, label: t('routes.stats_routes') },
        { key: 'enabled', value: enabled, label: t('routes.stats_enabled'), tone: 'green' },
        { key: 'disabled', value: disabled, label: t('routes.stats_disabled'), tone: 'red' },
      ]}
    />
  );
};

export default RouteStatsBar;
