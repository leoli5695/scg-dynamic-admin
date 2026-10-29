import React, { useEffect, useRef, useState } from 'react';
import { Button, Empty, Input, Select, Spin, Tooltip, Typography } from 'antd';
import { message } from '../../../utils/antdStatic';
import {
  ClearOutlined,
  DislikeFilled,
  DislikeOutlined,
  ExclamationCircleOutlined,
  LikeFilled,
  LikeOutlined,
  RobotOutlined,
  SendOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { clearCopilotChat, executeCopilotAction, getCopilotHistory, sendCopilotChatStream, submitCopilotFeedback } from '../../../api/copilot';
import { listRoutes } from '../../../api/routes';
import { useRequest } from '../../../hooks/useRequest';
import { ApiError, isAbortError } from '../../../api/http';
import type { CopilotPendingAction } from '../../../types/copilot';
import AiReportRenderer from '../../../components/ai/AiReportRenderer';
import { useAuth } from '../../auth/AuthContext';
import { copilotSessionStorageKey, createCopilotSessionId } from '../utils/session';
import '../../../styles/ai-report.css';

const { Text } = Typography;
const { TextArea } = Input;

interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  timestamp: Date;
  /** true 仅用于真正来自模型的回答（本地确认/取消提示不算），决定是否显示反馈按钮。 */
  fromAi?: boolean;
  /** Confirmed write actions link to the record they actually changed. */
  link?: { href: string; label: string };
}

interface ChatTabProps {
  instanceId: string;
  provider: string;
  model: string;
}

