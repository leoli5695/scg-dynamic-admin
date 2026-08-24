/**
 * Plugin development guide — info alert + collapsible example template.
 */
import React from 'react';
import { Alert, Button, Card, Collapse, Space, Typography } from 'antd';
import { message } from '../../../utils/antdStatic';
import { BookOutlined, DownloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

const { Text, Paragraph } = Typography;

const preStyle: React.CSSProperties = {
  background: '#1e1e1e',
  color: '#d4d4d4',
  padding: 12,
  borderRadius: 4,
  fontSize: 12,
  overflow: 'auto',
};

const DevGuideCard: React.FC = () => {
  const { t } = useTranslation();

  const schemaExample = `{
  "fields": [
    {
      "name": "maxRequests",
      "type": "number",
      "label": "${t('plugins.schema_field_max_requests')}",
      "default": 100,
      "min": 1,
      "max": 10000
    },
    {
      "name": "keyResolver",
      "type": "select",
      "label": "${t('plugins.schema_field_key_resolver')}",
      "options": [
        {"label": "${t('plugins.schema_option_ip')}", "value": "IP"},
        {"label": "${t('plugins.schema_option_user')}", "value": "USER"}
      ]
    }
  ]
}`;

  return (
    <>
      <Alert
        message={t('plugins.development_guide')}
        description={t('plugins.development_guide_desc')}
        type="info"
        showIcon
        closable
        style={{ marginBottom: 16 }}
      />
      <Card
        title={<span><BookOutlined style={{ marginRight: 8 }} />{t('plugins.example_template')}</span>}
        size="small"
        style={{ marginBottom: 16 }}
        extra={
          <Space>
            <Button
              type="primary"
              icon={<DownloadOutlined />}
              onClick={() => message.info(t('plugins.view_example_hint'))}
            >
              {t('plugins.view_example_source')}
            </Button>
          </Space>
        }
      >
        <Collapse
          ghost
          items={[
            {
              key: 'steps',
              label: t('plugins.dev_steps'),
              children: (
                <ol style={{ paddingLeft: 20, margin: 0 }}>
                  <li><Text>{t('plugins.dev_step_1')}</Text></li>
                  <li><Text>{t('plugins.dev_step_2')}</Text></li>
                  <li><Text>{t('plugins.dev_step_3')}</Text></li>
                  <li><Text>{t('plugins.dev_step_4')}</Text></li>
                  <li><Text>{t('plugins.dev_step_5')}</Text></li>
                  <li><Text>{t('plugins.dev_step_6')}</Text></li>
                  <li><Text>{t('plugins.dev_step_7')}</Text></li>
                </ol>
              ),
            },
            {
              key: 'structure',
              label: t('plugins.example_structure'),
              children: (
                <Paragraph style={{ marginBottom: 8 }}>
                  <pre style={preStyle}>
{`plugin-example/
├── pom.xml
├── src/main/java/com/example/
│   └── CustomRateLimitGatewayFilterFactory.java
└── src/main/resources/META-INF/
    └── plugin-schema.json`}
                  </pre>
                </Paragraph>
              ),
            },
            {
              key: 'schema',
              label: t('plugins.schema_example'),
              children: (
                <Paragraph style={{ marginBottom: 0 }}>
                  <pre style={{ ...preStyle, maxHeight: 200 }}>{schemaExample}</pre>
                </Paragraph>
              ),
            },
          ]}
        />
      </Card>
    </>
  );
};

export default DevGuideCard;
