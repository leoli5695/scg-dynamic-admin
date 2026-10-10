import React from 'react';
import { Modal } from 'antd';
import './form-ui.css';

export interface FormModalProps {
  title: React.ReactNode;
  subtitle?: React.ReactNode;
  icon?: React.ReactNode;
  open: boolean;
  onClose: () => void;
  onOk?: () => void;
  okText?: React.ReactNode;
  cancelText?: React.ReactNode;
  confirmLoading?: boolean;
  width?: number | string;
  /** Override the default Cancel/OK footer (e.g. extra buttons). */
  footer?: React.ReactNode;
  children: React.ReactNode;
}

/**
 * Shared form-modal shell: one place for modal chrome (header icon/title/subtitle,
 * footer buttons, widths) so feature modals stop hand-rolling markup and relying on
 * global CSS class families.
 */
export const FormModal: React.FC<FormModalProps> = ({
  title,
  subtitle,
  icon,
  open,
  onClose,
  onOk,
  okText,
  cancelText,
  confirmLoading,
  width = 640,
  footer,
  children,
}) => (
  <Modal
    className="app-form-modal"
    open={open}
    onCancel={onClose}
    onOk={onOk}
    okText={okText}
    cancelText={cancelText}
    confirmLoading={confirmLoading}
    width={width}
    destroyOnHidden
    footer={footer}
    title={(
      <div className="app-form-modal-header">
        {icon && <div className="app-form-modal-icon">{icon}</div>}
        <div className="app-form-modal-heading">
          <div className="app-form-modal-title">{title}</div>
          {subtitle && <div className="app-form-modal-subtitle">{subtitle}</div>}
        </div>
      </div>
    )}
  >
    {children}
  </Modal>
);
