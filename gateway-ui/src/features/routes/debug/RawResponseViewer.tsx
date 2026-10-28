import { memo, useMemo, useState } from 'react';
import { Button, Space, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { jsonTokens, rawFrames, type RawFrame } from './rawFrames';
import type { ResponseView } from './responseView';

function JsonCode({ value }: { value: unknown }) {
  const tokens = useMemo(() => jsonTokens(value), [value]);
  return <pre className="route-debug-json">{tokens.map((token, index) =>
    <span key={index} className={token.kind ? `json-${token.kind}` : undefined}>{token.text}</span>)}</pre>;
}

const EventFrame = memo(function EventFrame({ frame, number, single }: { frame: RawFrame; number: number; single: boolean }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(frame.usage || single);
  if (frame.kind === 'done') return <div className="route-debug-frame-done"><Tag color="green">#{number}</Tag> [DONE] · {t('routes.debug_stream_done')}</div>;
  return <details className={`route-debug-frame${frame.usage ? ' has-usage' : ''}`} open={open}
    onToggle={event => setOpen(event.currentTarget.open)}>
    <summary><Tag>#{number}</Tag> <Typography.Text code>{frame.event}</Typography.Text>
      {frame.usage && <Tag color="blue">{t('routes.debug_usage_event')}</Tag>}
      {frame.contentType && <Tag color={frame.contentType === 'answer' ? 'cyan' : 'default'}>{t(`routes.debug_frame_${frame.contentType}`)}</Tag>}
      {frame.kind === 'partial' && <Tag>{t('routes.debug_receiving_frame')}</Tag>}
      {frame.id && <Typography.Text type="secondary">{frame.id}</Typography.Text>}
      {frame.preview && <span className="route-debug-frame-preview">{frame.preview}</span>}
    </summary>
    {open && (frame.kind === 'json' ? <JsonCode value={frame.value} /> : <pre className="route-debug-json">{frame.data}</pre>)}
  </details>;
});

export default function RawResponseViewer({ raw, streaming, usage }: { raw: string; streaming: boolean; usage?: ResponseView['usage'] }) {
  const { t } = useTranslation();
  const [source, setSource] = useState(false);
  const frames = useMemo(() => streaming ? rawFrames(raw) : [], [raw, streaming]);
  const document = useMemo(() => {
    if (streaming) return null;
    try { return { value: JSON.parse(raw) }; } catch { return null; }
  }, [raw, streaming]);
  return <div className="route-debug-raw-view">
    <Space wrap className="route-debug-raw-toolbar">
      <Typography.Text type="secondary">{streaming ? t('routes.debug_event_count', { count: frames.length }) : document ? 'JSON' : t('routes.debug_plain_text')}</Typography.Text>
      <Button type="text" size="small" onClick={() => setSource(value => !value)}>{t(source ? 'routes.debug_structured' : 'routes.debug_source')}</Button>
    </Space>
    {usage && !source && <Space wrap className="route-debug-usage" style={{ marginBottom: 12 }}>
      <Tag>{t('routes.debug_input_tokens')}: {usage.input ?? '—'}</Tag>
      <Tag>{t('routes.debug_output_tokens')}: {usage.output ?? '—'}</Tag>
      <Tag color="blue">{t('routes.debug_total_tokens')}: {usage.total ?? '—'}</Tag>
    </Space>}
    {source ? <pre className="route-debug-output">{raw || t('routes.debug_no_body')}</pre> : streaming ?
      <div className="route-debug-frames">{frames.length ? frames.map((frame, index) =>
        <EventFrame key={`${index}-${frame.kind}`} frame={frame} number={index + 1} single={frames.length === 1} />)
        : <Typography.Text type="secondary">{raw ? t('routes.debug_waiting_frame') : t('routes.debug_no_body')}</Typography.Text>}</div>
      : <div className="route-debug-frames">{document ? <JsonCode value={document.value} /> : <pre className="route-debug-json">{raw || t('routes.debug_no_body')}</pre>}</div>}
  </div>;
}
