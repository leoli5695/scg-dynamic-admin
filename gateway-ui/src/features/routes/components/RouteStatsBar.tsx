/**
 * Stats bar showing route counts (total, enabled, disabled).
 */
import React from 'react';
import { Divider } from 'antd';
import { useTranslation } from 'react-i18next';

interface RouteStatsBarProps {
  total: number;
  enabled: number;
  disabled: number;
}

const RouteStatsBar: React.FC<RouteStatsBarProps> = ({ total, enabled, disabled }) => {
  const { t } = useTranslation();

  return (
    <div className="stats-bar">
      <div className="stat-item">
        <div className="stat-value">{total}</div>
        <div className="stat-label">{t('routes.stats_routes')}</div>
      </div>
      <Divider type="vertical" className="stat-divider" />
      <div className="stat-item">
        <div className="stat-value text-green-600">{enabled}</div>
        <div className="stat-label">{t('routes.stats_enabled')}</div>
      </div>
      <Divider type="vertical" className="stat-divider" />
      <div className="stat-item">
        <div className="stat-value text-red-600">{disabled}</div>
        <div className="stat-label">{t('routes.stats_disabled')}</div>
      </div>
    </div>
  );
};

export default RouteStatsBar;
