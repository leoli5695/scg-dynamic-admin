/**
 * Service card in the services grid.
 */
import React, { memo, useCallback, useMemo } from 'react';
import {
  Alert, Badge, Button, Card, Popconfirm, Space, Tag, Tooltip, Typography  } from 'antd';
import { message } from '../../../utils/antdStatic';
import {
  ApiOutlined, CloudServerOutlined, CopyOutlined, DeleteOutlined,
  EditOutlined, ExclamationCircleOutlined, EyeOutlined, RobotOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { ServiceDefinition } from '../../../types/service';
import { getLoadBalancerLabelKey, getServiceStatus } from '../utils';
import AiServiceConnection from './AiServiceConnection';

const { Text } = Typography;

interface ServiceCardProps {
  service: ServiceDefinition;
  onDetail: (service: ServiceDefinition) => void;
  onEdit: (service: ServiceDefinition) => void;
  onDelete: (service: ServiceDefinition) => void;
}

const ServiceCard: React.FC<ServiceCardProps> = memo(({
  service, onDetail, onEdit, onDelete,
}) => {
  const { t } = useTranslation();
  const isAi = service.category === 'AI';
  const status = useMemo(() => getServiceStatus(service), [service]);
  const instances = useMemo(() => service.instances || [], [service.instances]);
  const instanceCount = instances.length;
  const enabledInstances = useMemo(
    () => instances.filter((inst) => inst.enabled !== false),
    [instances],
  );
  const healthyEnabled = useMemo(
    () => enabledInstances.filter((inst) => inst.healthy !== false).length,
    [enabledInstances],
  );

  const warningMessage = useMemo(() => {
    if (isAi) return null;
    if (instanceCount === 0) return t('services.no_instances_warning');
    if (enabledInstances.length === 0) return t('services.all_instances_disabled_warning');
    if (healthyEnabled === 0) return t('services.all_instances_down_warning');
    return null;
  }, [t, isAi, instanceCount, enabledInstances.length, healthyEnabled]);

  const handleCopyId = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    navigator.clipboard.writeText(service.serviceId || service.name);
    message.success(t('message.copy_success'));
  }, [t, service.serviceId, service.name]);

  return (
    <Card
      className={`service-card ${warningMessage ? 'service-card-warning' : ''}`}
      hoverable
      style={{ minHeight: '260px', height: '100%' }}
    >
      {warningMessage && (
        <Alert
          type="warning"
          showIcon
          icon={<ExclamationCircleOutlined />}
          message={warningMessage}
          className="service-warning-alert"
          style={{ marginBottom: 16, background: 'rgba(245, 158, 11, 0.08)', border: '1px solid rgba(245, 158, 11, 0.2)' }}
        />
      )}
      <div className="service-card-header">
        <div className="service-info">
          <div className="service-icon">{isAi ? <RobotOutlined /> : <CloudServerOutlined />}</div>
          <div className="service-details">
            <Text strong className="service-name">{service.name}</Text>
            <div className="service-id-row">
              <Text type="secondary" className="service-id">{service.serviceId || service.name}</Text>
              <Tooltip title={t('common.copy')}>
                <Button
                  type="text"
                  size="small"
                  icon={<CopyOutlined />}
                  className="copy-btn"
                  onClick={handleCopyId}
                />
              </Tooltip>
            </div>
          </div>
        </div>
        <Space>
          <Tooltip title={t('common.detail')}>
            <Button
              type="text"
              size="small"
              icon={<EyeOutlined />}
              className="action-btn"
              onClick={() => onDetail(service)}
            />
          </Tooltip>
          <Tooltip title={t('common.edit')}>
            <Button
              type="text"
              size="small"
              icon={<EditOutlined />}
              className="action-btn"
              onClick={() => onEdit(service)}
            />
          </Tooltip>
          <Popconfirm
            title={`${t('common.delete')}: ${service.name}`}
            onConfirm={() => onDelete(service)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
          >
            <Tooltip title={t('common.delete')}>
              <Button type="text" size="small" danger icon={<DeleteOutlined />} className="action-btn" />
            </Tooltip>
          </Popconfirm>
        </Space>
      </div>
      <div className="service-meta">
        <Badge status={status.color} text={t(status.textKey, status.params)} />
        {!isAi && <Tag className="lb-tag">{t(getLoadBalancerLabelKey(service.loadBalancer || 'weighted'))}</Tag>}
      </div>
      {isAi ? (
        <div className="instances-section" style={{ flex: 1 }}>
          <AiServiceConnection service={service} />
        </div>
      ) : (
      <div className="instances-section" style={{ flex: 1 }}>
        <div className="instances-header">
          <Text type="secondary" className="instances-title">
            <ApiOutlined />
            {' '}
            {healthyEnabled}
            /
            {instanceCount}
            {' '}
            {t('services.available_instances')}
          </Text>
        </div>
        {instanceCount > 0 && (
          <div className="instances-list">
            {instances.slice(0, 5).map((inst, idx) => (
              <Tag
                key={idx}
                className={`instance-tag ${inst.healthy !== false && inst.enabled !== false ? 'instance-tag-healthy' : 'instance-tag-unhealthy'}`}
                bordered={false}
              >
                <span
                  className="instance-dot"
                  style={{ background: inst.healthy !== false && inst.enabled !== false ? '#10b981' : '#ef4444' }}
                />
                <span className="instance-addr">
                  {inst.ip}
                  :
                  {inst.port}
                </span>
                {inst.enabled === false && (
                  <Tag color="red" style={{ marginLeft: 4, fontSize: 12 }}>{t('common.disabled')}</Tag>
                )}
              </Tag>
            ))}
            {instanceCount > 5 && (
              <Tag className="instance-tag instance-tag-more">
                +
                {instanceCount - 5}
                {' '}
                more
              </Tag>
            )}
          </div>
        )}
        {instanceCount === 0 && (
          <Text type="secondary" className="no-instances">
            {t('services.no_instances_configured')}
          </Text>
        )}
      </div>
      )}
    </Card>
  );
});

export default ServiceCard;
