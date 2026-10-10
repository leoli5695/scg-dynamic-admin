/**
 * AI_PROXY strategy fields — multi-provider LLM proxy configuration.
 *
 * Renders the flat schema fields (provider selection, response format, limits)
 * plus two custom sections that the generic renderer cannot express:
 *  - Provider credentials: bind each provider to an encrypted credential ref
 *    (created inline via /api/provider-credentials; the key is write-only).
 *  - Model routing aliases: optional explicit model -> provider overrides
 *    (otherwise the gateway infers the provider from the model-name prefix).
 *
 * utils.buildStrategyConfig converts these into the AiProxyConfig shape.
 */
import React, { useCallback, useEffect, useState } from 'react';
import {
  Form, Input, InputNumber, Select, Switch, Row, Col, Card, Button, Space, Tooltip, Modal,
} from 'antd';
import type { FormInstance } from 'antd';
import {
  RobotOutlined, PlusOutlined, DeleteOutlined, KeyOutlined, ApiOutlined,
  InfoCircleOutlined, BranchesOutlined,
} from '@ant-design/icons';
import type { StrategyTypeField, StrategyTypeSchema } from '../../../types/strategy';
import type { TFunc } from './DynamicConfigFields';
import { message } from '../../../utils/antdStatic';
import {
  listProviderCredentials,
  createProviderCredential,
  type ProviderCredential,
} from '../../../api/providerCredentials';

/** Built-in provider catalogue — mirrors my-gateway ai/LlmProvider.java. */
const PROVIDERS = [
  { value: 'openai', label: 'OpenAI' },
  { value: 'deepseek', label: 'DeepSeek' },
  { value: 'qwen', label: '通义千问/百炼' },
  { value: 'zhipu', label: '智谱 GLM' },
  { value: 'moonshot', label: '月之暗面/Kimi' },
  { value: 'anthropic', label: 'Anthropic Claude' },
];

interface AiProxyFieldsProps {
  schema: StrategyTypeSchema | undefined;
  form: FormInstance;
  t: TFunc;
}

