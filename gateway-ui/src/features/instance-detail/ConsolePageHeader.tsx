import React from 'react';

interface ConsolePageHeaderProps {
  icon: React.ReactNode;
  eyebrow: string;
  title: string;
  description: string;
  actions?: React.ReactNode;
}

const ConsolePageHeader: React.FC<ConsolePageHeaderProps> = ({
  icon, eyebrow, title, description, actions,
}) => (
  <div className="diagnostics-hero">
    <div className="diagnostics-hero-main">
      <div className="diagnostics-hero-icon" aria-hidden="true">{icon}</div>
      <div className="diagnostics-hero-copy">
        <span className="diagnostics-hero-eyebrow">{eyebrow}</span>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
    </div>
    {actions && <div className="diagnostics-hero-actions">{actions}</div>}
  </div>
);

export default ConsolePageHeader;