const ChatTab: React.FC<ChatTabProps> = ({ instanceId, provider, model }) => {
  const { t } = useTranslation();
  const { user } = useAuth();
  const navigate = useNavigate();
  const sessionKey = copilotSessionStorageKey(user?.username || 'anonymous', instanceId || 'all');
  const [traceLookupId, setTraceLookupId] = useState('');
  const [routeSelection, setRouteSelection] = useState<{ sessionKey: string; routeId: string }>();
  const routesRequest = useRequest(async (signal) => ({
    instanceId,
    routes: await listRoutes({ instanceId }, { signal }),
  }), { deps: [instanceId] });
  // useRequest retains old data during refresh; never expose it under the next instance.
  const routes = routesRequest.data?.instanceId === instanceId ? routesRequest.data.routes : [];
  const targetRouteId = routeSelection?.sessionKey === sessionKey
    && !routesRequest.loading && !routesRequest.error
    && routes.some(route => route.id === routeSelection.routeId) ? routeSelection.routeId : undefined;
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [inputMessage, setInputMessage] = useState('');
  const [loading, setLoading] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(true);
  const [clearing, setClearing] = useState(false);
  const [sessionId, setSessionId] = useState('');
  const [pendingAction, setPendingAction] = useState<CopilotPendingAction | null>(null);
  const [executing, setExecuting] = useState(false);
  const [streamNote, setStreamNote] = useState('');
  const [streamingContent, setStreamingContent] = useState('');
  // 终态事件（message/error）已到达：进度提示必须立刻退场，绝不与正式回答同框
  const [streamDone, setStreamDone] = useState(false);
  const [feedback, setFeedback] = useState<Record<number, 'UP' | 'DOWN'>>({});
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const followBottomRef = useRef(true);
  const abortRef = useRef<AbortController | null>(null);
  const epochRef = useRef(0);
  const sendingRef = useRef(false);
  const executingRef = useRef(false);
  const clearingRef = useRef(false);
  const quickBusy = loading || historyLoading || clearing || executing;

  // 按账号与实例保存 sessionId，刷新后恢复该会话的持久化历史。
  useEffect(() => {
    const epoch = ++epochRef.current;
    const controller = new AbortController();
    abortRef.current?.abort();
    abortRef.current = null;
    sendingRef.current = false;
    executingRef.current = false;
    clearingRef.current = false;
    followBottomRef.current = true;
    // A session switch has to show an empty pane before the restored history arrives, so these
    // resets are the intent rather than a cascade to avoid. The clean fix is `key={sessionKey}` on
    // the tab child at copilot/index.tsx:160, but this component carries 12 useState and there are
    // no front-end tests, so that refactor should not happen blind inside a lint cleanup.
    /* eslint-disable react-hooks/set-state-in-effect */
    setMessages([]); setPendingAction(null); setFeedback({}); setInputMessage('');
    setRouteSelection(undefined); setTraceLookupId('');
    setLoading(false); setExecuting(false); setClearing(false); setHistoryLoading(true);
    setStreamNote(''); setStreamingContent(''); setStreamDone(false);
    /* eslint-enable react-hooks/set-state-in-effect */
    (async () => {
      let sid = localStorage.getItem(sessionKey);
      if (!sid) {
        sid = createCopilotSessionId();
        localStorage.setItem(sessionKey, sid);
      }
      setSessionId(sid);
      try {
        const h = await getCopilotHistory(sid, { signal: controller.signal });
        if (epochRef.current !== epoch) return;
        if (h.messages && h.messages.length > 0) {
          setMessages(h.messages.map(m => ({
            role: m.role,
            content: m.content,
            timestamp: new Date(),
            fromAi: m.role === 'assistant',
          })));
        }
      } catch (e) {
        if (!controller.signal.aborted) console.error('load copilot history failed', e);
      } finally {
        if (epochRef.current === epoch) setHistoryLoading(false);
      }
    })();
    return () => {
      // Bumping the shared epoch is the whole point: it is what makes an in-flight history load or
      // stream belonging to the previous session bail at its next guard. Copying epochRef.current
      // into a local first, as the rule suggests, would leave the counter un-bumped and defeat it.
      // eslint-disable-next-line react-hooks/exhaustive-deps
      epochRef.current++;
      controller.abort();
      abortRef.current?.abort();
    };
  }, [sessionKey]);

  // Abort any in-flight stream on unmount
  useEffect(() => () => { abortRef.current?.abort(); }, []);

  // Scroll to bottom when messages change or the answer streams in
  useEffect(() => {
    if (followBottomRef.current) messagesEndRef.current?.scrollIntoView({ behavior: loading ? 'auto' : 'smooth' });
  }, [messages, streamingContent, loading]);

  const handleSendMessage = async (override?: string) => {
    const content = (override ?? inputMessage).trim();
    if (sendingRef.current || executingRef.current || historyLoading || clearingRef.current || !sessionId) return;
    if (!content) return;
    sendingRef.current = true;
    followBottomRef.current = true;
    const epoch = ++epochRef.current;

    const userMessage: ChatMessage = {
      role: 'user',
      content,
      timestamp: new Date(),
    };

    setMessages(prev => [...prev, userMessage]);
    if (!override) setInputMessage('');
    setLoading(true);
    setStreamNote(t('copilot.ai_thinking'));
    setStreamingContent('');
    setStreamDone(false);
    setPendingAction(null);

    const controller = new AbortController();
    abortRef.current = controller;
    const current = () => epochRef.current === epoch && !controller.signal.aborted;

    try {
      await sendCopilotChatStream(
        {
          sessionId,
          message: userMessage.content,
          // context 不传递，让后端根据消息内容自动识别意图
          instanceId,
          provider: provider || undefined,
          model: model || undefined,
        },
        {
          signal: controller.signal,
          onStatus: () => { if (current()) setStreamNote(t('copilot.ai_thinking')); },
          onTool: (data) => {
            if (!current()) return;
            if (data.status === 'running') {
              // 新一轮工具调用：清掉可能残留的分片文本，进度以 streamNote 呈现
              setStreamingContent('');
              setStreamNote(t('copilot.calling_tool', { tool: data.toolName }));
            } else if (data.status === 'success') {
              setStreamNote(t('copilot.tool_done', { tool: data.toolName, ms: data.durationMs ?? 0 }));
            } else if (data.status === 'error') {
              setStreamNote(t('copilot.tool_failed', { tool: data.toolName }));
            } else if (data.status === 'confirm') {
              setStreamNote(t('copilot.please_confirm'));
            }
          },
          onDelta: (text) => { if (current()) setStreamingContent(prev => prev + text); },
          onMessage: (response) => {
            if (!current()) return;
            // 终态：用完整回答（含证据说明）替换打字机临时内容
            setStreamingContent('');
            setStreamDone(true);
            if (response.success) {
              setMessages(prev => [...prev, {
                role: 'assistant',
                content: response.response || '',
                timestamp: new Date(),
                fromAi: true,
              }]);
              setSessionId(response.sessionId);
              localStorage.setItem(sessionKey, response.sessionId);
              // AI proposed a write operation — surface it for explicit human confirmation.
              setPendingAction(response.pendingAction ?? null);
            } else {
              message.error(response.error || t('copilot.chat_failed'));
            }
          },
          onError: (msg) => {
            if (!current()) return;
            setStreamDone(true);
            message.error(msg || t('copilot.chat_failed'));
          },
        },
      );
    } catch (e) {
      if (current() && !isAbortError(e)) {
        message.error(e instanceof ApiError && e.message ? e.message : t('copilot.stream_interrupted'));
      }
    } finally {
      if (epochRef.current === epoch) {
        abortRef.current = null;
        sendingRef.current = false;
        setStreamNote('');
        setStreamingContent('');
        setLoading(false);
      }
    }
  };

  const handleConfirmAction = async () => {
    if (!pendingAction || executingRef.current || sendingRef.current || clearingRef.current) return;
    executingRef.current = true;
    const epoch = epochRef.current;
    setExecuting(true);
    try {
      const result = await executeCopilotAction({
        ticket: pendingAction.ticket,
        toolName: pendingAction.toolName,
      });
      if (epochRef.current !== epoch) return;
      const changedRouteId = (result.data as { routeId?: unknown } | null | undefined)?.routeId;
      const assistantMessage: ChatMessage = {
        role: 'assistant',
        content: result.success
          ? `✅ ${result.message || t('copilot.operation_success')}`
          : `❌ ${result.message || t('copilot.operation_failed')}`,
        timestamp: new Date(),
        link: result.success && typeof changedRouteId === 'string'
          ? {
              href: `/instances/${encodeURIComponent(instanceId)}/routes?highlight=${encodeURIComponent(changedRouteId)}`,
              label: t('copilot.open_route_record'),
            }
          : undefined,
      };
      setMessages(prev => [...prev, assistantMessage]);
      if (result.success) {
        message.success(t('copilot.operation_success'));
      } else {
        message.error(result.message || t('copilot.operation_failed'));
      }
    } catch (e) {
      if (epochRef.current === epoch) message.error(e instanceof ApiError && e.message ? e.message : t('copilot.operation_failed'));
    } finally {
      if (epochRef.current === epoch) {
        executingRef.current = false;
        setPendingAction(null);
        setExecuting(false);
      }
    }
  };

  const handleCancelAction = () => {
    setPendingAction(null);
    setMessages(prev => [...prev, {
      role: 'assistant',
      content: t('copilot.confirm_cancel'),
      timestamp: new Date(),
    }]);
  };

  const handleFeedback = async (index: number, rating: 'UP' | 'DOWN', answer: string) => {
    if (feedback[index]) return; // 已评过，不重复提交
    // 取该回答之前最近的一条用户消息作为问题快照
    let question = '';
    for (let i = index - 1; i >= 0; i--) {
      if (messages[i].role === 'user') {
        question = messages[i].content;
        break;
      }
    }
    // 先乐观置位，避免连点重复提交
    setFeedback(prev => ({ ...prev, [index]: rating }));
    try {
      await submitCopilotFeedback({ sessionId, question, answer, rating });
    } catch {
      setFeedback(prev => {
        const next = { ...prev };
        delete next[index];
        return next;
      });
      message.error(t('copilot.feedback_failed'));
    }
  };

  const handleClearConversation = async () => {
    if (clearingRef.current || executingRef.current || historyLoading || !sessionId) return;
    clearingRef.current = true;
    const epoch = ++epochRef.current;
    setClearing(true);
    abortRef.current?.abort(); // 中止在途流式请求，避免旧会话的回复串进来
    abortRef.current = null;
    sendingRef.current = false;
    setLoading(false); setStreamingContent(''); setStreamDone(false);
    try {
      await clearCopilotChat(sessionId);
      if (epochRef.current !== epoch) return;
      setMessages([]);
      setPendingAction(null);
      setStreamNote('');
      setFeedback({});
      const newSid = createCopilotSessionId();
      localStorage.setItem(sessionKey, newSid);
      setSessionId(newSid);
      message.success(t('copilot.conversation_cleared'));
    } catch {
      if (epochRef.current === epoch) message.error(t('copilot.clear_failed'));
    } finally {
      if (epochRef.current === epoch) { clearingRef.current = false; setClearing(false); }
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 350px)' }}>
      {/* Messages Area */}
      <div
        onScroll={event => {
          const area = event.currentTarget;
          followBottomRef.current = area.scrollHeight - area.scrollTop - area.clientHeight < 80;
        }}
        style={{
          flex: 1,
          overflowY: 'auto',
          padding: '16px',
          background: 'rgba(0,0,0,0.2)',
          borderRadius: '8px',
        }}
      >
        {messages.length === 0 ? (
          <Empty
            description={
              <span style={{ color: 'rgba(255,255,255,0.5)' }}>
                {t('copilot.empty_message')}
              </span>
            }
            image={Empty.PRESENTED_IMAGE_SIMPLE}
          />
        ) : (
          messages.map((msg, index) => (
            <div
              key={index}
              style={{
                marginBottom: '16px',
                display: 'flex',
                flexDirection: msg.role === 'user' ? 'row-reverse' : 'row',
              }}
            >
              <div
                style={{
                  maxWidth: '80%',
                  padding: '12px 16px',
                  borderRadius: '8px',
                  background:
                    msg.role === 'user'
                      ? 'rgba(22, 93, 255, 0.2)'
                      : 'rgba(255,255,255,0.05)',
                }}
              >
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    marginBottom: '8px',
                  }}
                >
                  {msg.role === 'assistant' ? (
                    <RobotOutlined style={{ color: '#52c41a', marginRight: '8px' }} />
                  ) : (
                    <UserOutlined style={{ color: '#165DFF', marginRight: '8px' }} />
                  )}
                  <Text type="secondary" style={{ fontSize: '12px' }}>
                    {msg.timestamp.toLocaleTimeString()}
                  </Text>
                </div>
                <div style={{ color: 'rgba(255,255,255,0.85)' }}>
                  <AiReportRenderer content={msg.content} />
                </div>
                {msg.link && (
                  <div style={{ marginTop: 8 }}>
                    <Button size="small" type="link" onClick={() => msg.link && navigate(msg.link.href)}>
                      {msg.link.label}
                    </Button>
                  </div>
                )}
                {msg.role === 'assistant' && msg.fromAi && (
                  <div style={{ marginTop: '8px', display: 'flex', gap: '4px', alignItems: 'center' }}>
                    <Tooltip title={t('copilot.feedback_up')}>
                      <Button
                        size="small"
                        type="text"
                        disabled={!!feedback[index]}
                        icon={feedback[index] === 'UP'
                          ? <LikeFilled style={{ color: '#52c41a' }} />
                          : <LikeOutlined style={{ color: 'rgba(255,255,255,0.45)' }} />}
                        onClick={() => handleFeedback(index, 'UP', msg.content)}
                      />
                    </Tooltip>
                    <Tooltip title={t('copilot.feedback_down')}>
                      <Button
                        size="small"
                        type="text"
                        disabled={!!feedback[index]}
                        icon={feedback[index] === 'DOWN'
                          ? <DislikeFilled style={{ color: '#ff4d4f' }} />
                          : <DislikeOutlined style={{ color: 'rgba(255,255,255,0.45)' }} />}
                        onClick={() => handleFeedback(index, 'DOWN', msg.content)}
                      />
                    </Tooltip>
                  </div>
                )}
              </div>
            </div>
          ))
        )}
        {/* 打字机：最终答案的 token 流式增量，临时气泡，收到终态 message 后被正式消息替换 */}
        {loading && streamingContent && (
          <div style={{ marginBottom: '16px', display: 'flex', flexDirection: 'row' }}>
            <div
              style={{
                maxWidth: '80%',
                padding: '12px 16px',
                borderRadius: '8px',
                background: 'rgba(255,255,255,0.05)',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', marginBottom: '8px' }}>
                <RobotOutlined style={{ color: '#52c41a', marginRight: '8px' }} />
              </div>
              <div style={{ color: 'rgba(255,255,255,0.85)' }}>
                <AiReportRenderer content={streamingContent} />
              </div>
            </div>
          </div>
        )}
        {/* 思考/调用工具阶段（尚无 token 流出）显示进度提示；终态到达后立即退场 */}
        {loading && !streamingContent && !streamDone && (
          <div style={{ textAlign: 'center', padding: '16px' }}>
            <Spin />
            <div style={{ marginTop: 8, fontSize: 12, color: 'rgba(255,255,255,0.65)' }}>
              {streamNote || t('copilot.ai_thinking')}
            </div>
          </div>
        )}
        <div ref={messagesEndRef} />
      </div>

      {/* Pending write action — requires explicit human confirmation (P0 fix) */}
      {pendingAction && (
        <div
          style={{
            marginTop: '12px',
            padding: '12px 16px',
            borderRadius: '8px',
            border: '1px solid rgba(250, 173, 20, 0.5)',
            background: 'rgba(250, 173, 20, 0.08)',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', marginBottom: '8px' }}>
            <ExclamationCircleOutlined style={{ color: '#faad14', marginRight: '8px' }} />
            <Text strong style={{ color: '#faad14' }}>
              {t('copilot.confirm_title')}: {String(pendingAction.preview?.operationType ?? pendingAction.toolName)}
            </Text>
          </div>
          {Boolean(pendingAction.preview?.warning) && (
            <div style={{ color: 'rgba(255,255,255,0.75)', fontSize: '12px', marginBottom: '8px' }}>
              {t('copilot.warning')}: {String(pendingAction.preview?.warning)}
            </div>
          )}
          <pre
            style={{
              maxHeight: '180px',
              overflow: 'auto',
              margin: '0 0 12px',
              padding: '8px',
              borderRadius: '4px',
              background: 'rgba(0,0,0,0.3)',
              color: 'rgba(255,255,255,0.85)',
              fontSize: '12px',
            }}
          >
            {JSON.stringify(pendingAction.arguments, null, 2)}
          </pre>
          <div style={{ display: 'flex', gap: '8px' }}>
            <Button
              type="primary"
              danger
              loading={executing}
              disabled={clearing || loading}
              onClick={handleConfirmAction}
            >
              {t('copilot.confirm_execute')}
            </Button>
            <Button onClick={handleCancelAction} disabled={executing || clearing}>
              {t('copilot.confirm_cancel')}
            </Button>
          </div>
        </div>
      )}

      {/* Focused entry points: trace troubleshooting, config explanation, draft generation */}
      <div style={{ marginTop: '12px', display: 'flex', gap: '8px', flexWrap: 'wrap', alignItems: 'center' }}>
        <Input
          size="small"
          allowClear
          value={traceLookupId}
          onChange={e => setTraceLookupId(e.target.value)}
          placeholder={t('copilot.trace_id_placeholder')}
          disabled={quickBusy}
          style={{ width: 190 }}
        />
        <Button
          size="small"
          disabled={quickBusy || !traceLookupId.trim()}
          onClick={() => handleSendMessage(t('copilot.trace_prompt', { traceId: traceLookupId.trim() }))}
        >
          {t('copilot.trace_action')}
        </Button>
        <Select
          size="small"
          showSearch
          allowClear
          optionFilterProp="label"
          value={targetRouteId}
          onChange={(routeId: string | undefined) => setRouteSelection(routeId ? { sessionKey, routeId } : undefined)}
          placeholder={t('copilot.route_placeholder')}
          disabled={quickBusy || routesRequest.loading || !!routesRequest.error}
          style={{ width: 220 }}
          options={routes.map(route => ({
            value: route.id,
            label: `${route.routeName || route.id} (${route.id})`,
          }))}
        />
        <Button
          size="small"
          disabled={quickBusy || !targetRouteId}
          onClick={() => handleSendMessage(t('copilot.explain_prompt', { routeId: targetRouteId }))}
        >
          {t('copilot.explain_action')}
        </Button>
        <Button
          size="small"
          disabled={quickBusy || !targetRouteId}
          onClick={() => handleSendMessage(t('copilot.draft_prompt', { routeId: targetRouteId }))}
        >
          {t('copilot.draft_action')}
        </Button>
      </div>

      {/* Input Area */}
      <div
        style={{
          marginTop: '16px',
          display: 'flex',
          gap: '8px',
          alignItems: 'flex-end',
        }}
      >
        <TextArea
          value={inputMessage}
          onChange={e => setInputMessage(e.target.value)}
          placeholder={t('copilot.input_placeholder')}
          autoSize={{ minRows: 1, maxRows: 4 }}
          disabled={loading || historyLoading || clearing || executing}
          maxLength={32000}
          onPressEnter={e => {
            if (!e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault();
              handleSendMessage();
            }
          }}
          style={{
            flex: 1,
            background: 'rgba(255,255,255,0.05)',
            border: '1px solid rgba(255,255,255,0.1)',
            color: '#fff',
          }}
        />
        <Button
          type="primary"
          icon={<SendOutlined />}
          onClick={() => handleSendMessage()}
          loading={loading}
          disabled={historyLoading || clearing || executing || !inputMessage.trim()}
        >
          {t('copilot.send')}
        </Button>
        <Tooltip title={t('copilot.clear_conversation')}>
          <Button icon={<ClearOutlined />} onClick={handleClearConversation} loading={clearing} disabled={historyLoading || executing} />
        </Tooltip>
      </div>
    </div>
  );
};

export default ChatTab;
