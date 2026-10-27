/**
 * Access log configuration tab — basic settings, log content settings,
 * file rotation settings and the Fluent Bit collection guide.
 * The form instance lives in the page shell so Save/Reset act on it.
 */
import React from 'react';
import {
  Alert, Badge, Card, Col, Divider, Form, Input, InputNumber, Row, Segmented,
  Select, Space, Switch, Tag, Tooltip, Typography
} from 'antd';
import type { FormInstance } from 'antd';
import {
  CheckCircleOutlined, CloudServerOutlined, CodeOutlined, ContainerOutlined,
  DesktopOutlined, FileOutlined, FileTextOutlined, FolderOutlined,
  InfoCircleOutlined, SettingOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type {
  AccessLogConfig, DeployMode, DeployModeOption, OutputTarget,
} from '../../../types/accessLog';
import CollectionGuide from './CollectionGuide';

const { Text } = Typography;

interface ConfigTabProps {
  form: FormInstance;
  config: AccessLogConfig | null;
  deployModes: DeployModeOption[];
  outputTarget: OutputTarget;
  showFileConfig: boolean;
  onDeployModeChange: (mode: DeployMode) => void;
  onOutputTargetChange: (target: OutputTarget) => void;
}

const getDeployModeIcon = (mode: string) => {
  switch (mode) {
    case 'LOCAL': return <DesktopOutlined />;
    case 'DOCKER': return <ContainerOutlined />;
    case 'K8S': return <CloudServerOutlined />;
    default: return <FolderOutlined />;
  }
};

const ConfigTab: React.FC<ConfigTabProps> = ({
  form, config, deployModes, outputTarget, showFileConfig,
  onDeployModeChange, onOutputTargetChange,
}) => {
  const { t } = useTranslation();
  // Keeps the collection guide tab in sync with uncommitted form edits.
  const watchedDeployMode = Form.useWatch('deployMode', form);
  const savedDeployMode = config?.deployMode;

  return (
    <div style={{ padding: '16px 0' }}>
      <Form form={form} layout="vertical">
        <Row gutter={24}>
          {/* Basic Settings */}
          <Col xs={24} lg={12}>
            <Card
              title={<Space><SettingOutlined />{t('access_log.basic_settings')}</Space>}
              className="config-card"
            >
              <Form.Item
                name="enabled"
                label={t('access_log.enabled')}
                valuePropName="checked"
              >
                <Switch
                  checkedChildren={t('common.enabled')}
                  unCheckedChildren={t('common.disabled')}
                />
              </Form.Item>

              <Form.Item
                name="deployMode"
                label={
                  <Space>
                    {t('access_log.deploy_mode')}
                    <Tooltip title={t('access_log.deploy_mode_tip')}>
                      <InfoCircleOutlined style={{ color: '#8c8c8c' }} />
                    </Tooltip>
                  </Space>
                }
              >
                <Select onChange={onDeployModeChange}>
                  {deployModes.map((mode) => (
                    <Select.Option key={mode.mode} value={mode.mode}>
                      <Space>
                        {getDeployModeIcon(mode.mode)}
                        {mode.description}
                      </Space>
                    </Select.Option>
                  ))}
                </Select>
              </Form.Item>

              {/* Output target — frontend-only, mapped to logToConsole/logDirectory on save */}
              <Form.Item
                label={
                  <Space>
                    {t('access_log.output_target')}
                    <Tooltip title={t('access_log.output_target_tip')}>
                      <InfoCircleOutlined style={{ color: '#8c8c8c' }} />
                    </Tooltip>
                  </Space>
                }
              >
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  <Segmented
                    value={outputTarget}
                    onChange={(value) => onOutputTargetChange(value as OutputTarget)}
                    block
                    options={[
                      {
                        value: 'stdout',
                        label: (
                          <Space>
                            <CodeOutlined />
                            <span>stdout</span>
                            {savedDeployMode === 'K8S' && (
                              <Badge status="success" text={<Text style={{ fontSize: 11, color: '#52c41a' }}>{t('access_log.recommended')}</Text>} />
                            )}
                          </Space>
                        ),
                      },
                      {
                        value: 'file',
                        label: (
                          <Space>
                            <FileOutlined />
                            <span>file</span>
                            {savedDeployMode === 'LOCAL' && (
                              <Badge status="success" text={<Text style={{ fontSize: 11, color: '#52c41a' }}>{t('access_log.recommended')}</Text>} />
                            )}
                          </Space>
                        ),
                        disabled: savedDeployMode === 'K8S',
                      },
                      {
                        value: 'both',
                        label: (
                          <Space>
                            <FileTextOutlined />
                            <span>both</span>
                            {savedDeployMode === 'DOCKER' && (
                              <Badge status="processing" text={<Text style={{ fontSize: 11, color: '#1890ff' }}>{t('access_log.recommended')}</Text>} />
                            )}
                          </Space>
                        ),
                        disabled: savedDeployMode === 'K8S',
                      },
                    ]}
                  />
                  {savedDeployMode === 'K8S' && (
                    <Alert
                      type="warning"
                      showIcon
                      style={{ marginTop: 8, padding: '8px 12px' }}
                      message={<Text style={{ fontSize: 12 }}>{t('access_log.file_both_disabled')}</Text>}
                    />
                  )}
                </div>
              </Form.Item>

              {/* Per-mode hints */}
              {savedDeployMode === 'K8S' && (
                <Alert
                  type="success"
                  showIcon
                  icon={<CheckCircleOutlined />}
                  style={{ marginBottom: 16, borderLeft: '4px solid #52c41a' }}
                  message={<Text strong>{t('access_log.k8s_stdout_hint')}</Text>}
                  description={
                    <div>
                      <p style={{ marginBottom: 8 }}>
                        {t('access_log.k8s_stdout_desc_line1')}<code style={{ background: '#f5f5f5', padding: '2px 6px', borderRadius: 4 }}>/var/log/containers/*.log</code>
                      </p>
                      <p style={{ color: '#52c41a', fontWeight: 500 }}>
                        {t('access_log.k8s_stdout_desc_line2')}
                      </p>
                    </div>
                  }
                />
              )}

              {savedDeployMode === 'DOCKER' && (
                <Alert
                  type="info"
                  showIcon
                  style={{ marginBottom: 16, borderLeft: '4px solid #1890ff' }}
                  message={<Text strong>{t('access_log.docker_hint')}</Text>}
                  description={
                    <div>
                      <p>{t('access_log.docker_desc_line1')}<code style={{ background: '#f5f5f5', padding: '2px 6px', borderRadius: 4 }}>both</code>{t('access_log.docker_desc_line2')}</p>
                      <p style={{ color: '#1890ff', fontWeight: 500 }}>
                        {t('access_log.docker_desc_line3')}
                      </p>
                    </div>
                  }
                />
              )}

              {savedDeployMode === 'LOCAL' && (
                <Alert
                  type="info"
                  showIcon
                  style={{ marginBottom: 16 }}
                  message={t('access_log.local_hint_msg')}
                  description={t('access_log.local_hint_desc')}
                />
              )}

              {savedDeployMode === 'CUSTOM' && (
                <Alert
                  type="warning"
                  showIcon
                  style={{ marginBottom: 16 }}
                  message={t('access_log.custom_hint_msg')}
                  description={t('access_log.custom_hint_desc')}
                />
              )}

              {/* File settings — hidden for stdout-only output */}
              {showFileConfig && (
                <>
                  <Form.Item
                    name="logDirectory"
                    label={t('access_log.log_directory')}
                    extra={t('access_log.directory_hint')}
                  >
                    <Input
                      placeholder={t('access_log.directory_placeholder')}
                      prefix={<FolderOutlined />}
                    />
                  </Form.Item>

                  <Form.Item
                    name="fileNamePattern"
                    label={t('access_log.file_pattern')}
                    extra={t('access_log.pattern_extra')}
                  >
                    <Input placeholder="access-{yyyy-MM-dd}.log" />
                  </Form.Item>
                </>
              )}

              <Form.Item name="logFormat" label={t('access_log.log_format')}>
                <Select>
                  <Select.Option value="JSON">
                    <Tag color="blue">JSON</Tag>
                    {t('access_log.json_desc')}
                  </Select.Option>
                  <Select.Option value="TEXT">
                    <Tag color="green">TEXT</Tag>
                    {t('access_log.text_desc')}
                  </Select.Option>
                </Select>
              </Form.Item>

              <Form.Item name="logLevel" label={t('access_log.log_level')}>
                <Select>
                  <Select.Option value="MINIMAL">
                    <Tag color="default">MINIMAL</Tag>
                    {t('access_log.level_minimal')}
                  </Select.Option>
                  <Select.Option value="NORMAL">
                    <Tag color="blue">NORMAL</Tag>
                    {t('access_log.level_normal')}
                  </Select.Option>
                  <Select.Option value="VERBOSE">
                    <Tag color="purple">VERBOSE</Tag>
                    {t('access_log.level_verbose')}
                  </Select.Option>
                </Select>
              </Form.Item>
            </Card>
          </Col>

          {/* Log Content Settings */}
          <Col xs={24} lg={12}>
            <Card
              title={<Space><FileTextOutlined />{t('access_log.content_settings')}</Space>}
              className="config-card"
            >
              <Form.Item
                name="logRequestHeaders"
                label={t('access_log.log_request_headers')}
                valuePropName="checked"
              >
                <Switch size="small" />
              </Form.Item>

              <Form.Item
                name="logResponseHeaders"
                label={t('access_log.log_response_headers')}
                valuePropName="checked"
              >
                <Switch size="small" />
              </Form.Item>

              <Form.Item
                name="logRequestBody"
                label={t('access_log.log_request_body')}
                valuePropName="checked"
              >
                <Switch size="small" />
              </Form.Item>

              <Form.Item
                name="logResponseBody"
                label={t('access_log.log_response_body')}
                valuePropName="checked"
              >
                <Switch size="small" />
              </Form.Item>

              <Form.Item
                name="includeAuthInfo"
                label={t('access_log.include_auth_info')}
                valuePropName="checked"
                extra={t('access_log.auth_info_extra')}
              >
                <Switch size="small" />
              </Form.Item>

              <Divider />

              <Form.Item
                name="maxBodyLength"
                label={t('access_log.max_body_length')}
                extra={t('access_log.max_body_extra')}
              >
                <InputNumber min={0} max={10000} style={{ width: '100%' }} />
              </Form.Item>

              <Form.Item
                name="samplingRate"
                label={t('access_log.sampling_rate')}
                extra={t('access_log.sampling_extra')}
              >
                <InputNumber min={1} max={100} style={{ width: '100%' }} />
              </Form.Item>
            </Card>
          </Col>
        </Row>

        {/* File rotation settings — only when file output is enabled */}
        {showFileConfig && (
          <Card
            title={<Space><FolderOutlined />{t('access_log.file_settings')}</Space>}
            className="config-card"
            style={{ marginTop: 16 }}
          >
            <Row gutter={24}>
              <Col span={8}>
                <Form.Item name="maxFileSizeMb" label={t('access_log.max_file_size')}>
                  <InputNumber min={1} max={1000} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="maxBackupFiles" label={t('access_log.max_backup_files')}>
                  <InputNumber min={0} max={100} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>
          </Card>
        )}

        {/* Fluent Bit collection guide */}
        <Card
          title={<Space><InfoCircleOutlined />{t('access_log.advanced_config')}</Space>}
          className="config-card"
          style={{ marginTop: 16 }}
        >
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={t('access_log.storage_hint_msg')}
            description={t('access_log.storage_hint_desc')}
          />
          <CollectionGuide activeDeployMode={watchedDeployMode || config?.deployMode || 'LOCAL'} />
        </Card>
      </Form>
    </div>
  );
};

export default ConfigTab;
