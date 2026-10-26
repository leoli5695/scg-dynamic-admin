import React from 'react';
import {
  Badge, Card, Col, Row, Select, Space, Tag, Typography
} from 'antd';
import { CloudServerOutlined, ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { InstancePod } from '../../../types/instance';
import { parseServerTime } from '../../../utils/serverTime';
import { getPodStatusColor } from '../utils';

const { Text } = Typography;

interface PodSelectorProps {
  pods: InstancePod[];
  loading: boolean;
  selectedPodName?: string;
  onSelect: (podName: string) => void;
  onRefresh: () => void;
}

/** Pod picker with a summary strip for the currently selected pod. */
const PodSelector: React.FC<PodSelectorProps> = ({
  pods, loading, selectedPodName, onSelect, onRefresh,
}) => {
  const { t } = useTranslation();
  const selectedPod = pods.find((p) => p.name === selectedPodName);

  return (
    <Card style={{ marginBottom: 16, overflow: 'visible' }} styles={{ body: { padding: '12px 16px', overflow: 'visible' } }}>
      <Row gutter={16} align="middle" style={{ overflow: 'visible' }}>
        <Col style={{ overflow: 'visible' }}>
          <Space style={{ overflow: 'visible' }}>
            <CloudServerOutlined />
            <Text strong>{t('monitor.select_pod')}</Text>
            <Select
              value={selectedPod?.name}
              onChange={onSelect}
              style={{ width: 280 }}
              loading={loading}
              getPopupContainer={(trigger) => trigger.parentNode as HTMLElement}
              listHeight={200}
              suffixIcon={<ReloadOutlined onClick={onRefresh} style={{ cursor: 'pointer' }} />}
            >
              {pods.map((pod) => (
                <Select.Option key={pod.name} value={pod.name}>
                  <Space>
                    <Badge status={getPodStatusColor(pod.phase)} />
                    <Text>{pod.name}</Text>
                    <Text type="secondary" style={{ fontSize: 12 }}>{pod.podIP}</Text>
                  </Space>
                </Select.Option>
              ))}
            </Select>
          </Space>
        </Col>
        {selectedPod && (
          <Col flex="auto">
            <Space size="large">
              <Tag color={getPodStatusColor(selectedPod.phase)}>{selectedPod.phase}</Tag>
              <Text type="secondary">
                IP: <Text code>{selectedPod.podIP}</Text>
              </Text>
              {selectedPod.startTime && (
                <Text type="secondary">
                  {t('monitor.pod_start_time')}: {parseServerTime(selectedPod.startTime).toLocaleString()}
                </Text>
              )}
              {selectedPod.containers && selectedPod.containers.length > 0 && (
                <Text type="secondary">
                  {t('monitor.container_ready')}:
                  {' '}
                  {selectedPod.containers.filter((c) => c.ready).length}
                  /
                  {selectedPod.containers.length}
                </Text>
              )}
            </Space>
          </Col>
        )}
      </Row>
    </Card>
  );
};

export default PodSelector;
