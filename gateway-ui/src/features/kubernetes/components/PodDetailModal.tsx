import React, { useCallback, useEffect, useState } from 'react';
import { Button, Card, Modal, Select, Space, Spin, Table, Tabs, Tag } from 'antd';
import { message } from '../../../utils/antdStatic';
import { CloudServerOutlined, CopyOutlined, PlayCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { getPodDetail, getPodLogs, getPodYaml } from '../../../api/kubernetes';
import type { KubernetesPodDetail, KubernetesPodInfo } from '../../../types/kubernetes';

const INITIAL_TAIL_LINES = 200;

interface PodDetailModalProps {
  clusterId?: number;
  pod: KubernetesPodInfo | null;
  onClose: () => void;
}

export const PodDetailModal: React.FC<PodDetailModalProps> = ({ clusterId, pod, onClose }) => {
  const { t } = useTranslation();

  const [detail, setDetail] = useState<KubernetesPodDetail | null>(null);
  const [yaml, setYaml] = useState('');
  const [logs, setLogs] = useState('');
  const [loading, setLoading] = useState(false);
  const [activeTab, setActiveTab] = useState('info');

  // Log viewer state
  const [logMode, setLogMode] = useState<'history' | 'realtime'>('history');
  const [logTailLines, setLogTailLines] = useState(INITIAL_TAIL_LINES);
  const [logSinceSeconds, setLogSinceSeconds] = useState(0);
  const [logAutoRefresh, setLogAutoRefresh] = useState(false);
  const [logRefreshInterval, setLogRefreshInterval] = useState(3);

  // Load detail + yaml + logs whenever a new pod is selected.
  useEffect(() => {
    if (clusterId === undefined || !pod) return undefined;
    let cancelled = false;

    (async () => {
      setDetail(null);
      setYaml('');
      setLogs('');
      setActiveTab('info');
      setLogMode('history');
      setLogAutoRefresh(false);
      setLoading(true);

      try {
        const [detailData, yamlData, logsData] = await Promise.all([
          getPodDetail(clusterId, pod.namespace, pod.name),
          getPodYaml(clusterId, pod.namespace, pod.name),
          getPodLogs(clusterId, pod.namespace, pod.name, { tailLines: INITIAL_TAIL_LINES }),
        ]);
        if (cancelled) return;
        setDetail(detailData);
        setYaml(yamlData);
        setLogs(logsData || 'No logs available');
      } catch {
        if (!cancelled) message.error(t('k8s.load_failed'));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [clusterId, pod, t]);

  // Reload logs with the current viewer options.
  const loadPodLogs = useCallback(async () => {
    if (clusterId === undefined || !pod) return;
    try {
      const data = await getPodLogs(
        clusterId,
        pod.namespace,
        pod.name,
        logSinceSeconds > 0 ? { sinceSeconds: logSinceSeconds } : { tailLines: logTailLines },
      );
      const newLogs = data || 'No logs available';
      if (logMode === 'realtime' && logAutoRefresh) {
        setLogs(prev => `${prev}\n${newLogs}`);
      } else {
        setLogs(newLogs);
      }
    } catch {
      // Keep the previous logs on transient failures.
    }
  }, [clusterId, pod, logTailLines, logSinceSeconds, logMode, logAutoRefresh]);

  // Auto refresh timer (realtime mode only), with guaranteed cleanup.
  useEffect(() => {
    if (!logAutoRefresh) return undefined;
    const timer = setInterval(() => {
      void loadPodLogs();
    }, logRefreshInterval * 1000);
    return () => clearInterval(timer);
  }, [logAutoRefresh, logRefreshInterval, loadPodLogs]);

  const copyYamlToClipboard = useCallback(() => {
    navigator.clipboard.writeText(yaml);
    message.success(t('common.copied') || 'Copied to clipboard');
  }, [yaml, t]);

  const handleLogModeChange = useCallback((mode: 'history' | 'realtime') => {
    setLogMode(mode);
    if (mode === 'history') {
      setLogAutoRefresh(false);
    }
  }, []);

  const handleStartAutoRefresh = useCallback(() => {
    setLogs('');
    setLogAutoRefresh(true);
  }, []);

  return (
    <Modal
      title={
        <div className="modal-pod-title">
          <CloudServerOutlined />
          <span className="modal-pod-name">{pod?.name}</span>
          {pod && (
            <Tag color={pod.phase === 'Running' ? 'green' : pod.phase === 'Pending' ? 'blue' : 'red'}>
              {pod.phase}
            </Tag>
          )}
        </div>
      }
      open={!!pod}
      onCancel={onClose}
      footer={null}
      width={900}
      destroyOnHidden
    >
      <Spin spinning={loading}>
        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          items={[
            {
              key: 'info',
              label: t('k8s.pod_info'),
              children: detail && (
                <div className="pod-detail-container">
                  {/* Basic Info */}
                  <Card size="small" className="pod-detail-card" title={t('k8s.basic_info')}>
                    <div className="pod-info-grid">
                      <div className="pod-info-item">
                        <span className="pod-info-label">{t('k8s.namespace')}</span>
                        <span className="pod-info-value">{detail.namespace}</span>
                      </div>
                      <div className="pod-info-item">
                        <span className="pod-info-label">Pod IP</span>
                        <span className="pod-info-value">{detail.podIP}</span>
                      </div>
                      <div className="pod-info-item">
                        <span className="pod-info-label">{t('k8s.host_ip')}</span>
                        <span className="pod-info-value">{detail.hostIP}</span>
                      </div>
                      <div className="pod-info-item">
                        <span className="pod-info-label">{t('k8s.node')}</span>
                        <span className="pod-info-value">{detail.nodeName}</span>
                      </div>
                      <div className="pod-info-item">
                        <span className="pod-info-label">{t('k8s.start_time')}</span>
                        <span className="pod-info-value">{detail.startTime}</span>
                      </div>
                      <div className="pod-info-item">
                        <span className="pod-info-label">{t('k8s.service_account')}</span>
                        <span className="pod-info-value">{detail.serviceAccountName}</span>
                      </div>
                      <div className="pod-info-item">
                        <span className="pod-info-label">{t('k8s.restart_policy')}</span>
                        <span className="pod-info-value">{detail.restartPolicy}</span>
                      </div>
                    </div>
                  </Card>

                  {/* Containers */}
                  <Card size="small" className="pod-detail-card" title={t('k8s.containers')}>
                    <div className="pod-container-section">
                      {detail.containers?.map((container, idx) => (
                        <div key={idx} className="pod-container-card">
                          <div className="pod-container-title">
                            <span className="pod-container-name-text">{container.name}</span>
                            <Tag color="blue" style={{ fontSize: 11 }}>{container.image}</Tag>
                          </div>
                          <div className="pod-container-details">
                            <div className="pod-container-detail-item">
                              <span className="pod-container-detail-label">{t('k8s.image_pull_policy')}</span>
                              <span className="pod-container-detail-value">{container.imagePullPolicy}</span>
                            </div>
                            <div className="pod-container-detail-item">
                              <span className="pod-container-detail-label">{t('k8s.ready')}</span>
                              <span className="pod-container-detail-value">
                                {detail.containerStatuses?.find(s => s.name === container.name)?.ready ? (
                                  <Tag color="green" style={{ fontSize: 11 }}>{t('k8s.ready_true')}</Tag>
                                ) : (
                                  <Tag color="red" style={{ fontSize: 11 }}>{t('k8s.ready_false')}</Tag>
                                )}
                              </span>
                            </div>
                            <div className="pod-container-detail-item">
                              <span className="pod-container-detail-label">{t('k8s.restarts')}</span>
                              <span className="pod-container-detail-value">
                                {detail.containerStatuses?.find(s => s.name === container.name)?.restartCount || 0}
                              </span>
                            </div>
                            {container.ports && container.ports.length > 0 && (
                              <div className="pod-container-detail-item">
                                <span className="pod-container-detail-label">{t('k8s.ports')}</span>
                                <span className="pod-container-detail-value">
                                  {container.ports.map((p, i) => (
                                    <Tag key={i} style={{ fontSize: 11, marginRight: 4 }}>{p.containerPort}/{p.protocol}</Tag>
                                  ))}
                                </span>
                              </div>
                            )}
                          </div>
                          {container.env && container.env.length > 0 && (
                            <div style={{ marginTop: 12, paddingTop: 12, borderTop: '1px solid #3f3f46' }}>
                              <span className="pod-container-detail-label">{t('k8s.env_vars')}</span>
                              <div style={{ marginTop: 8, maxHeight: 100, overflow: 'auto' }}>
                                {container.env.map((e, i) => (
                                  <div key={i} style={{ fontSize: 12, marginBottom: 4 }}>
                                    <code style={{ color: '#60a5fa' }}>{e.name}</code>: <span style={{ color: '#a1a1aa' }}>{e.value || `(${t('k8s.from_secret')})`}</span>
                                  </div>
                                ))}
                              </div>
                            </div>
                          )}
                        </div>
                      ))}
                    </div>
                  </Card>

                  {/* Conditions */}
                  {detail.conditions && detail.conditions.length > 0 && (
                    <Card size="small" className="pod-detail-card" title={t('k8s.conditions')} style={{ marginTop: 16 }}>
                      <Table
                        dataSource={detail.conditions}
                        rowKey="type"
                        size="small"
                        pagination={false}
                        columns={[
                          { title: t('k8s.type'), dataIndex: 'type', width: 150 },
                          { title: t('k8s.status'), dataIndex: 'status', width: 80, render: (s: string) => <Tag color={s === 'True' ? 'green' : 'red'}>{s}</Tag> },
                          { title: t('k8s.reason'), dataIndex: 'reason', width: 120 },
                          { title: t('k8s.message'), dataIndex: 'message', ellipsis: true },
                        ]}
                      />
                    </Card>
                  )}

                  {/* Events */}
                  {detail.events && detail.events.length > 0 && (
                    <Card size="small" className="pod-detail-card" title={t('k8s.events')} style={{ marginTop: 16 }}>
                      <div className="pod-events-section">
                        {detail.events.map((event, idx) => (
                          <div key={idx} className="pod-event-card">
                            <div className="pod-event-header-row">
                              <Tag color={event.type === 'Normal' ? 'blue' : 'orange'} style={{ fontSize: 11 }}>{event.reason}</Tag>
                              <span className="pod-event-timestamp">{event.lastTimestamp}</span>
                            </div>
                            <div className="pod-event-message-text">{event.message}</div>
                          </div>
                        ))}
                      </div>
                    </Card>
                  )}
                </div>
              ),
            },
            {
              key: 'yaml',
              label: 'YAML',
              children: (
                <div className="pod-code-container">
                  <div className="pod-code-header">
                    <span style={{ color: '#a1a1aa', fontSize: 13 }}>YAML Definition</span>
                    <Button className="copy-yaml-btn" icon={<CopyOutlined />} onClick={copyYamlToClipboard}>
                      {t('common.copy')}
                    </Button>
                  </div>
                  <div className="pod-code-body">
                    <pre className="pod-code-text">{yaml}</pre>
                  </div>
                </div>
              ),
            },
            {
              key: 'logs',
              label: t('k8s.logs'),
              children: (
                <div className="pod-code-container">
                  <div className="pod-code-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
                    <Space size="small">
                      <span style={{ color: '#a1a1aa', fontSize: 13 }}>Container Logs</span>
                      <Select
                        value={logMode}
                        onChange={handleLogModeChange}
                        style={{ width: 120 }}
                        options={[
                          { value: 'history', label: t('k8s.log_history') },
                          { value: 'realtime', label: t('k8s.log_realtime') },
                        ]}
                      />
                    </Space>
                    <Space size="small">
                      {logMode === 'history' && (
                        <>
                          <Select
                            value={logTailLines}
                            onChange={setLogTailLines}
                            style={{ width: 100 }}
                            options={[
                              { value: 100, label: '100 lines' },
                              { value: 200, label: '200 lines' },
                              { value: 500, label: '500 lines' },
                              { value: 1000, label: '1000 lines' },
                            ]}
                          />
                          <Select
                            value={logSinceSeconds}
                            onChange={setLogSinceSeconds}
                            style={{ width: 120 }}
                            options={[
                              { value: 0, label: 'All time' },
                              { value: 300, label: '5 min' },
                              { value: 600, label: '10 min' },
                              { value: 1800, label: '30 min' },
                              { value: 3600, label: '1 hour' },
                            ]}
                          />
                          <Button
                            icon={<ReloadOutlined />}
                            onClick={loadPodLogs}
                            loading={loading}
                          >
                            {t('common.refresh')}
                          </Button>
                        </>
                      )}
                      {logMode === 'realtime' && (
                        <>
                          <Select
                            value={logRefreshInterval}
                            onChange={setLogRefreshInterval}
                            style={{ width: 100 }}
                            options={[
                              { value: 1, label: '1s' },
                              { value: 3, label: '3s' },
                              { value: 5, label: '5s' },
                              { value: 10, label: '10s' },
                            ]}
                          />
                          <Button
                            type={logAutoRefresh ? 'primary' : 'default'}
                            icon={<PlayCircleOutlined />}
                            onClick={() => (logAutoRefresh ? setLogAutoRefresh(false) : handleStartAutoRefresh())}
                          >
                            {logAutoRefresh ? t('k8s.log_stop') : t('k8s.log_start')}
                          </Button>
                          <Button
                            icon={<ReloadOutlined />}
                            onClick={() => {
                              setLogs('');
                              void loadPodLogs();
                            }}
                          >
                            {t('k8s.log_clear')}
                          </Button>
                        </>
                      )}
                    </Space>
                  </div>
                  <div className="pod-code-body" style={{ maxHeight: logMode === 'realtime' ? '400px' : '500px', overflow: 'auto' }}>
                    <pre className="pod-code-text" style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{logs}</pre>
                  </div>
                </div>
              ),
            },
          ]}
        />
      </Spin>
    </Modal>
  );
};
