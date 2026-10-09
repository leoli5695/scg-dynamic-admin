import React, { useEffect, useRef, useState } from 'react';
import { Button, Empty, Input, Spin, Tooltip, Typography } from 'antd';
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
import { clearCopilotChat, executeCopilotAction, sendCopilotChatStream, submitCopilotFeedback } from '../../../api/copilot';
import { ApiError, isAbortError } from '../../../api/http';
import type { CopilotPendingAction } from '../../../types/copilot';
import AiReportRenderer from '../../../components/ai/AiReportRenderer';
import '../../../styles/ai-report.css';

const { Text } = Typography;
const { TextArea } = Input;

interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  timestamp: Date;
  /** true 仅用于真正来自模型的回答（本地确认/取消提示不算），决定是否显示反馈按钮。 */
  fromAi?: boolean;
}

interface ChatTabProps {
  instanceId: string;
  provider: string;
  model: string;
}

const ChatTab: React.FC<ChatTabProps> = ({ instanceId, provider, model }) => {
  const { t } = useTranslation();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [inputMessage, setInputMessage] = useState('');
  const [loading, setLoading] = useState(false);
  const [sessionId, setSessionId] = useState('');
  const [pendingAction, setPendingAction] = useState<CopilotPendingAction | null>(null);
  const [executing, setExecuting] = useState(false);
  const [streamNote, setStreamNote] = useState('');
  const [feedback, setFeedback] = useState<Record<number, 'UP' | 'DOWN'>>({});
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);

  // Initialize session
  useEffect(() => {
    (async () => {
      setSessionId(`session-${Date.now()}`);
    })();
  }, []);

  // Abort any in-flight stream on unmount
  useEffect(() => () => { abortRef.current?.abort(); }, []);

  // Scroll to bottom when messages change
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const handleSendMessage = async () => {
    if (loading) return; // 发送中禁止重复提交（回车会绕过按钮的 loading 禁用，进而触发后端同会话并发）
    if (!inputMessage.trim()) return;

    const userMessage: ChatMessage = {
      role: 'user',
      content: inputMessage.trim(),
      timestamp: new Date(),
    };

    setMessages(prev => [...prev, userMessage]);
    setInputMessage('');
    setLoading(true);
    setStreamNote(t('copilot.ai_thinking'));

    const controller = new AbortController();
    abortRef.current = controller;

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
          onStatus: () => setStreamNote(t('copilot.ai_thinking')),
          onTool: (data) => {
            if (data.status === 'running') {
              setStreamNote(t('copilot.calling_tool', { tool: data.toolName }));
            } else if (data.status === 'success') {
              setStreamNote(t('copilot.tool_done', { tool: data.toolName, ms: data.durationMs ?? 0 }));
            } else if (data.status === 'error') {
              setStreamNote(t('copilot.tool_failed', { tool: data.toolName }));
            } else if (data.status === 'confirm') {
              setStreamNote(t('copilot.please_confirm'));
            }
          },
          onMessage: (response) => {
            if (response.success) {
              setMessages(prev => [...prev, {
                role: 'assistant',
                content: response.response || '',
                timestamp: new Date(),
                fromAi: true,
              }]);
              setSessionId(response.sessionId);
              // AI proposed a write operation — surface it for explicit human confirmation.
              setPendingAction(response.pendingAction ?? null);
            } else {
              message.error(response.error || t('copilot.chat_failed'));
            }
          },
          onError: (msg) => message.error(msg || t('copilot.chat_failed')),
        },
      );
    } catch (e) {
      if (!isAbortError(e)) {
        message.error(e instanceof ApiError && e.message ? e.message : t('copilot.send_message_failed'));
      }
    } finally {
      abortRef.current = null;
      setStreamNote('');
      setLoading(false);
    }
  };

  const handleConfirmAction = async () => {
    if (!pendingAction) return;
    setExecuting(true);
    try {
      const result = await executeCopilotAction({
        ticket: pendingAction.ticket,
        toolName: pendingAction.toolName,
      });
      const assistantMessage: ChatMessage = {
        role: 'assistant',
        content: result.success
          ? `✅ ${result.message || t('copilot.operation_success')}`
          : `❌ ${result.message || t('copilot.operation_failed')}`,
        timestamp: new Date(),
      };
      setMessages(prev => [...prev, assistantMessage]);
      if (result.success) {
        message.success(t('copilot.operation_success'));
      } else {
        message.error(result.message || t('copilot.operation_failed'));
      }
    } catch (e) {
      message.error(e instanceof ApiError && e.message ? e.message : t('copilot.operation_failed'));
    } finally {
      setPendingAction(null);
      setExecuting(false);
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
    abortRef.current?.abort(); // 中止在途流式请求，避免旧会话的回复串进来
    try {
      await clearCopilotChat(sessionId);
      setMessages([]);
      setPendingAction(null);
      setStreamNote('');
      setFeedback({});
      setSessionId(`session-${Date.now()}`);
      message.success(t('copilot.conversation_cleared'));
    } catch {
      message.error(t('copilot.clear_failed'));
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 350px)' }}>
      {/* Messages Area */}
      <div
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
        <div ref={messagesEndRef} />
        {loading && (
          <div style={{ textAlign: 'center', padding: '16px' }}>
            <Spin />
            <div style={{ marginTop: 8, fontSize: 12, color: 'rgba(255,255,255,0.65)' }}>
              {streamNote || t('copilot.ai_thinking')}
            </div>
          </div>
        )}
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
              onClick={handleConfirmAction}
            >
              {t('copilot.confirm_execute')}
            </Button>
            <Button onClick={handleCancelAction} disabled={executing}>
              {t('copilot.confirm_cancel')}
            </Button>
          </div>
        </div>
      )}

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
          disabled={loading}
          onPressEnter={e => {
            if (!e.shiftKey) {
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
          onClick={handleSendMessage}
          loading={loading}
        >
          {t('copilot.send')}
        </Button>
        <Tooltip title={t('copilot.clear_conversation')}>
          <Button icon={<ClearOutlined />} onClick={handleClearConversation} />
        </Tooltip>
      </div>
    </div>
  );
};

export default ChatTab;
