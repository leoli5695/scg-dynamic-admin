import React from 'react';

export interface FormSectionProps {
  icon?: React.ReactNode;
  title: React.ReactNode;
  description?: React.ReactNode;
  children: React.ReactNode;
}

/** Card-like grouped section for forms: replaces the ad-hoc form-section-modern markup. */
export const FormSection: React.FC<FormSectionProps> = ({
  icon,
  title,
  description,
  children,
}) => (
  <section className="app-form-section">
    <header className="app-form-section-head">
      {icon && <span className="app-form-section-icon">{icon}</span>}
      <span className="app-form-section-title">{title}</span>
      {description && <span className="app-form-section-desc">{description}</span>}
    </header>
    <div className="app-form-section-body">{children}</div>
  </section>
);
