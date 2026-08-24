/**
 * Create tab — preset templates + full test configuration form.
 * Duration mode and request-count mode are mutually exclusive.
 */
import React from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  Collapse,
  Divider,
  Form,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography } from 'antd';
import { message } from '../../../utils/antdStatic';
import {
  InfoCircleOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  RocketOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { StressTestConfig } from '../../../types/stressTest';
import { TEST_TEMPLATES } from '../constants/templates';

const { Text } = Typography;
const { TextArea } = Input;

interface FormValues {
  testName: string;
  targetUrl: string;
  path: string;
  method: string;
  requestTimeoutSeconds?: number;
  concurrentUsers?: number;
  totalRequests?: number;
  durationSeconds?: number;
  targetQps?: number;
  rampUpSeconds?: number;
  headers?: string;
  body?: string;
}

interface Props {
  creating: boolean;
  /** Returns true when the test started successfully. */
  onStart: (config: StressTestConfig) => Promise<boolean>;
}

const PLACEHOLDER_ROWS = [
  { key: 'randomInt', syntax: '{{randomInt}}', descKey: 'stress_test.placeholder_randomInt_default' },
  { key: 'randomIntRange', syntax: '{{randomInt:min-max}}', descKey: 'stress_test.placeholder_randomInt_range' },
  { key: 'randomString', syntax: '{{randomString}}', descKey: 'stress_test.placeholder_randomString_default' },
  { key: 'randomStringLength', syntax: '{{randomString:length}}', descKey: 'stress_test.placeholder_randomString_length' },
  { key: 'randomUuid', syntax: '{{randomUuid}}', descKey: 'stress_test.placeholder_randomUuid' },
  { key: 'timestamp', syntax: '{{timestamp}}', descKey: 'stress_test.placeholder_timestamp' },
];

const CreateTab: React.FC<Props> = ({ creating, onStart }) => {
  const { t } = useTranslation();
  const [createForm] = Form.useForm<FormValues>();

  const handleFinish = async (values: FormValues) => {
    let headers: Record<string, string> | null = null;
    if (values.headers) {
      try {
        headers = JSON.parse(values.headers);
      } catch {
        message.error(t('stress_test.headers_help_json'));
        return;
      }
    }

    const config: StressTestConfig = {
      testName: values.testName,
      targetUrl: values.targetUrl,
      path: values.path,
      method: values.method,
      headers,
      body: values.body,
      concurrentUsers: values.concurrentUsers ?? 10,
      totalRequests: values.totalRequests ?? null,
      durationSeconds: values.durationSeconds ?? null,
      rampUpSeconds: values.rampUpSeconds ?? 0,
      targetQps: values.targetQps ?? null,
      requestTimeoutSeconds: values.requestTimeoutSeconds ?? 30,
    };

    // Duration mode wins when both modes are filled.
    if (config.durationSeconds && config.totalRequests) {
      config.totalRequests = null;
    }

    const ok = await onStart(config);
    if (ok) {
      createForm.resetFields();
    }
  };

  const collapseItems = [
    {
      key: 'basic',
      label: (
        <Space>
          <InfoCircleOutlined />
          {t('stress_test.basic_settings')}
        </Space>
      ),
      children: (
        <>
          <Form.Item
            name="testName"
            label={t('stress_test.test_name')}
            rules={[{ required: true, message: t('stress_test.test_name_required') }]}
          >
            <Input placeholder={t('stress_test.test_placeholder')} />
          </Form.Item>

          <Form.Item
            name="targetUrl"
            label={t('stress_test.target_url')}
            rules={[
              { required: true, message: t('stress_test.target_url_required') },
              { pattern: /^https?:\/\/.+/, message: t('stress_test.url_format_error') },
            ]}
            help={t('stress_test.target_url_help')}
          >
            <Input placeholder="http://localhost:8080" />
          </Form.Item>

          <Form.Item
            name="path"
            label={t('stress_test.test_path')}
            rules={[{ required: true, message: t('stress_test.test_path_required') }]}
            help={t('stress_test.test_path_help')}
          >
            <Input placeholder="/api/test" />
          </Form.Item>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="method" label={t('stress_test.http_method')} initialValue="GET">
                <Select>
                  <Select.Option value="GET">GET</Select.Option>
                  <Select.Option value="POST">POST</Select.Option>
                  <Select.Option value="PUT">PUT</Select.Option>
                  <Select.Option value="DELETE">DELETE</Select.Option>
                  <Select.Option value="PATCH">PATCH</Select.Option>
                </Select>
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="requestTimeoutSeconds"
                label={t('stress_test.request_timeout')}
                initialValue={30}
                rules={[{ required: true }]}
              >
                <InputNumber min={1} max={300} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
        </>
      ),
    },
  ];

  const advancedItems = [
    {
      key: 'advanced',
      label: (
        <Space>
          <SettingOutlined />
          {t('stress_test.advanced_options')}
        </Space>
      ),
      children: (
        <>
          <Form.Item name="headers" label={t('stress_test.headers')} help={t('stress_test.headers_help_json')}>
            <TextArea rows={3} placeholder='{"Authorization": "Bearer token", "Content-Type": "application/json"}' />
          </Form.Item>

          <Form.Item name="body" label={t('stress_test.request_body')} help={t('stress_test.body_help')}>
            <TextArea rows={4} placeholder='{"userId": {{randomInt:10000-100000}}, "token": "{{randomString:32}}"}' />
          </Form.Item>

          {/* Placeholder Help */}
          <Collapse
            ghost
            items={[
              {
                key: 'placeholders',
                label: (
                  <Space>
                    <Tag color="blue">{t('stress_test.placeholders')}</Tag>
                  </Space>
                ),
                children: (
                  <>
                    <Table
                      size="small"
                      pagination={false}
                      dataSource={PLACEHOLDER_ROWS.map((row) => ({ ...row, desc: t(row.descKey) }))}
                      columns={[
                        {
                          title: t('stress_test.placeholder_syntax'),
                          dataIndex: 'syntax',
                          render: (s: string) => <Tag color="geekblue" style={{ fontFamily: 'monospace' }}>{s}</Tag>,
                        },
                        {
                          title: t('stress_test.placeholder_description'),
                          dataIndex: 'desc',
                        },
                      ]}
                    />
                    <Typography.Text type="secondary" style={{ marginTop: 8, display: 'block' }}>
                      {t('stress_test.placeholder_example')}: <code style={{ background: 'rgba(0,0,0,0.1)', padding: '2px 6px', borderRadius: 4 }}>{'{"userId": {{randomInt:10000-100000}}}'}</code>
                    </Typography.Text>
                  </>
                ),
              },
            ]}
          />
        </>
      ),
    },
  ];

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      {/* Preset Templates */}
      <Card
        title={
          <Space>
            <RocketOutlined />
            {t('stress_test.quick_start_templates')}
          </Space>
        }
        style={{ background: 'rgba(255,255,255,0.05)' }}
      >
        <Row gutter={[16, 16]}>
          {TEST_TEMPLATES.map((template) => (
            <Col xs={24} sm={12} md={6} lg={4} key={template.key}>
              <Card
                hoverable
                size="small"
                onClick={() => {
                  createForm.setFieldsValue({ ...template.config });
                  message.success(`${t('stress_test.template_applied')}: ${t(template.nameKey)}`);
                }}
                style={{ background: 'rgba(255,255,255,0.08)', borderColor: '#165DFF' }}
              >
                <Space direction="vertical" style={{ width: '100%' }} size="small">
                  <Space>
                    {template.icon}
                    <Text strong style={{ color: '#fff' }}>{t(template.nameKey)}</Text>
                  </Space>
                  <Text type="secondary" style={{ fontSize: 12 }}>{t(template.descriptionKey)}</Text>
                  <Tooltip title={t(template.scenarioKey)}>
                    <Tag color="green" style={{ cursor: 'help' }}>{t('stress_test.scenario')}</Tag>
                  </Tooltip>
                  <Tag color="blue">{template.config.concurrentUsers} {t('stress_test.users')}</Tag>
                </Space>
              </Card>
            </Col>
          ))}
        </Row>
      </Card>

      {/* Test Configuration Form */}
      <Card
        title={
          <Space>
            <SettingOutlined />
            {t('stress_test.test_configuration')}
          </Space>
        }
        style={{ background: 'rgba(255,255,255,0.05)' }}
        extra={
          <Button icon={<ReloadOutlined />} onClick={() => createForm.resetFields()}>
            {t('stress_test.reset')}
          </Button>
        }
      >
        <Form form={createForm} layout="vertical" onFinish={handleFinish}>
          <Collapse defaultActiveKey={['basic']} ghost items={collapseItems} />

          <Divider style={{ textAlign: 'left' }}>{t('stress_test.load_parameters')}</Divider>

          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={t('stress_test.mode_hint_title')}
            description={t('stress_test.mode_hint_desc')}
          />

          <Row gutter={[16, 0]}>
            <Col xs={24} sm={12} md={8}>
              <Form.Item
                name="concurrentUsers"
                label={
                  <Space>
                    {t('stress_test.concurrent_users')}
                    <Tooltip title={t('stress_test.concurrent_users_tooltip')}>
                      <InfoCircleOutlined style={{ color: 'rgba(255,255,255,0.45)' }} />
                    </Tooltip>
                  </Space>
                }
                initialValue={10}
                rules={[{ required: true, message: t('stress_test.concurrent_users_required') }]}
              >
                <InputNumber min={1} max={500} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Form.Item
                name="totalRequests"
                label={
                  <Space>
                    {t('stress_test.total_requests')}
                    <Tooltip title={t('stress_test.total_requests_tooltip')}>
                      <InfoCircleOutlined style={{ color: 'rgba(255,255,255,0.45)' }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber
                  min={1}
                  max={1000000}
                  placeholder={t('stress_test.unlimited')}
                  style={{ width: '100%' }}
                  onChange={(value) => {
                    if (value !== null && value !== undefined) {
                      createForm.setFieldValue('durationSeconds', undefined);
                    }
                  }}
                />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Form.Item
                name="durationSeconds"
                label={
                  <Space>
                    {t('stress_test.duration_seconds')}
                    <Tooltip title={t('stress_test.duration_tooltip')}>
                      <InfoCircleOutlined style={{ color: 'rgba(255,255,255,0.45)' }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber
                  min={1}
                  max={3600}
                  placeholder={t('stress_test.unlimited')}
                  style={{ width: '100%' }}
                  onChange={(value) => {
                    if (value !== null && value !== undefined) {
                      createForm.setFieldValue('totalRequests', undefined);
                    }
                  }}
                />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={[16, 0]}>
            <Col xs={24} sm={12} md={8}>
              <Form.Item
                name="targetQps"
                label={
                  <Space>
                    {t('stress_test.target_qps')}
                    <Tooltip title={t('stress_test.target_qps_tooltip')}>
                      <InfoCircleOutlined style={{ color: 'rgba(255,255,255,0.45)' }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={1} max={10000} placeholder={t('stress_test.no_limit')} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Form.Item
                name="rampUpSeconds"
                label={
                  <Space>
                    {t('stress_test.ramp_up')}
                    <Tooltip title={t('stress_test.ramp_up_tooltip')}>
                      <InfoCircleOutlined style={{ color: 'rgba(255,255,255,0.45)' }} />
                    </Tooltip>
                  </Space>
                }
                initialValue={0}
              >
                <InputNumber min={0} max={300} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>

          <Collapse ghost style={{ marginTop: 16 }} items={advancedItems} />

          <Divider />

          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" icon={<PlayCircleOutlined />} loading={creating} size="large">
                {t('stress_test.start_test')}
              </Button>
              <Button onClick={() => createForm.resetFields()} size="large">
                {t('stress_test.reset')}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
    </Space>
  );
};

export default CreateTab;
