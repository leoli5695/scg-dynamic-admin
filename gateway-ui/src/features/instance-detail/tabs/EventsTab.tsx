/**
 * Events tab — Kubernetes events for the instance pods.
 * Uses GET /api/instances/{id}/events (sinceSeconds=3600, limit=50).
 */
import React from 'react';
import {
  Badge, Button, Card, Col, Row, Space, Spin, Tag, Typography
} from 'antd';
import { ClockCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../../hooks/useRequest';
import { getInstanceEvents } from '../../../api/instances';
import { useInstanceDetail } from '../InstanceDetailContext';

const { Text } = Typography;

const EventsTab: React.FC = () => {
  const { t } = useTranslation();
  const { instance } = useInstanceDetail();

  const eventsRequest = useRequest(
    (signal) => getInstanceEvents(
      instance.id,
      { sinceSeconds: 3600, limit: 50 },
      { signal },
    ),
    { deps: [instance.id] },
  );
  const events = eventsRequest.data?.events ?? [];
  const total = eventsRequest.data?.total ?? 0;
  const warningCount = eventsRequest.data?.warningCount ?? 0;
  const normalCount = eventsRequest.data?.normalCount ?? 0;

  return (
    <Card className="console-workspace-page console-resource-page console-cluster-panel">
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Space>
          <Badge status={warningCount > 0 ? 'error' : 'success'} text={`${warningCount} Warning`} />
          <Badge status="processing" text={`${normalCount} Normal`} />
          <Text type="secondary">
            Total:
            {' '}
            {total}
          </Text>
        </Space>
        <Button
          icon={<ReloadOutlined />}
          onClick={() => eventsRequest.run()}
          loading={eventsRequest.loading}
        >
          {t('common.refresh')}
        </Button>
      </div>
      {eventsRequest.loading && events.length === 0 ? (
        <div style={{ display: 'flex', justifyContent: 'center', padding: 40 }}>
          <Spin />
        </div>
      ) : events.length === 0 ? (
        <div style={{ textAlign: 'center', padding: 40 }}>
          <Text type="secondary">{t('events.no_events')}</Text>
        </div>
      ) : (
        <div style={{ maxHeight: 500, overflowY: 'auto' }}>
          {events.map((event, idx) => (
            <Card
              key={idx}
              size="small"
              style={{ marginBottom: 8 }}
              bordered={false}
              className={event.type === 'Warning' ? 'event-card-warning' : 'event-card-normal'}
            >
              <Row gutter={16}>
                <Col span={2}>
                  <Tag color={event.type === 'Warning' ? 'error' : 'success'}>
                    {event.type}
                  </Tag>
                </Col>
                <Col span={3}>
                  <Tag color="blue">{event.reason}</Tag>
                </Col>
                <Col span={14}>
                  <Text>{event.message}</Text>
                </Col>
                <Col span={5}>
                  <Space direction="vertical" size="small">
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      <ClockCircleOutlined />
                      {' '}
                      {event.objectName}
                    </Text>
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      Count:
                      {' '}
                      {event.count}
                    </Text>
                  </Space>
                </Col>
              </Row>
            </Card>
          ))}
        </div>
      )}
    </Card>
  );
};

export default EventsTab;
