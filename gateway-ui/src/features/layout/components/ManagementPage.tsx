import { useId, type ReactNode } from 'react';
import './management.css';

/** Tiny area sparkline for metric cards. Pure SVG: no chart framework, no animation. */
const Sparkline = ({ data, tone }: { data: number[]; tone: string }) => {
  const rawId = useId();
  const gradientId = `spark-${rawId.replace(/[^a-zA-Z0-9_-]/g, '')}`;
  if (data.length < 2) return null;
  const width = 100;
  const height = 32;
  const max = Math.max(...data);
  const min = Math.min(...data);
  const span = max - min || 1;
  const points = data.map((value, index) => ({
    x: (index / (data.length - 1)) * width,
    y: height - 3 - ((value - min) / span) * (height - 6),
  }));
  let line = `M${points[0].x.toFixed(2)},${points[0].y.toFixed(2)}`;
  for (let i = 0; i < points.length - 1; i += 1) {
    const p0 = points[i - 1] ?? points[i];
    const p1 = points[i];
    const p2 = points[i + 1];
    const p3 = points[i + 2] ?? p2;
    const c1x = p1.x + (p2.x - p0.x) / 6;
    const c1y = p1.y + (p2.y - p0.y) / 6;
    const c2x = p2.x - (p3.x - p1.x) / 6;
    const c2y = p2.y - (p3.y - p1.y) / 6;
    line += ` C${c1x.toFixed(2)},${c1y.toFixed(2)} ${c2x.toFixed(2)},${c2y.toFixed(2)} ${p2.x.toFixed(2)},${p2.y.toFixed(2)}`;
  }
  const area = `${line} L${width},${height} L0,${height} Z`;
  const last = points[points.length - 1];
  return (
    <svg
      className="management-metric-spark"
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      aria-hidden="true"
    >
      <defs>
        <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={tone} stopOpacity="0.26" />
          <stop offset="100%" stopColor={tone} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={area} fill={`url(#${gradientId})`} />
      <path
        d={line}
        fill="none"
        stroke={tone}
        strokeWidth="1.6"
        vectorEffect="non-scaling-stroke"
        strokeLinecap="round"
        strokeLinejoin="round"
        opacity="0.9"
      />
      <circle cx={last.x} cy={last.y} r="1.8" fill={tone} opacity="0.95" />
    </svg>
  );
};

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
  spark,
  sparkTone,
  accentBar,
}: {
  title: string;
  value: ReactNode;
  icon: ReactNode;
  subtitle?: ReactNode;
  tone?: 'blue' | 'green' | 'purple' | 'amber';
  spark?: number[];
  sparkTone?: string;
  accentBar?: ReactNode;
}) {
  const hasSpark = Boolean(spark && spark.length > 1);
  return (
    <div className={`management-metric metric-${tone}${hasSpark ? ' has-spark' : ''}`}>
      <div className="management-metric-heading">
        <span>{title}</span>
        <span className="management-metric-icon" aria-hidden="true">
          {icon}
        </span>
      </div>
      <div className="management-metric-value">{value}</div>
      {subtitle && <div className="management-metric-caption">{subtitle}</div>}
      {hasSpark && spark && <Sparkline data={spark} tone={sparkTone ?? '#8ab0ff'} />}
      {accentBar}
    </div>
  );
}
