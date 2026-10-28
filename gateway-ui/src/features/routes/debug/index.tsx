import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Card, Input, InputNumber, Result, Segmented, Select, Space, Spin, Tabs, Tag, Typography } from 'antd';
import { ArrowLeftOutlined, BugOutlined, SendOutlined, StopOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { getRouteById } from '../../../api/routes';
import { debugRoute, getDebugAuthOptions, type DebugEvent } from '../../../api/routeDebug';
import { useRequest } from '../../../hooks/useRequest';
import { useInstanceDetail } from '../../instance-detail/InstanceDetailContext';
import type { RouteResponse } from '../../../types/route';
import { debugDefaults, parseDebugHeaders } from './defaults';
import { responseView } from './responseView';
import RawResponseViewer from './RawResponseViewer';
import { requestJourneyUrl, routeConfigurationUrl } from '../../request-journey/links';
import './route-debug.css';

const { Text, Title } = Typography;

function Workbench({ route }: { route: RouteResponse }) {
  const { t } = useTranslation();
  const { instance } = useInstanceDetail();
  const { instanceId = '' } = useParams();
  const navigate = useNavigate();
  const defaults = debugDefaults(route);
  const [method, setMethod] = useState(defaults.method);
  const [path, setPath] = useState(defaults.path);
  const [headers, setHeaders] = useState(defaults.headers);
  const [body, setBody] = useState(defaults.body);
  const [timeout, setTimeoutSeconds] = useState(30);
  const [busy, setBusy] = useState(false);
  const [meta, setMeta] = useState<DebugEvent | null>(null);
  const [output, setOutput] = useState('');
  const [responseMode, setResponseMode] = useState<'readable' | 'raw'>('readable');
  const [completion, setCompletion] = useState<DebugEvent | null>(null);
  const [error, setError] = useState('');
  const [policyId, setPolicyId] = useState<string | null>();
  const [credential, setCredential] = useState('');
  const [credentialId, setCredentialId] = useState<string>();
  const policiesRequest = useRequest((signal) => getDebugAuthOptions(instanceId, route.id, { signal }), { deps: [instanceId, route.id] });
  const policies = policiesRequest.data ?? [];
  const activePolicyId = policyId === undefined ? policies.find((policy) => policy.bound)?.policyId : policyId || undefined;
  const selectedPolicy = policies.find((policy) => policy.policyId === activePolicyId);
  const abort = useRef<AbortController | null>(null);
  const generation = useRef(0);
  useEffect(() => () => { generation.current += 1; abort.current?.abort(); }, []);

  const send = async () => {
    let requestHeaders: Record<string, string>;
    try { requestHeaders = parseDebugHeaders(headers); }
    catch { setError(t('routes.debug_headers_invalid')); return; }
    if (!path.startsWith('/') || path.startsWith('//')) { setError(t('routes.debug_path_invalid')); return; }
    abort.current?.abort();
    const controller = new AbortController();
    abort.current = controller;
    const current = ++generation.current;
    setBusy(true); setError(''); setOutput(''); setMeta(null); setCompletion(null);
    try {
      if (activePolicyId && !selectedPolicy) throw new Error(t('routes.debug_auth_failed'));
      if (selectedPolicy && ((!selectedPolicy.automatic && !credential.trim())
        || (selectedPolicy.credentials.length > 1 && !credentialId && !credential.trim()))) {
        throw new Error(t('routes.debug_auth_required'));
      }
      await debugRoute(instanceId, route.id, { method, path, headers: requestHeaders,
        authPolicyId: activePolicyId, credential: credential || undefined, credentialId,
        body: ['GET', 'HEAD'].includes(method) ? '' : body, timeoutSeconds: timeout }, controller.signal, (name, event) => {
        if (current !== generation.current) return;
        if (name === 'response') setMeta(event);
        if (name === 'chunk') setOutput((old) => (old + (event.text || '')).slice(0, 262144));
        if (name === 'complete') setCompletion(event);
        if (name === 'failure') { setError(event.message || t('routes.debug_failed')); setCompletion(event); }
      });
    } catch (e) {
      if (current === generation.current) setError(controller.signal.aborted ? t('routes.debug_cancelled') : e instanceof Error ? e.message : t('routes.debug_failed'));
    } finally { if (current === generation.current) setBusy(false); }
  };

  const responseHeaders = meta?.headers ?? {};
  const traceId = Object.entries(responseHeaders).find(([name]) => name.toLowerCase() === 'x-trace-id')?.[1]?.[0];
  const matchedRoute = Object.entries(responseHeaders).find(([name]) => name.toLowerCase() === 'x-gateway-route-id')?.[1]?.[0];
  const sse = Object.entries(responseHeaders).some(([name, values]) => name.toLowerCase() === 'content-type' && values.some((value) => value.includes('text/event-stream')));
  const readable = useMemo(() => responseView(output, sse), [output, sse]);
  const gateway = instance.effectiveAccessUrl || instance.manualAccessUrl || instance.reportedAccessUrl || instance.discoveredAccessUrl || '';

  return <div className="route-debug-page">
    <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate(`/instances/${instanceId}/routes`)}>{t('routes.debug_back')}</Button>
    <div className="route-debug-heading"><BugOutlined /><div><Title level={3}>{t('routes.debug_title')}</Title><Text type="secondary">{route.routeName} · {route.id}</Text></div></div>
    {!route.enabled && <Alert type="warning" showIcon title={t('routes.debug_disabled')} />}
    <div className="route-debug-grid">
      <Card title={t('routes.debug_request')}>
        <label className="route-debug-label">{t('routes.debug_gateway')}</label>
        <Input value={gateway} readOnly placeholder={t('routes.debug_no_gateway')} />
        <label className="route-debug-label">{t('routes.debug_path')}</label>
        <Space.Compact block><Select aria-label={t('routes.debug_method')} value={method} onChange={setMethod} options={['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'].map((value) => ({ value, label: value }))} style={{ width: 110 }} /><Input aria-label={t('routes.debug_path')} value={path} onChange={(e) => setPath(e.target.value)} /></Space.Compact>
        <Text type="secondary" className="route-debug-hint">{t('routes.debug_path_hint')}</Text>
        <label className="route-debug-label">{t('routes.debug_auth')}</label>
        <Select allowClear showSearch optionFilterProp="label" style={{ width: '100%' }}
          aria-label={t('routes.debug_auth')} value={activePolicyId} loading={policiesRequest.loading}
          placeholder={t('routes.debug_auth_none')} onChange={(id) => { setPolicyId(id ?? null); setCredential(''); setCredentialId(undefined); }}
          options={[true, false].map((isBound) => ({ label: t(isBound ? 'routes.debug_auth_bound' : 'routes.debug_auth_other'),
            options: policies.filter((policy) => policy.bound === isBound).map((policy) => ({
              value: policy.policyId, label: `${policy.policyName} · ${policy.authType}`,
            })),
          })).filter((group) => group.options.length > 0)} />
        {policiesRequest.error && <Alert type="warning" title={t('routes.debug_auth_failed')} action={<Button size="small" onClick={() => policiesRequest.run()}>{t('common.retry')}</Button>} />}
        {selectedPolicy && <>
          <Text type="secondary" className="route-debug-hint">{t(selectedPolicy.authType === 'HMAC' ? 'routes.debug_auth_hmac' : 'routes.debug_auth_hint')}</Text>
          {selectedPolicy.credentials.length > 0 && <>
            <label className="route-debug-label">{t('routes.debug_auth_key')}</label>
            <Select allowClear aria-label={t('routes.debug_auth_key')} style={{ width: '100%' }} value={credentialId || (selectedPolicy.credentials.length === 1 ? selectedPolicy.credentials[0].id : undefined)}
              onChange={setCredentialId} options={selectedPolicy.credentials.map((item) => ({ value: item.id, label: item.label }))} />
          </>}
          {selectedPolicy.authType !== 'HMAC' && selectedPolicy.credentials.length === 0 && <>
            <label className="route-debug-label">{t('routes.debug_auth_credential')}</label>
            <Input.Password autoComplete="off" aria-label={t('routes.debug_auth_credential')} value={credential} onChange={(e) => setCredential(e.target.value)} placeholder={t('routes.debug_auth_placeholder')} />
          </>}
        </>}
        <Tabs items={[
          { key: 'headers', label: t('routes.debug_headers'), children: <Input.TextArea aria-label={t('routes.debug_headers')} value={headers} onChange={(e) => setHeaders(e.target.value)} rows={10} className="route-debug-code" spellCheck={false} /> },
          { key: 'body', label: t('routes.debug_body'), children: <Input.TextArea aria-label={t('routes.debug_body')} value={body} onChange={(e) => setBody(e.target.value)} rows={10} disabled={['GET', 'HEAD'].includes(method)} className="route-debug-code" spellCheck={false} /> },
        ]} />
        <div className="route-debug-send"><Space><Text>{t('routes.debug_timeout')}</Text><InputNumber aria-label={t('routes.debug_timeout')} min={1} max={60} value={timeout} onChange={(value) => setTimeoutSeconds(value ?? 30)} /><Text>s</Text></Space>
          {busy ? <Button danger icon={<StopOutlined />} onClick={() => abort.current?.abort()}>{t('routes.debug_stop')}</Button> : <Button type="primary" icon={<SendOutlined />} disabled={!gateway || policiesRequest.loading} onClick={send}>{t('routes.debug_send')}</Button>}
        </div>
      </Card>
      <Card title={t('routes.debug_response')} extra={busy && <Spin size="small" />}>
        <Space wrap>{meta && <Tag color={meta.status! >= 400 ? 'red' : 'green'}>HTTP {meta.status}</Tag>}{meta?.firstByteMs !== undefined && <Text type="secondary">{t('routes.debug_first_byte')}: {meta.firstByteMs}ms</Text>}{completion?.durationMs !== undefined && <Text type="secondary">{t('routes.debug_duration')}: {completion.durationMs}ms</Text>}</Space>
        {traceId && <div className="route-debug-trace"><Text type="secondary">Trace ID: </Text><Text code copyable>{traceId}</Text></div>}
        {traceId && <Space wrap style={{ marginBottom: 16 }}>
          <Button type="primary" onClick={() => navigate(requestJourneyUrl(instanceId, traceId, route.id))}>{t('journey.inspect')}</Button>
          <Button onClick={() => navigate(routeConfigurationUrl(instanceId, matchedRoute || route.id))}>{t('journey.openConfiguration')}</Button>
        </Space>}
        {matchedRoute && matchedRoute !== route.id && <Alert type="warning" showIcon title={t('journey.routeMismatch')} description={`${route.id} → ${matchedRoute}`} />}
        {error && <Alert type="error" showIcon title={error} />}
        {completion?.truncated && <Alert type="warning" showIcon title={t('routes.debug_truncated')} />}
        {sse && completion && !error && !readable.complete && route.category === 'AI' && <Alert type="warning" showIcon title={t('routes.debug_incomplete_stream')} />}
        {!meta && !busy && !error ? <div className="route-debug-empty">{t('routes.debug_empty')}</div> : <Tabs items={[
          { key: 'body', label: t('routes.debug_response_body'), children: <>
            <Segmented aria-label={t('routes.debug_display_mode')} value={responseMode} onChange={setResponseMode}
              options={[{ label: t('routes.debug_readable'), value: 'readable' }, { label: t('routes.debug_raw'), value: 'raw' }]} />
            {responseMode === 'raw' ? <RawResponseViewer raw={output} streaming={sse} usage={readable.usage} /> : <>
              {readable.usage && <Space wrap className="route-debug-usage">
                <Tag>{t('routes.debug_input_tokens')}: {readable.usage.input ?? '—'}</Tag>
                <Tag>{t('routes.debug_output_tokens')}: {readable.usage.output ?? '—'}</Tag>
                <Tag color="blue">{t('routes.debug_total_tokens')}: {readable.usage.total ?? '—'}</Tag>
              </Space>}
              <pre className="route-debug-output route-debug-readable">{readable.modelResponse ? readable.answer ||
                (busy ? t('routes.debug_generating') : readable.tools.length ? t('routes.debug_tools_only') : t('routes.debug_no_answer')) :
                sse ? (busy ? t('routes.debug_waiting') : t('routes.debug_unparsed_stream')) : readable.formatted || t('routes.debug_no_body')}</pre>
              {readable.reasoning && <details className="route-debug-secondary"><summary>{t('routes.debug_reasoning')}</summary><pre>{readable.reasoning}</pre></details>}
              {readable.tools.length > 0 && <details className="route-debug-secondary"><summary>{t('routes.debug_tool_calls')}</summary><pre>{JSON.stringify(readable.tools, null, 2)}</pre></details>}
            </>}
          </> },
          { key: 'headers', label: t('routes.debug_response_headers'), children: <pre className="route-debug-output">{JSON.stringify(responseHeaders, null, 2)}</pre> },
        ]} />}
      </Card>
    </div>
  </div>;
}

export default function RouteDebugPage() {
  const { routeId = '', instanceId = '' } = useParams();
  const { t } = useTranslation();
  const request = useRequest((signal) => getRouteById(routeId, { signal }), { deps: [routeId, instanceId] });
  if (request.loading || (!request.data && !request.error)) return <Spin />;
  if (!request.data || request.data.id !== routeId || request.error) return <Result status="warning" title={t('routes.debug_load_failed')} extra={<Button onClick={() => request.run()}>{t('common.retry')}</Button>} />;
  return <Workbench key={`${instanceId}:${routeId}`} route={request.data} />;
}
