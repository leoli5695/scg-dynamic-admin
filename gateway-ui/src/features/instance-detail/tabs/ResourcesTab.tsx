/**
 * Resources tab — cluster nodes and pods overview.
 * Uses GET /api/instances/{id}/resource-metrics; handles local mode.
 */
import React from 'react';
import {
  Alert, Badge, Button, Card, Col, Row, Space, Spin, Tag, Typography
} from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../../hooks/useRequest';
import { getInstanceResourceMetrics } from '../../../api/instances';
import { useInstanceDetail } from '../InstanceDetailContext';

const { Title, Text } = Typography;

const ResourcesTab: React.FC = () => {
  const { t } = useTranslation();
  const { instance } = useInstanceDetail();

  const metricsRequest = useRequest(
    (signal) => getInstanceResourceMetrics(instance.id, { signal }),
    { deps: [instance.id] },
  );
  const metrics = metricsRequest.data;

  return (
    <Card>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Title level={5}>{t('resources.title') || '集群资源监控'}</Title>
        <Button
          icon={<ReloadOutlined />}
          onClick={() => metricsRequest.run()}
          loading={metricsRequest.loading}
        >
          {t('common.refresh') || '刷新'}
        </Button>
      </div>
      {metricsRequest.loading && !metrics ? (
        <div style={{ display: 'flex', justifyContent: 'center', padding: 40 }}>
          <Spin />
        </div>
      ) : !metrics ? (
        <div style={{ textAlign: 'center', padding: 40 }}>
          <Text type="secondary">{t('resources.no_data') || '暂无资源数据'}</Text>
        </div>
      ) : metrics.mode === 'local' ? (
        <div style={{ textAlign: 'center', padding: 40 }}>
          <Alert
            type="info"
            showIcon
            message={t('resources.local_mode') || '本地运行模式'}
            description={t('resources.local_mode_desc') || '此网关实例为本地启动，未关联 Kubernetes 集群，无法获取资源指标'}
          />
        </div>
      ) : (
        <>
          {/* Nodes */}
          <Card title={t('resources.nodes') || '节点'} size="small" style={{ marginBottom: 16 }}>
            <Row gutter={[16, 16]}>
              {metrics.nodes?.list?.map((node, idx) => (
                <Col key={idx} xs={24} sm={12} md={8} lg={6}>
                  <Card size="small" bordered={false} style={{ background: '#fafafa' }}>
                    <Space direction="vertical" size="small">
                      <Text strong>{node.name}</Text>
                      <Badge status={node.ready ? 'success' : 'error'} text={node.ready ? 'Ready' : 'Not Ready'} />
                      {node.allocatable && (
                        <>
                          <Text type="secondary" style={{ fontSize: 12 }}>
                            CPU:
                            {' '}
                            {node.allocatable.cpu}
                          </Text>
                          <Text type="secondary" style={{ fontSize: 12 }}>
                            Memory:
                            {' '}
                            {node.allocatable.memory}
                          </Text>
                        </>
                      )}
                    </Space>
                  </Card>
                </Col>
              ))}
            </Row>
            <div style={{ marginTop: 16 }}>
              <Text type="secondary">
                {t('resources.total_nodes') || '节点总数'}
                :
                {' '}
                {metrics.nodes?.total || 0}
              </Text>
            </div>
          </Card>

          {/* Pods */}
          <Card title={t('resources.pods') || 'Pods'} size="small">
            <Row gutter={[16, 16]}>
              {metrics.pods?.list?.map((pod, idx) => (
                <Col key={idx} xs={24} sm={12} md={8} lg={6}>
                  <Card size="small" bordered={false} style={{ background: pod.phase === 'Running' ? '#f6ffed' : '#fff7e6' }}>
                    <Space direction="vertical" size="small">
                      <Text strong style={{ fontSize: 12 }}>{pod.name}</Text>
                      <Tag color={pod.phase === 'Running' ? 'success' : 'warning'}>{pod.phase}</Tag>
                      {pod.podIP && (
                        <Text type="secondary" className="text-xs-tiny">
                          IP:
                          {' '}
                          {pod.podIP}
                        </Text>
                      )}
                      {pod.containers && (
                        <Text type="secondary" className="text-xs-tiny">
                          {t('resources.containers') || '容器'}
                          :
                          {' '}
                          {pod.containers.length}
                        </Text>
                      )}
                    </Space>
                  </Card>
                </Col>
              ))}
            </Row>
            <div style={{ marginTop: 16 }}>
              <Space>
                <Text type="secondary">
                  {t('resources.total_pods') || 'Pod总数'}
                  :
                  {' '}
                  {metrics.pods?.total || 0}
                </Text>
                <Text type="secondary">
                  {t('resources.running_pods') || '运行中'}
                  :
                  {' '}
                  {metrics.pods?.running || 0}
                </Text>
              </Space>
            </div>
          </Card>
        </>
      )}
    </Card>
  );
};

export default ResourcesTab;
