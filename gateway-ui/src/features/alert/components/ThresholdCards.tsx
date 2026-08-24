import React from 'react';
import {
  Card, Form, InputNumber, Space, Switch
} from 'antd';
import { SettingOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

/**
 * The five threshold editor cards (CPU / Memory / HTTP / Instance / Thread).
 * Must render inside the parent <Form> — the Form.Items bind via context.
 */
const ThresholdCards: React.FC = () => {
  const { t } = useTranslation();

  return (
    <Card title={<Space><SettingOutlined />{t('alert.threshold_config')}</Space>} className="threshold-config-card">
      <div className="threshold-grid">
        <Card
          size="small"
          className="threshold-card threshold-card-cpu"
          title={(
            <div className="threshold-card-title">
              <Form.Item name={['cpu', 'enabled']} valuePropName="checked" noStyle>
                <Switch size="small" className="threshold-switch" />
              </Form.Item>
              <span>
                CPU {t('alert.threshold')}
              </span>
            </div>
          )}
        >
          <div className="threshold-card-content">
            <div className="threshold-input-row">
              <div className="threshold-input-col">
                <label className="threshold-label">{t('alert.process_cpu')}</label>
                <Form.Item name={['cpu', 'processThreshold']} noStyle>
                  <InputNumber className="threshold-input" min={0} max={100} placeholder="80" />
                </Form.Item>
              </div>
              <div className="threshold-input-col">
                <label className="threshold-label">{t('alert.system_cpu')}</label>
                <Form.Item name={['cpu', 'systemThreshold']} noStyle>
                  <InputNumber className="threshold-input" min={0} max={100} placeholder="90" />
                </Form.Item>
              </div>
            </div>
          </div>
        </Card>

        <Card
          size="small"
          className="threshold-card threshold-card-memory"
          title={(
            <div className="threshold-card-title">
              <Form.Item name={['memory', 'enabled']} valuePropName="checked" noStyle>
                <Switch size="small" className="threshold-switch" />
              </Form.Item>
              <span>
                {t('monitor.jvm_memory')} {t('alert.threshold')}
              </span>
            </div>
          )}
        >
          <div className="threshold-card-content">
            <div className="threshold-input-row">
              <div className="threshold-input-col">
                <label className="threshold-label">{t('alert.heap_threshold')}</label>
                <Form.Item name={['memory', 'heapThreshold']} noStyle>
                  <InputNumber className="threshold-input" min={0} max={100} placeholder="85" />
                </Form.Item>
              </div>
            </div>
          </div>
        </Card>

        <Card
          size="small"
          className="threshold-card threshold-card-http"
          title={(
            <div className="threshold-card-title">
              <Form.Item name={['http', 'enabled']} valuePropName="checked" noStyle>
                <Switch size="small" className="threshold-switch" />
              </Form.Item>
              <span>
                HTTP {t('alert.threshold')}
              </span>
            </div>
          )}
        >
          <div className="threshold-card-content">
            <div className="threshold-input-row">
              <div className="threshold-input-col">
                <label className="threshold-label">{t('alert.error_rate')}</label>
                <Form.Item name={['http', 'errorRateThreshold']} noStyle>
                  <InputNumber className="threshold-input" min={0} max={100} placeholder="5" />
                </Form.Item>
              </div>
              <div className="threshold-input-col">
                <label className="threshold-label">{t('alert.response_time')}</label>
                <Form.Item name={['http', 'responseTimeThreshold']} noStyle>
                  <InputNumber className="threshold-input" min={0} placeholder="2000" />
                </Form.Item>
              </div>
            </div>
          </div>
        </Card>

        <Card
          size="small"
          className="threshold-card threshold-card-instance"
          title={(
            <div className="threshold-card-title">
              <Form.Item name={['instance', 'enabled']} valuePropName="checked" noStyle>
                <Switch size="small" className="threshold-switch" />
              </Form.Item>
              <span>{t('alert.instance_health')}</span>
            </div>
          )}
        >
          <div className="threshold-card-content">
            <div className="threshold-input-row">
              <div className="threshold-input-col">
                <label className="threshold-label">{t('alert.unhealthy_threshold')}</label>
                <Form.Item name={['instance', 'unhealthyThreshold']} noStyle>
                  <InputNumber className="threshold-input" min={0} placeholder="1" />
                </Form.Item>
              </div>
            </div>
          </div>
        </Card>

        <Card
          size="small"
          className="threshold-card threshold-card-thread"
          title={(
            <div className="threshold-card-title">
              <Form.Item name={['thread', 'enabled']} valuePropName="checked" noStyle>
                <Switch size="small" className="threshold-switch" />
              </Form.Item>
              <span>{t('alert.thread_usage')}</span>
            </div>
          )}
        >
          <div className="threshold-card-content">
            <div className="threshold-input-row">
              <div className="threshold-input-col">
                <label className="threshold-label">{t('alert.thread_threshold')}</label>
                <Form.Item name={['thread', 'activeThreshold']} noStyle>
                  <InputNumber className="threshold-input" min={0} max={100} placeholder="90" />
                </Form.Item>
              </div>
            </div>
          </div>
        </Card>
      </div>
    </Card>
  );
};

export default ThresholdCards;
