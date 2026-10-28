import { Alert, Button, Col, Form, Input, InputNumber, Row, Select, Space, Switch, Typography } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';

const actions = [
  { value: 'BLOCK', label: '拦截' }, { value: 'REPLACE', label: '替换后放行' }, { value: 'DETECT', label: '仅检测（仍放行原内容）' },
];

function KeyValueFields({ name, label }: { name: string; label: string }) {
  return <><Typography.Paragraph>{label}</Typography.Paragraph>
    <Form.List name={name}>{(fields, { add, remove }) => <>
      {fields.map(({ key, name: index }) => <Space key={key} align="baseline" style={{ display: 'flex' }}>
        <Form.Item name={[index, 'key']} rules={[{ required: true, message: '填写参数名' }]}><Input placeholder="参数名" /></Form.Item>
        <Form.Item name={[index, 'value']} rules={[{ required: true, message: '填写值或环境变量引用' }]}>
          <Input placeholder="值，或 ${环境变量名}" style={{ width: 320 }} />
        </Form.Item>
        <Button icon={<DeleteOutlined />} onClick={() => remove(index)} aria-label="删除参数" />
      </Space>)}
      <Button onClick={() => add()} icon={<PlusOutlined />} style={{ marginBottom: 16 }}>添加参数</Button>
    </>}</Form.List>
  </>;
}

export function AiSecurityFields() {
  return <>
    <Alert type="info" showIcon style={{ marginBottom: 16 }} message="未配置外部服务时使用本地词表、正则和个人信息规则。内置检测不具备语义理解能力，不提供默认违禁词库。仅审核文本，不识别图片、音视频或文件内容。" />
    <Row gutter={16}>
      <Col span={12}><Form.Item name="scanRequest" label="审核请求" valuePropName="checked" initialValue={true}><Switch /></Form.Item></Col>
      <Col span={12}><Form.Item name="scanResponse" label="审核响应" valuePropName="checked" initialValue={true}><Switch /></Form.Item></Col>
      <Col span={12}><Form.Item name="inboundAction" label="请求违规处置" initialValue="BLOCK"><Select options={actions} /></Form.Item></Col>
      <Col span={12}><Form.Item name="outboundAction" label="响应违规处置" initialValue="REPLACE"><Select options={actions} /></Form.Item></Col>
    </Row>
    {([['bannedWords', '敏感词'], ['prohibitedWords', '违禁词'], ['bannedPatterns', '检测正则表达式']] as const).map(([name, label]) =>
      <Form.Item key={name} name={name} label={label} extra="输入一项后按回车；词表按不区分大小写的子串匹配，正则按 Java 语法执行。">
        <Select mode="tags" open={false} placeholder="输入后按回车添加" />
      </Form.Item>)}
    <Row gutter={16}>
      <Col span={8}><Form.Item name="enablePiiMasking" label="检测个人信息" valuePropName="checked" initialValue={true}><Switch /></Form.Item></Col>
      <Col span={16}><Form.Item name="piiAction" label="个人信息处置（独立于违规处置）" initialValue="REPLACE"><Select options={actions} /></Form.Item></Col>
    </Row>
    <Form.Item name="piiTypes" label="个人信息类型" initialValue={['EMAIL', 'PHONE', 'ID_CARD']}>
      <Select mode="multiple" options={[{ value: 'EMAIL', label: '邮箱' }, { value: 'PHONE', label: '大陆手机号' }, { value: 'ID_CARD', label: '18位身份证号格式' }]} />
    </Form.Item>
    <Form.Item name="blockMessage" label="拦截 / 外部违规替换提示" initialValue="请求或响应包含不被允许的内容，已被网关拦截。" rules={[{ required: true }]}><Input /></Form.Item>
    <Form.Item name="streamingPolicy" label="SSE 响应审核方式" initialValue="BUFFER" extra="完整审核会等待响应结束后一次性返回，失去逐字输出效果。逐片审核无法可靠拦截跨片词语。">
      <Select options={[{ value: 'BUFFER', label: '完整缓冲后审核（默认）' }, { value: 'BLOCK', label: '拒绝流式响应' }, { value: 'SKIP', label: '跳过流式响应审核，保留流式输出' }]} />
    </Form.Item>
    <Row gutter={16}>
      <Col span={12}><Form.Item name="maxBodySize" label="请求 / 响应审核大小上限（bytes）" initialValue={1048576}><InputNumber min={1024} max={10485760} style={{ width: '100%' }} /></Form.Item></Col>
      <Col span={12}><Form.Item name="responseTimeoutMs" label="完整响应等待上限（ms）" initialValue={60000}><InputNumber min={1000} max={300000} style={{ width: '100%' }} /></Form.Item></Col>
    </Row>
    <Typography.Title level={5}>外部审核（可选）</Typography.Title>
    <Alert type="info" showIcon style={{ marginBottom: 16 }} message="适配器由开发人员实现并随网关部署，负责请求构造、签名和响应判定。此处无需编写转换脚本。HTTP_JSON 仅支持约定协议，并非蚂蚁天鉴等服务的专用适配器。地址留空仅使用内置检测。" />
    <Form.Item name="externalUrl" label="审核服务地址"><Input placeholder="https://moderation.example/check，留空关闭外部调用" /></Form.Item>
    <Form.Item name="externalAdapter" label="已部署的 Java 适配器标识" initialValue="HTTP_JSON" rules={[{ pattern: /^[A-Za-z0-9_]{1,64}$/, message: '只支持字母、数字和下划线' }]}><Input /></Form.Item>
    <KeyValueFields name="externalHeaderRows" label="请求头（凭证建议填写网关环境变量引用）" />
    <KeyValueFields name="externalOptionRows" label="适配器参数（参数名由适配器文档约定）" />
    <Row gutter={16}>
      <Col span={12}><Form.Item name="externalTimeoutMs" label="外部审核总超时（ms）" initialValue={3000}><InputNumber min={100} max={30000} style={{ width: '100%' }} /></Form.Item></Col>
      <Col span={12}><Form.Item name="externalFailureAction" label="外部服务异常处置" initialValue="BLOCK"><Select options={[{ value: 'BLOCK', label: '拒绝请求 / 响应（默认）' }, { value: 'LOCAL', label: '退回内置规则（审核能力降低）' }]} /></Form.Item></Col>
    </Row>
  </>;
}
