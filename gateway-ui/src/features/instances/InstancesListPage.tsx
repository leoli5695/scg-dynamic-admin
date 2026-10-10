/**
 * Instances list page (rewritten).
 * Visual identical to the legacy page; data flow via typed API + useRequest.
 */
import React from 'react';
import {
  Button, Card, Col, Empty, Popconfirm, Row, Spin, Tag, Tooltip, Typography  } from 'antd';
import { message } from '../../utils/antdStatic';
import {
  CloudServerOutlined, ClusterOutlined, DashboardOutlined, DeleteOutlined,
  InboxOutlined, PauseCircleOutlined, PlayCircleOutlined, PlusOutlined,
  ReloadOutlined, SettingOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../hooks/useRequest';
import {
  deleteInstance, listInstances, refreshInstanceStatus, startInstance, stopInstance,
} from '../../api/instances';
import { getClusters } from '../../api/kubernetes';
import { INSTANCE_STATUS } from '../../types/instance';
import {
  getStatusColor, getStatusText, getSpecText,
} from '../../utils/instance';
import { ApiError } from '../../api/http';
// Legacy stylesheet — moved into this feature during final cleanup.
import './instances.css';

const { Text } = Typography;

const statusBarClass = (statusCode?: number): string => {
  switch (statusCode) {
    case INSTANCE_STATUS.RUNNING: return 'status-running';
    case INSTANCE_STATUS.STARTING: return 'status-starting';
    case INSTANCE_STATUS.STOPPED: return 'status-stopped';
    case INSTANCE_STATUS.ERROR: return 'status-error';
    case INSTANCE_STATUS.STOPPING: return 'status-stopping';
    default: return '';
  }
};

const InstancesListPage: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();

  const clusters = useRequest((signal) => getClusters({ signal }));
  const instances = useRequest((signal) => listInstances({ signal }));

  const list = instances.data ?? [];
  const clusterCount = clusters.data?.length ?? 0;
  const loading = instances.loading || clusters.loading;

  /** Run a mutating action then refresh the list. */
  const runAction = async (
    action: () => Promise<unknown>,
    successKey?: string,
    failKey?: string,
  ) => {
    try {
      await action();
      if (successKey) message.success(t(successKey));
      instances.run();
    } catch (error) {
      const msg = error instanceof ApiError && error.message ? error.message : t(failKey || 'common.operation_failed');
      message.error(msg);
    }
  };

  return (
    <div className="instances-page">
      <div className="instances-page-header">
        <div className="instances-page-header-left">
          <div className="instances-page-title">
            <CloudServerOutlined />
            <span className="instances-page-title-text">{t('menu.instances')}</span>
          </div>
          <Text className="instances-page-subtitle">
            {t('instance.page_subtitle')}
          </Text>
        </div>
        <div className="instances-page-actions">
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => navigate('/instances/create')}
            disabled={clusterCount === 0}
            size="large"
          >
            {t('instance.create_instance')}
          </Button>
        </div>
      </div>

      <Spin spinning={loading}>
        <Row gutter={[16, 16]} className="instances-grid">
          {list.map((instance) => (
            <Col xs={24} sm={12} lg={8} xl={8} key={instance.id}>
              <Card
                className={`instance-card ${statusBarClass(instance.statusCode)}`}
                hoverable
                onClick={() => navigate(`/instances/${instance.instanceId}`)}
                bodyStyle={{ padding: 20 }}
              >
                <div className="instance-card-header">
                  <div className="instance-card-icon">
                    <CloudServerOutlined />
                  </div>
                  <div className="instance-card-info">
                    <div className="instance-card-name">
                      <Text strong>{instance.instanceName}</Text>
                    </div>
                    <div className="instance-card-meta">
                      <span className="instance-cluster">
                        <ClusterOutlined style={{ marginRight: 4, fontSize: 12 }} />
                        {instance.clusterName}
                      </span>
                      <span className="instance-divider">·</span>
                      <span className="instance-namespace">
                        <InboxOutlined style={{ marginRight: 4, fontSize: 12 }} />
                        {instance.namespace}
                      </span>
                    </div>
                  </div>
                  <div className="instance-card-status-indicator">
                    <div className={`status-bar status-bar-${instance.statusCode}`} />
                    <Tag
                      className="instance-status-tag"
                      color={getStatusColor(instance.statusCode ?? -1)}
                    >
                      {getStatusText(instance.statusCode ?? -1, instance.status ?? '', t)}
                    </Tag>
                  </div>
                </div>

                <div className="instance-card-body">
                  <div className="instance-metrics-grid">
                    <div className="instance-metric-item">
                      <div className="instance-metric-icon">
                        <DashboardOutlined />
                      </div>
                      <div className="instance-metric-content">
                        <span className="instance-metric-label">{t('instance.spec') || '规格'}</span>
                        <span className="instance-metric-value">{getSpecText(instance, t)}</span>
                      </div>
                    </div>
                    <div className="instance-metric-item">
                      <div className="instance-metric-icon">
                        <SettingOutlined />
                      </div>
                      <div className="instance-metric-content">
                        <span className="instance-metric-label">{t('instance.replicas') || '副本'}</span>
                        <span className="instance-metric-value">{instance.replicas}</span>
                      </div>
                    </div>
                  </div>

                  {instance.statusMessage && (
                    <div className="instance-status-message">
                      <Tooltip title={instance.statusMessage} placement="topLeft">
                        <Text className="instance-status-text">
                          {instance.statusMessage}
                        </Text>
                      </Tooltip>
                    </div>
                  )}
                </div>

                <div className="instance-card-actions" onClick={(e) => e.stopPropagation()}>
                  {instance.statusCode === INSTANCE_STATUS.RUNNING && (
                    <Tooltip title={t('instance.stop')}>
                      <Button
                        className="instance-action-btn"
                        type="text"
                        icon={<PauseCircleOutlined />}
                        onClick={() => runAction(
                          () => stopInstance(instance.id),
                          'instance.stop_success',
                          'instance.stop_failed',
                        )}
                      />
                    </Tooltip>
                  )}
                  {(instance.statusCode === INSTANCE_STATUS.STOPPED
                    || instance.statusCode === INSTANCE_STATUS.ERROR) && (
                    <Tooltip title={t('instance.start')}>
                      <Button
                        className="instance-action-btn instance-action-btn-start"
                        type="text"
                        icon={<PlayCircleOutlined />}
                        onClick={() => runAction(
                          () => startInstance(instance.id),
                          'instance.start_success',
                          'instance.start_failed',
                        )}
                      />
                    </Tooltip>
                  )}
                  <Tooltip title={t('common.refresh')}>
                    <Button
                      className="instance-action-btn"
                      type="text"
                      icon={<ReloadOutlined />}
                      onClick={() => runAction(() => refreshInstanceStatus(instance.id), undefined, 'common.refresh_failed')}
                    />
                  </Tooltip>
                  <Popconfirm
                    title={t('instance.delete_confirm')}
                    onConfirm={() => runAction(
                      () => deleteInstance(instance.id),
                      'instance.delete_success',
                      'instance.delete_failed',
                    )}
                    okText={t('common.confirm')}
                    cancelText={t('common.cancel')}
                  >
                    <Tooltip title={t('common.delete')}>
                      <Button
                        className="instance-action-btn instance-action-btn-danger"
                        type="text"
                        danger
                        icon={<DeleteOutlined />}
                      />
                    </Tooltip>
                  </Popconfirm>
                </div>
              </Card>
            </Col>
          ))}
        </Row>

        {list.length === 0 && !loading && clusterCount > 0 && (
          <div className="instances-empty">
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={<Text type="secondary">{t('instance.no_instances')}</Text>}
            />
          </div>
        )}

        {list.length === 0 && !loading && clusterCount === 0 && (
          <div className="instances-empty">
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={<Text type="secondary">{t('instance.no_clusters_available')}</Text>}
            >
              <Button type="primary" onClick={() => navigate('/kubernetes')}>
                {t('instance.go_import_cluster')}
              </Button>
            </Empty>
          </div>
        )}
      </Spin>
    </div>
  );
};

export default InstancesListPage;
