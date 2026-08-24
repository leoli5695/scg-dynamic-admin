/**
 * Auth policy card — one policy in the grid with its bound routes.
 * Visual contract follows the shared strategy-card styles
 * (StrategiesPage.premium.css).
 */
import React from 'react';
import { Badge, Button, Card, Popconfirm, Space, Tag, Tooltip, Typography } from 'antd';
import {
  DeleteOutlined, EditOutlined, KeyOutlined, LinkOutlined,
  PlayCircleOutlined, QuestionCircleOutlined, StopOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { AuthPolicyDefinition, RouteAuthBinding } from '../../../types/authPolicy';
import { getAuthTypeColor, getAuthTypeDescKey, getAuthTypeLabel } from '../utils';

const { Text } = Typography;

interface AuthPolicyCardProps {
  policy: AuthPolicyDefinition;
  bindings: RouteAuthBinding[];
  getRouteName: (routeId: string) => string;
  onEdit: (policy: AuthPolicyDefinition) => void;
  onBind: (policy: AuthPolicyDefinition) => void;
  onUsageExample: (policy: AuthPolicyDefinition) => void;
  onToggle: (policy: AuthPolicyDefinition) => void;
  onDelete: (policy: AuthPolicyDefinition) => void;
}

const AuthPolicyCard: React.FC<AuthPolicyCardProps> = ({
  policy, bindings, getRouteName, onEdit, onBind, onUsageExample, onToggle, onDelete,
}) => {
  const { t } = useTranslation();
  const descKey = getAuthTypeDescKey(policy.authType);

  return (
    <Card className="strategy-card">
      <div className="strategy-card-header">
        <div className="strategy-info">
          <div className="strategy-icon" style={{ background: getAuthTypeColor(policy.authType) }}>
            <KeyOutlined />
          </div>
          <div className="strategy-details">
            <Text strong className="strategy-name">{policy.policyName}</Text>
            <Text type="secondary" className="strategy-id">
              {policy.policyId?.substring(0, 12)}...
            </Text>
          </div>
        </div>
        <Badge status={policy.enabled ? 'success' : 'default'} />
        <Space>
          <Tooltip title={t('common.edit')}>
            <Button type="text" size="small" icon={<EditOutlined />} className="action-btn" onClick={() => onEdit(policy)} />
          </Tooltip>
          <Tooltip title={t('auth_policy.usage_example')}>
            <Button type="text" size="small" icon={<QuestionCircleOutlined />} className="action-btn" onClick={() => onUsageExample(policy)} />
          </Tooltip>
          <Tooltip title={t('auth_policy.bind_routes')}>
            <Button type="text" size="small" icon={<LinkOutlined />} className="action-btn" onClick={() => onBind(policy)} />
          </Tooltip>
          <Tooltip title={policy.enabled ? t('common.disable') : t('common.enable')}>
            <Button
              type="text"
              size="small"
              icon={policy.enabled ? <StopOutlined /> : <PlayCircleOutlined />}
              className="action-btn"
              onClick={() => onToggle(policy)}
            />
          </Tooltip>
          <Popconfirm
            title={`${t('common.delete')}: ${policy.policyName}`}
            description={t('auth_policy.delete_confirm_content')}
            onConfirm={() => onDelete(policy)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
            okButtonProps={{ danger: true }}
          >
            <Tooltip title={t('common.delete')}>
              <Button type="text" size="small" danger icon={<DeleteOutlined />} className="action-btn" />
            </Tooltip>
          </Popconfirm>
        </Space>
      </div>

      <div className="strategy-section">
        <Text type="secondary" className="section-label">{t('auth_policy.auth_type')}</Text>
        <Tag color={getAuthTypeColor(policy.authType)}>{getAuthTypeLabel(policy.authType, t)}</Tag>
        {descKey && (
          <Tooltip
            title={t(descKey)}
            placement="bottom"
            overlayStyle={{ maxWidth: '350px' }}
            align={{ offset: [8, 0] }}
          >
            <QuestionCircleOutlined style={{ marginLeft: 4, color: '#6b7280', cursor: 'pointer' }} />
          </Tooltip>
        )}
      </div>

      <div className="strategy-section">
        <Text type="secondary" className="section-label">{t('auth_policy.bound_routes')}</Text>
        {bindings.length > 0 ? (
          <div className="tags-container">
            {bindings.slice(0, 3).map((binding) => (
              <Tag key={binding.bindingId} color="blue">
                {getRouteName(binding.routeId)}
              </Tag>
            ))}
            {bindings.length > 3 && <Tag>+{bindings.length - 3}</Tag>}
          </div>
        ) : (
          <Text type="secondary">{t('auth_policy.no_bindings')}</Text>
        )}
      </div>

      {policy.description && (
        <div className="strategy-section">
          <Text type="secondary" className="section-label">{t('common.description')}</Text>
          <Text className="strategy-description">{policy.description}</Text>
        </div>
      )}
    </Card>
  );
};

export default AuthPolicyCard;
