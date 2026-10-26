/**
 * Instances list page.
 * Cards surface the fields the backend actually maintains: effective access URL,
 * heartbeat freshness (10s cycle) and instance ID. Spec/replicas are Kubernetes-only
 * and stay hidden for externally managed instances, where they are just defaults.
 */
import React, { useEffect, useMemo, useState } from 'react';
import {
  Button, Card, Col, Empty, Popconfirm, Row, Space, Spin, Tag, Tooltip, Typography,
} from 'antd';
import { message } from '../../utils/antdStatic';
import {
  CloudServerOutlined, CopyOutlined, DeleteOutlined,
  InboxOutlined, LinkOutlined, PauseCircleOutlined, PlayCircleOutlined, PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import copy from 'copy-to-clipboard';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { usePolling, useRequest } from '../../hooks/useRequest';
import {
  deleteInstance, listInstances, refreshInstanceStatus, startInstance, stopInstance,
} from '../../api/instances';
import { getClusters } from '../../api/kubernetes';
import { INSTANCE_STATUS } from '../../types/instance';
import type { GatewayInstance } from '../../types/instance';
import {
  getEffectiveAccessUrl, getStatusColor, getStatusText, getSpecText,
} from '../../utils/instance';
import { ApiError } from '../../api/http';
import { parseServerTime } from '../../utils/serverTime';
import './instances.css';

const { Text } = Typography;

/** Heartbeats arrive every 10s and the list polls every 30s; stay above both cycles
 *  so a healthy instance never flickers into "stale" between polls. */
const HEARTBEAT_FRESH_MS = 45_000;
const LIST_POLL_MS = 30_000;

type TFunc = (key: string, options?: Record<string, unknown>) => string;

const formatRelative = (elapsedMs: number, t: TFunc): string => {
  if (elapsedMs < 5_000) return t('instance.time_just_now');
  const seconds = Math.floor(elapsedMs / 1_000);
  if (seconds < 60) return t('instance.time_seconds_ago', { n: seconds });
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return t('instance.time_minutes_ago', { n: minutes });
  return t('instance.time_hours_ago', { n: Math.floor(minutes / 60) });
};

interface HeartbeatView {
  tone: 'ok' | 'warn' | 'bad' | 'idle';
  text: string;
}

const heartbeatView = (instance: GatewayInstance, now: number, t: TFunc): HeartbeatView => {
  const raw = instance.lastHeartbeatTime
    ? parseServerTime(instance.lastHeartbeatTime).getTime()
    : Number.NaN;
  if (Number.isNaN(raw)) {
    return { tone: 'idle', text: t('instance.heartbeat_never') };
  }
  const relative = formatRelative(Math.max(0, now - raw), t);
  if (instance.statusCode !== INSTANCE_STATUS.RUNNING) {
    return { tone: 'idle', text: relative };
  }
  const missed = instance.missedHeartbeats ?? 0;
  if (missed === 0 && now - raw <= HEARTBEAT_FRESH_MS) {
    return { tone: 'ok', text: relative };
  }
  return {
    tone: missed > 0 ? 'bad' : 'warn',
    text: missed > 0
      ? `${relative} · ${t('instance.missed_heartbeats', { n: missed })}`
      : relative,
  };
};

const InstancesListPage: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [now, setNow] = useState(() => Date.now());
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);

  // Ticks the "x seconds ago" heartbeat labels without refetching.
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 5_000);
    return () => window.clearInterval(timer);
  }, []);

  const clusters = useRequest((signal) => getClusters({ signal }));
  const instances = usePolling((signal) => listInstances({ signal }), {
    intervalMs: LIST_POLL_MS,
    onSuccess: () => setUpdatedAt(new Date()),
  });

  const list = instances.data ?? [];
  const clusterCount = clusters.data?.length ?? 0;
  const loading = instances.loading || clusters.loading;

  const stats = useMemo(() => ({
    total: list.length,
    running: list.filter((i) => i.statusCode === INSTANCE_STATUS.RUNNING).length,
    error: list.filter((i) => i.statusCode === INSTANCE_STATUS.ERROR).length,
    stopped: list.filter((i) => i.statusCode === INSTANCE_STATUS.STOPPED).length,
  }), [list]);

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

  const copyText = (value: string, successKey: string) => {
    copy(value);
    message.success(t(successKey));
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
          <Tooltip title={t('instance.auto_refresh_hint')}>
            <Button
              icon={<ReloadOutlined spin={instances.loading} />}
              onClick={() => instances.run()}
            >
              {updatedAt
                ? t('instance.list_updated_at', { time: updatedAt.toLocaleTimeString() })
                : t('common.refresh')}
            </Button>
          </Tooltip>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => navigate('/instances/create')}
            size="large"
          >
            {t('instance.create_instance')}
          </Button>
        </div>
      </div>

      {list.length > 0 && (
        <div className="instances-stats">
          <span className="instances-stat">
            <i className="instances-stat-dot dot-total" />
            {t('instance.stat_total')}
            <b>{stats.total}</b>
          </span>
          <span className="instances-stat">
            <i className="instances-stat-dot dot-running" />
            {t('instance.stat_running')}
            <b>{stats.running}</b>
          </span>
          <span className="instances-stat">
            <i className="instances-stat-dot dot-error" />
            {t('instance.stat_error')}
            <b>{stats.error}</b>
          </span>
          <span className="instances-stat">
            <i className="instances-stat-dot dot-stopped" />
            {t('instance.stat_stopped')}
            <b>{stats.stopped}</b>
          </span>
        </div>
      )}

      <Spin spinning={loading}>
        <Row gutter={[16, 16]} className="instances-grid">
          {list.map((instance) => {
            // An externally managed instance has no Deployment, so the console cannot start or
            // stop it - offering the buttons would only produce a 400.
            const hasCluster = instance.clusterId != null;
            const accessUrl = getEffectiveAccessUrl(instance);
            const heartbeat = heartbeatView(instance, now, t);
            return (
              <Col xs={24} md={12} key={instance.id}>
                <Card
                  className="instance-card"
                  hoverable
                  onClick={() => navigate(`/instances/${instance.instanceId}`)}
                >
                  <div className="instance-card-header">
                    <div className="instance-card-icon">
                      <CloudServerOutlined />
                    </div>
                    <div className="instance-card-info">
                      <div className="instance-card-name">
                        <Text strong ellipsis={{ tooltip: instance.instanceName }}>
                          {instance.instanceName}
                        </Text>
                        <Tag
                          className={`instance-type-tag ${hasCluster ? 'type-cluster' : 'type-external'}`}
                        >
                          {hasCluster ? instance.clusterName : t('instance.external_tag')}
                        </Tag>
                      </div>
                      <div className="instance-card-meta">
                        <span className="instance-namespace">
                          <InboxOutlined style={{ marginRight: 4, fontSize: 12 }} />
                          {instance.namespace}
                        </span>
                      </div>
                    </div>
                    <div className="instance-card-status">
                      <span className={`instance-status-dot status-dot-${instance.statusCode}`} />
                      <Tag
                        className="instance-status-tag"
                        color={getStatusColor(instance.statusCode ?? -1)}
                      >
                        {getStatusText(instance.statusCode ?? -1, instance.status ?? '', t)}
                      </Tag>
                    </div>
                  </div>

                  <div className="instance-access-row">
                    <span className="instance-access-label">{t('instance.access_url')}</span>
                    {accessUrl ? (
                      <>
                        <Tooltip title={t('instance.click_to_open')}>
                          <a
                            className="instance-access-url"
                            href={accessUrl}
                            target="_blank"
                            rel="noopener noreferrer"
                            onClick={(e) => e.stopPropagation()}
                          >
                            <LinkOutlined />
                            {accessUrl}
                          </a>
                        </Tooltip>
                        <Tooltip title={t('common.copy')}>
                          <Button
                            className="instance-mini-btn"
                            type="text"
                            size="small"
                            icon={<CopyOutlined />}
                            onClick={(e) => {
                              e.stopPropagation();
                              copyText(accessUrl, 'instance.url_copied');
                            }}
                          />
                        </Tooltip>
                      </>
                    ) : (
                      <Text type="secondary" className="instance-access-empty">
                        {t('instance.no_access_url')}
                      </Text>
                    )}
                  </div>

                  <div className="instance-cells">
                    <div className="instance-cell">
                      <span className="instance-cell-label">{t('instance.last_heartbeat')}</span>
                      <span className={`instance-cell-value hb-${heartbeat.tone}`}>
                        <i className={`heartbeat-dot heartbeat-${heartbeat.tone}`} />
                        {heartbeat.text}
                      </span>
                    </div>
                    <div className="instance-cell">
                      <span className="instance-cell-label">{t('instance.instance_id')}</span>
                      <span className="instance-cell-value">
                        <Tooltip title={instance.instanceId}>
                          <span className="instance-id-text">{instance.instanceId}</span>
                        </Tooltip>
                        <Tooltip title={t('common.copy')}>
                          <Button
                            className="instance-mini-btn"
                            type="text"
                            size="small"
                            icon={<CopyOutlined />}
                            onClick={(e) => {
                              e.stopPropagation();
                              copyText(instance.instanceId, 'instance.copied');
                            }}
                          />
                        </Tooltip>
                      </span>
                    </div>
                    {hasCluster && (
                      <>
                        <div className="instance-cell">
                          <span className="instance-cell-label">{t('instance.spec')}</span>
                          <span className="instance-cell-value">{getSpecText(instance, t)}</span>
                        </div>
                        <div className="instance-cell">
                          <span className="instance-cell-label">{t('instance.replicas')}</span>
                          <span className="instance-cell-value">{instance.replicas}</span>
                        </div>
                      </>
                    )}
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

                  <div className="instance-card-actions" onClick={(e) => e.stopPropagation()}>
                    {hasCluster && instance.statusCode === INSTANCE_STATUS.RUNNING && (
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
                    {hasCluster && (instance.statusCode === INSTANCE_STATUS.STOPPED
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
            );
          })}
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
              <Space>
                <Button onClick={() => navigate('/kubernetes')}>
                  {t('instance.go_import_cluster')}
                </Button>
                <Button type="primary" onClick={() => navigate('/instances/create')}>
                  {t('instance.register_external')}
                </Button>
              </Space>
            </Empty>
          </div>
        )}
      </Spin>
    </div>
  );
};

export default InstancesListPage;
