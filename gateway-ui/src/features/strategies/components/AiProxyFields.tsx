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
import { fieldUnit } from './schemaLabels';
import { message } from '../../../utils/antdStatic';
import {
  listProviderCredentials,
  createProviderCredential,
  type ProviderCredential,
} from '../../../api/providerCredentials';
import { palette } from '../../../styles/palette';

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

  /** Built-in provider catalogue — mirrors my-gateway ai/LlmProvider.java.
   * Labels are resolved during render so they follow the active language. */
  const providers = [
    { value: 'openai', label: t('strategy.ai.provider_openai') },
    { value: 'deepseek', label: t('strategy.ai.provider_deepseek') },
    { value: 'qwen', label: t('strategy.ai.provider_qwen') },
    { value: 'zhipu', label: t('strategy.ai.provider_zhipu') },
    { value: 'moonshot', label: t('strategy.ai.provider_moonshot') },
    { value: 'anthropic', label: t('strategy.ai.provider_anthropic') },
  ];

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
    // loadCredentials only calls setCredentials after `await listProviderCredentials()`, on both the
    // success and the catch path, so no state is set synchronously and there is no cascade to avoid.
    // The rule flags the call site because it cannot see through the async boundary.
    /* eslint-disable react-hooks/set-state-in-effect */
    loadCredentials();
    /* eslint-enable react-hooks/set-state-in-effect */
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
              <InputNumber min={field.min} max={field.max} addonAfter={fieldUnit(field)} style={{ width: '100%' }} />
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
      message.success(t('strategy.ai.credential_created'));
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
      message.error((e as Error)?.message || t('common.error_request_failed'));
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
        title={<Space><ApiOutlined /><span>{t('strategy.ai.basic')}</span></Space>}
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
            <span>{t('strategy.ai.credentials')}</span>
            <Tooltip title={t('strategy.ai.credentials_help')}>
              <InfoCircleOutlined style={{ color: palette.textMuted }} />
            </Tooltip>
          </Space>
        }
        extra={
          <Button size="small" icon={<PlusOutlined />} onClick={() => setCredModalOpen(true)}>
            {t('strategy.ai.new_credential')}
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
                      <Select placeholder={t('strategy.ai.provider')} options={providers} />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name={[name, 'credentialRef']} style={{ marginBottom: 0 }}>
                      <Select
                        placeholder={t('strategy.ai.credential')}
                        showSearch
                        optionFilterProp="label"
                        options={credentialOptions}
                        notFoundContent={t('strategy.ai.no_credentials')}
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
                {t('strategy.ai.add_credential_binding')}
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
            <ApiOutlined />
            <span>{t('strategy.ai.key_pools')}</span>
            <Tooltip title={t('strategy.ai.key_pools_help')}>
              <InfoCircleOutlined style={{ color: palette.textMuted }} />
            </Tooltip>
          </Space>
        }
      >
        <Form.List name="keyPools">
          {(fields, { add, remove }) => (
            <>
              {fields.map(({ key, name }) => (
                <Row gutter={12} key={key} align="middle" style={{ marginBottom: 8 }}>
                  <Col span={6}>
                    <Form.Item name={[name, 'provider']} style={{ marginBottom: 0 }}>
                      <Select placeholder={t('strategy.ai.provider')} options={providers} />
                    </Form.Item>
                  </Col>
                  <Col span={6}>
                    <Form.Item name={[name, 'strategy']} style={{ marginBottom: 0 }} initialValue="ROUND_ROBIN">
                      <Select
                        options={[
                          { value: 'ROUND_ROBIN', label: t('strategy.ai.lb_rr') },
                          { value: 'LEAST_INFLIGHT', label: t('strategy.ai.lb_least') },
                        ]}
                      />
                    </Form.Item>
                  </Col>
                  <Col span={9}>
                    <Form.Item name={[name, 'credentialRefs']} style={{ marginBottom: 0 }}>
                      <Select
                        mode="multiple"
                        placeholder={t('strategy.ai.pool_refs')}
                        showSearch
                        optionFilterProp="label"
                        options={credentialOptions}
                        notFoundContent={t('strategy.ai.no_credentials')}
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
                onClick={() => add({ provider: undefined, strategy: 'ROUND_ROBIN', credentialRefs: [] })}
              >
                {t('strategy.ai.add_key_pool')}
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
            <span>{t('strategy.ai.model_routing')}</span>
            <Tooltip title={t('strategy.ai.model_routing_help')}>
              <InfoCircleOutlined style={{ color: palette.textMuted }} />
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
                      <Input placeholder={t('strategy.ai.model_name')} />
                    </Form.Item>
                  </Col>
                  <Col span={10}>
                    <Form.Item name={[name, 'provider']} style={{ marginBottom: 0 }}>
                      <Select placeholder={t('strategy.ai.route_to')} options={providers} />
                    </Form.Item>
                  </Col>
                  <Col span={3}>
                    <Button type="text" danger icon={<DeleteOutlined />} onClick={() => remove(name)} />
                  </Col>
                </Row>
              ))}
              <Button type="dashed" block icon={<PlusOutlined />} onClick={() => add({ model: '', provider: undefined })}>
                {t('strategy.ai.add_model_route')}
              </Button>
            </>
          )}
        </Form.List>
      </Card>

      <Modal
        title={<Space><RobotOutlined />{t('strategy.ai.new_provider_credential')}</Space>}
        open={credModalOpen}
        onCancel={() => setCredModalOpen(false)}
        onOk={handleCreateCredential}
        confirmLoading={creating}
        okText={t('common.create')}
        cancelText={t('common.cancel')}
        destroyOnHidden
      >
        <Form form={credForm} layout="vertical">
          <Form.Item name="name" label={t('strategy.ai.cred_name')} rules={[{ required: true }]}>
            <Input placeholder={t('strategy.ai.cred_name_placeholder')} />
          </Form.Item>
          <Form.Item name="provider" label={t('strategy.ai.provider')} rules={[{ required: true }]}>
            <Select options={providers} placeholder={t('strategy.ai.provider')} />
          </Form.Item>
          <Form.Item
            name="apiKey"
            label={t('strategy.ai.api_key')}
            rules={[{ required: true }]}
            extra={t('strategy.ai.api_key_help')}
          >
            <Input.Password placeholder="sk-..." autoComplete="new-password" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}

export default AiProxyFields;
