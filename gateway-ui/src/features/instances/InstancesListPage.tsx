/**
 * Instances list page.
 * Cards surface the fields the backend actually maintains: effective access URL,
 * heartbeat freshness (10s cycle) and instance ID. Spec/replicas are Kubernetes-only
 * and stay hidden for externally managed instances, where they are just defaults.
 */
import React, { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  Input,
  Popconfirm,
  Row,
  Space,
  Spin,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import { message } from '../../utils/antdStatic';
import {
  CloudServerOutlined,
  CopyOutlined,
  DeleteOutlined,
  InboxOutlined,
  LinkOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  ArrowRightOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import copy from 'copy-to-clipboard';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { usePolling, useRequest } from '../../hooks/useRequest';
import {
  deleteInstance,
  listInstances,
  refreshInstanceStatus,
  startInstance,
  stopInstance,
} from '../../api/instances';
import { getClusters } from '../../api/kubernetes';
import { INSTANCE_STATUS } from '../../types/instance';
import type { GatewayInstance } from '../../types/instance';
import {
  getEffectiveAccessUrl,
  getStatusColor,
  getStatusText,
  getSpecText,
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
    text: missed > 0 ? `${relative} · ${t('instance.missed_heartbeats', { n: missed })}` : relative,
  };
};

const InstancesListPage: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [now, setNow] = useState(() => Date.now());
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<number | null>(null);

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

  const stats = useMemo(
    () => ({
      total: list.length,
      running: list.filter((i) => i.statusCode === INSTANCE_STATUS.RUNNING).length,
      error: list.filter((i) => i.statusCode === INSTANCE_STATUS.ERROR).length,
      stopped: list.filter((i) => i.statusCode === INSTANCE_STATUS.STOPPED).length,
    }),
    [list],
  );

  const visibleList = useMemo(() => {
    const query = search.trim().toLowerCase();
    return list.filter(
      (instance) =>
        (statusFilter === null || instance.statusCode === statusFilter) &&
        (!query ||
          [
            instance.instanceName,
            instance.instanceId,
            instance.namespace,
            getEffectiveAccessUrl(instance),
          ].some((value) => value?.toLowerCase().includes(query))),
    );
  }, [list, search, statusFilter]);
  const staleCount = list.filter((instance) => {
    const heartbeat = heartbeatView(instance, now, t);
    return heartbeat.tone === 'warn' || heartbeat.tone === 'bad';
  }).length;

  const statItems = [
    {
      key: 'total',
      label: 'instance.stat_total',
      value: stats.total,
      status: null,
      icon: <CloudServerOutlined />,
      hint: 'instance.total_hint',
    },
    {
      key: 'running',
      label: 'instance.stat_running',
      value: stats.running,
      status: INSTANCE_STATUS.RUNNING,
      icon: <CheckCircleOutlined />,
      hint: 'instance.running_hint',
    },
    {
      key: 'error',
      label: 'instance.stat_error',
      value: stats.error,
      status: INSTANCE_STATUS.ERROR,
      icon: <CloseCircleOutlined />,
      hint: 'instance.error_hint',
    },
    {
      key: 'stopped',
      label: 'instance.stat_stopped',
      value: stats.stopped,
      status: INSTANCE_STATUS.STOPPED,
      icon: <PauseCircleOutlined />,
      hint: 'instance.stopped_hint',
    },
  ];

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
      const msg =
        error instanceof ApiError && error.message
          ? error.message
          : t(failKey || 'common.operation_failed');
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
          <span className="instances-eyebrow">{t('instance.workspace_label')}</span>
          <div className="instances-page-title">
            <span className="instances-page-title-text">{t('menu.instances')}</span>
          </div>
          <Text className="instances-page-subtitle">{t('instance.page_subtitle')}</Text>
        </div>
        <div className="instances-page-actions">
          <Tooltip title={t('instance.auto_refresh_hint')}>
            <Button
              icon={<ReloadOutlined spin={instances.loading} />}
              onClick={() => instances.run()}
            >
              {t('common.refresh')}
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

      <div className="instances-stats" aria-label={t('instance.overview_label')}>
        {statItems.map((item) => (
          <button
            key={item.key}
            type="button"
            className={`instances-stat stat-${item.key} ${statusFilter === item.status ? 'is-selected' : ''}`}
            aria-pressed={statusFilter === item.status}
            onClick={() => setStatusFilter(statusFilter === item.status ? null : item.status)}
          >
            <span className="instances-stat-top">
              <span>{t(item.label)}</span>
              <span className="instances-stat-icon">{item.icon}</span>
            </span>
            <b>{instances.data ? item.value : '—'}</b>
            <span className="instances-stat-hint">{t(item.hint)}</span>
          </button>
        ))}
      </div>

      <div className="instances-inventory-header">
        <div>
          <h2>
            {t('instance.inventory_title')}
            <span>{visibleList.length}</span>
          </h2>
          <p>
            {updatedAt
              ? t('instance.list_updated_at', { time: updatedAt.toLocaleTimeString() })
              : t('instance.auto_refresh_hint')}
          </p>
        </div>
        <Input
          className="instances-search"
          prefix={<SearchOutlined />}
          allowClear
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          placeholder={t('instance.search_placeholder')}
          aria-label={t('instance.search_placeholder')}
        />
      </div>
      {statusFilter !== null && (
        <div className="instances-filter-note">
          <span>
            {t('instance.filtered_status', { status: getStatusText(statusFilter, '', t) })}
          </span>
          <Button type="link" size="small" onClick={() => setStatusFilter(null)}>
            {t('instance.clear_filter')}
          </Button>
        </div>
      )}
      {staleCount > 0 && (
        <Alert
          className="instances-heartbeat-alert"
          type="warning"
          showIcon
          message={t('instance.stale_notice', { count: staleCount })}
        />
      )}

      <Spin spinning={loading && !instances.data}>
        <Row gutter={[16, 16]} className="instances-grid">
          {visibleList.map((instance) => {
            // An externally managed instance has no Deployment, so the console cannot start or
            // stop it - offering the buttons would only produce a 400.
            const hasCluster = instance.clusterId != null;
            const accessUrl = getEffectiveAccessUrl(instance);
            const heartbeat = heartbeatView(instance, now, t);
            return (
              <Col xs={24} lg={12} xxl={8} key={instance.id}>
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
                            aria-label={t('common.copy')}
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
                            aria-label={t('common.copy')}
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
                        <Text className="instance-status-text">{instance.statusMessage}</Text>
                      </Tooltip>
                    </div>
                  )}

                  <div className="instance-card-actions" onClick={(e) => e.stopPropagation()}>
                    <Button
                      className="instance-open-button"
                      type="link"
                      onClick={() => navigate(`/instances/${instance.instanceId}`)}
                    >
                      {t('instance.open_console')}
                      <ArrowRightOutlined />
                    </Button>
                    {hasCluster && instance.statusCode === INSTANCE_STATUS.RUNNING && (
                      <Tooltip title={t('instance.stop')}>
                        <Button
                          className="instance-action-btn"
                          type="text"
                          aria-label={t('instance.stop')}
                          icon={<PauseCircleOutlined />}
                          onClick={() =>
                            runAction(
                              () => stopInstance(instance.id),
                              'instance.stop_success',
                              'instance.stop_failed',
                            )
                          }
                        />
                      </Tooltip>
                    )}
                    {hasCluster &&
                      (instance.statusCode === INSTANCE_STATUS.STOPPED ||
                        instance.statusCode === INSTANCE_STATUS.ERROR) && (
                        <Tooltip title={t('instance.start')}>
                          <Button
                            className="instance-action-btn instance-action-btn-start"
                            type="text"
                            aria-label={t('instance.start')}
                            icon={<PlayCircleOutlined />}
                            onClick={() =>
                              runAction(
                                () => startInstance(instance.id),
                                'instance.start_success',
                                'instance.start_failed',
                              )
                            }
                          />
                        </Tooltip>
                      )}
                    <Tooltip title={t('common.refresh')}>
                      <Button
                        className="instance-action-btn"
                        type="text"
                        aria-label={t('common.refresh')}
                        icon={<ReloadOutlined />}
                        onClick={() =>
                          runAction(
                            () => refreshInstanceStatus(instance.id),
                            undefined,
                            'common.refresh_failed',
                          )
                        }
                      />
                    </Tooltip>
                    <Popconfirm
                      title={t('instance.delete_confirm')}
                      onConfirm={() =>
                        runAction(
                          () => deleteInstance(instance.id),
                          'instance.delete_success',
                          'instance.delete_failed',
                        )
                      }
                      okText={t('common.confirm')}
                      cancelText={t('common.cancel')}
                    >
                      <Tooltip title={t('common.delete')}>
                        <Button
                          className="instance-action-btn instance-action-btn-danger"
                          type="text"
                          danger
                          aria-label={t('common.delete')}
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
        {list.length > 0 && visibleList.length === 0 && (
          <div className="instances-empty">
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('instance.no_matches')}>
              <Button
                onClick={() => {
                  setSearch('');
                  setStatusFilter(null);
                }}
              >
                {t('instance.reset_search')}
              </Button>
            </Empty>
          </div>
        )}

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
