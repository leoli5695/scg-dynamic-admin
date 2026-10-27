import React from 'react';
import { ClusterOutlined, RobotOutlined } from '@ant-design/icons';

interface EditorCategorySwitchProps {
  id?: string;
  value?: 'NORMAL' | 'AI';
  onChange?: (value: 'NORMAL' | 'AI') => void;
  disabled?: boolean;
  label: string;
  normalLabel: string;
  aiLabel: string;
}

/** A persistent sliding indicator makes the type change visible without remounting fields. */
const EditorCategorySwitch: React.FC<EditorCategorySwitchProps> = ({
  id, value = 'NORMAL', onChange, disabled, label, normalLabel, aiLabel,
}) => (
  <div id={id} className="editor-category-switch" role="radiogroup" aria-label={label}
    aria-disabled={disabled} data-value={value}>
    <span className="editor-category-indicator" aria-hidden="true" />
    {(['NORMAL', 'AI'] as const).map((category, index) => (
      <button key={category} type="button" role="radio" aria-checked={value === category}
        disabled={disabled} tabIndex={value === category ? 0 : -1}
        onClick={() => onChange?.(category)}
        onKeyDown={(event) => {
          if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return;
          event.preventDefault();
          const next = event.key === 'Home' ? 0 : event.key === 'End' ? 1 : 1 - index;
          onChange?.(next === 0 ? 'NORMAL' : 'AI');
          event.currentTarget.parentElement?.querySelectorAll('button')[next]?.focus();
        }}>
        {category === 'AI' ? <RobotOutlined /> : <ClusterOutlined />}
        {category === 'AI' ? aiLabel : normalLabel}
      </button>
    ))}
  </div>
);

export default EditorCategorySwitch;