export function AiProxyFields({ schema, form, t }: AiProxyFieldsProps) {
  const [credentials, setCredentials] = useState<ProviderCredential[]>([]);
  const [credModalOpen, setCredModalOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [credForm] = Form.useForm();

  const selection = Form.useWatch('providerSelection', form);

  const loadCredentials = useCallback(async () => {
    try {
      setCredentials(await listProviderCredentials());
    } catch {
      // httpClient surfaces the error via interceptor toast; keep the list empty.
      setCredentials([]);
    }
  }, []);

  useEffect(() => {
    loadCredentials();
  }, [loadCredentials]);

  const getFieldColSpan = (field: StrategyTypeField): number =>
    field.colSpan || (field.type === 'textarea' ? 24 : 12);

  const renderField = (field: StrategyTypeField): React.ReactNode => {
    const label = field.label || field.name;
    const colSpan = getFieldColSpan(field);
    switch (field.type) {
      case 'number':
        return (
          <Col span={colSpan} key={field.name}>
            <Form.Item name={field.name} label={label} initialValue={field.default}>
              <InputNumber min={field.min} max={field.max} addonAfter={field.unit} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        );
      case 'switch':
        return (
          <Col span={colSpan} key={field.name}>
            <Form.Item name={field.name} label={label} initialValue={field.default ?? false} valuePropName="checked">
              <Switch />
            </Form.Item>
          </Col>
        );
      case 'select':
        return (
          <Col span={colSpan} key={field.name}>
            <Form.Item name={field.name} label={label} initialValue={field.default}>
              <Select>
                {field.options?.map((opt) => (
                  <Select.Option key={opt.value} value={opt.value}>{opt.label || opt.value}</Select.Option>
                ))}
              </Select>
            </Form.Item>
          </Col>
        );
      case 'text':
      default:
        return (
          <Col span={colSpan} key={field.name}>
            <Form.Item name={field.name} label={label} initialValue={field.default}>
              <Input placeholder={field.placeholder} />
            </Form.Item>
          </Col>
        );
    }
  };

  // Hide mode-specific fields that don't apply to the current selection mode.
  const visibleFlatFields = (schema?.fields || []).filter((f) => {
    if (f.name === 'enabled') return false; // strategy-level toggle covers activation
    if (f.name === 'fixedProvider') return selection === 'FIXED';
    if (f.name === 'providerHeader') return selection === 'HEADER';
    return true;
  });

  const handleCreateCredential = async () => {
    let vals: { name: string; provider: string; apiKey: string };
    try {
      vals = await credForm.validateFields();
    } catch {
      return; // validation errors are shown inline
    }
    setCreating(true);
    try {
      const created = await createProviderCredential({
        name: vals.name,
        provider: vals.provider,
        apiKey: vals.apiKey,
      });
      message.success(t('strategy.ai.credential_created', { defaultValue: '凭据已创建（密钥已加密存储）' }));
      setCredModalOpen(false);
      credForm.resetFields();
      await loadCredentials();
      const rows = (form.getFieldValue('providerCredentials') || []) as Array<{ provider?: string; credentialRef?: string }>;
      if (!rows.some((r) => r?.provider === created.provider)) {
        form.setFieldValue('providerCredentials', [
          ...rows,
          { provider: created.provider, credentialRef: created.credentialRef },
        ]);
      }
    } catch (e) {
      message.error((e as Error)?.message || t('common.error_request_failed', { defaultValue: '创建失败' }));
    } finally {
      setCreating(false);
    }
  };

  const credentialOptions = credentials.map((c) => ({
    value: c.credentialRef,
    label: `${c.name} · ${c.provider}${c.keyHint ? ` · ${c.keyHint}` : ''}`,
  }));

  return (
    <>
      <Card
        size="small"
        style={{ marginBottom: 16 }}
        title={<Space><ApiOutlined /><span>{t('strategy.ai.basic', { defaultValue: '代理基本配置' })}</span></Space>}
      >
        <Row gutter={16}>
          {visibleFlatFields.map((field) => (
            <React.Fragment key={field.name}>{renderField(field)}</React.Fragment>
          ))}
        </Row>
      </Card>

      <Card
        size="small"
        style={{ marginBottom: 16 }}
        title={
          <Space>
            <KeyOutlined />
            <span>{t('strategy.ai.credentials', { defaultValue: 'Provider 凭据（API Key）' })}</span>
            <Tooltip title={t('strategy.ai.credentials_help', { defaultValue: '为每个 Provider 绑定一个凭据；密钥加密存储、只写不回显。' })}>
              <InfoCircleOutlined style={{ color: '#999' }} />
            </Tooltip>
          </Space>
        }
        extra={
          <Button size="small" icon={<PlusOutlined />} onClick={() => setCredModalOpen(true)}>
            {t('strategy.ai.new_credential', { defaultValue: '新建凭据' })}
          </Button>
        }
      >
        <Form.List name="providerCredentials">
          {(fields, { add, remove }) => (
            <>
              {fields.map(({ key, name }) => (
                <Row gutter={12} key={key} align="middle" style={{ marginBottom: 8 }}>
                  <Col span={9}>
                    <Form.Item name={[name, 'provider']} style={{ marginBottom: 0 }}>
                      <Select placeholder={t('strategy.ai.provider', { defaultValue: 'Provider' })} options={PROVIDERS} />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name={[name, 'credentialRef']} style={{ marginBottom: 0 }}>
                      <Select
                        placeholder={t('strategy.ai.credential', { defaultValue: '选择凭据' })}
                        showSearch
                        optionFilterProp="label"
                        options={credentialOptions}
                        notFoundContent={t('strategy.ai.no_credentials', { defaultValue: '暂无凭据，请先新建' })}
                      />
                    </Form.Item>
                  </Col>
                  <Col span={3}>
                    <Button type="text" danger icon={<DeleteOutlined />} onClick={() => remove(name)} />
                  </Col>
                </Row>
              ))}
              <Button
                type="dashed"
                block
                icon={<PlusOutlined />}
                onClick={() => add({ provider: undefined, credentialRef: undefined })}
              >
                {t('strategy.ai.add_credential_binding', { defaultValue: '绑定 Provider 凭据' })}
              </Button>
            </>
          )}
        </Form.List>
      </Card>

      <Card
        size="small"
        style={{ marginBottom: 16 }}
        title={
          <Space>
            <BranchesOutlined />
            <span>{t('strategy.ai.model_routing', { defaultValue: 'Model 路由别名（可选）' })}</span>
            <Tooltip title={t('strategy.ai.model_routing_help', { defaultValue: '把自定义 model 名显式指向某 Provider；不配则按内置前缀自动识别（gpt-→openai、qwen→qwen、deepseek-→deepseek、glm-→zhipu、kimi-→moonshot、claude-→anthropic）。' })}>
              <InfoCircleOutlined style={{ color: '#999' }} />
            </Tooltip>
          </Space>
        }
      >
        <Form.List name="modelRouting">
          {(fields, { add, remove }) => (
            <>
              {fields.map(({ key, name }) => (
                <Row gutter={12} key={key} align="middle" style={{ marginBottom: 8 }}>
                  <Col span={11}>
                    <Form.Item name={[name, 'model']} style={{ marginBottom: 0 }}>
                      <Input placeholder={t('strategy.ai.model_name', { defaultValue: 'model 名，如 my-gpt' })} />
                    </Form.Item>
                  </Col>
                  <Col span={10}>
                    <Form.Item name={[name, 'provider']} style={{ marginBottom: 0 }}>
                      <Select placeholder={t('strategy.ai.route_to', { defaultValue: '路由到 Provider' })} options={PROVIDERS} />
                    </Form.Item>
                  </Col>
                  <Col span={3}>
                    <Button type="text" danger icon={<DeleteOutlined />} onClick={() => remove(name)} />
                  </Col>
                </Row>
              ))}
              <Button type="dashed" block icon={<PlusOutlined />} onClick={() => add({ model: '', provider: undefined })}>
                {t('strategy.ai.add_model_route', { defaultValue: '添加 model 别名' })}
              </Button>
            </>
          )}
        </Form.List>
      </Card>

      <Modal
        title={<Space><RobotOutlined />{t('strategy.ai.new_credential', { defaultValue: '新建 Provider 凭据' })}</Space>}
        open={credModalOpen}
        onCancel={() => setCredModalOpen(false)}
        onOk={handleCreateCredential}
        confirmLoading={creating}
        okText={t('common.create', { defaultValue: '创建' })}
        cancelText={t('common.cancel', { defaultValue: '取消' })}
        destroyOnClose
      >
        <Form form={credForm} layout="vertical">
          <Form.Item name="name" label={t('strategy.ai.cred_name', { defaultValue: '凭据名称' })} rules={[{ required: true }]}>
            <Input placeholder="bailian-qwen" />
          </Form.Item>
          <Form.Item name="provider" label={t('strategy.ai.provider', { defaultValue: 'Provider' })} rules={[{ required: true }]}>
            <Select options={PROVIDERS} placeholder={t('strategy.ai.provider', { defaultValue: 'Provider' })} />
          </Form.Item>
          <Form.Item
            name="apiKey"
            label={t('strategy.ai.api_key', { defaultValue: 'API Key' })}
            rules={[{ required: true }]}
            extra={t('strategy.ai.api_key_help', { defaultValue: '加密存储，保存后不再显示明文。' })}
          >
            <Input.Password placeholder="sk-..." autoComplete="new-password" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}

export default AiProxyFields;
