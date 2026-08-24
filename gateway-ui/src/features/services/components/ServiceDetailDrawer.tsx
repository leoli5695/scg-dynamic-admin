/**
 * Service detail drawer — status, description, upstream instances, actions.
 */
import React from 'react';
import {
  Badge, Button, Drawer, Empty, Tag, Tooltip, Typography  } from 'antd';
import { message, modal } from '../../../utils/antdStatic';
import {
  ApiOutlined, CloseOutlined, CloudServerOutlined, CopyOutlined,
  DeleteOutlined, EditOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { ServiceDefinition } from '../../../types/service';
import { getLoadBalancerLabelKey, getServiceStatus } from '../utils';

const { Text, Title } = Typography;

interface ServiceDetailDrawerProps {
  service: ServiceDefinition;
  onClose: () => void;
  onEdit: (service: ServiceDefinition) => void;
  onDelete: (service: ServiceDefinition) => void;
}

const ServiceDetailDrawer: React.FC<ServiceDetailDrawerProps> = ({
  service, onClose, onEdit, onDelete,
}) => {
  const { t } = useTranslation();
  const status = getServiceStatus(service);

  const handleCopy = () => {
    navigator.clipboard.writeText(service.serviceId || service.name);
    message.success(t('message.copy_success'));
  };

  const handleDeleteClick = () => {
    modal.confirm({
      title: t('common.confirm'),
      content: t('message.confirm_delete_service', { name: service.name }),
      okText: t('common.delete'),
      okType: 'danger',
      cancelText: t('common.cancel'),
      onOk: () => onDelete(service),
    });
  };

  return (
    <Drawer
      placement="right"
      styles={{ wrapper: { width: 480 } }}
      open
      closable={false}
      onClose={onClose}
      className="service-detail-drawer"
      mask
      maskClosable
      destroyOnClose
      style={{ zIndex: 999 }}
    >
      <div className="drawer-content">
        {/* Header */}
        <div className="drawer-header">
          <div className="drawer-header-left">
            <div className="drawer-icon"><CloudServerOutlined /></div>
            <div className="drawer-title-wrapper">
              <Title level={4} className="drawer-title">{service.name}</Title>
              <div className="drawer-id-row">
                <Text type="secondary">{service.serviceId || service.name}</Text>
                <Tooltip title={t('common.copy')}>
                  <Button
                    type="text"
                    size="small"
                    icon={<CopyOutlined />}
                    className="copy-btn"
                    onClick={handleCopy}
                  />
                </Tooltip>
              </div>
            </div>
          </div>
          <Button
            type="text"
            icon={<CloseOutlined />}
            onClick={onClose}
            className="drawer-close-btn"
          />
        </div>

        {/* Status bar */}
        <div className="drawer-status-bar">
          <Badge status={status.color} text={t(status.textKey, status.params)} />
          <Tag color="blue">{t(getLoadBalancerLabelKey(service.loadBalancer || 'weighted'))}</Tag>
        </div>

        {/* Description */}
        {service.description && (
          <div className="drawer-section">
            <Text type="secondary">{service.description}</Text>
          </div>
        )}

        {/* Instances */}
        <div className="drawer-section">
          <div className="section-title">
            <ApiOutlined />
            {' '}
            {t('services.instances')}
            <span className="count">{service.instances?.length || 0}</span>
          </div>
          {service.instances && service.instances.length > 0 ? (
            <div className="instances-detail-list">
              {service.instances.map((inst, idx) => (
                <div
                  key={idx}
                  className={`instance-detail-card ${inst.healthy !== false && inst.enabled !== false ? 'healthy' : 'unhealthy'}`}
                >
                  <div className="instance-main-info">
                    <div className="instance-address">
                      <span className="instance-dot" />
                      <Text strong>
                        {inst.ip}
                        :
                        {inst.port}
                      </Text>
                    </div>
                    <div className="instance-badges">
                      {inst.enabled === false && <Tag color="red">{t('common.disabled')}</Tag>}
                      {inst.healthy === false && <Tag color="orange">{t('services.unhealthy')}</Tag>}
                      {inst.enabled !== false && inst.healthy !== false && <Tag color="green">{t('services.healthy')}</Tag>}
                    </div>
                  </div>
                  <div className="instance-meta">
                    <span>
                      {t('services.weight')}
                      :
                      {' '}
                      {inst.weight || 1}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <Empty description={t('services.no_instances_configured')} />
          )}
        </div>

        {/* Actions */}
        <div className="drawer-actions">
          <Button type="primary" icon={<EditOutlined />} onClick={() => onEdit(service)}>
            {t('common.edit')}
          </Button>
          <Button danger icon={<DeleteOutlined />} onClick={handleDeleteClick}>
            {t('common.delete')}
          </Button>
        </div>
      </div>
    </Drawer>
  );
};

export default ServiceDetailDrawer;
