import React, { useEffect, useRef, useState } from 'react';
import { Button, Empty, Input, Spin, Tooltip, Typography } from 'antd';
import { message } from '../../../utils/antdStatic';
import {
  ClearOutlined,
  RobotOutlined,
  SendOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { clearCopilotChat, sendCopilotChat } from '../../../api/copilot';
import { ApiError } from '../../../api/http';
import AiReportRenderer from '../../../components/ai/AiReportRenderer';
import '../../../styles/ai-report.css';

const { Text } = Typography;
const { TextArea } = Input;

interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  timestamp: Date;
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
  const messagesEndRef = useRef<HTMLDivElement>(null);

  // Initialize session
  useEffect(() => {
    (async () => {
      setSessionId(`session-${Date.now()}`);
    })();
  }, []);

  // Scroll to bottom when messages change
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const handleSendMessage = async () => {
    if (!inputMessage.trim()) return;

    const userMessage: ChatMessage = {
      role: 'user',
      content: inputMessage.trim(),
      timestamp: new Date(),
    };

    setMessages(prev => [...prev, userMessage]);
    setInputMessage('');
    setLoading(true);

    try {
      const response = await sendCopilotChat({
        sessionId,
        message: userMessage.content,
        // context 不传递，让后端根据消息内容自动识别意图
        instanceId,
        provider: provider || undefined,
        model: model || undefined,
      });

      if (response.success) {
        const assistantMessage: ChatMessage = {
          role: 'assistant',
          content: response.response || '',
          timestamp: new Date(),
        };
        setMessages(prev => [...prev, assistantMessage]);
        setSessionId(response.sessionId);
      } else {
        message.error(response.error || t('copilot.chat_failed'));
      }
    } catch (e) {
      message.error(e instanceof ApiError && e.message ? e.message : t('copilot.send_message_failed'));
    } finally {
      setLoading(false);
    }
  };

  const handleClearConversation = async () => {
    try {
      await clearCopilotChat(sessionId);
      setMessages([]);
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
              </div>
            </div>
          ))
        )}
        <div ref={messagesEndRef} />
        {loading && (
          <div style={{ textAlign: 'center', padding: '16px' }}>
            <Spin tip={t('copilot.ai_thinking')} />
          </div>
        )}
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
