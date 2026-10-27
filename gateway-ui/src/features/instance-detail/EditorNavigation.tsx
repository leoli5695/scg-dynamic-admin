import React from 'react';
import { CheckOutlined, RightOutlined } from '@ant-design/icons';

interface EditorNavigationProps {
  steps: string[];
  active: number;
  onChange: (step: number) => void;
}

/** Real buttons keep every section reachable by keyboard during create and edit. */
const EditorNavigation: React.FC<EditorNavigationProps> = ({ steps, active, onChange }) => (
  <nav className="editor-navigation" style={{ '--editor-active-step': active, '--editor-step-count': steps.length } as React.CSSProperties}>
    {steps.map((label, index) => (
      <button key={label} type="button" className={`editor-step${active === index ? ' is-active' : ''}`}
        aria-current={active === index ? 'step' : undefined} onClick={() => onChange(index)}>
        <span className="editor-step-number">{index < active ? <CheckOutlined /> : `0${index + 1}`}</span>
        <span>{label}</span>
        {index === active && <RightOutlined className="editor-step-arrow" />}
      </button>
    ))}
  </nav>
);

export default EditorNavigation;
