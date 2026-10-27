import React, { useState } from 'react';
import { Button, Form, Modal } from 'antd';
import type { FormInstance } from 'antd';
import { useTranslation } from 'react-i18next';
import EditorNavigation from './EditorNavigation';
import { useEditorStep } from './useEditorStep';

interface EditorSection {
  label: string;
  content: React.ReactNode;
  /** Omit for schema-driven configuration fields. */
  fields?: string[];
}

interface ResourceEditorProps<Values extends object> {
  open?: boolean;
  footerExtra?: React.ReactNode;
  className?: string;
  title: React.ReactNode;
  form: FormInstance<Values>;
  initialValues: Partial<Values>;
  sections: EditorSection[];
  submitting?: boolean;
  onCancel: () => void;
  onFinish: (values: Values) => Promise<unknown>;
  submitLabel: string;
}

/** Shared route-style workspace for instance resource create and edit flows. */
export default function ResourceEditor<Values extends object>({
  open = true,
  footerExtra,
  className,
  title,
  form,
  initialValues,
  sections,
  submitting,
  onCancel,
  onFinish,
  submitLabel,
}: ResourceEditorProps<Values>) {
  const { t } = useTranslation();
  const [active, setActive, direction] = useEditorStep();
  const [saving, setSaving] = useState(false);
  const busy = submitting || saving;
  const next = async () => {
    try {
      await form.validateFields(sections[active].fields, { recursive: true });
      setActive(Math.min(active + 1, sections.length - 1));
    } catch {
      /* Field errors stay visible in the current section. */
    }
  };
  return (
    <Modal
      open={open}
      title={title}
      width={1060}
      className={`console-editor resource-editor${className ? ` ${className}` : ''}`}
      footer={null}
      onCancel={onCancel}
      destroyOnHidden
      mask={{ closable: false }}
      closable={!busy}
      keyboard={!busy}
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={initialValues}
        disabled={busy}
        onFinish={async (values) => {
          setSaving(true);
          try {
            await onFinish(values);
          } finally {
            setSaving(false);
          }
        }}
        onFinishFailed={({ errorFields }) => {
          const field = String(errorFields[0]?.name[0] ?? '');
          const index = sections.findIndex((section) => section.fields?.includes(field));
          setActive(
            index < 0
              ? Math.max(
                  0,
                  sections.findIndex((section) => !section.fields),
                )
              : index,
          );
        }}
      >
        <div className="editor-workspace">
          <EditorNavigation
            steps={sections.map((section) => section.label)}
            active={active}
            onChange={(step) => {
              if (!busy) setActive(step);
            }}
          />
          <div className="editor-scroll" data-direction={direction}>
            {sections.map((section, index) => (
              <div key={section.label} hidden={active !== index}>
                {section.content}
              </div>
            ))}
          </div>
        </div>
        <div className="modal-footer-modern">
          <span className="editor-progress">
            {active + 1} / {sections.length}
          </span>
          <Button disabled={busy} onClick={onCancel}>
            {t('common.cancel')}
          </Button>
          {active > 0 && (
            <Button disabled={busy} onClick={() => setActive(active - 1)}>
              {t('routes.previous_step')}
            </Button>
          )}
          {active < sections.length - 1 ? (
            <Button
              key="next"
              type="primary"
              htmlType="button"
              disabled={busy}
              onClick={(event) => {
                event.preventDefault();
                void next();
              }}
            >
              {t('routes.next_step')}
            </Button>
          ) : (
            <React.Fragment key="submit-actions">
              {footerExtra}
              <Button key="submit" type="primary" htmlType="submit" loading={busy}>
                {submitLabel}
              </Button>
            </React.Fragment>
          )}
        </div>
      </Form>
    </Modal>
  );
}
