/**
 * Overview tab — stat cards, cluster health score popover, basic info.
 * Extracted from the old monolithic InstanceDetailPage.
 */
import React, { useState } from 'react';
import {
  Badge, Button, Card, Col, Descriptions, Popover, Progress, Row,
  Space, Spin, Statistic, Tag, Tooltip, Typography
} from 'antd';
import {
  AlertOutlined, BugOutlined, DownOutlined, LinkOutlined,
  ReloadOutlined, RightOutlined, SafetyOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../../hooks/useRequest';
import { getInstanceHealthScore } from '../../../api/instances';
import {
  getEffectiveAccessUrl, getEffectiveNacosServerAddr, getSpecText,
  getStatusTextByName,
} from '../../../utils/instance';
import { useInstanceDetail } from '../InstanceDetailContext';

const { Text } = Typography;

/** Health score → progress color. */
const getHealthColor = (score: number): string => {
  if (score >= 90) return '#52c41a';
  if (score >= 80) return '#73d13d';
  if (score >= 60) return '#faad14';
  if (score >= 40) return '#fa8c16';
  return '#f5222d';
};

/** Health score → tag color. */
const getHealthTagColor = (score: number): string => {
  if (score >= 90) return 'success';
  if (score >= 80) return 'green';
  if (score >= 60) return 'warning';
  if (score >= 40) return 'orange';
  return 'error';
};

const HealthScorePopover: React.FC = () => {
  const { t } = useTranslation();
  const { instance } = useInstanceDetail();
  const [expanded, setExpanded] = useState(false);

  const healthRequest = useRequest(
    (signal) => getInstanceHealthScore(instance.id, { signal }),
    { deps: [instance.id] },
  );
  const health = healthRequest.data;
  const score = health?.score ?? null;
  const podScore = health?.podHealth?.score ?? 0;

  return (
    <div className="text-center">
      <div style={{ marginBottom: 8, color: 'var(--text-muted)', fontSize: 12 }}>
        {t('instance.health_score')}
      </div>
      {healthRequest.loading && !health ? (
        <Spin size="small" />
      ) : score !== null ? (
        <Popover
          content={(
            <div style={{ width: 340 }}>
              {/* Header with score */}
              <div style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                padding: '12px 0',
                borderBottom: '1px solid #f0f0f0',
                marginBottom: 12,
              }}
              >
                <div>
                  <div style={{ fontSize: 13, fontWeight: 600, color: '#262626' }}>
                    {t('instance.health_score')}
                  </div>
                  <div style={{ fontSize: 12, color: '#8c8c8c', marginTop: 4 }}>
                    {health?.clusterName}
                    {' / '}
                    {health?.namespace}
                  </div>
                </div>
                <div style={{ textAlign: 'right' }}>
                  <div style={{ fontSize: 24, fontWeight: 'bold', color: getHealthColor(score) }}>
                    {score}
                  </div>
                  <Tag color={getHealthTagColor(score)} style={{ marginTop: 4 }}>
                    {health?.level}
                  </Tag>
                </div>
              </div>

              {/* Node / Pod health grid */}
              <Row gutter={[12, 12]} style={{ marginBottom: 12 }}>
                <Col span={12}>
                  <div style={{
                    background: '#f6ffed',
                    borderRadius: 8,
                    padding: '10px 12px',
                    border: '1px solid #b7eb8f',
                  }}
                  >
                    <div className="flex-items-center mb-2">
                      <Badge status={health?.nodeHealth?.score === 100 ? 'success' : 'warning'} />
                      <span style={{ fontSize: 12, fontWeight: 500, marginLeft: 6 }}>
                        {t('instance.node_health')}
                      </span>
                    </div>
                    <div style={{ fontSize: 16, fontWeight: 'bold', color: '#52c41a' }}>
                      {health?.nodeHealth?.readyNodes || 0}
                      /
                      {health?.nodeHealth?.totalNodes || 0}
                    </div>
                    <Progress
                      percent={health?.nodeHealth?.score || 0}
                      size="small"
                      showInfo={false}
                      strokeColor="#52c41a"
                      trailColor="#d9f7be"
                    />
                  </div>
                </Col>

                <Col span={12}>
                  <div style={{
                    background: podScore >= 80 ? '#f6ffed' : '#fffbe6',
                    borderRadius: 8,
                    padding: '10px 12px',
                    border: `1px solid ${podScore >= 80 ? '#b7eb8f' : '#ffe58f'}`,
                  }}
                  >
                    <div className="flex-items-center mb-2">
                      <Badge status={podScore >= 80 ? 'success' : 'warning'} />
                      <span style={{ fontSize: 12, fontWeight: 500, marginLeft: 6 }}>
                        {t('instance.pod_health')}
                      </span>
                    </div>
                    <div style={{ fontSize: 16, fontWeight: 'bold', color: podScore >= 80 ? '#52c41a' : '#faad14' }}>
                      {health?.podHealth?.runningPods || 0}
                      /
                      {health?.podHealth?.totalPods || 0}
                    </div>
                    <div style={{ fontSize: 11, color: '#8c8c8c', marginTop: 4 }}>
                      {t('instance.restarts')}
                      :
                      {' '}
                      {health?.podHealth?.totalRestarts || 0}
                    </div>
                    <Progress
                      percent={health?.podHealth?.score || 0}
                      size="small"
                      showInfo={false}
                      strokeColor={podScore >= 80 ? '#52c41a' : '#faad14'}
                      trailColor={podScore >= 80 ? '#d9f7be' : '#fff1b8'}
                    />
                  </div>
                </Col>
              </Row>

              {/* Issues */}
              {(health?.issues?.length ?? 0) > 0 && (
                <div style={{
                  marginBottom: 12,
                  background: '#fff2f0',
                  borderRadius: 8,
                  padding: '10px 12px',
                  border: '1px solid #ffccc7',
                }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', marginBottom: 8 }}>
                    <AlertOutlined style={{ color: '#ff4d4f', fontSize: 14 }} />
                    <span style={{ fontWeight: 500, marginLeft: 8, color: '#ff4d4f' }}>
                      {t('instance.issues')}
                      {' ('}
                      {health!.issues!.length}
                      )
                    </span>
                  </div>
                  <div style={{ maxHeight: expanded ? 200 : 80, overflowY: 'auto' }}>
                    {health!.issues!.slice(0, expanded ? health!.issues!.length : 3).map((issue, idx) => (
                      <div
                        key={idx}
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          marginBottom: 4,
                          padding: '4px 8px',
                          background: '#fff',
                          borderRadius: 4,
                          fontSize: 11,
                        }}
                      >
                        <Tag
                          color={issue.severity === 'critical' ? 'error' : issue.severity === 'high' ? 'warning' : 'default'}
                          style={{ fontSize: 10, marginRight: 8 }}
                        >
                          {issue.severity}
                        </Tag>
                        <span style={{ color: '#595959', flex: 1 }}>{issue.message}</span>
                      </div>
                    ))}
                  </div>
                  {health!.issues!.length > 3 && (
                    <div
                      style={{
                        textAlign: 'center',
                        color: '#1890ff',
                        fontSize: 11,
                        padding: 4,
                        cursor: 'pointer',
                        marginTop: 4,
                      }}
                      onClick={() => setExpanded(!expanded)}
                    >
                      {expanded ? (
                        <>
                          {t('instance.collapse')}
                          {' '}
                          <DownOutlined style={{ marginLeft: 4 }} />
                        </>
                      ) : (
                        <>
                          +
                          {health!.issues!.length - 3}
                          {' '}
                          {t('instance.more_issues')}
                          {' '}
                          <RightOutlined style={{ marginLeft: 4 }} />
                        </>
                      )}
                    </div>
                  )}
                </div>
              )}

              {/* Problematic pods */}
              {(health?.podHealth?.problematicPods?.length ?? 0) > 0 && (
                <div style={{
                  marginBottom: 12,
                  background: '#fafafa',
                  borderRadius: 8,
                  padding: '10px 12px',
                }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', marginBottom: 8 }}>
                    <BugOutlined style={{ color: '#fa8c16', fontSize: 14 }} />
                    <span style={{ fontWeight: 500, marginLeft: 8, color: '#595959' }}>
                      {t('instance.problematic_pods')}
                      {' ('}
                      {health!.podHealth!.problematicPods!.length}
                      )
                    </span>
                  </div>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                    {health!.podHealth!.problematicPods!.slice(0, expanded ? health!.podHealth!.problematicPods!.length : 4).map((pod, idx) => (
                      <Tag key={idx} style={{ fontSize: 11, borderRadius: 4 }}>
                        {pod.name?.substring(0, 20)}
                        ... (
                        {pod.restartCount}
                        {t('instance.times')}
                        )
                      </Tag>
                    ))}
                  </div>
                  {health!.podHealth!.problematicPods!.length > 4 && (
                    <div
                      style={{
                        textAlign: 'center',
                        color: '#1890ff',
                        fontSize: 11,
                        padding: 4,
                        cursor: 'pointer',
                        marginTop: 6,
                      }}
                      onClick={() => setExpanded(!expanded)}
                    >
                      {expanded ? (
                        <>
                          {t('instance.collapse')}
                          {' '}
                          <DownOutlined style={{ marginLeft: 4 }} />
                        </>
                      ) : (
                        <>
                          +
                          {health!.podHealth!.problematicPods!.length - 4}
                          {' '}
                          {t('instance.more_pods')}
                          {' '}
                          <RightOutlined style={{ marginLeft: 4 }} />
                        </>
                      )}
                    </div>
                  )}
                </div>
              )}

              {/* Recommendations */}
              {(health?.recommendations?.length ?? 0) > 0 && (
                <div style={{
                  background: '#e6f7ff',
                  borderRadius: 8,
                  padding: '10px 12px',
                  border: '1px solid #91d5ff',
                }}
                >
                  <div className="flex-items-center mb-2">
                    <SafetyOutlined style={{ color: '#1890ff', fontSize: 14 }} />
                    <span style={{ fontWeight: 500, marginLeft: 8, color: '#1890ff' }}>
                      {t('instance.recommendations')}
                    </span>
                  </div>
                  <ul style={{ margin: 0, paddingLeft: 18, fontSize: 11, color: '#595959' }}>
                    {health!.recommendations!.slice(0, 2).map((rec, idx) => (
                      <li key={idx} style={{ marginBottom: 2 }}>{rec}</li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          )}
          title={null}
          placement="bottom"
          trigger="hover"
          overlayStyle={{ maxWidth: 380 }}
        >
          <div style={{ cursor: 'pointer' }}>
            <Progress
              type="circle"
              percent={score}
              size={80}
              strokeColor={getHealthColor(score)}
              format={(percent) => (
                <span style={{ fontWeight: 'bold', fontSize: 18 }}>{percent}</span>
              )}
            />
            <div style={{ marginTop: 8, fontSize: 12 }}>
              <Tag color={getHealthTagColor(score)}>{health?.level}</Tag>
            </div>
          </div>
        </Popover>
      ) : (
        <Text type="secondary">{t('instance.health_unavailable')}</Text>
      )}
      <Button
        size="small"
        type="link"
        icon={<ReloadOutlined />}
        onClick={() => healthRequest.run()}
        loading={healthRequest.loading}
        style={{ marginTop: 8 }}
      />
    </div>
  );
};

const OverviewTab: React.FC = () => {
  const { t } = useTranslation();
  const { instance, globalNacosAddr } = useInstanceDetail();

  const isRunning = instance.status === 'Running' || instance.status === 'RUNNING';
  const accessUrl = getEffectiveAccessUrl(instance);
  const nacosAddr = instance.nacosServerAddr
    ? getEffectiveNacosServerAddr(instance)
    : globalNacosAddr;

  let accessUrlSource = t('instance.access_url_default');
  if (instance.manualAccessUrl) {
    accessUrlSource = t('instance.access_url_manual');
  } else if (instance.discoveredAccessUrl) {
    accessUrlSource = t('instance.access_url_discovered');
  } else if (instance.reportedAccessUrl) {
    accessUrlSource = t('instance.access_url_reported');
  }

  return (
    <div className="instance-overview-stats">
      <Row gutter={[24, 24]}>
        <Col xs={24} md={12} lg={6}>
          <Card className="instance-stat-card">
            <Statistic
              title={t('instance.status')}
              value={getStatusTextByName(instance.status ?? '', t)}
              valueStyle={{ color: isRunning ? 'var(--success-color)' : 'var(--warning-color)' }}
              prefix={<Badge status={isRunning ? 'success' : 'warning'} />}
            />
          </Card>
        </Col>
        <Col xs={24} md={12} lg={6}>
          <Card className="instance-stat-card">
            <Statistic
              title={t('instance.replicas')}
              value={instance.replicas}
              suffix="Pods"
              valueStyle={{ color: 'var(--primary-color)' }}
            />
          </Card>
        </Col>
        <Col xs={24} md={12} lg={6}>
          <Card className="instance-stat-card">
            <Statistic
              title={t('instance.spec')}
              value={getSpecText(instance, t)}
              valueStyle={{ color: 'var(--text-primary)' }}
            />
          </Card>
        </Col>
        <Col xs={24} md={12} lg={6}>
          <Card className="instance-stat-card">
            <HealthScorePopover />
          </Card>
        </Col>
      </Row>

      <Card className="instance-info-section" title={t('instance.basic_info')}>
        <Descriptions column={{ xs: 1, sm: 2, md: 3 }}>
          <Descriptions.Item label={t('instance.instance_id')}>
            <Text copyable>{instance.instanceId}</Text>
          </Descriptions.Item>
          <Descriptions.Item label={t('instance.cluster')}>
            {instance.clusterName}
          </Descriptions.Item>
          <Descriptions.Item label={t('instance.namespace')}>
            {instance.namespace}
          </Descriptions.Item>
          <Descriptions.Item label={t('instance.nacos_namespace')}>
            <Text copyable>{instance.nacosNamespace}</Text>
          </Descriptions.Item>
          <Descriptions.Item label={t('instance.image')}>
            {instance.image}
          </Descriptions.Item>
          <Descriptions.Item label={t('instance.deployment')}>
            {instance.deploymentName}
          </Descriptions.Item>
          <Descriptions.Item label={t('instance.access_url')}>
            {!accessUrl ? (
              <Text type="secondary">-</Text>
            ) : (
              <Space direction="vertical" size={0}>
                <Tooltip title={t('instance.click_to_open')}>
                  <a
                    href={accessUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="instance-access-url"
                  >
                    <LinkOutlined style={{ marginRight: '4px' }} />
                    {accessUrl}
                  </a>
                </Tooltip>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  (
                  {accessUrlSource}
                  )
                </Text>
              </Space>
            )}
          </Descriptions.Item>
          <Descriptions.Item label={t('instance.nacos_console')}>
            <Tooltip title={t('instance.click_to_open_nacos')}>
              <a
                href={`http://${nacosAddr}/nacos/#/configurationManagement?dataId=&group=&namespace=${instance.nacosNamespace ?? ''}`}
                target="_blank"
                rel="noopener noreferrer"
                className="instance-access-url"
              >
                <LinkOutlined style={{ marginRight: '4px' }} />
                http://
                {nacosAddr}
                /nacos
              </a>
            </Tooltip>
          </Descriptions.Item>
          <Descriptions.Item label={t('instance.created_at')}>
            {instance.createdAt ? new Date(instance.createdAt).toLocaleString() : '-'}
          </Descriptions.Item>
          {instance.description && (
            <Descriptions.Item label={t('instance.description')}>
              {instance.description}
            </Descriptions.Item>
          )}
        </Descriptions>
      </Card>
    </div>
  );
};

export default OverviewTab;
