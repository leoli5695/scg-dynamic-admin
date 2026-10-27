import type { ReactNode } from 'react';
import './management.css';

export function ManagementPageHeader({
  title,
  subtitle,
  eyebrow,
  actions,
}: {
  title: string;
  subtitle: string;
  eyebrow: string;
  actions?: ReactNode;
}) {
  return (
    <header className="management-page-header">
      <div>
        <span className="management-eyebrow">{eyebrow}</span>
        <h2>{title}</h2>
        <p>{subtitle}</p>
      </div>
      {actions && <div className="management-page-actions">{actions}</div>}
    </header>
  );
}

export function ManagementMetric({
  title,
  value,
  icon,
  subtitle,
  tone = 'blue',
}: {
  title: string;
  value: ReactNode;
  icon: ReactNode;
  subtitle?: ReactNode;
  tone?: 'blue' | 'green' | 'purple' | 'amber';
}) {
  return (
    <div className={`management-metric metric-${tone}`}>
      <div className="management-metric-heading">
        <span>{title}</span>
        <span className="management-metric-icon" aria-hidden="true">
          {icon}
        </span>
      </div>
      <div className="management-metric-value">{value}</div>
      {subtitle && <div className="management-metric-caption">{subtitle}</div>}
    </div>
  );
}
