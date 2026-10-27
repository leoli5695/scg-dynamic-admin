import React, { useMemo, useState } from 'react';
import {
  Button,
  Card,
  Form,
  Input,
  Popconfirm,
  Select,
  Space,
  Spin,
  Switch,
  Tag,
  Typography,
} from 'antd';
import { message } from '../../utils/antdStatic';
import { BellOutlined, MailOutlined, PlusOutlined, SendOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../hooks/useRequest';
import {
  clearAlertHistory,
  getAlertConfig,
  getAlertHistory,
  getAlertThresholdMetrics,
  saveAlertConfig,
  sendTestEmail,
} from '../../api/alerts';
import { useInstanceDetail } from '../instance-detail/InstanceDetailContext';
import { EMAIL_PATTERN, mergeThresholdConfig } from './utils';
import CurrentMetricsCard from './components/CurrentMetricsCard';
import ThresholdCards from './components/ThresholdCards';
import HistoryTable from './components/HistoryTable';

const { Title, Text } = Typography;

const AlertPage: React.FC = () => {
  const { instance } = useInstanceDetail();
  const instanceId = instance.instanceId;
  const { t } = useTranslation();
  const [form] = Form.useForm();
  const [emails, setEmails] = useState<string[]>([]);
  const [emailInput, setEmailInput] = useState('');
  const [saving, setSaving] = useState(false);
  // Alert history is server-paginated: the backend returns one page at a time, so
  // page/size must be sent explicitly (the old call fetched only the default first
  // page and the table client-paginated over those <=20 rows, hiding older alerts).
  const [historyPage, setHistoryPage] = useState(0);
  const [historyPageSize, setHistoryPageSize] = useState(10);

  const configRequest = useRequest((signal) => getAlertConfig({ instanceId }, { signal }), {
    deps: [instanceId],
    onSuccess: (data) => {
      setEmails(data.emailRecipients ?? []);
      form.setFieldsValue({
        emailLanguage: data.emailLanguage || 'zh',
        enabled: data.enabled,
        ...mergeThresholdConfig(data.thresholdConfig),
      });
    },
  });
  const config = configRequest.data;

  const historyRequest = useRequest(
    (signal) =>
      getAlertHistory({ instanceId, page: historyPage, size: historyPageSize }, { signal }),
    { deps: [instanceId, historyPage, historyPageSize] },
  );
  const history = useMemo(() => historyRequest.data?.content ?? [], [historyRequest.data]);
  const historyTotal = historyRequest.data?.totalElements ?? 0;

  const handleHistoryPageChange = (page: number, pageSize: number) => {
    setHistoryPage(page);
    setHistoryPageSize(pageSize);
  };

  const metricsRequest = useRequest((signal) => getAlertThresholdMetrics({ signal }));

  /** Persist just the recipient list (used by add/remove auto-save). */
  const saveEmailRecipients = async (emailList: string[]) => {
    try {
      const values = form.getFieldsValue();
      await saveAlertConfig({
        id: config?.id,
        instanceId,
        configName: config?.configName || 'Default Alert Config',
        emailRecipients: emailList,
        emailLanguage: values.emailLanguage || config?.emailLanguage || 'zh',
        enabled: values.enabled ?? config?.enabled ?? true,
        thresholdConfig: mergeThresholdConfig(config?.thresholdConfig),
      });
      message.success(t('alert.save_success'));
    } catch (e) {
      message.error(e instanceof Error ? e.message : t('alert.save_failed'));
    }
  };

  const handleAddEmail = async () => {
    const email = emailInput.trim();
    if (!email) return;
    if (!EMAIL_PATTERN.test(email)) {
      message.error(t('alert.invalid_email'));
      return;
    }
    if (emails.includes(email)) {
      message.warning(t('alert.email_exists'));
      return;
    }
    const newEmails = [...emails, email];
    setEmails(newEmails);
    setEmailInput('');
    await saveEmailRecipients(newEmails);
  };

  const handleRemoveEmail = async (email: string) => {
    const newEmails = emails.filter((e) => e !== email);
    setEmails(newEmails);
    await saveEmailRecipients(newEmails);
  };

  const handleSave = async () => {
    try {
      setSaving(true);
      const values = await form.validateFields();
      await saveAlertConfig({
        id: config?.id,
        instanceId,
        configName: config?.configName || 'Default Alert Config',
        emailRecipients: emails,
        emailLanguage: values.emailLanguage,
        enabled: values.enabled,
        thresholdConfig: mergeThresholdConfig({
          cpu: values.cpu,
          memory: values.memory,
          http: values.http,
          instance: values.instance,
          thread: values.thread,
        }),
      });
      message.success(t('alert.save_success'));
      configRequest.run();
    } catch (e) {
      // Form validation failures surface inline; skip the toast for them.
      if (e && typeof e === 'object' && 'errorFields' in e) return;
      message.error(e instanceof Error ? e.message : t('alert.save_failed'));
    } finally {
      setSaving(false);
    }
  };

  const handleTestEmail = async () => {
    if (emails.length === 0) {
      message.warning(t('alert.no_recipients'));
      return;
    }
    try {
      await sendTestEmail({
        recipients: emails,
        language: form.getFieldValue('emailLanguage'),
      });
      message.success(t('alert.test_email_sent'));
    } catch (e) {
      message.error(e instanceof Error ? e.message : t('alert.test_email_failed'));
    }
  };

  const handleClearHistory = async () => {
    try {
      await clearAlertHistory({ instanceId });
      message.success(t('alert.clear_history_success'));
      // Jump back to the first page; if already there, re-run to refresh in place
      // (avoids a double fetch from a no-op state change plus run()).
      if (historyPage !== 0) {
        setHistoryPage(0);
      } else {
        historyRequest.run();
      }
    } catch (e) {
      message.error(e instanceof Error ? e.message : t('alert.clear_history_failed'));
    }
  };

  if (configRequest.loading && !config) {
    return (
      <div className="console-workspace-page console-loading-page">
        <Spin size="large" />
      </div>
    );
  }

  return (
    <div className="alert-settings-container console-workspace-page console-resource-page">
      <div className="alert-page-header console-page-header">
        <div className="alert-header-title">
          <BellOutlined className="alert-header-icon" />
          <Title level={3} style={{ margin: 0 }}>
            {t('alert.title')}
          </Title>
        </div>
        <Space className="alert-header-actions">
          <Popconfirm title={t('alert.test_email_confirm')} onConfirm={handleTestEmail}>
            <Button
              icon={<SendOutlined />}
              disabled={emails.length === 0}
              className="test-email-btn"
            >
              {t('alert.test_email')}
            </Button>
          </Popconfirm>
        </Space>
      </div>

      <Form form={form} layout="vertical" className="alert-form">
        <div className="settings-grid">
          <div className="settings-card-wrapper">
            <Card
              title={
                <Space>
                  <MailOutlined />
                  {t('alert.email_settings')}
                </Space>
              }
              className="settings-card email-settings-card"
            >
              <Form.Item label={t('alert.email_recipients')}>
                <Space.Compact style={{ width: '100%' }}>
                  <Input
                    placeholder={t('alert.email_placeholder')}
                    value={emailInput}
                    onChange={(e) => setEmailInput(e.target.value)}
                    onPressEnter={handleAddEmail}
                  />
                  <Button type="primary" icon={<PlusOutlined />} onClick={handleAddEmail}>
                    {t('common.add')}
                  </Button>
                </Space.Compact>
              </Form.Item>

              <div style={{ marginBottom: 16 }}>
                {emails.map((email) => (
                  <Tag
                    key={email}
                    closable
                    onClose={() => handleRemoveEmail(email)}
                    style={{ marginBottom: 4 }}
                  >
                    <MailOutlined style={{ marginRight: 4 }} />
                    {email}
                  </Tag>
                ))}
                {emails.length === 0 && <Text type="secondary">{t('alert.no_emails')}</Text>}
              </div>

              <Form.Item name="emailLanguage" label={t('alert.email_language')}>
                <Select style={{ width: 200 }}>
                  <Select.Option value="zh">{t('alert.chinese')}</Select.Option>
                  <Select.Option value="en">{t('alert.english')}</Select.Option>
                </Select>
              </Form.Item>

              <Form.Item name="enabled" label={t('alert.enabled')} valuePropName="checked">
                <Switch
                  checkedChildren={t('common.enabled')}
                  unCheckedChildren={t('common.disabled')}
                  className="alert-switch"
                />
              </Form.Item>
            </Card>
          </div>

          <CurrentMetricsCard metrics={metricsRequest.data} />
        </div>

        <ThresholdCards />
      </Form>
      <div className="console-settings-footer">
        {' '}
        <Button type="primary" onClick={handleSave} loading={saving} className="save-config-btn">
          {t('alert.save_config')}
        </Button>
      </div>

      <HistoryTable
        history={history}
        loading={historyRequest.loading}
        hasInstance
        total={historyTotal}
        page={historyPage}
        pageSize={historyPageSize}
        onPageChange={handleHistoryPageChange}
        onClear={handleClearHistory}
        onRefresh={historyRequest.run}
      />
    </div>
  );
};

export default AlertPage;
