import { Alert, Button, Col, Form, Input, InputNumber, Row, Select, Space, Switch, Typography } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

function KeyValueFields({ name, label }: { name: string; label: string }) {
  const { t } = useTranslation();
  return <><Typography.Paragraph>{label}</Typography.Paragraph>
    <Form.List name={name}>{(fields, { add, remove }) => <>
      {fields.map(({ key, name: index }) => <Space key={key} align="baseline" style={{ display: 'flex' }}>
        <Form.Item name={[index, 'key']} rules={[{ required: true, message: t('strategy.ai_security.param_key_required') }]}><Input placeholder={t('strategy.ai_security.param_key_placeholder')} /></Form.Item>
        <Form.Item name={[index, 'value']} rules={[{ required: true, message: t('strategy.ai_security.param_value_required') }]}>
          <Input placeholder={t('strategy.ai_security.param_value_placeholder')} style={{ width: 320 }} />
        </Form.Item>
        <Button icon={<DeleteOutlined />} onClick={() => remove(index)} aria-label={t('strategy.ai_security.param_remove')} />
      </Space>)}
      <Button onClick={() => add()} icon={<PlusOutlined />} style={{ marginBottom: 16 }}>{t('strategy.ai_security.param_add')}</Button>
    </>}</Form.List>
  </>;
}

export function AiSecurityFields() {
  const { t } = useTranslation();
  // Built during render so the option labels follow the active language.
  const actions = [
    { value: 'BLOCK', label: t('strategy.ai_security.action_block') },
    { value: 'REPLACE', label: t('strategy.ai_security.action_replace') },
    { value: 'DETECT', label: t('strategy.ai_security.action_detect') },
  ];
  return <>
    <Alert type="info" showIcon style={{ marginBottom: 16 }} title={t('strategy.ai_security.builtin_hint')} />
    <Row gutter={16}>
      <Col span={12}><Form.Item name="scanRequest" label={t('strategy.ai_security.scan_request')} valuePropName="checked" initialValue={true}><Switch /></Form.Item></Col>
      <Col span={12}><Form.Item name="scanResponse" label={t('strategy.ai_security.scan_response')} valuePropName="checked" initialValue={true}><Switch /></Form.Item></Col>
      <Col span={12}><Form.Item name="inboundAction" label={t('strategy.ai_security.inbound_action')} initialValue="BLOCK"><Select options={actions} /></Form.Item></Col>
      <Col span={12}><Form.Item name="outboundAction" label={t('strategy.ai_security.outbound_action')} initialValue="REPLACE"><Select options={actions} /></Form.Item></Col>
    </Row>
    {([['bannedWords', t('strategy.ai_security.banned_words')], ['prohibitedWords', t('strategy.ai_security.prohibited_words')], ['bannedPatterns', t('strategy.ai_security.banned_patterns')]] as const).map(([name, label]) =>
      <Form.Item key={name} name={name} label={label} extra={t('strategy.ai_security.word_list_extra')}>
        <Select mode="tags" open={false} placeholder={t('strategy.ai_security.word_list_placeholder')} />
      </Form.Item>)}
    <Row gutter={16}>
      <Col span={8}><Form.Item name="enablePiiMasking" label={t('strategy.ai_security.enable_pii')} valuePropName="checked" initialValue={true}><Switch /></Form.Item></Col>
      <Col span={16}><Form.Item name="piiAction" label={t('strategy.ai_security.pii_action')} initialValue="REPLACE"><Select options={actions} /></Form.Item></Col>
    </Row>
    <Form.Item name="piiTypes" label={t('strategy.ai_security.pii_types')} initialValue={['EMAIL', 'PHONE', 'ID_CARD']}>
      <Select mode="multiple" options={[{ value: 'EMAIL', label: t('strategy.ai_security.pii_email') }, { value: 'PHONE', label: t('strategy.ai_security.pii_phone') }, { value: 'ID_CARD', label: t('strategy.ai_security.pii_id_card') }]} />
    </Form.Item>
    {/* blockMessage's initialValue is persisted into the strategy config and returned to callers by
        the gateway at runtime: it is data, not a UI label, so it stays untranslated. */}
    <Form.Item name="blockMessage" label={t('strategy.ai_security.block_message')} initialValue="请求或响应包含不被允许的内容，已被网关拦截。" rules={[{ required: true }]}><Input /></Form.Item>
    <Form.Item name="streamingPolicy" label={t('strategy.ai_security.streaming_policy')} initialValue="BUFFER" extra={t('strategy.ai_security.streaming_policy_extra')}>
      <Select options={[{ value: 'BUFFER', label: t('strategy.ai_security.streaming_buffer') }, { value: 'BLOCK', label: t('strategy.ai_security.streaming_block') }, { value: 'SKIP', label: t('strategy.ai_security.streaming_skip') }]} />
    </Form.Item>
    <Row gutter={16}>
      <Col span={12}><Form.Item name="maxBodySize" label={t('strategy.ai_security.max_body_size')} initialValue={1048576}><InputNumber min={1024} max={10485760} style={{ width: '100%' }} /></Form.Item></Col>
      <Col span={12}><Form.Item name="responseTimeoutMs" label={t('strategy.ai_security.response_timeout')} initialValue={60000}><InputNumber min={1000} max={300000} style={{ width: '100%' }} /></Form.Item></Col>
    </Row>
    <Typography.Title level={5}>{t('strategy.ai_security.external_title')}</Typography.Title>
    <Alert type="info" showIcon style={{ marginBottom: 16 }} title={t('strategy.ai_security.external_hint')} />
    <Form.Item name="externalUrl" label={t('strategy.ai_security.external_url')}><Input placeholder={t('strategy.ai_security.external_url_placeholder')} /></Form.Item>
    <Form.Item name="externalAdapter" label={t('strategy.ai_security.external_adapter')} initialValue="HTTP_JSON" rules={[{ pattern: /^[A-Za-z0-9_]{1,64}$/, message: t('strategy.ai_security.external_adapter_pattern') }]}><Input /></Form.Item>
    <KeyValueFields name="externalHeaderRows" label={t('strategy.ai_security.external_headers')} />
    <KeyValueFields name="externalOptionRows" label={t('strategy.ai_security.external_options')} />
    <Row gutter={16}>
      <Col span={12}><Form.Item name="externalTimeoutMs" label={t('strategy.ai_security.external_timeout')} initialValue={3000}><InputNumber min={100} max={30000} style={{ width: '100%' }} /></Form.Item></Col>
      <Col span={12}><Form.Item name="externalFailureAction" label={t('strategy.ai_security.external_failure_action')} initialValue="BLOCK"><Select options={[{ value: 'BLOCK', label: t('strategy.ai_security.external_failure_block') }, { value: 'LOCAL', label: t('strategy.ai_security.external_failure_local') }]} /></Form.Item></Col>
    </Row>
  </>;
}
